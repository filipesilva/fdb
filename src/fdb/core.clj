(ns fdb.core
  "Reactive file metadata database."
  (:require
   #_[clojure.repl.deps :as deps]
   [babashka.fs :as fs]
   [clojure.core.async :refer [go <!!]]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [fdb.call :as call]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.notifier :as notifier]
   [fdb.reactive :as reactive]
   [fdb.reactive.ignore :as r.ignore]
   [fdb.utils :as u]
   [fdb.watcher :as watcher]
   [hashp.core]
   [taoensso.timbre :as log]
   [tick.core :as t]
   [xtdb.api :as xt]))


(defn mount-paths-str
  [mount-id paths]
  (->> paths
       (map (partial metadata/id mount-id))
       (str/join ", ")))

(defn- mount-watch-spec
  "Returns [mount-path update-fn delete-fn stale-fn] for mount."
  [config-path node [mount-id mount-from]]
  (let [mount-path (u/sibling-path config-path mount-from)]
    [mount-path
     ;; update-fn
     (fn [paths]
       (log/info "update" (mount-paths-str mount-id paths))
       (->> paths
            (mapv (fn [p] [(metadata/id mount-id p)
                           (metadata/read (fs/file mount-path p))]))
            (db/put node)))
     ;; delete-fn
     (fn [paths]
       (log/info "delete" (mount-paths-str mount-id paths))
       (let [{:keys [puts deletes]}
             (->> paths
                  (mapv (fn [p] [p (metadata/id mount-id p) (metadata/read (fs/file mount-path p))]))
                  (group-by (fn [[_ _ data]] (if data :puts :deletes))))]
         (db/put node (mapv rest puts))
         (db/delete node (mapv second deletes))))
     ;; stale-fn
     (fn [paths]
       (let [mount-modified (ffirst
                              (xt/q (xt/db node)
                                    '{:find  [(max ?modified)]
                                      :in    [?mount-id]
                                      :where [[?e :xt/id ?id]
                                              ;; TODO: if this is inneficient, we can put split path in metadata
                                              ;; e.g. ["/mount" "/mount/folder" "/mount/folder/file.txt"]
                                              ;; I'd really like efficient folder lookups so might be worth it
                                              [(fdb.metadata/in-mount? ?mount-id ?id)]
                                              [?e :fdb/modified ?modified]]}
                                    mount-id))
             stale-paths    (->> paths
                                 (map    (fn [p] [p (metadata/id mount-id p) (metadata/modified mount-path p)]))
                                 (filter (fn [[_ _ modified]]
                                           ;; t>= because we might not have updated all files for that modified time
                                           (->> [modified mount-modified] (remove nil?) (apply t/>=))))
                                 (filter (fn [[_ id modified]]
                                           ;; t> because we only want to update files that is newer than in the db
                                           (->> [modified (:fdb/modified (db/pull node [:fdb/modified] id))] (remove nil?) (apply t/>))))
                                 (mapv   (fn [[p _ _]] p)))]
         (log/info "stale" (mount-paths-str mount-id paths))
         stale-paths))]))

(defn do-with-watch
  "Call f with a running fdb configured with config-path."
  [config-path f]
  (let [{:fdb/keys [db-path mount _extra-deps] :as config} (-> config-path slurp edn/read-string)]
    ;; TODO: should just work when clojure 1.12 is released and installed globally
    #_(binding [clojure.core/*repl* true]
      (when extra-deps
        (deps/add-libs extra-deps)))
    ;; Clear the reactive ignore list before attaching the listener.
    (r.ignore/clear config-path)
    (with-open [node            (db/node (u/sibling-path config-path db-path))
                _tx-listener    (db/listen node (partial reactive/on-tx config-path config node))
                ;; Start watching before the stale check, so no change is lost.
                all-watch-specs (u/closeable (mapv (partial mount-watch-spec config-path node) mount))
                _mount-watchers (->> @all-watch-specs
                                     (mapv butlast) ;; watch doesn't use stale-fn
                                     watcher/watch-many
                                     u/closeable-seq)]
      ;; Update stale files.
      (run! (fn [[mount-path update-fn _ stale-fn]]
              (->> (watcher/glob mount-path)
                   stale-fn
                   update-fn))
            @all-watch-specs)
      ;; Call existing triggers after watcher startup and stale check.
      (reactive/call-all-k config-path config node :fdb.on/startup)
      (reactive/start-all-schedules config-path config node)
      (let [return (f node)]
        (reactive/stop-config-path-schedules config-path)
        (reactive/call-all-k config-path config node :fdb.on/shutdown)
        return))))

(defmacro with-watch
  "Call body with a running fdb configured with config-path."
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[config-path node] & body]
  `(do-with-watch ~config-path (fn [~node] ~@body)))

(defn watch-config-path
  "Watch config-path and restart fdb on changes. Returns a closeable that stops watching on close.
  Use ((-> config-watcher deref :wait)) to wait on the watcher."
  [config-path]
  (let [ntf (notifier/create config-path)]
    (when-not ntf
      ;; xtdb doesn't support multiple master.
      ;; This doesn't help when multiple processes are watching the same though.
      ;; TODO: Need a mechanism for that too, maybe lock file
      ;; via https://stackoverflow.com/a/11713345 or https://stackoverflow.com/a/6405721
      (throw (ex-info "Server already running" {:config-path config-path})))
    (when-not (fs/exists? config-path)
      (throw (ex-info "Config file not found" {:config-path config-path})))
    ;; TODO: not sure if this works very well when there's multiple in the same process
    ;; I tried using log/with-merged-config but would lose some logs, I think because of
    ;; core.async stuff around watch-config-path
    (let [refresh (fn [_]
                    (log/info "loading config")
                    (notifier/notify! ntf))
          close   (fn [_]
                    (log/info "shutting down")
                    (notifier/destroy! config-path))
          ch      (go
                    (log/info "watching config" config-path)
                    (with-open [_config-watcher (watcher/watch config-path refresh close)]
                      (loop [restart? (notifier/wait ntf)]
                        (when restart?
                          (recur (with-watch [config-path _db]
                                   (log/info "fdb running")
                                   (notifier/wait ntf))))))
                    (log/info "shutdown"))]
      (u/closeable {:wait #(<!! ch) :ntf ntf} close))))

(defn call
  [config-path id-or-path sym]
  (let [{:fdb/keys [db-path] :as config} (-> config-path slurp edn/read-string)]
    (with-open [node (db/node (u/sibling-path config-path db-path))]
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
          (log/error "id not found" id-or-path))))))

;; TODO:
;; - consider java-time.api instead of tick
;; - preload clj libs on config and use them in edn call sexprs (waiting for clojure 1.12 release)
;; - run mode instead of watch, does initial stale check and calls all triggers
;;   - to use in repos might need a git mode where it replays commits, don't think we
;;     can save xtdb db in git and expect it to work with merges
;;   - really helps if we don't reactively touch files as part of normal operation,
;;     that way one-shot runs do everything in one pass
;;   - sync mode is good name
;; - parse existing ignore files, at least gitignore
;; - validate mounts, don't allow slashes on mount-id
;;   - special :/ ns gets mounted at /, doesn't watch folders in it
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
;; - stale db check
;;   - delete metadata for files that don't exist anymore
;;   - list all ids for a given host and delete all that don't have a content or metadata
;;     file on disk
;; - register protocol to be able to do fdb://name/call/something
;;   - a bit like the Oberon system that had text calls, but only for urls
;;   - urlencode the call args
