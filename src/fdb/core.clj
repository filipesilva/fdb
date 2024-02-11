(ns fdb.core
  "A programmable database over your files."
  (:refer-clojure :exclude [sync])
  (:require
   #_[clojure.repl.deps :as deps]
   hashp.core
   [clojure.core.async :refer [<!! >!! chan close! go sliding-buffer]]
   [clojure.data :as data]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [fdb.call :as call]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.processor :as processor]
   [fdb.repl :as repl]
   [fdb.triggers.ignore :as tr.ignore]
   [fdb.triggers :as triggers]
   [fdb.utils :as u]
   [fdb.watcher :as watcher]
   [taoensso.timbre :as log]
   [tick.core :as t]
   [xtdb.api :as xt]))

(defn do-with-fdb
  "Call f over an initialized fdb. Uses repl xtdb node if available, otherwise creates a new one."
  [config-path f]
  (let [{:keys [db-path _extra-deps] :as config} (-> config-path slurp edn/read-string)]
    ;; TODO: should just work when clojure 1.12 is released and installed globally
    #_(binding [clojure.core/*repl* true]
      (when extra-deps
        (deps/add-libs extra-deps)))
    (if-some [node (repl/node config-path)]
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
    (some->> id-or-ids
             u/x-or-xs->xs
             not-empty
             (u/side-effect->> (fn [ids]
                                 (log/info "updating" (str/join ", " (take 5 ids))
                                           (if (> (count ids) 5)
                                             (str "and " (-> ids count (- 5) str) " more")
                                             ""))))
             (pmap (fn [id]
                     (if-some [metadata (->> id (metadata/id->path config-path config) metadata/read)]
                       [::xt/put (merge ;; order matters: processor data, then metadata, then id
                                  ;; metadata overrides processor data, id overrides all
                                  (processor/read config-path config id)
                                  metadata
                                  {:xt/id id})]
                       [::xt/delete id])))
             (xt/submit-tx node))))

(defn stale
  "Returns all ids that are out of sync between fs and node."
  [config-path {:keys [mounts]} node]
  (u/with-time [t-ms #(log/debug "stale took" (t-ms) "ms")]
    (let [in-fs                     (->> mounts
                                         ;; get all paths for all mounts
                                         (map (fn [[mount-id mount-spec]]
                                                [mount-id (metadata/mount-path config-path mount-spec)]))
                                         (pmap (fn [[mount-id mount-path]]
                                                 (pmap (fn [p] [(metadata/id mount-id p)
                                                                (metadata/modified mount-path p)])
                                                       (watcher/glob mount-path))))
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
    [mount-path update-fn]))

(defn watch
  "Call f inside a watching fdb."
  [config-path f]
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
                _repl-server    (if (= repl false)
                                  (u/closeable nil)
                                  (repl/start-server {:config-path config-path
                                                      :config      config
                                                      :node        node}))]
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

(defn watch-config
  "Watch config-path and restart fdb on changes. Returns a closeable that stops watching on close.
  Use ((-> config-watcher deref :wait)) to wait on the watcher."
  [config-path]
  (let [control-ch (chan (sliding-buffer 1))
        restart!   (fn [_]
                     (log/info "config changed, restarting")
                     (>!! control-ch true))
        stop!      (fn [_]
                     (log/info "shutting down")
                     (close! control-ch))
        process-ch (go
                     (with-open [_config-watcher (watcher/watch config-path restart!)]
                       (loop [restart? true]
                         (if restart?
                           (recur (with-watch [config-path _node]
                                    (log/info "fdb running")
                                    ;; Block waiting for config changes.
                                    (<!! control-ch)))
                           (log/info "shutdown")))))]
    (u/closeable {:wait #(<!! process-ch)} stop!)))

(defn call
  "Call call-spec over id-or-path in fdb. Returns the result of the call.
  Optionally receives a args-xf that will be eval'ed with bindings for config-path,
  doc-path, self-path, and call-arg, and should return a vector of args to apply to sym."
  [config-path id-or-path sym & {:keys [args-xf] :or {args-xf ['call-arg]}}]
  (with-fdb [config-path config node]
    (let [[id path] (when-some [id (metadata/path->id config-path config id-or-path)]
                      [id (metadata/id->path config-path config id)])]
      (if id
        (apply
         (call/to-fn sym)
         (call/eval-under-call-arg
          {:config-path config-path
           :config      config
           :node        node
           :db          (xt/db node)
           :self        (when id (db/pull node id))
           :self-path   path}
          args-xf))
        (log/error "id not found" id-or-path)))))

;; TODO:
;; - consider java-time.api instead of tick
;; - preload clj libs on config and use them in edn call sexprs (waiting for clojure 1.12 release)
;; - validate mounts, don't allow slashes on mount-id, nor empty
;; - allow config to auto-evict based on age, but start with forever
;; - just doing a doc with file listings for the month would already help with taxes
;; - repl file repl.fdb.clj
;;   - starts a repl session, outputs to file, and puts a ;; user> prompt line
;;     - whenever you save the file, it sends everything after the prompt to the repl
;;     - then it puts the result in a comment at the end
;;     - and moves the prompt line to the end
;;     - maybe do .output file instead, like query, and call query one output too
;;       - actually... query.fdb.edn -> results.fdb.edn, and repl.fdb.edn -> output.fdb.edn
;;       - actually looks better to have them different
;;   - should have some binding they can import with call-arg data
;;   - doesn't clear input maybe? so the clj tooling recognizes imports
;; - check https://github.com/clj-commons/marginalia for docs
;; - register protocol to be able to do fdb://name/call/something
;;   - a bit like the Oberon system that had text calls, but only for urls
;;   - urlencode the call args
;; - malli for config validation
;;   - use dev mode https://www.metosin.fi/blog/2024-01-16-malli-data-modelling-for-clojure-developers
;; - ensure sync/call work over running watch, otherwise can't have a live env in lisp terms
;;   - maybe just connect via the repl, and call sync/call
;;   - then watch just stores the current node in an atom, and sync/call use it if available
;; - fdb sync --update /foo/bar/*.md
;;   - handy for when you add a reader
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
