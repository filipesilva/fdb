(ns fdb.cli
  (:refer-clojure :exclude [sync])
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [fdb.repl :as repl]
   [fdb.utils :as u]
   [taoensso.timbre :as log]
   [taoensso.timbre.appenders.core :as appenders]))

(defn config-path [m]
  (-> m :opts :config fs/absolutize str))

;; Not sure if this is the best way to set the log file,
;; but had trouble using log/with-merged-config before together with core.async stuff
;; presumably because of the weird state machine that core.async does.
;; The trouble was some logs would not show up in the log file, even tho they
;; showed up in the console.
(defn- log-to-file!
  [m]
  (let [path      (u/sibling-path (config-path m) "fdb.log")
        min-level (if (-> m :opts :debug) :debug :info)]
    (log/merge-config! {:min-level min-level
                        :appenders {:spit (appenders/spit-appender {:fname path})}})))

(defn- setup-shutdown-hook!
  [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. f)))

(defn watch [m]
  (log-to-file! m)
  (log/info "starting fdb in watch mode")
  ;; only load everything when we need it, so we can have fast call and sync
  (let [config-path    (-> m :opts :config fs/absolutize str)
        watch-config   (requiring-resolve 'fdb.core/watch-config)
        config-watcher (watch-config config-path)
        wait           (-> config-watcher deref :wait)]
    (setup-shutdown-hook! (fn []
                            (.close config-watcher)
                            ;; really have to wait here otherwise xtdb rocksdb gets bork
                            (wait)
                            ;; If we don't wait a little bit, errors don't get logged
                            (Thread/sleep 500)))
    (wait)
    :watch-exit))

(defn apply-repl-or-local
  [config-path refresh? sym & args]
  (if (repl/has-server? config-path)
    (do
      (when refresh?
        (repl/apply config-path 'fdb.repl/refresh))
      ;; Note: adds config-path as first arg, because sync/call need it
      (apply repl/apply config-path sym config-path args))
    (let [f (requiring-resolve sym)]
      (apply f args))))

(defn sync [m]
  (log-to-file! m)
  (apply-repl-or-local (config-path m) (-> m :opts :refresh) 'fdb.core/sync)
  ;; Don't wait for 1m for futures thread to shut down.
  ;; See https://clojuredocs.org/clojure.core/future
  (shutdown-agents))

(defn call [{{:keys [id-or-path sym args-xf]} :opts :as m}]
  (log-to-file! m)
  (log/info (apply-repl-or-local (config-path m) (-> m :opts :refresh)
                                 'fdb.core/call
                                 (str (fs/absolutize id-or-path)) sym
                                 (when args-xf {:args-xf args-xf})))
  (shutdown-agents))

(defn refresh [m]
  (log-to-file! m)
  (let [config-path (config-path m)]
    (when-not (repl/has-server? config-path)
      (log/error "no repl server running, can't refresh")
      (System/exit 1))
    (repl/apply config-path 'fdb.repl/refresh)
    (log/info "refreshed fdb")
    (shutdown-agents)))

(defn repl [m]
  (assoc m :fn :repl))

(defn reference [_]
  (println
"{:xt/id           \"/example/doc.txt\"
 :fdb/modified    \"2021-03-21T20:00:00.000-00:00\"
 :fdb/refs        #{\"/test/two.txt\"
                    \"/test/three.txt\"
                    \"/test/folder\"}
 :fdb.on/modify   println ;; same as {:call println}
 :fdb.on/refs     [println] ;; can pass a single one or a vec
 :fdb.on/pattern  {:glob \"/test/*.txt\"
                   :call println} ;; you can pass in extra properties on this map
 :fdb.on/query    {:q    [:find ?e :where [?e :file/modified ?m]]
                   :path \"./query-results.edn\"
                   :call println}
 :fdb.on/tx       println
 :fdb.on/schedule {:cron \"0 0 0 * * ?\" ;; https://crontab.guru/
                   ;; or :every [1 :seconds]
                   :call println}
 :fdb.on/startup  println
 :fdb.on/shutdown println}"))

(defn help [m]
  #_(assoc m :fn :help)
  (println "help!"))

(def spec {:config {:desc    "The FileDB config file."
                    :alias   :c
                    :default "fdbconfig.edn"}
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
   {:cmds ["watch"]     :fn watch}
   {:cmds ["reference"] :fn reference}
   {:cmds ["refresh"]   :fn refresh}
   {:cmds ["sync"]      :fn sync}
   {:cmds ["call"]      :fn call
    :args->opts [:id-or-path :sym :args-xf]
    :coerce {:sym :symbol :args-xf :edn}}])

(defn -main [& args]
  (cli/dispatch table args))

;; TODO:
;; - reference metadata, reference config
;; - resolve fdbconfig.edn up from current dir, like node_modules
;; - fdb example outputs config, files, etc, readme uses it
;; - is it worth to have sync/call/trigger in the cli while they could be called from the repl/repl-file?
