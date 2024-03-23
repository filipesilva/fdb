(ns fdb.call
  (:refer-clojure :exclude [apply])
  (:require
   [babashka.process :refer [shell]]
   [fdb.utils :as u]
   [taoensso.timbre :as log]))

(defn specs
  "Returns a seq of call-specs from x."
  [x]
  (cond
    ;; looks like a sexp call-spec
    (list? x)
    [x]

    ;; looks like a vector call-spec
    (and (vector? x)
         (keyword? (first x)))
    [x]

    :else
    (u/one-or-many x)))

(defmulti to-fn
  "Takes call-spec and returns a function that takes a call-arg.
  call-specs are dispatched by type of x, or by keyword if it's
  a vector with a keyword first element:
  - map     Use :call key to resolve fn
  - symbol  Resolves and returns the var
  - list    Evaluates and returns the result
  - :sh     Runs shell command via babashka.process/shell
            You can use the shell option map, and the config-path,
            doc-path and self-path bindings."
  (fn [call-spec]
    (if (and (vector? call-spec)
             (keyword? (first call-spec)))
      (first call-spec)
      (type call-spec))))

(defmethod to-fn :default
  [call-spec]
  (fn [_call-arg]
    (log/error "Unknown call-spec" call-spec)))

(defmethod to-fn clojure.lang.PersistentArrayMap
  [{:keys [call]}]
  (to-fn call))

(defmethod to-fn clojure.lang.Symbol
  [sym]
  (binding [*ns* (create-ns 'user)]
    (if (qualified-symbol? sym)
      (requiring-resolve sym)
      (resolve sym))))

(defmethod to-fn clojure.lang.PersistentList
  [sexp]
  (binding [*ns* (create-ns 'user)]
    (eval sexp)))

(defn eval-under-call-arg
  "Evaluates form under common call-arg bindings, i.e.
  (fn [{:keys [config-path doc-path self-path] :as call-arg}]
    <form>)
  Useful to transform call-args from CLI."
  [call-arg form]
  (let [bind-args-fn (eval (list 'fn '[{:keys [config-path doc-path self-path] :as call-arg}] form))]
    (bind-args-fn call-arg)))

(defmethod to-fn :sh
  [[_ & shell-args]]
  (fn [call-arg]
    (let [[opts & rest :as all] (eval-under-call-arg call-arg (vec shell-args))
          io-opts               {:out *out* :err *err*}
          shell-args'           (if (map? opts)
                                  (into [(merge io-opts opts)] rest)
                                  (into [io-opts] all))]
      (clojure.core/apply shell shell-args'))))

(defmethod to-fn clojure.lang.Fn
  [f]
  f)

;; The argument sent into trigger and reader calls.
;; Also available as a dynamic binding for triggers, readers, repl files, and
;; files loaded in fdbconfig.edn.
(def ^:dynamic *call-arg* nil)

(defn apply
  "Applies call-spec fn to call-arg.
  Binds call-arg to fdb.call/*call-arg*."
  [call-spec call-arg]
  (binding [*call-arg* call-arg]
    ((to-fn call-spec) call-arg)))

;; TODO:
;; - maybe get rid of eval-under-call-args and just replace bindings with kws
