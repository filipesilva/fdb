(ns fdb.call
  (:refer-clojure :exclude [apply])
  (:require
   [babashka.process :refer [shell]]
   [taoensso.timbre :as log]))

(defmulti to-fn
  "Takes call-spec and returns a function that takes a call-arg.
  call-specs are dispatched by type of x:
  - map:    Use :call key to resolve fn
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

(defmethod to-fn clojure.lang.PersistentArrayMap
  [{:keys [call]}]
  (to-fn call))

(defmethod to-fn clojure.lang.Symbol
  [sym]
  (if (qualified-symbol? sym)
    (requiring-resolve sym)
    (resolve sym)))

(defmethod to-fn clojure.lang.PersistentList
  [sexp]
  (eval sexp))

(defn eval-under-call-arg
  "Evaluates form under common call-arg bindings, i.e.
  (fn [{:keys [config-path doc-path self-path] :as call-arg}]
    <form>)
  Useful to transform call-args from CLI."
  [call-arg form]
  (let [bind-args-fn (eval (list 'fn '[{:keys [config-path doc-path self-path] :as call-arg}] form))]
    (bind-args-fn call-arg)))

(defmethod to-fn clojure.lang.PersistentVector
  [shell-args]
  (fn [call-arg]
    (let [[opts & rest :as all] (eval-under-call-arg call-arg shell-args)
          io-opts               {:out *out* :err *err*}
          shell-args'           (if (map? opts)
                                  (into [(merge io-opts opts)] rest)
                                  (into [io-opts] all))]
      (clojure.core/apply shell shell-args'))))

(defmethod to-fn clojure.lang.Fn
  [f]
  f)

(defn apply
  "Applies call-spec fn to args."
  [call-spec & args]
  (clojure.core/apply (to-fn call-spec) args))

;; TODO:
;; - apply doesn't work quite like clojure.core/apply, which means I can't use it in fdb/call
;;   - calling (apply identity [1]) and (clojure.core/apply identity [1]) have different results
