(ns fdb.notifier
  "Basic notifier registry based on core.async.
  Lets you register a notifier under a key, notify it, wait for it.
  You can also destroy a notifier, or destroy all notifiers."
  (:require [clojure.core.async :refer [close! chan sliding-buffer >!! <!!]]))

(def ^:private *notifiers (atom {}))

(defn destroy!
  [k]
  (swap! *notifiers (fn [notifiers]
                      (when-let [ntf (get notifiers k)]
                        (close! ntf))
                      (dissoc notifiers k)))
  nil)

(defn destroy-all!
  []
  (swap! *notifiers (fn [notifiers]
                      (run! (comp close! second) notifiers)
                      {})))

(defn get-or-create
  "Returns a notifier registered under k. Will create if not present."
  [k]
  (let [ntf    (chan (sliding-buffer 1))
        added? (-> (swap! *notifiers (fn [notifiers]
                                       (if (contains? notifiers k)
                                         notifiers
                                         (assoc notifiers k ntf))))
                   (get k)
                   (= ntf))]
    (if added?
      ntf
      (close! ntf))))

(defn notify!
  "Notify ntf."
  [ntf]
  (>!! ntf true))

(defn wait
  "Wait for a notification from ntf."
  [ntf]
  (<!! ntf))
