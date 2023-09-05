(ns fdb.core
  (:require
   [xtdb.api :as xt]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.edn :as edn]
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nextjournal.beholder :as beholder]))

;; db

(def rocksdb-cfg {:xtdb/index-store         {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                        :db-dir      (io/file "./tmp/rocksdb/index")}}
                  :xtdb/document-store      {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                        :db-dir      (io/file "./tmp/rocksdb/document")}}
                  :xtdb/tx-log              {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                        :db-dir      (io/file "./tmp/rocksdb/tx-log")}}
                  :xtdb.lucene/lucene-store {:db-dir "./tmp/lucene"}})

(defonce node (xt/start-node rocksdb-cfg))

;; fs

(defn spit-edn [f content]
  (io/make-parents f)
  (spit f content)
  f)

(defn slurp-edn [f]
  (-> f
      slurp
      read-string))

(defn id [ns path]
  (str "file://"
       (if (keyword? ns) (name ns) ns)
       (when-not (str/starts-with? path "/") "/")
       (first (fs/split-ext path {:ext "fdb"}))))

(defn metadata-file? [f]
  (-> f fs/extension (= "fdb")))

(defn attrs [f]
  (let [{:keys [lastModifiedTime creationTime]}
        (fs/read-attributes f "basic:lastModifiedTime,creationTime")]
    {:file/modified   (fs/file-time->instant lastModifiedTime)
     :file/created    (fs/file-time->instant creationTime)
     :local.file/path f}))

(defn read-file
  [f]
  (if (metadata-file? f)
    (-> f slurp edn/read-string)
    (-> f attrs)))

;; query

(defn entity [id]
  (xt/entity (xt/db node) id))

;; watchers

(defonce watchers (atom {}))

(defn stop
  "Stop the watcher registered as kw."
  [kw]
  (swap! watchers
         (fn [m]
           (when-some [watcher (get m kw)]
             (beholder/stop watcher))
           (dissoc m kw))))

(defn maybe-update-file-bad-name-😢
  [{:keys [type path] :as aaa}]
  (xt/submit-tx node [[::xt/put #p (read-file (str path))]]))

(defn watch
  "Watch dir and add file metadata to kw host.
  Watching a different dir for kw will stop the previous watcher."
  [kw dir]
  (swap! watchers update kw
         (fn [_]
           ;; Start watching first, so we don't lose any changes.
           (let [w (beholder/watch maybe-update-file-bad-name-😢 (str dir))]
             ;; Check if any of the files changed outside watch.
             (run! #(maybe-update-file-bad-name-😢 #p {:type :modify :path %})
                  #p (fs/list-dir (str dir) "**"))
             w)
           )))










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

  (fs/mkdirs tmp-root)

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
                          fs/absolute
                          :path)
                    tmp-query-input-file)))

;; maybe don't put the watcher in the db, just use the db as config for watcher
;; then watch, and query, and output
#_(beholder/stop query-watcher)

;; # email ingestion

;; # web ingestion

;; # obsidian ingestion

;; # cronut watcher

(comment

(file-seq (io/file tmp-root))
(fs/delete-dir tmp-root)

(file-seq (io/file "/Users/filipesilva/work/fdb/tmp/fdb"))

(xt/submit-tx node [[::xt/put (rand-file-tx)]])

(spit tmp-query-input-file
      (pr-str {:find [':file/string]
               :where [':file/string "123"]}))



;;
  )
