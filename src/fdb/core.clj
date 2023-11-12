(ns fdb.core
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :refer [go]]
   [clojure.edn :as edn]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.notifier :as notifier]
   [fdb.utils :as u]
   [fdb.watcher :as watcher]
   [taoensso.timbre :as log]))

(defn- host-watch-spec
  [config-path node [host dir]]
  (let [host-path (-> config-path fs/parent (fs/path dir) str)]
    [host-path
     (fn [p]
       (log/info host "updated" p)
       (db/put node (metadata/id host p) (metadata/read (fs/file host-path p))))
     (fn [p]
       (log/info host "deleted" p)
       (let [id   (metadata/id host p)
             data (metadata/read (fs/file host-path p))]
         (if data
           (db/put node id data)
           (db/delete node id))))
     (fn [p]
       (let [{m1 :content/modified m2 :metadata/modified} (db/get node (metadata/id host p))]
         (when (> (inst-ms (metadata/modified host-path p))
                  (max (inst-ms m1) (inst-ms m2)))
           (log/info host "stale" p)
           true)))]))

(defn do-with-fdb
  [config-path f]
  (let [{:keys [db-path hosts] :as _config} (-> config-path slurp edn/read-string)]
    (with-open [node             (db/node db-path)
                ;; TODO: node listeners for reactive triggers (on-change and refs)
                _hosts-watcher (->> hosts
                                    (mapv (partial host-watch-spec config-path node))
                                    watcher/watch-many
                                    u/closeable-seq)]
      (f node))))

(defmacro with-fdb
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[config-path node] & body]
  `(do-with-fdb ~config-path (fn [~node] ~@body)))

(defn watch-config-path
  [config-path]
  (let [ntf     (notifier/create config-path)
        refresh #(notifier/notify! ntf)
        close   #(notifier/destroy! ntf)]
    (when-not ntf
      (throw (ex-info "Server already running" {:config-path config-path})))
    (go
      (log/info "watching config" config-path)
      (with-open [_config-watcher (watcher/watch config-path refresh close (constantly true))]
        (loop [restart? (notifier/wait ntf)]
          (when restart?
            (log/info "restarting with config" config-path)
            (recur (with-fdb [config-path _db]
                     (notifier/wait ntf))))))
      (u/closeable ntf (fn [_] (close))))))

(comment
  (require '[hashp.core]))

;; Some usecases I want to try:
;; - query file
;; - email ingestion
;; - web ingestion
;; - obsidian ingestion
;; - cronut watcher (maybe not cronut, seems to need integrant, but lists alternatives)
;; - server https://github.com/tonsky/clj-simple-router
;; - docs https://github.com/clj-commons/meta/issues/76
