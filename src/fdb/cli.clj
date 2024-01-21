(ns fdb.cli
  (:refer-clojure :exclude [sync])
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [fdb.utils :as u]
   [taoensso.timbre :as log]
   [taoensso.timbre.appenders.core :as appenders]))

;; Not sure if this is the best way to set the log file,
;; but had trouble using log/with-merged-config before together with core.async stuff
;; presumably because of the weird state machine that core.async does.
;; The trouble was some logs would not show up in the log file, even tho they
;; showed up in the console.
(defn- log-to-file!
  [m]
  (let [config-path (-> m :opts :config fs/absolutize str)
        path (u/sibling-path config-path "fdb.log")]
    (log/merge-config! {:appenders {:spit (appenders/spit-appender {:fname path})}})))

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

(defn sync [m]
  (log-to-file! m)
  (let [config-path (-> m :opts :config fs/absolutize str)
        sync        (requiring-resolve 'fdb.core/sync)]
    (sync config-path)
    ;; Don't wait for 1m for futures thread to shut down.
    ;; See https://clojuredocs.org/clojure.core/future
    (shutdown-agents)))

(defn call [{{:keys [id-or-path sym args-xf]} :opts :as m}]
  (log-to-file! m)
  (let [config-path' (-> m :opts :config fs/absolutize str)
        call         (requiring-resolve 'fdb.core/call)]
    (log/info (call config-path' (fs/absolutize id-or-path) sym
                    (when args-xf {:args-xf args-xf})))))

(defn repl [m]
  (assoc m :fn :repl))

(defn reference [_]
  (println
"{:xt/id           \"/example/doc.txt\"
 :fdb/modified    \"2021-03-21T20:00:00.000-00:00\"
 :fdb/refs        #{\"/test/two.txt\"
                    \"/test/three.txt\"
                    \"/test/folder\"}
 :fdb.on/modify   [println] ;; same as {:call println}
 :fdb.on/refs     [println]
 :fdb.on/pattern  [{:glob \"/test/*.txt\"
                    :call println}] ;; you can pass in extra properties on this map
 :fdb.on/query    [{:q    [:find ?e :where [?e :file/modified ?m]]
                    :path \"./query-results.edn\"
                    :call println}]
 :fdb.on/tx       [println]
 :fdb.on/schedule [{:cron \"0 0 0 * * ?\" ;; https://crontab.guru/
                    ;; or :every [1 :seconds]
                    :call println}]
 :fdb.on/startup  [println]
 :fdb.on/shutdown [println]}"))

(defn help [m]
  #_(assoc m :fn :help)
  (println "help!"))

(def spec {:config {:desc    "The FileDB config file."
                    :alias   :c
                    :default "fdbconfig.edn"}})

(def table
  [{:cmds []            :spec spec}
   {:cmds ["watch"]     :fn watch}
   {:cmds ["reference"] :fn reference}
   {:cmds ["sync"]      :fn sync}
   {:cmds ["call"]      :fn call
    :args->opts [:id-or-path :sym :args-xf]
    :coerce {:sym :symbol :args-xf :edn}}
   ;; {:cmds ["repl"]   :fn repl}
   {:cmds []         :fn help}])

(defn -main [& args]
  (cli/dispatch table args))

;; TODO:
;; - reference metadata, reference config
;; - resolve fdbconfig.edn up from current dir, like node_modules
;; - support call over a running watch
;; - fdb example outputs config, files, etc, readme uses it
