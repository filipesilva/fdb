(ns fdb.utils
  (:refer-clojure :exclude [spit])
  (:require
   [clojure.core.async :refer [timeout alts!!]]
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

(defn do-eventually
  ([f]
   (do-eventually f 1000 10))
  ([f timeout-ms interval-ms]
   (let [timeout-ch (timeout timeout-ms)]
     (loop []
       (or (f)
           (case (alts!! [timeout-ch (timeout interval-ms)])
             timeout-ch  nil
             (recur)))))))

(defmacro eventually
  [& body]
  `(do-eventually (fn [] ~@body)))
