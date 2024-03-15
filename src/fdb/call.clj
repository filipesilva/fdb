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
  call-specs are dispatched by type of x, or if it's a keyword, the kw itself:
  - map     Use :call key to resolve fn
  - vector  Use first element to resolve fn
  - symbol  Resolves and returns the var
  - list    Evaluates and returns the result
  - :sh     Runs shell command via babashka.process/shell
            You can use the shell option map, and the config-path,
            doc-path and self-path bindings."
  (fn [call-spec]
    (if (vector? call-spec)
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

(defn apply
  "Applies call-spec fn to args."
  [call-spec & args]
  (clojure.core/apply (to-fn call-spec) args))

;; TODO:
;; - apply doesn't work quite like clojure.core/apply, which means I can't use it in fdb/call
;;   - calling (apply identity [1]) and (clojure.core/apply identity [1]) have different results
;; - maybe support fdb/call style arg mapping in call-spec?
;;   - {:call 'u/slurp-edn :args-xf [self-path]}
;;   - instead of '(fn [x] (-> x :self-path u/slurp-end))
;;   - tbh since that's special syntax vs normal fn, it's worse
;; - maybe get rid of eval-under-call-args and just replace bindings with kws
;; - loading a repl file, or I guess a clj file, would be very cool
;;   - would be really easy to make and iterate over small scripts
;;   - string type, for id, would be straightforward
;;   - put call arg in a binding
;;   - :load k on call-spec, loads a clj before calling trigger
;;   - if you don't want to load it all the time, make a lib, add to extra-deps
