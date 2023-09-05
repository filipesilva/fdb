(ns core-test
  (:require
   [babashka.fs :as fs :refer [with-temp-dir]]
   [clojure.test :as t :refer [deftest is testing]]
   [fdb.core :as fdb]
   [hashp.core]))

(defn- modified [f]
  (-> f fs/last-modified-time fs/file-time->instant))

(defn- created [f]
  (-> f fs/creation-time fs/file-time->instant))

(deftest id
  (is (= "file://test/foo.txt"
         (fdb/id :test "foo.txt")))
  (is (= "file://test/foo.txt"
         (fdb/id "test" "foo.txt")))
  (is (= "file://test/foo.txt"
         (fdb/id :test "/foo.txt")))
  (is (= "file://test/foo.txt"
         (fdb/id :test "foo.txt.fdb"))))

(deftest read-file
  (testing "content file"
    (with-temp-dir [dir]
      (let [f (str dir "/f.txt")]
        (fdb/spit-edn f "foo")
        (is (= {:file/modified   (modified f)
                :file/created    (created f)
                :local.file/path f}
               (fdb/read-file f))))))
  (testing "metadata file"
    (with-temp-dir [dir]
      (let [f   (str dir "/f.txt.fdb")
            edn {:bar "bar"}]
        (fdb/spit-edn f edn)
        (is (= (merge edn (fdb/id f)
               (fdb/read-file f)))))))

(defmacro with-watch
  "Evaluate body while watching f as kw."
  [[kw f] & body]
  `(try
     (fdb/watch ~kw ~f)
     ~@body
     (finally
       (fdb/stop ~kw))))

(deftest fs-to-fdb
  (with-temp-dir [dir]
    (let [kw :test]

      (testing "create on watch"
        (let [f  (str dir "/foo.txt")
              fm (str f ".fdb")
              m  {:bar "bar"}]
          (fdb/spit-edn f "")
          (fdb/spit-edn fm m)
          (with-watch [kw dir]
             (is (= (merge (fdb/read-file f)
                           (fdb/read-file fm))
                    (fdb/entity (fdb/id kw f)))))))

      (testing "create while watching")
      (testing "modify while watching")
      (testing "delete while watching")
      (testing "modify on watch")
      (testing "delete on watch")


      #_#_#_@(fdb/watch :test dir)
          (is (= (merge metadata
                        {:xt/id         "file://test/foo.txt"
                         :file/modified (modified f)
                         :file/created  (created f)
                         :file/path     f
                         :file/type     "text/plain"})
                 (fdb/entity "file://test/foo.txt")))

        #p (slurp-edn f1)

      #_#_#_#_#_#_#p (modified f1)
                #p (created f1)
              (spit-edn f2 "2")
            #p (created f2)
          (spit-edn f1 "3")
        #p (modified f1))))

(deftest fdb-to-fs)

(deftest query-file)
