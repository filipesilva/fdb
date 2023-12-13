(ns fdb.fns.gmail
  (:require
   [babashka.fs :as fs]
   [clojure-mail.core :as mail]
   [clojure-mail.message :as message]
   [clojure-mail.parser :as parser]
   [clojure.string :as str]
   [fdb.metadata :as metadata]
   [fdb.utils :as u]
   [taoensso.timbre :as log])
  (:import
   [com.sun.mail.gimap GmailMessage]))

(defn labels
  [^GmailMessage x]
  (mapv str (.getLabels x)))

(defn to-references-vector
  [references-str]
  (some->> references-str
           u/unix-line-separators
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

(defn message-metadata
  [message uid message-edn]
  (let [from-message (-> message-edn
                         (update :from #(mapv :address %))
                         (update :to #(mapv :address %))
                         (update :cc #(mapv :address %))
                         (update :bcc #(mapv :address %))
                         (update :sender :address)
                         (dissoc :multipart? :content-type :headers
                                 :body ;; writing it to content separately
                                 :id ;; getting messag-id from headers instead
                                 ))
        from-headers (-> (->> message-edn
                              :headers
                              (apply merge)
                              (map (fn [[k v]]
                                     [(-> k str/lower-case keyword) v]))
                              (into {}))
                         (select-keys [:message-id :references :in-reply-to :reply-to])
                         (update :references to-references-vector))
        from-gmail   {:labels (labels message)}]
    (strip-nil-empty
     (merge from-message from-headers from-gmail {:uid uid}))))

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

(defn message-content
  [message-edn]
  (let [subject   (or (:subject message-edn) "<no subject>")
        plaintext (->> (:body message-edn)
                       ;; for each list, prefer text if it looks like an alternative between plain and html
                       (tree-seq seq? prefer-text)
                       ;; lists are branches, we only care about the part leaves
                       (remove seq?)
                       (map part->text)
                       (str/join "\n"))]
    ;; TODO: probably should also account for windows line separators
    (u/unix-line-separators
     (str subject "\n\n" plaintext))))

(defn store
  [email password]
  ;; Using gimaps to get access to gmail labels.
  ;; https://github.com/javaee/javamail/blob/master/gimap/src/main/java/com/sun/mail/gimap/package.html
  (mail/store "gimaps" "imap.gmail.com" email password))

(defn- try-msg->map
  [msg]
  {:content-type (.getContentType msg)
   :body         (try (.getContent msg)
                      (catch java.io.UnsupportedEncodingException _
                        "<unsupported encoding>"))})

(defn read-message
  "Like clojure-mail.message/read-message, but catches unsupported exceptions while reading content."
  [message]
  (with-redefs [message/msg->map try-msg->map]
    (let [ret (message/read-message message)]
      (doall (:body ret))
      ret)))

(defn sync-folder
  "Syncs a folder from gmail to self-path. self-path must be a folder.
  You'll need to create a app password in google -> security -> 2-step verification -> app passwords.
  Uses :fdb.fns.gmail/email and :fdb.fns.gmail/password from config. "
  [{:keys [config config-path self self-path on on-ks]}]
  (if-not (and self-path (fs/directory? self-path))
    (throw (ex-info "self-path must be a directory" {:self-path self-path}))
    ;; if we can't get a lock on self-path, that means another run is in progress and we should bail
    (with-open [lock (u/lockfile (metadata/metadata-path self-path))]
      (if-not @lock
        (log/info "another email sync for" self-path "is in progress, bailing")
        (let [{::keys [email password]} config
              [_ {:keys [mail-folder last-uid take-n]}]      on
              *new-last-uid (atom nil)]
          (log/info "syncing" mail-folder "to" self-path "since" last-uid)
          (with-open [gstore (store email password)]
            (let [messages (->> (mail/all-messages gstore mail-folder (when last-uid
                                                                        {:since-uid (inc last-uid)}))
                                reverse
                                ;; read-message takes about 1s, so pmap really helps here
                                (pmap (fn [msg] [msg (message/uid msg) (read-message msg)])))]
              (doseq [[message uid message-edn] (cond->> messages
                                                  take-n (take take-n))]
                (let [_                            (log/info "syncing" mail-folder uid)
                      content                      (message-content message-edn)
                      metadata                     (message-metadata message uid message-edn)
                      filename                     (str (u/ellipsis
                                                         (str (u/filename-inst (:date-received metadata))
                                                              " "
                                                              (u/filename-str (or (:subject metadata)
                                                                                  "no subject")))
                                                         101 ;; 20 from inst, 1 from space, and 80 for subject
                                                         )
                                                        ".txt")
                      [content-path metadata-path] (metadata/content-and-metadata-paths self-path filename)]
                  (log/info "writing mail to" content-path)
                  (u/spit content-path content)
                  (u/spit-edn metadata-path metadata)
                  (reset! *new-last-uid uid)
                  (metadata/silent-swap! self-path config-path (:xt/id self)
                                          assoc-in (conj on-ks :last-uid) @*new-last-uid)))))
          (when @*new-last-uid
            (log/info "synced" mail-folder "to" self-path "until" @*new-last-uid)))))))

(comment
  {:xt/id           "/mount/mail/all"
   :fdb.on/schedule [{:every       [30 :minutes]
                      :call        'fdb.fns.gmail/sync-folder
                      :mail-folder "[Gmail]/All Mail"
                      }]}

  (def email "")
  (def password "")
  (def last-uid 0)

  (def gstore (mail/store "gimaps" "imap.gmail.com" email password))
  @(def msg (first (reverse (mail/all-messages gstore "[Gmail]/All Mail"  {:since-uid last-uid}))))

  ;; printing the message is when msg->map is called, it forces the lazy seq
  @(def msg-edn (read-message msg))

  (message-metadata msg (message/uid msg) (message/read-message msg))

  (println (message-content (message/read-message msg)))

  (->> (:body msg-edn)
                       ;; for each list, prefer text if it looks like an alternative between plain and html
       (tree-seq seq? prefer-text)
       ;; lists are branches, we only care about the part leaves
      #_ (remove seq?)
       #_#_(map part->text)
       (str/join "\n"))
  (prefer-text (first (:body msg-edn)))

  ;;
  )
