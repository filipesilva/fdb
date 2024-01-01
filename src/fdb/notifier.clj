(ns fdb.notifier
  "Basic notifier registry based on core.async.
  Lets you register a notifier under a key, notify it, wait for it.
  You can also destroy a notifier, or destroy all notifiers."
  (:refer-clojure :exclude [get])
  (:require [clojure.core.async :refer [close! chan sliding-buffer >!! <!!]]))

(def ^:private *notifiers (atom {}))

(defn destroy!
  [k]
  (swap! *notifiers (fn [notifiers]
                      (when-let [ntf (clojure.core/get notifiers k)]
                        (close! ntf))
                      (dissoc notifiers k)))
  nil)

(defn destroy-all!
  []
  (swap! *notifiers (fn [notifiers]
                      (run! (comp close! second) notifiers)
                      {})))

(defn get
  "Returns the notifier registered under k, if any."
  [k]
  (clojure.core/get @*notifiers k))

(defn create
  "Returns a newly created notifier registered under k, or nil if it already exists."
  [k]
  (let [ntf       (chan (sliding-buffer 1))
        saved-ntf (-> (swap! *notifiers (fn [notifiers]
                                          (if (contains? notifiers k)
                                            notifiers
                                            (assoc notifiers k ntf))))
                      (clojure.core/get k))]
    (if (not= ntf saved-ntf)
      (close! ntf) ;; returns nil
      ntf)))

(defn notify!
  "Notify ntf."
  [ntf]
  (>!! ntf true))

(defn wait
  "Wait for a notification from ntf."
  [ntf]
  (<!! ntf))

;; TODO:
;; - haven't really needed these notifiers much except for watch-config-path, and even
;;   there it's not really needed because I need to use file locks for cross-process
;;   concurrency, so maybe get rid of this ns?
