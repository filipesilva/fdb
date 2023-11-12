(ns fdb.core-test
  (:require
   [babashka.fs :refer [with-temp-dir] :as fs]
   [clojure.test :as t :refer [deftest is testing]]
   [fdb.core :as fdb]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.utils :as utils]
   [hashp.core]))

(defmacro with-temp-fdb-config
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[config-path host] & body]
  `(with-temp-dir [dir# {}]
     (let [~host     (str dir# "/host")
           ~config-path (str dir# "/fdb.edn")]
       (fs/create-dirs ~host)
       (utils/spit ~config-path {:db-path (str dir# "/db")
                                 :hosts   [[:test "./host"]]})
       ~@body)))

(deftest make-me-a-fdb
  (with-temp-fdb-config [config-path host]
    (let [f        (fs/path host "file.txt")
          fm       (fs/path host "file.txt.fdb.edn")
          snapshot (atom nil)]
      (fdb/with-fdb [config-path node]
        (is (empty? (db/all node)))
        (testing "updates from content and metadata files separately"
          (utils/spit f "")
          (is (utils/eventually (= #{{:xt/id            "file://test/file.txt"
                                      :content/modified (metadata/modified f)}}
                                   (db/all node))))
          (utils/spit fm {:foo "bar"})
          (is (utils/eventually (= #{{:xt/id             "file://test/file.txt"
                                      :content/modified  (metadata/modified f)
                                      :metadata/modified (metadata/modified fm)
                                      :foo               "bar"}}
                                   (db/all node)))))
        (reset! snapshot (db/all node)))

      (utils/spit f "1")

      (fdb/with-fdb [config-path node]
        (testing "updates on stale data"
          (is (utils/eventually (not= @snapshot (db/all node)))))

        (testing "updates on partial delete"
          (fs/delete f)
          (is (utils/eventually (= #{{:xt/id             "file://test/file.txt"
                                      :metadata/modified (metadata/modified fm)
                                      :foo               "bar"}}
                                   (db/all node)))))
        (testing "deletes"
          (fs/delete fm)
          (is (utils/eventually (empty? (db/all node)))))))))
