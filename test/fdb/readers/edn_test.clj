(ns fdb.readers.edn-test
  (:require
   [babashka.fs :as fs :refer [with-temp-dir]]
   [clojure.test :refer [deftest is]]
   [fdb.readers.edn :as sut]
   [fdb.utils :as u]))

(deftest read-test
  (with-temp-dir [dir {}]
    (u/spit-edn dir "foo.edn" {:a 1})
    (is (= {:a 1} (sut/read {:self-path (fs/path dir "foo.edn")})))
    (is (= nil (sut/read {:self-path (fs/path dir "bar.edn")})))))
