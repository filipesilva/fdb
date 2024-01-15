(ns fdb.db-test
  (:require
   [babashka.fs :refer [with-temp-dir]]
   [clojure.test :refer [deftest is]]
   [fdb.db :as sut]
   [xtdb.api :as xt]))

(defmacro with-db
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[node] & body]
  `(with-temp-dir [db-path# {}]
     (with-open [~node (sut/node db-path#)]
       ~@body)))

(deftest make-me-a-db
  (with-db [node]
    (sut/put node :foo {:bar "bar"})
    (xt/sync node)
    (is (= {:xt/id :foo :bar "bar"}
           (sut/pull node :foo)))))
