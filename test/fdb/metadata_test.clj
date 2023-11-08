(ns fdb.metadata-test
  (:require
   [babashka.fs :refer [with-temp-dir] :as fs]
   [clojure.test :refer [deftest is]]
   [fdb.metadata :as sut]))

(defn- as-md
  [s]
  (str s "." sut/default-metadata-ext))

(defn- pspit [f content]
  (-> f fs/parent fs/create-dirs)
  (spit f content)
  f)

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
    (let [f   (str dir "/f.txt")
          fmd (as-md f)
          edn {:bar "bar"}]
      (pspit f "foo")
      (pspit fmd edn)
      (is (= (merge edn
                    {:content/modified (sut/modified f)
                     :metadata/modified (sut/modified fmd)})
             (sut/read f)
             (sut/read fmd))))))
