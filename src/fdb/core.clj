(ns fdb.core
  "Reactive file metadata database."
  (:require
   [hashp.core]
   [babashka.fs :as fs]
   [clojure.core.async :refer [go <!!]]
   [clojure.edn :as edn]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.notifier :as notifier]
   [fdb.reactive :as reactive]
   [fdb.utils :as u]
   [fdb.watcher :as watcher]
   [taoensso.timbre :as log]
   [tick.core :as t]))

(defn- mount-watch-spec
  "Returns watcher/watch args for mounts."
  [config-path node [mount-id mount-from]]
  (let [mount-path (u/sibling-path config-path mount-from)]
    [mount-path
     (fn [p]
       (log/info mount-id "updated" p)
       (db/put node (metadata/id mount-id p) (metadata/read (fs/file mount-path p))))
     (fn [p]
       (log/info mount-id "deleted" p)
       (let [id   (metadata/id mount-id p)
             data (metadata/read (fs/file mount-path p))]
         (if data
           (db/put node id data)
           (db/delete node id))))
     (fn [p]
       (let [db-modified (->> (metadata/id mount-id p) (db/pull node) :fdb/modified)]
         (when (or (not db-modified)
                   (t/> (metadata/modified mount-path p) db-modified))
           (log/info mount-id "stale" p)
           true)))]))

(defn do-with-fdb
  "Call f with a running fdb configured with config-path."
  [config-path f]
  (let [{:keys [db-path mount] :as config} (-> config-path slurp edn/read-string)]
    (with-open [node            (db/node (u/sibling-path config-path db-path))
                _               (u/closeable (reactive/call-all-k config-path config node :fdb.on/startup))
                _               (u/closeable (reactive/start-all-schedules config-path config node))
                _tx-listener    (db/listen node (partial reactive/on-tx config-path config node))
                ;; Don't do anything about files that were deleted while not watching.
                ;; Might need some sort of purge functionality later.
                ;; We also don't handle renames because they are actually delete+update pairs.
                _mount-watchers (->> mount
                                     (mapv (partial mount-watch-spec config-path node))
                                     watcher/watch-many
                                     u/closeable-seq)]
      (let [return (f node)]
        (reactive/stop-config-path-schedules config-path)
        (reactive/call-all-k config-path config node :fdb.on/shutdown)
        return))))

(defmacro with-fdb
  "Call body with a running fdb configured with config-path."
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[config-path node] & body]
  `(do-with-fdb ~config-path (fn [~node] ~@body)))

(defn watch-config-path
  "Watch config-path and restart fdb on changes. Returns a closeable that stops watching on close.
  Use ((-> config-watcher deref :wait)) to wait on the watcher."
  [config-path]
  (let [ntf     (notifier/create config-path)
        refresh (fn [_]
                  (log/info "loading config")
                  (notifier/notify! ntf))
        close   (fn [_]
                  (log/info "shutting down")
                  (notifier/destroy! config-path))]
    (when-not ntf
      ;; xtdb doesn't support multiple master.
      ;; This doesn't help when multiple processes are watching the same though.
      ;; TODO: Need a mechanism for that too, maybe lock file
      ;; via https://stackoverflow.com/a/11713345 or https://stackoverflow.com/a/6405721
      (throw (ex-info "Server already running" {:config-path config-path})))
    (when-not (fs/exists? config-path)
      (throw (ex-info "Config file not found" {:config-path config-path})))
    (let [ch (go
               (log/info "watching config" config-path)
               (with-open [_config-watcher (watcher/watch config-path refresh close (constantly true))]
                 (loop [restart? (notifier/wait ntf)]
                   (when restart?
                     (recur (with-fdb [config-path _db]
                              (log/info "fdb running")
                              (notifier/wait ntf))))))
               (log/info "shutdown"))]
      (u/closeable {:wait #(<!! ch) :ntf ntf} close))))

;; TODO:
;; - preload clj libs on config and use them in edn call sexprs
;; - run mode instead of watch, does initial stale check and calls all triggers
;;   - to use in repos might need a git mode where it replays commits, don't think we
;;     can save xtdb db in git and expect it to work with merges
;;   - really helps if we don't reactively touch files as part of normal operation,
;;     that way one-shot runs do everything in one pass
;;   - sync mode is good name
;; - parse existing ignore files, at least gitignore
;; - cli to call code in on config (running or not), probably just a one-shot repl call
;;   - works really well with run mode, you can choose when to update and run scripts anytime
;;   - probably needs always-on repl
;; - expose fdb/id or keep xt/id?
;; - hicckup files with scripts as a inside-out web-app, kinda like php, code driven by template
;; - feed preprocessor, fetch rss/atom, filter, cache, tag metadata, re-serve locally
;; - feed server supporting rss cloud, serve anything as a feed (e.g. local file changestream, scrapped sites)
;; - webserver with rss for changes to any of its files
;; - shadow dir in config, also look for metadata files there, avoids cluttering up dirs
;; - make schedules play nice with sync
;;   - millis runs once immediately
;;   - cron saves last execution and runs immediately if missed
;;   - need to make sure to wait on all listeners before exiting
;;   - would make tests much easier
;; - leave a log in config-path
;; - validate mounts, don't allow slashes on mount-id
;;   - special :/ ns gets mounted at /, doesn't watch folders in it
;; - naming hard
;;   - "main" file is content, or is it data? makes sense with metadata
;;   - metadata or properties?
;; - file atom, lock file to ensure single access, then swap! to update
;; - namespace config paths, and then let rest of it as user keys
;; - fdb reference, cli command that prints a reference metadata file, can pipe it to a real file
;; - spit-edn in utils, pprints etc
;; - always read content for some file types, e.g. json, yaml, xml, html, but allow config
;; - allow config to auto-evict based on age, but start with forever
;; use:
;; - cli, process, http-client from babashka
;; - server https://github.com/tonsky/clj-simple-router
;; - docs https://github.com/clj-commons/meta/issues/76
;; - status bar https://github.com/tonsky/AnyBar
