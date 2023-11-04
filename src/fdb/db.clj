(ns fdb.db
  (:refer-clojure :exclude [get])
  (:require
   [clojure.java.io :as io]
   [xtdb.api :as xt]))

(defonce state (atom nil))

(defn start!
  [path]
  (let [cfg {:xtdb/index-store         {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                   :db-dir      (io/file path "rocksdb/index")}}
             :xtdb/document-store      {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                   :db-dir      (io/file path "rocksdb/document")}}
             :xtdb/tx-log              {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                   :db-dir      (io/file path "rocksdb/tx-log")}}
             :xtdb.lucene/lucene-store {:db-dir (io/file path "lucene")}}]
    (swap! state (fn [node]
                  (when node (.close node))
                  (xt/start-node cfg)))))

(defn stop!
  []
  (swap! state (fn [node]
                 (when node (.close node))
                 nil)))

(defn put
  [data]
  {:pre [(some? @state)]}
  (xt/submit-tx @state [[::xt/put data]]))

(defn delete
  [id]
  {:pre [(some? @state)]}
  (xt/submit-tx @state [[:xt/delete id]]))

(defn get
  [id]
  {:pre [(some? @state)]}
  (xt/entity (xt/db @state) id))

(defn query
  [query & args]
  {:pre [(some? @state)]}
  (apply xt/q (xt/db @state) query args))
