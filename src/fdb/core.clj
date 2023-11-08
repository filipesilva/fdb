(ns fdb.core
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :refer [<! go promise-chan put!]]
   [fdb.closeable :as closeable]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.watcher :as watcher]))

(def ^:private servers (atom []))

(defn- host->watch-spec
  [db [host dir]]
  [dir
   #(db/put db (metadata/id host %) (metadata/read (fs/file dir %)))
   #(db/delete db (metadata/id host %))
   #(let [{m1 :content/modified m2 :metadata/modified} (db/get db (metadata/id host %))]
      (> (metadata/modified (fs/file dir %))
         (max m1 m2)))])

(defn do-with-fdb [{:keys [db-path hosts]} f]
  (with-open [db             (db/node db-path)
              ;; TODO: db listeners for reactive triggers (on-change and refs)
              _host-watchers (->> hosts
                                  (mapv (partial host->watch-spec db))
                                  watcher/watch-many
                                  closeable/closeable-seq)]
    (f)))

(defmacro with-fdb
  [config & body]
  `(do-with-fdb ~config (fn [] ~@body)))


;; watch config file, restart server on change, stop on delete
;; don't let server start if theres already one running for that
;; config file, because xtdb is single master

(defn server
  [{:keys [hosts]}]

  (let [wait-ch (promise-chan)]
    (swap! servers conj wait-ch)
    (go
      (println "Start server")
      (<! wait-ch)
      (println "Shutdown server")
      )

    wait-ch
    )


  )

(defn stop-server
  [srv]
  (put! srv :close))

(defn- stop-all!
  []
  (pmap stop-server @servers))


(comment
  (require '[hashp.core])

  (server {})

  (stop-all!)

  )

;; Some usecases I want to try:
;; - email ingestion
;; - web ingestion
;; - obsidian ingestion
;; - cronut watcher (maybe not cronut, seems to need integrant, but lists alternatives)
;; - server https://github.com/tonsky/clj-simple-router
;; - docs https://github.com/clj-commons/meta/issues/76
