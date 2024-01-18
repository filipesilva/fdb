(ns fdb.core
  "A reactive database over your files."
  (:refer-clojure :exclude [sync])
  (:require
   #_[clojure.repl.deps :as deps]
   [clojure.core.async :refer [go >!! <!! close! chan sliding-buffer]]
   [clojure.data :as data]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [fdb.call :as call]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.processor :as processor]
   [fdb.reactive :as reactive]
   [fdb.reactive.ignore :as r.ignore]
   [fdb.utils :as u]
   [fdb.watcher :as watcher]
   [hashp.core]
   [taoensso.timbre :as log]
   [tick.core :as t]
   [xtdb.api :as xt]))

(defn do-with-fdb
  "Call f over an initialized fdb."
  [config-path f]
  (let [{:keys [db-path _extra-deps] :as config} (-> config-path slurp edn/read-string)]
    ;; TODO: should just work when clojure 1.12 is released and installed globally
    #_(binding [clojure.core/*repl* true]
      (when extra-deps
        (deps/add-libs extra-deps)))
    (with-open [node (db/node (u/sibling-path config-path db-path))]
      (f config-path config node))))

(defmacro with-fdb
  "Call body with over fdb configured with config-path."
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[config-path config node ] & body]
  `(do-with-fdb ~config-path (fn [~config-path ~config ~node] ~@body)))

(defn update!
  "Read id-or-ids from fs and update them in node. Returns tx without ops."
  [config-path config node id-or-ids]
  (some->> id-or-ids
           u/x-or-xs->xs
           not-empty
           (u/side-effect->> #(log/info "updating" (str/join ", " %)))
           (pmap (fn [id]
                   (if-some [metadata (->> id (metadata/id->path config-path config) metadata/read)]
                     [::xt/put (merge ;; order matters: processor data, then metadata, then id
                                      ;; metadata overrides processor data, id overrides all
                                      (processor/read config-path config id)
                                      metadata
                                      {:xt/id id})]
                     [::xt/delete id])))
           (xt/submit-tx node)))

(defn stale
  "Returns all ids that are out of sync between fs and node."
  [config-path {:keys [mounts]} node]
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
    stale-ids))

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
  (binding [reactive/*sync* true]
    (with-fdb [config-path {:keys [mounts] :as config} node]
      (reactive/call-all-k config-path config node :fdb.on/startup)
      ;; Update stale files.
      (let [[stale-ids tx] (update-stale! config-path config node)]
        (when tx
          (xt/await-tx node tx)
          ;; TODO: sync call missed cron schedules
          (reactive/on-tx config-path config node (db/tx-with-ops node tx)))
        (reactive/call-all-k config-path config node :fdb.on/shutdown)
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
  (with-fdb [config-path {:keys [mounts] :as config} node]
    (r.ignore/clear config-path)
    (reactive/call-all-k config-path config node :fdb.on/startup)
    (with-open [_tx-listener    (xt/listen node
                                           {::xt/event-type ::xt/indexed-tx
                                            :with-tx-ops?   true}
                                           (partial reactive/on-tx config-path config node))
                ;; Start watching before the stale check, so no change is lost.
                ;; TODO: don't tx anything before the stale update
                _mount-watchers (->> mounts
                                     (map (partial mount->watch-spec config config-path node))
                                     watcher/watch-many
                                     u/closeable-seq)]
      (update-stale! config-path config node)
      (reactive/start-all-schedules config-path config node)
      (let [return (f node)]
        (reactive/stop-config-path-schedules config-path)
        (reactive/call-all-k config-path config node :fdb.on/shutdown)
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
                           (with-watch [config-path _node]
                             (log/info "fdb running")
                             ;; Block waiting for config changes.
                             (recur (<!! control-ch)))
                           (log/info "shutdown")))))]
    (u/closeable {:wait #(<!! process-ch)} stop!)))

(defn call
  "Call call-spec over id-or-path in fdb. Returns the result of the call."
  [config-path id-or-path sym]
  (with-fdb [config-path config node]
    (let [[id path] (when-some [id (metadata/path->id config-path config id-or-path)]
                      [id (metadata/id->path config-path config id)])]
      (if id
        ((call/to-fn sym)
         {:config-path config-path
          :config      config
          :node        node
          :db          (xt/db node)
          :self        (when id (db/pull node id))
          :self-path   path})
        (log/error "id not found" id-or-path)))))

;; TODO:
;; - consider java-time.api instead of tick
;; - preload clj libs on config and use them in edn call sexprs (waiting for clojure 1.12 release)
;; - validate mounts, don't allow slashes on mount-id, nor empty
;; - allow config to auto-evict based on age, but start with forever
;; - just doing a doc with file listings for the month would already help with taxes
;; - add debug logging for call eval/require errors
;;   - leave only high level update on info, put rest on debug
;; - repl files repl.fdb.clj and nrepl.fdb.clj
;;   - repl one starts a repl session, outputs to file, and puts a ;; user> prompt line
;;     - whenever you save the file, it sends everything after the prompt to the repl
;;     - then it puts the result in a comment at the end
;;     - and moves the prompt line to the end
;;   - nrepl one starts a nrepl session, outputs port to a nrepl sibling file
;;     - this one is for you to connect to with your editor and mess around
;;   - both these sessions should have some binding they can import with call-arg data
;; - check https://github.com/clj-commons/marginalia for docs
;; - register protocol to be able to do fdb://name/call/something
;;   - a bit like the Oberon system that had text calls, but only for urls
;;   - urlencode the call args
;; - pass args to call
