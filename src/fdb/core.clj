(ns fdb.core
  "Reactive file metadata database."
  (:require
   [hashp.core]
   [babashka.fs :as fs]
   [clojure.core.async :refer [go <!!]]
   [clojure.edn :as edn]
   #_[clojure.repl.deps :as deps]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.notifier :as notifier]
   [fdb.reactive :as reactive]
   [fdb.reactive.ignore :as r.ignore]
   [fdb.utils :as u]
   [fdb.watcher :as watcher]
   [taoensso.timbre :as log]
   [taoensso.timbre.appenders.core :as appenders]
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
  (let [{:fdb/keys [db-path mount _extra-deps] :as config} (-> config-path slurp edn/read-string)]
    ;; TODO: should just work when clojure 1.12 is released and installed globally
    #_(binding [clojure.core/*repl* true]
      (when extra-deps
        (deps/add-libs extra-deps)))
    ;; Clear the reactive ignore list before attaching the listener.
    (r.ignore/clear config-path)
    (with-open [node            (db/node (u/sibling-path config-path db-path))
                _tx-listener    (db/listen node (partial reactive/on-tx config-path config node))
                ;; Don't do anything about files that were deleted while not watching.
                ;; Might need some sort of purge functionality later.
                ;; We also don't handle renames because they are actually delete+update pairs.
                _mount-watchers (->> mount
                                     (mapv (partial mount-watch-spec config-path node))
                                     watcher/watch-many
                                     u/closeable-seq)]
      ;; Call existing trigger after watcher startup and stale check.
      (reactive/call-all-k config-path config node :fdb.on/startup)
      (reactive/start-all-schedules config-path config node)
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
    (log/merge-config! {:appenders {:spit (appenders/spit-appender {:fname (u/sibling-path config-path "fdb.log")})}})
    (let [refresh (fn [_]
                    (log/info "loading config")
                    (notifier/notify! ntf))
          close   (fn [_]
                    (log/info "shutting down")
                    (notifier/destroy! config-path))
          ch      (go
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
;; - review the whole on, on-ks, trigger names
;; - support file ext processors, e.g. markdown with props
;;   - extract data from content directly to db metadata, without making the metadata file
;;   - avoids lots of clutter in existing dirs
;;   - really good for obsidian or for code ASTs and such
;; - do stale check on with-fdb body instead of on watcher, that way we can use it in run mode
;; - consider java-time.api instead of tick
;; - preload clj libs on config and use them in edn call sexprs (waiting for clojure 1.12 release)
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
;; - web server
;;   - serves mounted paths
;;   - has fdb.server/get and fdb.server/put etc metadata for fns
;;   - does auto content negotiation
;;   - maybe plays well with templates or htmx or whatever
;;   - hicckup files with scripts as a inside-out web-app, kinda like php, code driven by template
;; - feed preprocessor, fetch rss/atom, filter, cache, tag metadata, re-serve locally
;;   - feed server supporting rss cloud, serve anything as a feed (e.g. local file changestream, scrapped sites)
;;   - webserver with rss for changes to any of its files
;; - shadow dir in config, also look for metadata files there, avoids cluttering up dirs
;; - make schedules play nice with sync
;;   - every runs once immediately
;;   - cron saves last execution and runs immediately if missed using cron/times arity 2
;;   - need to make sure to wait on all listeners before exiting
;;     - or don't try to wait, load all files in a big tx, then just call trigger on tx one by one
;;     - this batch load mode is probably better anyway for stale check
;;   - would make tests much easier
;; - validate mounts, don't allow slashes on mount-id
;;   - special :/ ns gets mounted at /, doesn't watch folders in it
;; - naming hard
;;   - "main" file is content, or is it data? makes sense with metadata
;;   - metadata or properties?
;; - file atom, lock file to ensure single access, then swap! to update
;; - always read content for some file types, e.g. json, yaml, xml, html, but allow config
;; - allow config to auto-evict based on age, but start with forever
;; - mtg database, but not in core project
;;   - mtg game engine based on files too
;;     - cards contain hooks for triggered and activated abilities
;;     - counters are just a set of kws, and triggers check them
;;     - a game is a folder with files for each card in each zone
;; - code ast
;;   - maybe fine grained function-level deps like in speculation
;;   - code loader for clojure
;; - just doing a doc with file listings for the month would already help with taxes
;; - just generally try to have stuff I use a lot on-disk and try to come up with cool ways to use it
;; - store as much as possible in txt/md, goal is to be human readable
;; - add debug logging for call eval/require errors
;;   - leave only high level update on info, put rest on debug
;; - don't print mount-id separate from path on stale/update/delete
;; - sort stale by modified id before updating them
;; - repl files repl.fdb.clj and nrepl.fdb.clj
;;   - repl one starts a repl session, outputs to file, and puts a ;; user> prompt line
;;     - whenever you save the file, it sends everything after the prompt to the repl
;;     - then it puts the result in a comment at the end
;;     - and moves the prompt line to the end
;;   - nrepl one starts a nrepl session, outputs port to a nrepl sibling file
;;     - this one is for you to connect to with your editor and mess around
;;   - both these sessions should have some binding they can import with call-arg data
;; use:
;; - cli, process, http-client from babashka
;; - server https://github.com/tonsky/clj-simple-router
;; - docs https://github.com/clj-commons/meta/issues/76
;; - status bar https://github.com/tonsky/AnyBar
