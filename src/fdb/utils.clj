(ns fdb.utils
  "Grab bag of utilities."
  (:refer-clojure :exclude [spit slurp])
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :refer [timeout alts!! <!!]]
   [clojure.edn :as edn]
   [taoensso.timbre :as log]))

(defmacro catch-log
  "Wraps expr in a try/catch that logs to err any exceptions messages, without stack trace."
  [expr]
  `(try ~expr
     (catch Exception e#
       (log/error (ex-message e#))
       nil)))

(defmacro catch-nil
  "Wraps expr in a try/catch that returns nil on err."
  [expr]
  `(try ~expr
     (catch Exception _#)))

(defn spit
  "Writes content to a file and returns file path, creating parent directories if necessary.
  Accepts any number of path fragments followed by content value."
  [& args]
  (let [f (apply fs/file (butlast args))
        content (last args)]
    (-> f fs/parent fs/create-dirs)
    (clojure.core/spit f content)
    (str f)))

(defn slurp
  "Reads content from a file and returns it as a string. Returns nil instead of erroring out.
  Accepts any number of path fragments."
  [& paths]
  (catch-nil
   (-> (apply fs/path paths)
       str
       clojure.core/slurp)))

(defn slurp-edn
  "Reads content from a file and returns it as edn. Returns nil instead of erroring out.
  Accepts any number of path fragments, and uses current *data-readers*."
  [& paths]
  (catch-nil
   (->> (apply slurp paths)
        ;; xt has a bunch of readers, especially around time.
        (edn/read-string {:readers *data-readers*}))))

(defn sibling-path
  "Returns normalized sibling-path relative to file-paths parent."
  [file-path sibling-path]
  (-> file-path fs/parent (fs/file sibling-path) fs/normalize str))

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
  "Close a value if it is not nil."
  [x]
  (when x
    (.close x)))

(defn closeable-seq
  "Returns a closeable, which closes each value in the seq when closed."
  [coll]
  (closeable coll #(run! close %)))

(defn do-eventually
  "Repeatedly calls f ever interval-ms until it returns a truthy value, or timeout-ms has passed.
  timeout-ms defaults to 1000, interval-ms defaults to 50."
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
  "Repeatedly evaluates body until it returns a truthy value, or timeout-ms has passed.
  See do-eventually for defaults."
  [& body]
  `(do-eventually (fn [] ~@body)))

(defn ellipsis
  "Truncates a string to max-len (default 60), adding ellipsis if necessary."
  ([s] (ellipsis s 60))
  ([s max-len]
   (if (> (count s) max-len)
     (str (subs s 0 (- max-len 3)) "...")
     s)))

(defn sleep
  "Sleeps for ms milliseconds."
  [ms]
  (<!! (timeout ms)))
