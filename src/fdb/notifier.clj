(ns fdb.notifier
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

(defn create
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
  [ntf]
  (>!! ntf true))

(defn wait
  [ntf]
  (<!! ntf))
