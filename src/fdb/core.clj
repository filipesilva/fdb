(ns fdb.core
  "A hackable database environment for your file library."
  (:refer-clojure :exclude [sync read])
  (:require
   hashp.core
   [babashka.fs :as fs]
   [clojure.core.async :refer [<!! >!! chan close! go sliding-buffer]]
   [clojure.data :as data]
   [clojure.edn :as edn]
   [clojure.repl.deps :as deps]
   [clojure.string :as str]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.readers :as readers]
   [fdb.state :as state]
   [fdb.triggers :as triggers]
   [fdb.triggers.ignore :as tr.ignore]
   [fdb.utils :as u]
   [fdb.watcher :as watcher]
   [nrepl.server :as nrepl-server]
   [taoensso.timbre :as log]
   [tick.core :as t]
   [xtdb.api :as xt]))

(defn set-dynamic-classloader!
  "Set dynamic classloader to current thread."
  []
  (->>(Thread/currentThread)
      (.getContextClassLoader)
      (clojure.lang.DynamicClassLoader.)
      (.setContextClassLoader (Thread/currentThread))))

(defn node [config-path]
  (when (= config-path (:config-path @state/*fdb))
    (:node @state/*fdb)))

(defn do-with-fdb
  "Call f over an initialized fdb. Uses repl xtdb node if available, otherwise creates a new one."
  [config-path f]
  (let [{:keys [db-path extra-deps load] :as config} (-> config-path slurp edn/read-string)]
    (when extra-deps
      (binding [clojure.core/*repl* true]
        ;; Needs dynamic classloader when running from cli
        ;; https://ask.clojure.org/index.php/10761/clj-behaves-different-in-the-repl-as-opposed-to-from-a-file
        (set-dynamic-classloader!)
        (deps/add-libs extra-deps)))
    (when load
      (doseq [f load]
        (binding [*ns* (create-ns 'user)]
          (some->> f
                   (metadata/id->path config-path config)
                   fs/absolutize
                   str
                   (u/side-effect->> #(log/info "loading" %))
                   load-file))))
    (if-some [node (node config-path)]
      (f config-path config node)
      (with-open [node (db/node (u/sibling-path config-path db-path))]
        (f config-path config node)))))

(defmacro with-fdb
  "Call body with over fdb configured with config-path."
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[config-path config node ] & body]
  `(do-with-fdb ~config-path (fn [~config-path ~config ~node] ~@body)))

(defn update!
  "Read id-or-ids from fs and update them in node. Returns tx without ops."
  [config-path config node id-or-ids]
  (u/with-time [t-ms #(log/debug "update! took" (t-ms) "ms")]
    (let [call-arg {:config-path config-path
                    :config      config
                    :node        node
                    :db          (xt/db node)}]
      (some->> id-or-ids
               u/one-or-many
               not-empty
               (u/side-effect->> (fn [ids]
                                   (when-some [ids' (->> ids
                                                         (remove (partial tr.ignore/ignoring? config-path))
                                                         seq)]
                                     (log/info "updating" (str/join ", " (take 5 ids'))
                                               (if (> (count ids') 5)
                                                 (str "and " (-> ids' count (- 5) str) " more")
                                                 "")))))
               (pmap (fn [id]
                       (let [path (metadata/id->path config-path config id)]
                         (if-some [metadata (metadata/read path)]
                           (let [call-arg' (merge call-arg
                                                  {:self      {:xt/id id}
                                                   :self-path path})]
                             [::xt/put (merge
                                        ;; order matters: reader data, then metadata, then id
                                        ;; metadata overrides reader data, id overrides all
                                        (readers/read call-arg')
                                        metadata
                                        {:xt/id      id
                                         :fdb/parent (-> id fs/parent str)})])
                           [::xt/delete id]))))
               (xt/submit-tx node)))))

(defn stale
  "Returns all ids that are out of sync between fs and node."
  [config-path {:keys [mounts] :as config} node]
  (u/with-time [t-ms #(log/debug "stale took" (t-ms) "ms")]
    (let [in-fs                     (->> mounts
                                         ;; get all paths for all mounts
                                         (map (fn [[mount-id mount-spec]]
                                                [mount-id (metadata/mount-path config-path mount-spec)]))
                                         (pmap (fn [[mount-id mount-path]]
                                                 (pmap (fn [p] [(metadata/id mount-id p)
                                                                (metadata/modified mount-path p)])
                                                       (watcher/glob config mount-path))))
                                         (mapcat identity)
                                         ;; id is the same for content and metadata file, we want
                                         ;; to keep only the most recent modified
                                         (group-by first)
                                         (map #(reduce (fn [[_ m1 :as x] [_ m2 :as y]]
                                                         (if (t/> m1 m2) x y))
                                                       (second %)))
                                         set)
          in-db                     (xt/q (xt/db node)
                                          '{:find  [?id ?modified]
                                            :where [[?e :xt/id ?id]
                                                    [?e :fdb/modified ?modified]]})
          [only-in-fs only-in-db _] (data/diff in-fs in-db)
          stale-ids                 (->> (concat only-in-fs only-in-db)
                                         (map first)
                                         set)]
      stale-ids)))

(defn update-stale!
  "Update all stale files. Returns [stale-ids tx]."
  [config-path config node]
  (let [stale-ids (stale config-path config node)
        tx (update! config-path config node stale-ids)]
      (when (empty? stale-ids)
        (log/info "nothing to update"))
      [stale-ids tx]))

(defn sync
  "Sync fdb with fs, running reactive triggers over the changes. Returns stale ids."
  [config-path]
  ;; Call triggers synchronously
  (binding [triggers/*sync* true]
    (with-fdb [config-path {:keys [mounts] :as config} node]
      (triggers/call-all-k config-path config node :fdb.on/startup)
      ;; Update stale files.
      (let [[stale-ids tx] (update-stale! config-path config node)]
        (when tx
          (xt/await-tx node tx)
          ;; TODO: sync call missed cron schedules
          (triggers/on-tx config-path config node (db/tx-with-ops node tx)))
        (triggers/call-all-k config-path config node :fdb.on/shutdown)
        stale-ids))))

(defn mount->watch-spec
  [config config-path node [mount-id mount-spec]]
  (let [mount-path (metadata/mount-path config-path mount-spec)
        update-fn  #(->> %
                         (metadata/id mount-id)
                         (update! config-path config node))]
    [config mount-path update-fn]))

(defn watch
  "Call f inside a watching fdb."
  [config-path f]
  (log/info "starting fdb in watch mode")
  (with-fdb [config-path {:keys [mounts repl] :as config} node]
    (tr.ignore/clear config-path)
    (triggers/call-all-k config-path config node :fdb.on/startup)
    (with-open [_tx-listener    (xt/listen node
                                           {::xt/event-type ::xt/indexed-tx
                                            :with-tx-ops?   true}
                                           (partial triggers/on-tx config-path config node))
                ;; Start watching before the stale check, so no change is lost.
                ;; TODO: don't tx anything before the stale update
                _mount-watchers (u/with-time [t-ms #(log/debug "watch took" (t-ms) "ms")]
                                  (->> mounts
                                       (map (partial mount->watch-spec config config-path node))
                                       watcher/watch-many
                                       u/closeable-seq))
                _state          (u/closeable-atom state/*fdb
                                                  {:config-path config-path
                                                   :config      config
                                                   :node        node})]
      (when-let [tx (second (update-stale! config-path config node))]
        (xt/await-tx node tx))
      (triggers/start-all-schedules config-path config node)
      (let [return (f node)]
        (triggers/stop-config-path-schedules config-path)
        (triggers/call-all-k config-path config node :fdb.on/shutdown)
        return))))

(defmacro with-watch
  "Call body inside a watching fdb."
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[config-path node] & body]
  `(watch ~config-path (fn [~node] ~@body)))

(defn watch-config!
  "Watch config-path and restart fdb on changes.
  Blocks, saves a closeable on state/*config-watcher that will stop it."
  [config-path]
  (let [control-ch (chan (sliding-buffer 1))
        restart!   (fn [_]
                     (log/info "config changed, restarting")
                     (>!! control-ch true))
        process-ch (go
                     (with-open [_config-watcher (watcher/watch {} config-path restart!)]
                       (loop [restart? true]
                         (if restart?
                           (recur (with-watch [config-path _node]
                                    (log/info "fdb running")
                                    ;; Block waiting for config changes.
                                    (<!! control-ch)))
                           ;; I don't see this message when calling from cli,
                           ;; but I tested that stop! can block the process from
                           ;; being killed so I think it's shutting down correctly.
                           (log/info "shutdown")))))
        stop!      (fn [_]
                     (close! control-ch)
                     (<!! process-ch))]
    (reset! state/*config-watcher (u/closeable process-ch stop!))
    (<!! process-ch)))

(defn read
  "Force a read of pattern on root. Useful when updating readers."
  [config-path root pattern]
  (log/info "reading" pattern)
  (with-fdb [config-path config node]
    (->> (watcher/glob config root :pattern pattern)
         (map fs/absolutize)
         (map (partial metadata/path->id config-path config))
         (remove nil?)
         (update! config-path config node))))

(defn repl
  "Start a repl and wait forever.
  Takes a map because it's called via clojure -X."
  [{:keys [config-path debug]}]
  (log/merge-config! {:min-level (if debug :debug :info)})
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn []
                               ;; Close and wait for the config watcher on exit, if any.
                               (some-> @state/*config-watcher .close)
                               ;; Don't wait for 1m for futures thread to shut down.
                               ;; See https://clojuredocs.org/clojure.core/future
                               (shutdown-agents))))
  (let [opts      (merge {:port 2525}
                         (-> config-path slurp edn/read-string :repl))
        ;; Like nrepl.cmdline/save-port-file, but next to the config file.
        port-file (-> config-path (u/sibling-path ".nrepl-port") fs/file)]
    (.deleteOnExit ^java.io.File port-file)
    (nrepl-server/start-server opts)
    (log/info "nrepl server running at" (:port opts))
    (spit port-file (:port opts)))
  @(promise))

;; TODO:
;; - consider java-time.api instead of tick
;; - preload clj libs on config and use them in edn call sexprs (waiting for clojure 1.12 release)
;; - validate mounts, don't allow slashes on mount-id, nor empty
;; - allow config to auto-evict based on age, but start with forever
;; - just doing a doc with file listings for the month would already help with taxes
;; - check https://github.com/clj-commons/marginalia for docs
;; - register protocol to be able to do fdb://name/call/something
;;   - a bit like the Oberon system that had text calls, but only for urls
;;   - urlencode the call args
;; - malli for config validation
;;   - use dev mode https://www.metosin.fi/blog/2024-01-16-malli-data-modelling-for-clojure-developers
;; - ensure sync/call work over running watch, otherwise can't have a live env in lisp terms
;;   - maybe just connect via the repl, and call sync/call
;;   - then watch just stores the current node in an atom, and sync/call use it if available
;; - call should be able to call existing triggers, pretending to be them
;;   - fdb call id "[:fdb.on/schedule 0]"
;;   - might need to be call-trigger?
;;   - fdb trigger id :anything 0
;;   - yeah this lgtm
;;   - fdb trigger implies fdb process/read
;; - pre-tx triggers could do db-with and verify something, like a query
;; - some facility to view db contents in a certain format
;;   - email as edn, json, md, pdf
;;   - I guess it'd need some mapping, because each view should expect some stuff
;;     - default view comes from file ext
;;   - server should provide views via content negotiation, but maybe also some &as=md param
;;   - fdb open /mount/path/file.ext
;;     - open in browser
;;     - open folder opens listing, with pagination etc
;;     - open file opens default view, :as ext for different one
;;     - maybe an editor there too? for query, repl files, metadata
;; - what's the google-like search for fdb?
;;   - not just fdb.query I imagine, but that's deff the advanced version
;;   - should return things as views
;; - in xtdb2, tables could be mounts, or file types
;; - bulk change config-file-path to config-path
;; - rename fdb.fns to fdb.ext
;; - maybe call-arg stuff should be in fdb.call, with a bit more structure...
;; - what's a google search over all docs like?
;;   - not just a query
;;   - maybe its grep over the disk files
;; - stale on-db should be deleting ignores
