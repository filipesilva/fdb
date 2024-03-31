(ns fdb.readers.eml
  "Read .eml MIME message files, see https://en.wikipedia.org/wiki/MIME.
  Also splits .mbox files into .eml, see https://en.wikipedia.org/wiki/Mbox."
  (:refer-clojure :exclude [read])
  (:require
   [clojure-mail.core :as mail]
   [clojure-mail.message :as message]
   [clojure-mail.parser :as parser]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [fdb.utils :as u]
   [tick.core :as t]
   [fdb.readers.eml :as eml]
   [babashka.fs :as fs]
   [taoensso.timbre :as log])
  (:import
   [java.util Properties]
   [javax.mail Session]
   [javax.mail.internet MimeMessage]))

(defn- try-msg->map
  [msg]
  {:content-type (.getContentType msg)
   :body         (try (.getContent msg)
                      (catch java.io.UnsupportedEncodingException _
                        "<unsupported encoding>")
                      (catch Throwable _
                        "<could not read body>"))})

(defn read-message
  "Like clojure-mail.message/read-message, but catches unsupported/unknown
  encoding exceptions while reading content."
  [message]
  (with-redefs [message/msg->map try-msg->map]
    (let [ret (message/read-message message)]
      (doall (:body ret))
      ret)))

(defn match-type
  ([part type]
   (some-> part :content-type str/lower-case (str/starts-with? type)))
  ([part type ignore]
   (when-some [content-type (some-> part :content-type str/lower-case)]
     (and (str/starts-with? content-type type)
          (not (str/includes? content-type ignore))))))

(defn prefer-text
  [parts]
  (if (and (seq? parts)
           (>= (count parts) 2))
    (let [maybe-plain (some #(when (match-type % "text/plain" "; name=") %) parts)
          maybe-html  (some #(when (match-type % "text/html") %) parts)]
      (if (and maybe-plain maybe-html)
        ;; looks like an alternative between plain and html, prefer plain
        (list maybe-plain)
        parts))
    parts))

(defn part->text
  [{:keys [content-type body] :as part}]
  (cond
    (nil? part)                            "<no text body>"
    (str/includes? content-type "; name=") (str "Attachment: " content-type "\n")
    (match-type part "text/plain")         body
    ;; With a nice html parser we could make it the preferred option.
    (match-type part "text/html")          (parser/html->text body)
    :else                                  (str "Attachment: " content-type "\n")))

;; TODO: use multipart/alternative in content-type to figure out which to take
(defn message-content
  [message-edn]
  (try (->> (:body message-edn)
            ;; for each list, prefer text if it looks like an alternative between plain and html
            (tree-seq seq? prefer-text)
            ;; lists are branches, we only care about the part leaves
            (remove seq?)
            (map part->text)
            (str/join "\n")
            u/unix-line-separators)
       (catch Throwable _
         "<could not read body>")))

(defn to-references-vector
  [references-str]
  (some->> references-str
           u/unix-line-separators
           str/trim
           str/split-lines
           (mapv str/trim)))

(defn read
  [{:keys [self-path]}]
  (let [message-edn  (-> self-path mail/file->message read-message)
        from-message (-> message-edn
                         (update :from #(mapv :address %))
                         (update :to #(mapv :address %))
                         (update :cc #(mapv :address %))
                         (update :bcc #(mapv :address %))
                         (update :sender :address)
                         (dissoc :multipart? :content-type :headers
                                 :body ;; writing it to content separately
                                 :id   ;; getting message-id from headers instead
                                 )
                         (set/rename-keys {:date-sent :date})) ;; match headers better
        headers      (->> message-edn
                          :headers
                          (apply merge)
                          (map (fn [[k v]]
                                 [(-> k str/lower-case keyword) v]))
                          (into {}))
        from-headers (-> headers
                         (select-keys [:message-id :references :in-reply-to :reply-to])
                         (update :references to-references-vector))
        from-body    {:text (message-content message-edn)}
        from-gmail   (-> headers
                         (select-keys [:x-gm-thrid :x-gmail-labels])
                         (set/rename-keys {:x-gm-thrid :thread-id
                                           :x-gmail-labels :labels})
                         (update :labels #(when % (str/split % #","))))]
    (u/strip-nil-empty
     (merge from-message from-headers from-body from-gmail))))

(defn str->message
  "Like clojure-mail.core/file->message, but doesn't read the file from disk."
  [str]
  (let [props (Session/getDefaultInstance (Properties.))]
    (MimeMessage. props (io/input-stream (.getBytes str)))))

(defn filename
  "Returns file name for a eml message in the following format:
  timestamp-or-epoch message-id-or-random-uuid-8-char-hex-hash subject-up-to-80-chars.eml
  The message-id hash is there to avoid overwriting emails with same timestamp and subject."
  ([str-or-msg]
   (let [msg (cond-> str-or-msg
               (string? str-or-msg) str->message)]
     (filename (or (message/date-sent msg)
                   (message/date-received msg))
               (message/subject msg)
               (message/id msg))))
  ([date subject message-id]
   (str
    (u/filename-inst (or date (t/epoch)))
    " "
    (->> (or message-id (str "<no-message-id-" (random-uuid) ">"))
         hash
         (format "%08x"))
    " "
    (u/filename-str (u/ellipsis (or subject "<no subject>") 80))
    ".eml")))

(defn write-message
  [path message message-number]
  (let [filename (->> message filename (fs/file path) str)]
    (log/info "writing" (str "#" message-number) filename)
    (spit filename message)))

;; Permissive version of mime4j mbox regex
;; org.apache.james.mime4j.mboxiterator.FromLinePatterns/DEFAULT2
@(def line-re #"^From \S+.*\d{4}$")

(defn from-line?
    [line]
    (->> line (re-matches line-re) boolean))

;; MboxIterator from mime4j blows up on big mboxes, rolling my own
(defn split-mbox
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
    (split-mbox mbox-path to-folder-path)))


(comment
  (read {:self-path "./resources/eml/sample.eml"})
  ;;
  )
