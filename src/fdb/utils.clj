(ns fdb.utils
  (:refer-clojure :exclude [spit])
  (:require
   [clojure.core.async :refer [go-loop timeout <!! >! <! chan pipe sliding-buffer close!]]
   [babashka.fs :as fs]))

(defn spit
  "Writes content to a file and returns file path, creating parent directories if necessary.
  Accepts any number of path fragments followed by content value."
  [& args]
  (let [f (apply fs/file (butlast args))
        content (last args)]
    (-> f fs/parent fs/create-dirs)
    (clojure.core/spit f content)
    (str f)))

(defn pulse
  "Put `true` into ch every `interval` ms until `ch` is closed. Returns `ch`."
  [ch interval]
  (go-loop []
    (when (>! ch true)
      (<! (timeout interval))
      (recur)))
  ch)

(def ^:dynamic *eventually-timeout* 1000)
(def ^:dynamic *eventually-interval* 10)

(defn do-eventually
  [f]
  (let [ch (-> (timeout *eventually-timeout*)
               ;; pipe timeout-ch into chan, closing chan when timeout-ch closes
               (pipe (chan (sliding-buffer 1) (filter (fn [_] (f)))))
               (pulse *eventually-interval*))
        value (<!! ch)]
    (close! ch)
    value))

(defmacro eventually
  [& body]
  `(do-eventually (fn [] ~@body)))
