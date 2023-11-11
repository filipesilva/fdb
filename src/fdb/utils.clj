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

(defn closeable
  "From https://medium.com/@maciekszajna/reloaded-workflow-out-of-the-box-be6b5f38ea98
  Used to manage state using with-open, for values that do not implement closeable."
  ([value] (closeable value identity))
  ([value close] (reify
                   clojure.lang.IDeref
                   (deref [_] value)
                   java.io.Closeable
                   (close [_] (close value)))))

(defn closeable-seq
  [coll]
  (closeable coll #(run! (fn [x] (.close x)) %)))

(defn do-eventually
  ([f]
   (do-eventually f 1000 10))
  ([f timeout-ms interval-ms]
   (let [timeout-ch (timeout timeout-ms)]
     (loop []
       (or (f)
           (when-not (-> (alts!! [timeout-ch (timeout interval-ms)])
                         second
                         (= timeout-ch))
             (recur)))))))

(defmacro eventually
  [& body]
  `(do-eventually (fn [] ~@body)))
