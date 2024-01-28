(ns fdb.fns.email.mbox-test
  (:require
   [babashka.fs :refer [with-temp-dir] :as fs]
   [clojure.test :refer [deftest is]]
   [fdb.fns.email.mbox :as sut]
   [clojure.java.io :as io]))

(deftest mbox->eml
  (with-temp-dir [path {}]
    (fs/copy (io/resource "email/sample-crlf.mbox")
             (fs/file path "sample-crlf.mbox"))
    (sut/mbox->eml (fs/file path "sample-crlf.mbox"))
    (let [f1 "sample-crlf/1970-01-01T00.00.00Z 8d247ee6 Sample message 1.eml"
          f2 "sample-crlf/1970-01-01T00.00.00Z c2dfc80c Sample message 2.eml"]
      (is (= (slurp (io/resource (str "email/" f1)))
             (slurp (fs/file path f1))))
      (is (= (slurp (io/resource (str "email/" f2)))
             (slurp (fs/file path f2)))))))
