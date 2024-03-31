(ns fdb.readers.eml-test
  (:require
   [babashka.fs :as fs :refer [with-temp-dir]]
   [clojure.test :refer [deftest is]]
   [fdb.readers.eml :as sut]
   [clojure.java.io :as io]))

(deftest read-test
  (is (= (sut/read {:self-path (-> "eml/sample.eml" io/resource fs/file)})
         (sut/read {:self-path (-> "eml/sample-crlf/1970-01-01T00.00.00Z 8d247ee6 Sample message 1.eml"
                                   io/resource fs/file)})
         {:message-id "123",
          :from       ["author@example.com"],
          :text       "This is the body.\nThere are 2 lines.\n",
          :subject    "Sample message 1",
          :to         ["recipient@example.com"]})))

(deftest split-mbox-test
  (with-temp-dir [path {}]
    (fs/copy (io/resource "eml/sample-crlf.mbox")
             (fs/file path "sample-crlf.mbox"))
    (sut/split-mbox (fs/file path "sample-crlf.mbox"))
    (let [f1 "sample-crlf/1970-01-01T00.00.00Z 8d247ee6 Sample message 1.eml"
          f2 "sample-crlf/1970-01-01T00.00.00Z c2dfc80c Sample message 2.eml"]
      (is (= (slurp (io/resource (str "eml/" f1)))
             (slurp (fs/file path f1))))
      (is (= (slurp (io/resource (str "eml/" f2)))
             (slurp (fs/file path f2)))))))
