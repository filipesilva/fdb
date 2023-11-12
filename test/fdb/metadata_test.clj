(ns fdb.metadata-test
  (:require
   [babashka.fs :refer [with-temp-dir] :as fs]
   [clojure.test :refer [deftest is]]
   [fdb.metadata :as sut]
   [fdb.utils :as u]))

(defn- as-md
  [s]
  (str s "." sut/default-metadata-ext))

(deftest id
  (is (= "file://test/foo.txt"
         (sut/id :test "foo.txt")))
  (is (= "file://test/foo.txt"
         (sut/id "test" "foo.txt")))
  (is (= "file://test/foo.txt"
         (sut/id :test "/foo.txt")))
  (is (= "file://test/foo.txt"
         (sut/id :test (as-md "foo.txt")))))

(deftest content-path->metadata-path
  (is (= (as-md "foo.txt")
         (sut/content-path->metadata-path "foo.txt")))
  (is (thrown? AssertionError
               (sut/content-path->metadata-path (as-md "foo.txt")))))

(deftest metadata-path->content-path
  (is (= "foo.txt"
         (sut/metadata-path->content-path (as-md "foo.txt"))))
  (is (thrown? AssertionError
               (sut/metadata-path->content-path "foo.txt"))))

(deftest read-metadata
  (with-temp-dir [dir {}]
    (is (nil? (sut/read (str dir "/foo.txt"))))
    (let [f   (u/spit dir "f.txt" "")
          edn {:bar "bar"}
          fmd (u/spit (as-md f) edn)]
      (is (= (merge edn
                    {:content/modified (sut/modified f)
                     :metadata/modified (sut/modified fmd)})
             (sut/read f)
             (sut/read fmd))))))
