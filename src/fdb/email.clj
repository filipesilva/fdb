(ns fdb.email
  (:refer-clojure :exclude [send sync])
  (:require
   [babashka.fs :as fs]
   [clojure-mail.core :as mail]
   [clojure-mail.folder :as folder]
   [clojure-mail.gmail :as gmail]
   [clojure-mail.message :as message]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.readers.eml :as eml]
   [fdb.utils :as u]
   [taoensso.timbre :as log]
   [tick.core :as t])
  (:import
   [com.sun.mail.gimap GmailMessage]
   [java.util Properties]
   [javax.mail Message$RecipientType Session Transport]
   [javax.mail.internet InternetAddress MimeMessage]))

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

(defn email-config
  "Returns email config from call-arg."
  [call-arg]
  (let [{:keys [email email-env password password-env imap smtp gmail] :as cfg} (-> call-arg :config :email)
        cfg' (cond-> cfg
              (and email-env (not email))       (assoc :email (System/getenv email-env))
              (and password-env (not password)) (assoc :password (System/getenv password-env))
              (nil? imap)                       (assoc :imap {:host "imap.gmail.com"})
              (nil? smtp)                       (assoc :smtp {:host "smtp.gmail.com" :port 587}))]
    (when (or (nil? (:password cfg'))
              (nil? (:email cfg')))
      (throw (ex-info "Couldn't get email and password from config or env" {})))
    (assoc cfg' :gmail (or gmail (str/ends-with? (:email cfg') "@gmail.com")))))

;; smtp

(defn smtp-session
  [{:keys [host port]}]
  (Session/getInstance (mail/as-properties
                        {"mail.smtp.host"            host
                         "mail.smtp.port"            port
                         "mail.smtp.auth"            true
                         "mail.smtp.starttls.enable" true})))

(defn send
  "Send mail."
  [call-arg {:keys [to subject text]}]
  (let [{:keys [password smtp] from-email :email} (email-config call-arg)]
    (Transport/send (doto (MimeMessage. (smtp-session smtp))
                      (.setFrom (InternetAddress. from-email))
                      (.setRecipient Message$RecipientType/TO
                                     (InternetAddress. (if (= to :self) from-email to)))
                      (.setSubject subject)
                      (.setText text))
                    from-email password)))

;; imap

(defn next-uid
  "Returns next uid for `since`, which can be a uid or a inst."
  [store folder since]
  (inc (if (int? since)
         since ;; already looks like uid
         (let [msg (-> (mail/open-folder store folder :readonly)
                       (folder/search :received-after since)
                       first)]
           (if msg
             (message/uid msg)
             0)))))

(defn store
  [{:keys [email password imap gmail]}]
  ;; Using gimaps to get access to gmail labels and thread-id.
  ;; https://github.com/javaee/javamail/blob/master/gimap/src/main/java/com/sun/mail/gimap/package.html
  (mail/store (if gmail "gimaps" "imaps") (:host imap) email password))

(defn add-gmail-headers
  "Return new MimeMessage thread-id and labels from GMail APIs.
  You won't be able to call message/uid on this message anymore since that needs folder information."
  [^GmailMessage message]
  (let [labels    (->> message
                       .getLabels
                       (map str)
                       (map #(cond-> %
                               (str/starts-with? % "\\") (subs 1))))
        seen?     (-> message
                      message/flags
                      str
                      ;; Bit hacky, but couldn't find another way
                      (str/includes? "\\Seen"))
        archived? (not (contains? (set labels) "Inbox"))
        ;; Got these rules from adding a label to a few messages and exporting
        ;; them via google takeout, then seeing what labels they had.
        ;; Not complete, there seems to be some "Category ..." labels that's I
        ;; don't know how to get, and also once I saw a mail with both "Unread"
        ;; "Opened" at the same time, which is weird, and only happens on Takeout.
        labels'   (cond-> labels
                    seen?       (conj "Opened")
                    (not seen?) (conj "Unread")
                    archived?   (conj "Archived"))]
    (doto (MimeMessage. message)
      (.setHeader "X-GM-THRID" (str (.getThrId message)))
      (.setHeader "X-Gmail-Labels" (str/join "," labels')))))

(defn fetch
  [{:keys [gmail] :as cfg} {:keys [folder since order] :or {order :asc}}]
  (let [store (store cfg)
        folder (if gmail (gmail/folder->folder-name folder) folder)
        uid (next-uid store folder since)]
    (cond->> (mail/all-messages store folder {:since-uid uid})
      (= order :asc) reverse)))

(defn sync
  "Sync :folder in trigger to :self-path since :since.
  Self-updates trigger to last :since written."
  [{:keys [self self-path on on-path] :as call-arg}]
  (if (fs/exists? self-path)
    (assert (fs/directory? self-path) "Sync for email path must be a folder if it exists.")
    (fs/create-dirs self-path))
  (let [{:keys [take-n self-update folder] :or {take-n 50 self-update true}} on
        {:keys [gmail] :as cfg} (email-config call-arg)
        on' (cond-> on
              self-update (assoc :since (-> (db/entity (:xt/id self))
                                            (get-in on-path)
                                            :since)))]
    (run!
     (fn [^MimeMessage msg]
       (let [path (str (fs/path self-path (filename msg)))
             uid  (message/uid msg)
             msg' (cond-> msg
                    gmail add-gmail-headers)]
         (log/debug "syncing" folder uid "to" path)
         (.writeTo msg' (io/output-stream path))
         (when self-update
           (metadata/swap! self-path
                           #(-> %
                               (assoc :fdb.on/ignore true)
                               (update-in on-path assoc :since uid))))))
     (take take-n (fetch cfg on')))))


;; mbox

;; Permissive version of mime4j mbox regex
;; org.apache.james.mime4j.mboxiterator.FromLinePatterns/DEFAULT2
@(def line-re #"^From \S+.*\d{4}$")

(defn from-line?
    [line]
    (->> line (re-matches line-re) boolean))

(defn write-message
  [path message message-number]
  (let [filename (->> message filename (fs/file path) str)]
    (log/info "writing" (str "#" message-number) filename)
    (spit filename message)))

;; MboxIterator from mime4j blows up on big mboxes, rolling my own
(defn split-mbox
  [mbox & {:keys [to drop-n]
           :or   {to (u/sibling-path mbox (u/filename-without-extension mbox "mbox"))
                  drop-n         0}}]
  (when-not (fs/exists? to)
    (fs/create-dir to))
  (with-open [rdr (io/reader mbox)]
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
        (write-message to message (vswap! counter inc))))))

;; TODO:
;; - support multiple mail accounts, make mail config one-or-map
;; - support muliple to, cc, and bcc in send
;; - support headers in send
;; - default to INBOX sync?

(comment
  (def call-arg {:config    {:email {:email-env "FDB_GMAIL_DEMO_PASSWORD"
                                     :password-env "FDB_GMAIL_DEMO_PASSWORD"
                                     ;; :email-env    "FDB_GMAIL_EMAIL"
                                     ;; :password-env "FDB_GMAIL_PASSWORD"
                                     }}
                 :on        {:folder :all
                             :since #inst "2024-05-15" #_ 1058154
                             :take-n 5
                             :self-update false}
                 :self-path "tmp/email-sync"})
  @(def cfg (email-config call-arg))

  (send call-arg {:to :self :subject "test subject" :text "test body"})

  (def store' (store cfg))
  (def folder (gmail/folder->folder-name :all))
  (next-uid store' folder 0)
  (next-uid store' folder #inst "2024-05-15")
  (->> (fetch cfg {:folder :all
                   :since  #inst "2024-05-15"})
       (take 2)
       (map eml/read-message))
  (sync call-arg)

  @(def mbox (fs/file (io/resource "email/sample-crlf.mbox")))
  @(def to (fs/file (fs/home) "fdb/user/email"))
  (fs/delete-tree to)
  (split-mbox mbox :to to)

)
