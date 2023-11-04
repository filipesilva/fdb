(ns fdb.fs-test
  (:require
   [fdb.fs :as sut]
   [babashka.fs :refer [with-temp-dir]]
   [clojure.test :refer [deftest is]]))

(deftest id
  (is (= "file://test/foo.txt"
         (sut/id :test "foo.txt")))
  (is (= "file://test/foo.txt"
         (sut/id "test" "foo.txt")))
  (is (= "file://test/foo.txt"
         (sut/id :test "/foo.txt")))
  (is (= "file://test/foo.txt.fdb"
         (sut/id :test "foo.txt.fdb"))))

(deftest content-path->metadata-path
  (is (= "foo.txt.fdb"
         (sut/content-path->metadata-path "foo.txt")))
  (is (thrown? AssertionError
               (sut/content-path->metadata-path "foo.txt.fdb"))))

(deftest metadata-path->content-path
  (is (= "foo.txt"
         (sut/metadata-path->content-path "foo.txt.fdb")))
  (is (thrown? AssertionError
               (sut/metadata-path->content-path "foo.txt"))))

(deftest read-content
  (with-temp-dir [dir {}]
    (let [f (str dir "/f.txt")]
      (sut/spit-edn f "foo")
      (is (= {:file/modified (sut/modified f)
              :file/created  (sut/created f)
              :local/path    f}
             (sut/read-content f))))))

(deftest read-metadata
  (with-temp-dir [dir {}]
    (let [f   (str dir "/f.txt.fdb")
          edn {:bar "bar"}]
      (sut/spit-edn f edn)
      (is (= (merge edn
                    {:metadata/modified (sut/modified f)
                     :metadata/created  (sut/created f)
                     :local/path        f})
             (sut/read-metadata f))))))
