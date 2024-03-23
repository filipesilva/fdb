(ns fdb.utils
  "Grab bag of utilities."
  (:refer-clojure :exclude [spit slurp])
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :refer [timeout alts!! <!!]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [puget.printer :as puget]
   [taoensso.timbre :as log]
   [tick.core :as t])
  (:import
   (java.io RandomAccessFile)))

(defn one-or-many
  "Returns x if it's a set, sequential, or nil, otherwise [x]."
  [x]
  (if ((some-fn sequential? set? nil?) x)
    x
    [x]))

(defn side-effect->>
  "Call f with x (presumably for side effects), then return x."
  [f x]
  (f x)
  x)

(defmacro catch-log
  "Wraps expr in a try/catch that logs to err any exceptions messages, without stack trace."
  [expr]
  `(try ~expr
     (catch Exception e#
       (log/error (str (str/replace-first (type e#) "class " "") ":")
                  (or (ex-message e#) "<no message>"))
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

(defn edn-str
  [edn]
  (with-out-str (puget/pprint edn {:map-delimiter ""})))

(defn spit-edn
  "Same as spit but writes it pretty printed as edn."
  [& args]
  (apply spit (concat (butlast args) (list (edn-str (last args))))))

(defn slurp
  "Reads content from a file and returns it as a string. Returns nil instead of erroring out.
  Accepts any number of path fragments."
  [& paths]
  (catch-nil
   (-> (apply fs/path paths)
       str
       clojure.core/slurp)))

(defn read-edn
  [s]
  ;; xt has a bunch of readers, especially around time.
  (catch-nil (edn/read-string {:readers *data-readers*} s)))

(defn slurp-edn
  "Same as slurp but reads it as edn, using current *data-readers*."
  [& paths]
  (->> (apply slurp paths)
       read-edn))

(defn sibling-path
  "Returns normalized sibling-path relative to file-paths parent.
  If sibling-path is absolute, returns it."
  [file-path sibling-path]
  (if (fs/absolute? sibling-path)
    (str sibling-path)
    (-> file-path fs/parent (fs/file sibling-path) fs/normalize str)))

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

(defn closeable-atom
  "Returns a closeable, which resets an atom to value and back to nil on close."
  [atom value]
  (closeable (reset! atom value) (fn [_] (reset! atom nil))))

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

(defn- high-surrogate? [char-code]
  (<= 0xD800 char-code 0xDBFF))

(defn- char-code-at [^String str pos]
  (long ^Character (.charAt str pos)))

(defn char-seq
  "Return a seq of the characters in a string, making sure not to split up
  UCS-2 (or is it UTF-16?) surrogate pairs. Because JavaScript. And Java.
  From https://lambdaisland.com/blog/2017-06-12-clojure-gotchas-surrogate-pairs"
  ([str]
   (char-seq str 0))
  ([str offset]
   (loop [offset offset
          res []]
     (if (>= offset (count str))
       res
       (let [code (char-code-at str offset)
             width (if (high-surrogate? code) 2 1)
             next-offset (+ offset width)
             cur-char (subs str offset next-offset)]
         (recur next-offset
                (conj res cur-char)))))))

(defn ellipsis
  "Truncates a string to max-len (default 60), adding ellipsis if necessary."
  ([s] (ellipsis s 60))
  ([s max-len]
   (if (> (count s) max-len)
     (str (apply str (take (- max-len 3) (char-seq s)))
          "...")
     s)))

(defn sleep
  "Sleeps for ms milliseconds."
  [ms]
  (<!! (timeout ms)))

(defmacro with-time
  "Binds time-ms to a fn that returns the number of elapsed ms."
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[time-ms f] & body]
  `(let [start#   (System/nanoTime)
         ~time-ms #(/ (- (System/nanoTime) start#) 1e6)]
     (try
       ~@body
       (finally
         (when ~f
           (apply ~f []))))))

(defn lockfile
  "Returns a closeable that attempts to lock the file at paths.
  Deref'ing the closeable returns the lock if acquired or nil."
  [& paths]
  (let [file    (apply fs/file paths)
        channel (.getChannel (RandomAccessFile. file "rw"))]
    (closeable
     (catch-nil (.tryLock channel))
     (fn [_] (.close channel)))))

(defn swap-edn-file!
  "Like swap! over an edn file, but does not ensure atomicity by itself.
  Use with lockfile for exclusive access, and with eventually for retries.
  Throws if file does not exist. "
  [path f & args]
  (if-not (fs/exists? path)
    (throw (ex-info "File not found" {:path path}))
    (let [edn (slurp-edn path)
          ret (apply f edn args)]
      (spit-edn path ret)
      ret)))

(defn filename-inst
  "Returns a filename friendly version of inst, with : replaced by .
  e.g. 2023-11-30T14:20:23 -> 2023-11-30T14.20.23Z
  Parse it back to inst by replacing . with : again."
  [inst]
  (-> inst
      t/instant
      str
      (str/replace ":" ".")))

(defn filename-str
  "Returns a filename friendly version of s.
  Banned characters from https://stackoverflow.com/a/35352640
  Control & unused category from https://www.regular-expressions.info/unicode.html#category"
  [s]
  (-> s
      (str/replace #"\p{C}" "")
      (str/replace #"[\\/:*?\"<>|]" " ")
      str/trim))

(defn unix-line-separators
  "Converts line separators to \n."
  [s]
  (-> s
      (str/replace #"\r\n" "\n")
      (str/replace #"\r" "\n")))

(defn duration-ms
  [duration]
  (cond
    (number? duration)           duration
    (and (vector? duration)
         (= (count duration) 2)) (t/millis (apply t/new-duration duration))
    :else                        nil))

(defn maybe-timeout
  [timeout f]
  (let [timeout-ms (duration-ms timeout)
        fut        (future (f))
        ret        (try (if timeout-ms
                          (deref fut timeout-ms ::timeout)
                          (deref fut))
                        (catch InterruptedException _
                          ;; The future can still be interrupted from outside this fn.
                          ::interrupted))]
    (when (and timeout-ms
               (#{::timeout ::interrupted} ret))
      (catch-nil (future-cancel fut)))
    ret))

(defn filename-without-extension
  [path ext]
  (-> path
      fs/file-name
      (fs/split-ext {:ext ext})
      first))

(defn strip-nil-empty
  "Removes nil and empty values from a map."
  [m]
  (->> m
       (filter (fn [[_ v]]
                 (and (not (nil? v))
                      (or (not (coll? v))
                          (seq v)))))
       (into {})))

(defmacro current-file
  "Returns file path of the current file."
  []
  `(:file (meta (defn foo# []))))

(defn fdb-root
  "Returns fdb root path."
  []
  (-> (io/resource "file.txt")
      (fs/path  "../../")
      fs/normalize
      str))

(defn as-comments
  "Return s as a clojure comment."
  [s & {:keys [prefix]}]
  (let [first-line (str ";; " prefix)
        other-lines (str ";; " (apply str (repeat (count prefix) " ")))]
    (str first-line (str/replace s #"\n(?!\Z)" (str "\n" other-lines)))))

(defn eval-to-comment
  "Eval form to a comment string."
  [form]
  (try
    (let [*val        (atom nil)
          out-and-err (with-out-str
                        (binding [*err* *out*]
                          (reset! *val (load-string form))))]
      (str
       (when-not (empty? out-and-err) (as-comments out-and-err))
       (as-comments (with-out-str (pprint/pprint @*val)) :prefix "=> ")))
    (catch Exception e
      (as-comments (with-out-str (pprint/pprint e))))))

;; TODO:
;; - str-path fn
;; - the watch-config and watch-and-block loop are very similar
;;   - can probably capure the "stop and wait" returns somehow
;;   - maybe a closeable-go ?
;;   - it's a bit more complex... it can be restarted/stopped
;; - all the string converstions for paths are starting to piss me off
;;   - maybe it's just adding a u/path that returns a str
;; - puget/pprint is a bit different than pprint/pprint for large stuff
;;   - e.g. printing call-arg in a repl file
;;   - figure out how and if I can just use one of them
