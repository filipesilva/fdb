(ns fdb.fs-test
  (:require
   [fdb.fs :as sut]
   [babashka.fs :refer [with-temp-dir]]
   [clojure.test :refer [deftest is]]))

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

(deftest read-content
  (with-temp-dir [dir {}]
    (let [f (str dir "/f.txt")]
      (sut/spit-edn f "foo")
      (is (= {:content/modified (sut/modified f)}
             (sut/read-content f))))))

(deftest read-metadata
  (with-temp-dir [dir {}]
    (let [f   (str dir "/" (as-md "f.txt"))
          edn {:bar "bar"}]
      (sut/spit-edn f edn)
      (is (= (merge edn
                    {:metadata/modified (sut/modified f)})
             (sut/read-metadata f))))))
