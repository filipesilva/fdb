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

(defn put
  [node id data]
  (xt/submit-tx node [[::xt/put (merge {:xt/id id} data)]]))

(defn pull
  [node id]
  (xt/pull (xt/db node) '[*] id))

(defn all
  [node]
  (->> (xt/q (xt/db node)
             '{:find  [(pull e [*])]
               :where [[e :xt/id]]})
       (map first)
       set))

(defn xtdb-id->xt-id
  "Get :xt/id for the xtdb-id provided for :xtdb.api/delete operations in a tx.
  From https://github.com/xtdb/xtdb/issues/1769."
  [node eid]
  (with-open [i (xt/open-entity-history (xt/db node)
                                        eid
                                        :asc
                                        {:with-docs? true
                                         :with-corrections? true})]
    (:xt/id (some :xtdb.api/doc (iterator-seq i)))))

(defn tx-with-ops
  "Returns the tx with ops for the given tx without ops."
  [node {::xt/keys [tx-id] :as _tx-without-ops}]
  (first (iterator-seq (xt/open-tx-log node (dec tx-id) true))))
