(ns fdb.db
  (:refer-clojure :exclude [get sync])
  (:require
   [babashka.fs :as fs]
   [xtdb.api :as xt]))

(defn node
  [db-path]
  (let [cfg {:xtdb/index-store         {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                   :db-dir      (fs/file db-path "rocksdb/index")}}
             :xtdb/document-store      {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                   :db-dir      (fs/file db-path "rocksdb/document")}}
             :xtdb/tx-log              {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                   :db-dir      (fs/file db-path "rocksdb/tx-log")}}
             :xtdb.lucene/lucene-store {:db-dir (fs/file db-path "lucene")}}]
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
