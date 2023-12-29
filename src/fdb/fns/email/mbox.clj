(ns fdb.fns.email.mbox
  "Process .mbox files.
  See https://en.wikipedia.org/wiki/Mbox"
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [fdb.utils :as u]
   [taoensso.timbre :as log]
   [tick.core :as t])
  (:import
   [org.apache.james.mime4j.mboxiterator MboxIterator FromLinePatterns]
   [org.apache.james.mime4j.message DefaultMessageBuilder]
   [org.apache.james.mime4j.stream MimeConfig]))

(defn iterator
  "Iterate over a mbox file.
  Mostly taken from https://github.com/apache/james-mime4j/blob/master/examples/src/main/java/org/apache/james/mime4j/samples/mbox/IterateOverMbox.java"
  [path]
  (-> path
      fs/file
      MboxIterator/fromFile
      (.maxMessageSize (* 130 1024 1024)) ;; MimeConfig/PERMISSIVE reads up to 100mb bodies, so 130mb should be enough
      (.fromLine FromLinePatterns/DEFAULT2) ;; more permissive, doesn't require @ in from line
      .build))

(defn parse-message
  "Parse a message from a string.
  Mostly taken from https://github.com/apache/james-mime4j/blob/master/examples/src/main/java/org/apache/james/mime4j/samples/dom/ParsingMessage.java"
  [s]
  (let [builder (DefaultMessageBuilder.)]
    (.setMimeEntityConfig builder MimeConfig/PERMISSIVE)
    (.parseMessage builder (io/input-stream (.getBytes s)))))

(defn filename
  "Returns file name for eml message in the following format:
  timestamp-or-epoch subject-up-to-80-chars message-id-or-random-uuid"
  [s]
  (let [msg (parse-message s)]
    (str
     (u/filename-inst (or (.getDate msg)
                          (t/epoch)))
     " "
     (u/filename-str (u/ellipsis (or (.getSubject msg)
                                     "<no subject>")
                                 80))
     " "
     (u/filename-str (or (.getMessageId msg)
                         (str "<no-message-id-" (random-uuid) ">")))

     ".eml")))

(defn write-message
  [path message]
  (let [filename (str (fs/file path (filename message)))]
    (log/info "writing" filename)
    (spit filename message)))

(defn strip-leading-newline
  "Strip leading newline from string.
  mbox with CRLF line endings will have a leading newline in the message body
  that breaks message parsing."
  [s]
  (cond-> s
    (-> s first (= \newline)) (subs 1)))

(defn mbox->eml
  [mbox-path to-folder-path]
  (when-not (fs/exists? to-folder-path)
    (fs/create-dir to-folder-path))
  (doseq [message (iterator mbox-path)]
    (write-message to-folder-path (-> message str strip-leading-newline))))

(comment
  (let [mbox-path      (fs/file (io/resource "email/sample-crlf.mbox"))
        to-folder-path (fs/file (fs/parent mbox-path) "sample-crlf")]
    (fs/delete-tree to-folder-path)
    (mbox->eml mbox-path to-folder-path)))
