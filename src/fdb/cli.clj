(ns fdb.cli
  (:refer-clojure :exclude [sync])
  (:require
   [fdb.core :as fdb]
   [babashka.cli :as cli]))

(defn watch [m]
  (assoc m :fn :watch))

(defn sync [m]
  (assoc m :fn :update))

(defn call [m]
  (assoc m :fn :call))

(defn help [m]
  (assoc m :fn :help))

(def table
  [{:cmds ["watch"]  :fn watch   :args->opts [:file]}
   {:cmds ["sync"]   :fn sync    :args->opts [:file]}
   {:cmds ["call"]   :fn call    :args->opts [:file]}
   {:cmds []         :fn help}])

(defn main [& args]
  ;; docs https://github.com/babashka/cli#babashka-tasks
  (println (cli/dispatch table args {:coerce {:depth :long}})))

