(ns fdb.core
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :refer [go]]
   [clojure.edn :as edn]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.notifier :as notifier]
   [fdb.reactive :as reactive]
   [fdb.utils :as u]
   [fdb.watcher :as watcher]
   [hashp.core]
   [taoensso.timbre :as log]
   [tick.core :as t]))

(defn- host-watch-spec
  [config-path node [host dir]]
  (let [host-path (-> config-path fs/parent (fs/path dir) str)]
    [host-path
     (fn [p]
       (log/info host "updated" p)
       (db/put node (metadata/id host p) (metadata/read (fs/file host-path p))))
     (fn [p]
       (log/info host "deleted" p)
       (let [id   (metadata/id host p)
             data (metadata/read (fs/file host-path p))]
         (if data
           (db/put node id data)
           (db/delete node id))))
     (fn [p]
       (let [db-modified (->> (metadata/id host p) (db/pull node) :fdb/modified)]
         (when (or (not db-modified)
                   (t/> (metadata/modified host-path p) db-modified))
           (log/info host "stale" p)
           true)))]))

(defn do-with-fdb
  [config-path f]
  (let [{:keys [db-path hosts] :as config} (-> config-path slurp edn/read-string)]
    (with-open [node           (db/node db-path)
                _              (u/closeable (reactive/call-all-k config-path config node :fdb.on/startup))
                _              (u/closeable (reactive/start-all-schedules config-path config node))
                _tx-listener   (db/listen node (partial reactive/on-tx node config-path node))
                _host-watchers (->> hosts
                                    (mapv (partial host-watch-spec config-path node))
                                    watcher/watch-many
                                    u/closeable-seq)]
      (f node)
      (reactive/stop-config-path-schedules config-path)
      (reactive/call-all-k config-path config node :fdb.on/shutdown)
      nil)))

(defmacro with-fdb
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[config-path node] & body]
  `(do-with-fdb ~config-path (fn [~node] ~@body)))

(defn watch-config-path
  [config-path]
  (let [ntf     (notifier/create config-path)
        refresh #(notifier/notify! ntf)
        close   #(notifier/destroy! ntf)]
    (when-not ntf
      (throw (ex-info "Server already running" {:config-path config-path})))
    (go
      (log/info "watching config" config-path)
      (with-open [_config-watcher (watcher/watch config-path refresh close (constantly true))]
        (loop [restart? (notifier/wait ntf)]
          (when restart?
            (log/info "restarting with config" config-path)
            (recur (with-fdb [config-path _db]
                     (notifier/wait ntf))))))
      (u/closeable ntf u/close))))

;; TODO:
;; - fdb.on/startup and fdb.on/shutdown triggers, good for servers and repl
;; - preload clj libs on config and use them in edn call sexprs
;; - store data (like config secrets/items/whatever) in config to look up in fns
;; - run mode instead of watch, does initial stale check and calls all triggers
;;   - to use in repos might need a git mode where it replays commits, don't think we
;;     can save xtdb db in git and expect it to work with merges
;;   - really helps if we don't reactively touch files as part of normal operation,
;;     that way one-shot runs do everything in one pass
;; - parse existing ignore files, at least gitignore
;; - cli to call code in on config (running or not), probably just a one-shot repl call
;;   - works really well with run mode, you can choose when to update and run scripts anytime
;; - expose fdb/id or keep xt/id?
;; - hicckup files with scripts as a inside-out web-app, kinda like php, code driven by template
;; - feed preprocessor, fetch rss/atom, filter, cache, tag metadata, re-serve locally
;; - feed server supporting rss cloud, serve anything as a feed (e.g. local file changestream, scrapped sites)
;; - webserver with rss for changes to any of its files
;; use:
;; - cli, process, http-client from babashka
;; - server https://github.com/tonsky/clj-simple-router
;; - docs https://github.com/clj-commons/meta/issues/76
