(ns fdb.cli
  (:refer-clojure :exclude [sync read])
  (:require
   [clojure.core.async :refer [<!! >!! chan close! go-loop sliding-buffer]]
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [fdb.config :as config]
   [fdb.repl :as repl]
   [fdb.state :as state]
   [fdb.utils :as u]
   [taoensso.timbre :as log]
   [taoensso.timbre.appenders.core :as appenders]))

(defn config-file [{{:keys [config]} :opts}]
  (config/file config))

;; Not sure if this is the best way to set the log file,
;; but had trouble using log/with-merged-config before together with core.async stuff
;; presumably because of the weird state machine that core.async does.
;; The trouble was some logs would not show up in the log file, even tho they
;; showed up in the console.
(defn- log-to-file!
  [{{:keys [debug]} :opts :as m}]
  (let [path      (u/sibling-path (config-file m) "fdb.log")
        min-level (if debug :debug :info)]
    (log/merge-config! {:min-level min-level
                        :appenders {:spit (appenders/spit-appender {:fname path})}})))

(defn- setup-shutdown-hook!
  [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. f)))

(defn init [{{:keys [dir demo]} :opts :as m}]
  (log-to-file! m)
  (let [path (config/new-file dir)]
    (if (fs/exists? path)
      (log/error path "already exists!")
      (do
        (u/spit-edn path (cond-> {:db-path    "./db"
                                  :mounts     {}
                                  :readers    {}
                                  :extra-deps {}
                                  :load       []}
                           demo (assoc-in [:mounts :demo]
                                          (u/sibling-path (u/fdb-src) "demo"))))
        (log/info "created new config at" path)))))

(defn watch [m]
  (log-to-file! m)
  (log/info "starting fdb in watch mode")
  ;; only load everything when we need it, so we can have fast call and sync
  (let [watch-config (requiring-resolve 'fdb.core/watch-config)]
    (watch-config (config-file m))))

(defn restart-watch! []
  (>!! @state/*watch-ch :restart))

(defn watch-loop [m watch-ch]
  (go-loop [restart? true]
    (when restart?
      (let [config-watcher (#'watch m)
            restart?       (<!! watch-ch)]
        (.close config-watcher)
        ;; really have to wait here otherwise xtdb rocksdb gets bork
        ((-> config-watcher deref :wait))
        (recur restart?)))))

(defn watch-and-block [m]
  (let [watch-ch (chan (sliding-buffer 1))
        _        (reset! state/*watch-ch watch-ch)
        ;; Add a go-loop to restart the watcher in main thread from repl.
        loop-ch  (watch-loop m watch-ch)]
    ;; Wait for loop to finish on shutdown.
    (setup-shutdown-hook! (fn []
                            (close! watch-ch)
                            (<!! loop-ch)))
    ;; Wait forever.
    @(promise)))

(defn apply-repl-or-local
  [{{:keys [refresh]} :opts :as m} sym & args]
  (let [config-file (config-file m)]
    (if (repl/has-server? config-file)
      (do
        (when refresh
          (repl/apply config-file 'fdb.repl/refresh))
        ;; Note: adds config-file as first arg, because sync/call need it
        (apply repl/apply config-file sym config-file args))
      (let [f (requiring-resolve sym)]
        (apply f args)))))

(defn sync [m]
  (log-to-file! m)
  (apply-repl-or-local m 'fdb.core/sync)
  ;; Don't wait for 1m for futures thread to shut down.
  ;; See https://clojuredocs.org/clojure.core/future
  (shutdown-agents))

(defn read [{{:keys [pattern]} :opts :as m}]
  (log-to-file! m)
  (apply-repl-or-local m 'fdb.core/read (str (fs/cwd)) pattern)
  (shutdown-agents))

(defn refresh [m]
  (log-to-file! m)
  (let [config-file (config-file m)]
    (when-not (repl/has-server? config-file)
      (log/error "no repl server running, can't refresh")
      (System/exit 1))
    (log/info "refreshing source code and restarting watch")
    (repl/apply config-file 'fdb.repl/refresh)
    ;; Couldn't get below to work, so doing it manually
    ;;   (repl/apply config-file 'fdb.repl/refresh :after fdb.cli/restart-watch!)
    (repl/apply config-file 'fdb.cli/restart-watch!)
    (shutdown-agents)))

(defn help [m]
  #_(assoc m :fn :help)
  (println "help!"))

(def spec {:config {:desc    "The FileDB config file."
                    :alias   :c}
           :debug  {:desc    "Print debug info."
                    :alias   :d
                    :default false
                    :coerce  :boolean}
           :refresh {:desc    "Refresh the repl."
                     :alias   :r
                     :default false
                     :coerce  :boolean}})

(def table
  [{:cmds []            :fn help :spec spec}
   {:cmds ["init"]      :fn init :args->opts [:dir]}
   {:cmds ["watch"]     :fn watch-and-block}
   {:cmds ["sync"]      :fn sync}
   {:cmds ["read"]      :fn read :args->opts [:pattern]}
   {:cmds ["refresh"]   :fn refresh}])

(defn -main [& args]
  (cli/dispatch table args))

;; TODO:
;; - resolve fdbconfig.edn up from current dir, like node_modules
;; - is it worth to have sync/call/trigger in the cli while they could be called from the repl/repl-file?
;;   - sync I think always makes sense for the not-watch usecase
;;   - call is gone, trigger doesn't exist and we can debug stuff better than that
;;   - refresh I think could go away, and be called from repl instead
;; - cli could always be a bb script, that calls repl/clojure when needed
;;   - would need a way to start the java process with a repl, which sounds doable
;;   - would also need to pipe out to this process
;;   - the config watch and blocking stuff would need to move to the core too
;;     - might even simplify it
;; - fdb init
;;   - makes cfg at folder
;;   - folder is provided `fdb init .` or defaults to ~
;;   - --demo flag adds demo folder to mounts
;;   - use demo files in tests
;;   - maybe always make some fdb folder with a query and repl file
