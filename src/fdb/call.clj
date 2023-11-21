(ns fdb.call)

(defn to-fn
  [call-spec]
  (fn [call-arg]
    call-arg))
