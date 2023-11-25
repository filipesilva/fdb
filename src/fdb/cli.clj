(ns fdb.cli
  (:refer-clojure :exclude [sync])
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]))

(defn- setup-shutdown-hook!
  [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. f)))

(defn watch [m]
  ;; only load everything when we need it, so we can have fast call and sync
  (let [config-path       (-> m :opts :config fs/absolutize str)
        watch-config-path (requiring-resolve 'fdb.core/watch-config-path)
        config-watcher    (watch-config-path config-path)
        wait              (-> config-watcher deref :wait)]
    (setup-shutdown-hook! (fn []
                            (.close config-watcher)
                            ;; really have to wait here otherwise xtdb rocksdb gets bork
                            (wait)
                            ;; If we don't wait a little bit, errors don't get logged
                            (Thread/sleep 500)))
    (wait)
    ;; TODO: don't print on exit, how?
    :watch-exit))

(defn sync [m]
  (assoc m :fn :sync))

(defn call [m]
  (assoc m :fn :call))

(defn repl [m]
  (assoc m :fn :repl))

(defn help [m]
  #_(assoc m :fn :help)
  (println "help!"))

(def table
  [{:cmds ["watch"]  :fn watch}
   ;; {:cmds ["sync"]   :fn sync}
   ;; {:cmds ["call"]   :fn call}
   ;; {:cmds ["repl"]   :fn repl}
   {:cmds []         :fn help}])

(def spec {:config {:desc    "The FileDB config file."
                    :alias   :c
                    :default "fdbconfig.edn"}})

(defn -main [& args]
  (println (cli/dispatch table args {:spec spec})))
