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
  (is (= "/test/foo.txt"
         (sut/id :test "foo.txt")))
  (is (= "/test/foo.txt"
         (sut/id "test" "foo.txt")))
  (is (= "/test/foo.txt"
         (sut/id :test "/foo.txt")))
  (is (= "/test/foo.txt"
         (sut/id :test (as-md "foo.txt")))))

(deftest id->path
  (let [config-path "/root/foo/config.edn"
        config      {:fdb/mount {:not-test "./not-test"
                                 :test     "./test"} }]
    (is (= "/root/foo/test/folder/foo.txt"
           (sut/id->path config-path config "/test/folder/foo.txt")))
    (is (nil? (sut/id->path config-path config "not-/test/folder/foo.txt")))
    (is (nil? (sut/id->path config-path config "/just-mount-id")))
    (is (nil? (sut/id->path config-path config "/missing-mount-id/foo.txt")))))

(deftest path->id
  (let [config-path "/root/foo/config.edn"
        config      {:fdb/mount {:test "./test"} }]
    (is (= "/test/foo.txt" (sut/path->id config-path config "/root/foo/test/foo.txt")))
    (is (= "/test/foo.txt" (sut/path->id config-path config "/test/foo.txt")))
    (is (nil? (sut/path->id config-path config "/root/foo/not-test/foo.txt")))))

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
      (is (= (merge {:fdb/modified (sut/modified fmd)} edn)
             (sut/read f)
             (sut/read fmd))))))
