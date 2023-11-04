(ns fdb.core
  (:require
   [hashp.core]
   [xtdb.api :as xt]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [babashka.fs :as bb-fs]
   [clojure.string :as str]
   [fdb.fs :as fs]
   [nextjournal.beholder :as beholder]))

;; watchers

(defonce watchers (atom {}))

(defn modified?
  [id path]
  (if-some [db-modified (get (entity id)
                             (if (fs/metadata-path? path)
                               :metadata/modified
                               :file/modified))]
    (> (fs/modified path) db-modified)
    true))

(defn update-file
  ([kw dir path]
   (update-file kw dir path :modify))
  ([kw dir path type]
   (let [relative-path (str/replace-first path dir "")
         id            (fs/id kw relative-path)
         op            (case type
                         (:create :modify) (try
                                             (when (modified? id path)
                                               [::xt/put (merge {:xt/id id}
                                                                (if (fs/metadata-path? path)
                                                                  (fs/read-metadata path)
                                                                  (fs/read-content path)))])
                                             #_(catch Exception _
                                               ;; File doesn't exist anymore.
                                               [::xt/delete id]))
                         :delete           [::xt/delete id]
                         :overflow         nil)]
     (when op
       (xt/submit-tx node #p [op])))))

(defn stop
  "Stop the watcher registered as kw."
  [kw]
  (swap! watchers
         (fn [m]
           (when-some [watcher (get m kw)]
             (beholder/stop watcher))
           (dissoc m kw))))

(defn watch
  "Watch dir and add file metadata to kw host.
  Watching a different dir for kw will stop the previous watcher."
  [kw dir]
  (swap! watchers update kw
         (fn [watcher]
           (when watcher
             (beholder/stop watcher))
           ;; Start watching first, so we don't lose any changes.
           (let [dir' (str dir)
                 f    (partial update-file kw dir')
                 w    (beholder/watch (fn [{:keys [path type]}]
                                        (f path type))
                                      (str dir'))]
             ;; Check if any of the files changed outside watch.
             (run! f (bb-fs/list-dir dir' "**"))
             w))))

(comment

  (defonce listeners (atom {}))

  (defn add-listener! [node k f]
    (swap! listeners update k (fn [listener]
                                (when listener
                                  (.close listener))
                                (xt/listen node
                                           {::xt/event-type ::xt/indexed-tx
                                            :with-tx-ops? true}
                                           f))))

  (defn remove-listener! [_node k]
    (swap! listeners (fn [m]
                       (when-some [listener (get m k)]
                         (.close listener))
                       (dissoc m k))))

  (defn remove-all-listeners! [_node]
    (swap! listeners (fn [m]
                       (doseq [listener (vals m)]
                         (.close listener))
                       {})))

;; # debug

  ;; (add-listener! node :pprint pp/pprint)
  ;; (remove-listener! node :pprint)

  ;; # fs write

  ;; file://host/path
  ;; host can be ommited, assumed localhost, but slash is not
  ;; host is a good way to have scopes/ns
  (defn rand-file-tx []
    {:xt/id       (str "file://host/" (random-uuid) ".txt")
     :file/string "123"})

  ;; TODO: config host root on db too
  (def tmp-root "./tmp/fdb/debug-fs-host/")

  (bb-fs/mkdirs tmp-root)

  (defn tx-report->fs
    [{:keys [committed? ::xt/tx-ops]}]
    (when committed?
      (doseq [[op {:keys [xt/id file/string]}]
              (filter (comp #(str/starts-with? % "file://") :xt/id second)
                      tx-ops)]
        (let [path (str/replace id "file://host/" tmp-root)]
          (println path)
          (case op
            ::xt/put (do
                       (io/make-parents path)
                       (spit path string))
            ::xt/delete (io/delete-file path true))))))

  (add-listener! node :fs tx-report->fs)

  ;; # query file watcher

  ;; TODO: store these in db
  ;; TODO: derive output file as name.output.ext
  ;; TODO: on txt, support code block
  (def tmp-query-input-file
    "./tmp/fdb/query-input.edn"
    #_"/Users/filipesilva/Library/Mobile Documents/iCloud~md~obsidian/Documents/personal/fdb/xtdb-query.md")
  (def tmp-query-output-file
    "./tmp/fdb/query-output.edn"
    #_"/Users/filipesilva/Library/Mobile Documents/iCloud~md~obsidian/Documents/personal/fdb/xtdb-query.output.md")

  (defn parse-and-query [node s]
    (try
      (with-out-str
        (->> (read-string s)
             (xt/q (xt/db node))
             pp/pprint))
      (catch Exception e
        (str "Error parsing query:\n" e))))

  ;; how to ignore watch on writes we do?
  ;; think i need transactor fn, because xt match only checks if the doc is already the same
  ;; (e.g. it can put if match, but not if not match)
  (def query-watcher
    (beholder/watch (comp (partial spit tmp-query-output-file)
                          (partial parse-and-query node)
                          slurp
                          bb-fs/absolute
                          :path)
                    tmp-query-input-file)))

;; maybe don't put the watcher in the db, just use the db as config for watcher
;; then watch, and query, and output
#_(beholder/stop query-watcher)

;; # email ingestion

;; # web ingestion

;; # obsidian ingestion

;; # cronut watcher (maybe not cronut, seems to need integrant, but lists alternatives)

;; # server https://github.com/tonsky/clj-simple-router

;; # docs https://github.com/clj-commons/meta/issues/76

(comment

  (file-seq (io/file tmp-root))
  (bb-fs/delete-dir tmp-root)

  (file-seq (io/file "/Users/filipesilva/work/fdb/tmp/fdb"))

  (xt/submit-tx node [[::xt/put (rand-file-tx)]])

  (spit tmp-query-input-file
        (pr-str {:find [':file/string]
                 :where [':file/string "123"]}))

;;
  )
