(ns fdb.utils
  (:refer-clojure :exclude [spit])
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :refer [timeout alts!! <!!]]
   [clojure.edn :as edn]
   [taoensso.timbre :as log]))

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

(defn close
  [x]
  (when x
    (.close x)))

(defn closeable-seq
  [coll]
  (closeable coll #(run! close %)))

(defn do-eventually
  ([f]
   (do-eventually f 1000 50))
  ([f timeout-ms]
   (do-eventually f timeout-ms 50))
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

(defmacro catch-log
  [expr]
  `(try ~expr
     (catch Exception e#
       (log/error e#)
       nil)))

(defn ellipsis
  ([s] (ellipsis s 60))
  ([s max-len]
   (if (> (count s) max-len)
     (str (subs s 0 (- max-len 3)) "...")
     s)))

(defn sleep
  [ms]
  (<!! (timeout ms)))

(defn slurp-edn
  [& paths]
  (try
    (-> (apply fs/path paths)
        str
        slurp
        edn/read-string)
    (catch Exception _)))
