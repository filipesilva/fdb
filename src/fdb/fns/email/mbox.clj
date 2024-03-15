(ns fdb.fns.email.mbox
  "Process .mbox files.
  See https://en.wikipedia.org/wiki/Mbox"
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [fdb.fns.email.eml :as eml]
   [fdb.utils :as u]
   [taoensso.timbre :as log]))

(defn write-message
  [path message message-number]
  (let [filename (->> message eml/filename (fs/file path) str)]
    (log/info "writing" (str "#" message-number) filename)
    (spit filename message)))

;; Permissive version of mime4j mbox regex
;; org.apache.james.mime4j.mboxiterator.FromLinePatterns/DEFAULT2
@(def line-re #"^From \S+.*\d{4}$")

(defn from-line?
    [line]
    (->> line (re-matches line-re) boolean))

;; MboxIterator from mime4j blows up on big mboxes, rolling my own
(defn mbox->eml
  [mbox-path & {:keys [to-folder-path drop-n]
                :or   {to-folder-path (u/sibling-path mbox-path (u/filename-without-extension mbox-path "mbox"))
                       drop-n         0}}]
  (when-not (fs/exists? to-folder-path)
    (fs/create-dir to-folder-path))
  (with-open [rdr (clojure.java.io/reader mbox-path)]
    (let [counter (volatile! drop-n)]
      (doseq [message (sequence
                       ;; potentially big file, worth it to use transducer
                       (comp
                        (partition-by from-line?)
                        (partition-all 2)
                        (drop drop-n)
                        (map second)
                        (map (partial str/join "\n")))
                       (line-seq rdr))]
        (write-message to-folder-path message (vswap! counter inc))))))

(comment
  (let [mbox-path      (fs/file (io/resource "email/sample-crlf.mbox"))
        to-folder-path (fs/file (fs/parent mbox-path) "sample-crlf")]
    (fs/delete-tree to-folder-path)
    (mbox->eml mbox-path to-folder-path)))

;; - make a version usable in reader, or just call from repl file
