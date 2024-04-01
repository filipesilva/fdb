(ns fdb.http-test
  (:require
   [clojure.test :refer [deftest is]]
   [fdb.http :as sut]
   ))

(deftest encode-and-decode-uri
  (is (= "foo%20bar" (sut/encode-uri "foo bar")))
  (is (= "../foo.bar" (sut/encode-uri "../foo.bar")))
  (is (= "foo bar" (-> "foo bar" sut/encode-uri sut/decode-uri))))

(deftest add-params-test
  (is (= "foo?q=Lisbon&limit=1&format=json"
         (sut/add-params "foo" {:q "Lisbon" :limit 1 :format "json"})))
  (is (= "foo?latitude=38.7077507&longitude=-9.1365919&daily=temperature_2m_max&daily=temperature_2m_min&past_days=7"
         (sut/add-params "foo" {:latitude  "38.7077507"
                                :longitude "-9.1365919"
                                :daily     ["temperature_2m_max", "temperature_2m_min"] ,
                                :past_days 7}))))
