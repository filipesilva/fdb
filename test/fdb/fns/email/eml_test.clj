(ns fdb.fns.email.eml-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [fdb.fns.email.eml :as sut]
   [clojure.java.io :as io]))

(deftest metadata
  (is (= (-> "email/sample.eml"
             io/resource fs/file sut/metadata)
         (-> "email/sample-crlf/1970-01-01T00.00.00Z 8d247ee6 Sample message 1.eml"
             io/resource fs/file sut/metadata)
         {:message-id "123",
          :from ["author@example.com"],
          :text "This is the body.\nThere are 2 lines.\n",
          :subject "Sample message 1",
          :to ["recipient@example.com"]})))
