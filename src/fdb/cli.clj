(ns fdb.cli
  (:refer-clojure :exclude [sync])
  (:require
   [babashka.cli :as cli]))

(defn watch [m]
  ;; only load everything when we need it, so we can have fast call and sync
  (let [watch (requiring-resolve 'fdb.core/watch-config-path)]
    (assoc m :fn :watch)))

(defn sync [m]
  (assoc m :fn :sync))

(defn call [m]
  (assoc m :fn :call))

(defn repl [m]
  (assoc m :fn :repl))

(defn help [m]
  (assoc m :fn :help))

(def table
  [{:cmds ["watch"]  :fn watch}
   {:cmds ["sync"]   :fn sync}
   {:cmds ["call"]   :fn call}
   {:cmds ["repl"]   :fn repl}
   {:cmds []         :fn help}])

(def spec {:config {:desc    "The FileDB config file."
                    :alias   :c
                    :default "fdbconfig.edn"}})

(defn -main [& args]
  (println (cli/dispatch table args {:spec spec})))
