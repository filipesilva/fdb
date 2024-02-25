(ns fdb.fns.email.gmail
  (:require
   [babashka.fs :as fs]
   [clojure-mail.core :as mail]
   [clojure-mail.folder :as folder]
   [clojure-mail.gmail :as gmail]
   [clojure-mail.message :as message]
   [clojure.string :as str]
   [fdb.db :as db]
   [fdb.fns.email.eml :as eml]
   [fdb.metadata :as metadata]
   [fdb.utils :as u]
   [taoensso.timbre :as log]
   [clojure.java.io :as io])
  (:import
   [com.sun.mail.gimap GmailMessage]
   [javax.mail Message$RecipientType Session Transport]
   [javax.mail.internet InternetAddress MimeMessage]))

(defn email+password
  [{:keys [config on]}]
  (try
    (let [{::keys [email password email-env password-env]} (merge config on)
          email (or email (System/getenv email-env))
          password (or password (System/getenv password-env))]
      [email password])
    (catch Exception _
      (throw (ex-info "Couldn't get email and password from config or env" {})))))

(defn store
  [email password]
  ;; Using gimaps to get access to gmail labels and thread-id.
  ;; https://github.com/javaee/javamail/blob/master/gimap/src/main/java/com/sun/mail/gimap/package.html
  (mail/store "gimaps" "imap.gmail.com" email password))

(defn message->folder-uid
  "Note: spam and trash won't show up in all-messages, so this will return nil for those."
  [store folder-name message]
  (with-open [folder (mail/open-folder store folder-name :readonly)]
    (->> [:subject (message/subject message)]
         (folder/search folder)
         (some (fn [msg-match]
                 (when (= (message/id message) (message/id msg-match))
                   (message/uid msg-match)))))))

(defn thread-id
  [^GmailMessage x]
  (.getThrId x))

(defn labels
  [^GmailMessage message]
  (let [labels'   (->> message
                       .getLabels
                       (map str)
                       (map #(cond-> %
                               (str/starts-with? % "\\") (subs 1))))
        seen?     (-> message
                      message/flags
                      str
                      ;; Bit hacky, but couldn't find another way
                      (str/includes? "\\Seen"))
        archived? (not (contains? (set labels') "Inbox"))]
    ;; Got these rules from adding a label to a few messages and exporting
    ;; them via google takeout, then seeing what labels they had.
    ;; Not complete, there seems to be some "Category ..." labels that's I
    ;; don't know how to get, and also once I saw a mail with both "Unread"
    ;; "Opened" at the same time, which is weird, and only happens on Takeout.
    (cond-> labels'
      seen?       (conj "Opened")
      (not seen?) (conj "Unread")
      archived?   (conj "Archived"))))

(defn write
  [message path]
  (let [lines (into [(str "X-GM-THRID: " (thread-id message))
                     (str "X-Gmail-Labels: " (str/join "," (labels message)))]
                    (line-seq (io/reader (.getMimeStream message))))]
    (with-open [o (io/output-stream path)]
      (io/copy (str/join "\n" lines) o))))

(defn sync-folder
  "Syncs a folder from gmail to self-path. self-path must be a folder.
  You'll need to create a app password in google -> security -> 2-step verification -> app passwords.
  Uses :fdb.fns.gmail/email and :fdb.fns.gmail/password from config. "
  [{:keys [config-path node self self-path on on-path] :as call-arg}]
  (if-not (and self-path (fs/directory? self-path))
    (throw (ex-info "self-path must be a directory" {:self-path self-path}))
    ;; if we can't get a lock on self-path, that means another run is in progress and we should bail
    (with-open [lock (u/lockfile (metadata/metadata-path self-path))]
      (if-not @lock
        (log/info "another email sync for" self-path "is in progress, bailing")
        (let [[email password]                 (email+password call-arg)
              [_ {:keys [mail-folder take-n]}] on
              folder-name                      (gmail/folder->folder-name (or mail-folder :all))
              ;; pull last-uid from latest version of the db, because we
              ;; silently update it after writing each mail
              last-uid                         (-> (db/pull node (:xt/id self))
                                                   (get-in on-path)
                                                   :last-uid
                                                   (or 0))
              *new-last-uid                    (atom nil)]
          (with-open [gstore (store email password)]
            (let [uid (if (string? last-uid)
                        (->> last-uid
                             (fs/path self-path)
                             str
                             mail/file->message
                             (message->folder-uid gstore folder-name))
                        last-uid)]
              (log/debug "syncing" folder-name "to" self-path "since" uid)
              (doseq [message (cond->> (reverse (mail/all-messages gstore folder-name {:since-uid uid}))
                                take-n (take take-n))]
                (let [uid  (message/uid message)
                      path (str (fs/path self-path (eml/filename message)))]
                  (when (not= uid last-uid)
                    (log/debug "syncing" folder-name uid "to" path)
                    (write message path)
                    (reset! *new-last-uid uid)
                    (metadata/silent-swap! self-path config-path (:xt/id self)
                                           assoc-in (conj on-path :last-uid) @*new-last-uid))))))
          (if @*new-last-uid
            (log/info "synced" folder-name "to" self-path "until" @*new-last-uid)
            (log/debug "no new messages in" folder-name "!")))))))


(defn send-session
  []
  (Session/getInstance (mail/as-properties
                        {"mail.smtp.host"                         "smtp.gmail.com"
                         "mail.smtp.port"                         587
                         "mail.smtp.auth"                         true
                         "mail.smtp.starttls.enable"              true})))

(defn self-mail
  [call-arg subject text]
  (let [[email password] (email+password call-arg)
        address          (InternetAddress. email)
        store            (send-session)
        msg              (doto (MimeMessage. store)
                           (.setFrom address)
                           (.setRecipient Message$RecipientType/TO address)
                           (.setSubject subject)
                           (.setText text))]
    (Transport/send msg email password)))

;; TODO:
;; - get gmail creds from env vars, much easier to not leak them
;; - no need to update last-uid on all writes, update at the end and every 10

(comment
  {:fdb.on/schedule [{:every       [30 :minutes]
                      :call        'fdb.fns.email.gmail/sync-folder}]
   :last-uid        1052926 ;; or filename
   }

  (def config (u/slurp-edn "tmp/fdbconfig.edn"))
  (def e+p (email+password {:config config}))
  (def email (first e+p))
  (def password (second e+p))
  (def folder-name (gmail/folder->folder-name :all))
  (def last-uid 1052650)

  (def gstore (mail/store "gimaps" "imap.gmail.com" email password))
  (def folder (mail/open-folder gstore folder-name :readonly))
  @(def msg (first (mail/all-messages gstore folder-name)))
  @(def msg (first (reverse (mail/all-messages gstore folder-name {:since-uid last-uid}))))
  @(def msg (folder/get-message-by-uid folder 1052990))

  (message/flags msg)
  (eml/read-message msg)
  (eml/filename msg)
  (message/uid msg)
  (write msg "tmp/msg.eml")

  (self-mail {:config config} "test subject" "test body")
  ;;
  )
