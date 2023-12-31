(ns fdb.fns.email.eml
  "Process .eml MIME message files.
  See https://en.wikipedia.org/wiki/MIME"
  (:require
   [clojure-mail.core :as mail]
   [clojure-mail.message :as message]
   [clojure-mail.parser :as parser]
   [clojure.set :as set]
   [clojure.string :as str]
   [fdb.utils :as u]
   [tick.core :as t]))

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

(defn strip-nil-empty
  [m]
  (->> m
       (filter (fn [[_ v]]
                 (and (not (nil? v))
                      (or (not (coll? v))
                          (seq v)))))
       (into {})))

(defn metadata
  [message-path]
  (let [message-edn  (-> message-path mail/file->message read-message)
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
    (strip-nil-empty
     (merge from-message from-headers from-body from-gmail))))

(defn filename
  "Returns file name for a eml message in the following format:
  timestamp-or-epoch subject-up-to-80-chars message-id-or-random-uuid"
  [date subject message-id]
  (str
   (u/filename-inst (or date (t/epoch)))
   " "
   (u/filename-str (u/ellipsis (or subject "<no subject>") 80))
   " "
   (u/filename-str (or message-id (str "<no-message-id-" (random-uuid) ">")))
   ".eml"))

(comment
  (metadata "tmp/msg.eml")
  ;;
  )
