(ns fdb.call
  (:refer-clojure :exclude [apply])
  (:require
   [babashka.fs :as fs]
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
  - symbol  Resolves and returns the sym
  - var     Calls var-get on var
  - list    Evaluates and returns the result
  - :sh     Runs shell command via babashka.process/shell
            You can use the shell option map, and the config-path,
            target-path and self-path bindings."
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

(defmethod to-fn clojure.lang.Var
  [var]
  (var-get var))

(defmethod to-fn clojure.lang.PersistentList
  [sexp]
  (binding [*ns* (create-ns 'user)]
    (eval sexp)))

(defn eval-under-call-arg
  "Evaluates form under common call-arg bindings, i.e.
  (fn [{:keys [config-path target-path self-path] :as call-arg}]
    <form>)
  Useful to transform call-args from CLI."
  [call-arg form]
  (let [bind-args-fn (eval (list 'fn '[{:keys [config-path target-path self-path] :as call-arg}] form))]
    (bind-args-fn call-arg)))

(defmethod to-fn :sh
  [[_ & shell-args]]
  (fn [call-arg]
    (let [[opts & rest :as all] (eval-under-call-arg call-arg (vec shell-args))
          shell-opts            {:dir (-> call-arg :self-path fs/parent str)
                                 :out *out*
                                 :err *err*}
          shell-args'           (if (map? opts)
                                  (into [(merge shell-opts opts)] rest)
                                  (into [shell-opts] all))]
      (clojure.core/apply shell shell-args'))))

(defmethod to-fn clojure.lang.Fn
  [f]
  f)

;; Set by fdb during triggered calls. Nil during repl sessions, but that's what (arg) below is for.
(def ^:dynamic *arg* nil)
;; Set by watch so repl sessions can also get a call-arg, and for restarts after code reload.
(defonce *arg-from-watch (atom nil))

(defmacro with-arg
  "Run body with m merged into current *arg*."
  [m & body]
  `(binding [*arg* (merge *arg* ~m)]
     ~@body))

(defmacro arg
  "The argument sent into trigger and reader calls.
  Also available as a dynamic binding for triggers, readers, repl files, and
  files loaded in fdbconfig.edn."
  []
  `(or
    ;; Calls from triggers, readers, and repl files should have this set.
    *arg*
    ;; If it's not set, it must be a nrepl session.
    ;; Watch should be running so we can get state from there.
    ;; *file* should work from a repl session when it evals a file.
    (merge {:self-path *file*} @*arg-from-watch)))

(defn apply
  "Applies call-spec fn to call-arg, defaulting to current *call*. "
  ([call-spec]
   (apply call-spec *arg*))
  ([call-spec call-arg]
   ((to-fn call-spec) call-arg)))

;; TODO:
;; - maybe get rid of eval-under-call-args and just replace bindings with kws
;; - ref call-spec
;;   - can ref nested keys
;;   - good for shared stuff, say the handler for this trigger is that other one
;;   - would load it from db
;;   - usable for other things too, db would have a resolver for it
;;   - maybe easier is [:ref "user/folder/whatever" :key :another-key]
;;     - no ambiguity
