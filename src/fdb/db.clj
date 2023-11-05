(ns fdb.db
  (:refer-clojure :exclude [get sync])
  (:require
   [babashka.fs :as bb-fs]
   [xtdb.api :as xt]))

(defn start
  [db-path]
  (let [cfg {:xtdb/index-store         {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                   :db-dir      (bb-fs/file db-path "rocksdb/index")}}
             :xtdb/document-store      {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                   :db-dir      (bb-fs/file db-path "rocksdb/document")}}
             :xtdb/tx-log              {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                   :db-dir      (bb-fs/file db-path "rocksdb/tx-log")}}
             :xtdb.lucene/lucene-store {:db-dir (bb-fs/file db-path "lucene")}}]
    (xt/start-node cfg)))

(defn sync
  [node]
  (xt/sync node))

(defn put
  [node id data]
  (xt/submit-tx node [[::xt/put (merge {:xt/id id} data)]]))

(defn delete
  [node id]
  (xt/submit-tx node [[:xt/delete id]]))

(defn get
  [node id]
  (xt/entity (xt/db node) id))

(defn query
  [node query & args]
  (apply xt/q (xt/db node) query args))
