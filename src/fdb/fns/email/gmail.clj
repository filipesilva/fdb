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
   [com.sun.mail.gimap GmailMessage]))

(defn store
  [email password]
  ;; Using gimaps to get access to gmail labels and thread-id.
  ;; https://github.com/javaee/javamail/blob/master/gimap/src/main/java/com/sun/mail/gimap/package.html
  (mail/store "gimaps" "imap.gmail.com" email password))

(defn message->folder-uid
  "Note: spam and trash won't show up in all-messages, so this will return nil for those."
  [folder message]
  (->> [:subject (message/subject message)]
       (folder/search folder)
       (some (fn [msg-match]
               (when (= (message/id message) (message/id msg-match))
                 (message/uid msg-match))))))

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
    ;; "Opened" at the same time, which is weird.
    (cond-> labels'
      seen?       (conj "Opened")
      (not seen?) (conj "Unread")
      archived?   (conj "Archived"))))

(defn write
  [message path]
  (with-open [o (io/output-stream path)]
    (let [gmail-headers (str "X-GM-THRID: " (thread-id message) "\r\n"
                             "X-Gmail-Labels: " (str/join "," (labels message)) "\r\n")]
      (.write o (.getBytes gmail-headers))
      (.writeTo message o))))

(defn filename
  [message]
  (eml/filename (or
                 ;; message/read-message populates :date-sent when reading from file
                 ;; so it seems like the one that matches Date: in eml
                 (message/date-sent message)
                 (message/date-received message))
                (message/subject message)
                (message/id message)))

(defn sync-folder
  "Syncs a folder from gmail to self-path. self-path must be a folder.
  You'll need to create a app password in google -> security -> 2-step verification -> app passwords.
  Uses :fdb.fns.gmail/email and :fdb.fns.gmail/password from config. "
  [{:keys [config config-path node self self-path on on-ks]}]
  (if-not (and self-path (fs/directory? self-path))
    (throw (ex-info "self-path must be a directory" {:self-path self-path}))
    ;; if we can't get a lock on self-path, that means another run is in progress and we should bail
    (with-open [lock (u/lockfile (metadata/metadata-path self-path))]
      (if-not @lock
        (log/info "another email sync for" self-path "is in progress, bailing")
        (let [{::keys [email password]}        config
              [_ {:keys [mail-folder take-n]}] on
              folder-name                      (gmail/folder->folder-name mail-folder)
              ;; pull last-uid from latest version of the db, because we
              ;; silently update it after writing each mail
              last-uid                         (-> (db/pull node (:xt/id self))
                                                   (get-in on-ks)
                                                   :last-uid
                                                   ;; TODO: lookup using message->folder-uid over latest
                                                   ;; message in db for this folder
                                                   (or 0))
              *new-last-uid                    (atom nil)]
          (log/info "syncing" folder-name "to" self-path "since" last-uid)
          (with-open [gstore (store email password)]
            (doseq [message (cond->> (mail/all-messages gstore folder-name {:since-uid (inc last-uid)})
                              take-n (take take-n))]
              (let [uid  (message/uid message)
                    path (str (fs/path self-path (filename message)))]
                ;; when there's no newer messages, mail/all-messages returns the last one again
                (when (not= uid last-uid)
                  (log/info "syncing" folder-name uid)
                  (log/info "writing mail to" path)
                  (write message path)
                  (reset! *new-last-uid uid)
                  (metadata/silent-swap! self-path config-path (:xt/id self)
                                         assoc-in (conj on-ks :last-uid) @*new-last-uid)))))
          (when @*new-last-uid
            (log/info "synced" folder-name "to" self-path "until" @*new-last-uid)))))))

;; TODO:
;; - throw for now if there's messages but can't find folder-uid
;; - maybe just pick latest date email, and start syncing from first uid found at that date?
;; - get gmail creds from env vars, much easier to not leak them

(comment
  {:xt/id           "/mount/mail/all"
   :fdb.on/schedule [{:every       [30 :minutes]
                      :call        'fdb.fns.gmail/sync-folder
                      :mail-folder "[Gmail]/All Mail"}]}

  (def email "xxx@gmail.com")
  (def password "xxx")
  (def folder-name (gmail/folder->folder-name :all))
  (def last-uid 1052926)

  (def gstore (mail/store "gimaps" "imap.gmail.com" email password))
  (def folder (mail/open-folder gstore folder-name :readonly))
  @(def msg (first (mail/all-messages gstore folder-name)))
  @(def msg (first (reverse (mail/all-messages gstore folder-name {:since-uid last-uid}))))
  @(def msg (folder/get-message-by-uid folder 1052990))

  (message/flags msg)
  (eml/read-message msg)
  (write msg "tmp/msg.eml")
  (filename msg)

  ;;
  )
