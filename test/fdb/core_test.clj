(ns fdb.core-test
  (:require
   [clojure.test :as t :refer [deftest is testing]]
   [fdb.core :as fdb]
   [hashp.core]))


(defmacro with-watch
  "Evaluate body while watching f as kw."
  [[kw f] & body]
  `(try
     (fdb/watch ~kw ~f)
     ~@body
     (finally
       (fdb/stop ~kw))))

#_(deftest fs-to-fdb
  (with-temp-dir [dir]
    (let [kw :test]

      (testing "create on watch"
        (let [f  (str dir "/foo.txt")
              fm (str f ".fdb")
              m  {:bar "bar"}
              id (fdb/id kw "/foo.txt")]
          (fdb/spit-edn f "")
          (fdb/spit-edn fm m)
          (with-watch [kw dir]
            (is (= (fdb/read-file f)
                   (fdb/file id)))
            (is (= (fdb/read-metadata fm)
                   (fdb/metadata id))))))

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
