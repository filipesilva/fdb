(ns fdb.call
  (:require
   [babashka.process :refer [shell]]
   [taoensso.timbre :as log]))

(defmulti to-fn
  "Takes call-spec and returns a function that takes a call-arg.
  call-specs are dispatched by type:
  - symbol: Resolves and returns the var
  - list:   Evaluates and returns the result
  - vec:    Runs shell command via babashka.process/shell
            You can use the shell option map, and the config-path,
            doc-path and self-path bindings."
  (fn [call-spec] (type call-spec)))

(defmethod to-fn :default
  [call-spec]
  (fn [_call-arg]
    (log/error "Unknown call-spec" call-spec)))

(defmethod to-fn clojure.lang.Symbol
  [sym]
  (resolve sym))

(defmethod to-fn clojure.lang.PersistentList
  [sexp]
  (eval sexp))

(defmethod to-fn clojure.lang.PersistentVector
  [shell-args]
  (fn [call-arg]
    (let [bind-args     (eval (list 'fn '[{:keys [config-path doc-path self-path]}] shell-args))
          shell-args'   (bind-args call-arg)
          [opts & rest] shell-args'
          io-opts       {:out *out* :err *err*}
          shell-args''  (if (map? opts)
                          (into [(merge io-opts opts)] rest)
                          (into [io-opts] shell-args'))]
      (apply shell shell-args''))))
