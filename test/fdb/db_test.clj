(ns fdb.db-test
  (:require
   [fdb.db :as sut]
   [babashka.fs :refer [with-temp-dir]]
   [clojure.test :refer [deftest is]]))

(defmacro with-db
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[node] & body]
  `(with-temp-dir [db-path# {}]
     (with-open [~node (sut/node db-path#)]
       ~@body)))

(deftest make-me-a-db
  (with-db [node]
    (sut/put node :foo {:bar "bar"})
    (sut/sync node)
    (is (= {:xt/id :foo :bar "bar"}
           (sut/pull node :foo)))))
