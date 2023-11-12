(ns fdb.watcher-test
  (:require
   [babashka.fs :refer [with-temp-dir] :as fs]
   [clojure.test :refer [deftest is]]
   [fdb.utils :as u]
   [fdb.watcher :as sut]
   [spy.core :as spy]))

(deftest watch-me-a-dir
  (with-temp-dir [db-path {}]
    (let [f1        (u/spit db-path "f1.txt" "")
          f2        (u/spit db-path "f2.txt" "")
          f3        (u/spit db-path "folder/f3.txt" "")
          update-fn (spy/spy)
          delete-fn (spy/spy)
          stale-fn  (spy/spy (fn [f]
                               (case f
                                 "f1.txt" true
                                 "f2.txt" true
                                 "folder" true
                                 "folder/f3.txt" false)))]
      (with-open [_watcher (sut/watch (str db-path) update-fn delete-fn stale-fn)]
        (is (u/eventually (spy/called-n-times? stale-fn 4)))
        (is (u/eventually (spy/called-n-times? update-fn 3)))
        (u/spit f1 "1")
        (is (u/eventually (spy/called-n-times? update-fn 3)))
        (u/spit f1 "11")
        (u/spit f2 "2")
        (u/spit f3 "3")
        (is (u/eventually (spy/called-n-times? update-fn 6)))
        (fs/delete f1)
        (is (u/eventually (spy/called-once? delete-fn)))
        (u/spit db-path "folder2/f4.txt" "")
        (is (u/eventually (spy/called-n-times? update-fn 8)))))))

(deftest watch-me-a-file
  (with-temp-dir [db-path {}]
    (let [f1        (u/spit db-path "f1.txt" "")
          update-fn (spy/spy)]
      (with-open [_watcher (sut/watch (str db-path) update-fn identity identity)]
        (is (u/eventually (spy/called-n-times? update-fn 1)))
        (is (spy/called-with? update-fn "f1.txt"))
        (u/spit f1 "1")
        (is (u/eventually (spy/called-n-times? update-fn 2)))))))
