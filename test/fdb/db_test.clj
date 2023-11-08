(ns fdb.db-test
  (:require
   [fdb.db :as sut]
   [babashka.fs :refer [with-temp-dir]]
   [clojure.test :refer [deftest is]]))

(deftest make-me-a-db
  (with-temp-dir [db-path {}]
    (with-open [node (sut/node db-path)]
      (sut/put node :foo {:bar "bar"})
      (sut/sync node)
      (is (= {:xt/id :foo :bar "bar"}
             (sut/get node :foo))))))
