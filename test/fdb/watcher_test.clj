(ns fdb.watcher-test
  (:require
   [babashka.fs :refer [with-temp-dir] :as fs]
   [clojure.test :refer [deftest is]]
   [fdb.utils :as utils]
   [fdb.watcher :as sut]
   [spy.core :as spy]))

(deftest watch-me-a-dir
  (with-temp-dir [db-path {}]
    (let [f1        (utils/spit db-path "f1.txt" "")
          f2        (utils/spit db-path "f2.txt" "")
          f3        (utils/spit db-path "folder/f3.txt" "")
          update-fn (spy/spy)
          delete-fn (spy/spy)
          stale-fn  (spy/spy (fn [f]
                               (case f
                                 "f1.txt" true
                                 "f2.txt" true
                                 "folder/f3.txt" false)))]
      (with-open [_watcher (sut/watch (str db-path) update-fn delete-fn stale-fn)]
        (is (utils/eventually (spy/called-n-times? stale-fn 3)))
        (is (utils/eventually (spy/called-n-times? update-fn 2)))
        (utils/spit f1 "1")
        (is (utils/eventually (spy/called-n-times? update-fn 3)))
        (utils/spit f1 "11")
        (utils/spit f2 "2")
        (utils/spit f3 "3")
        (is (utils/eventually (spy/called-n-times? update-fn 6)))
        (fs/delete f1)
        (is (utils/eventually (spy/called-once? delete-fn)))))))

(deftest watch-me-a-file
  (with-temp-dir [db-path {}]
    (let [f1        (utils/spit db-path "f1.txt" "")
          update-fn (spy/spy)]
      (with-open [_watcher (sut/watch (str db-path) update-fn identity identity)]
        (is (utils/eventually (spy/called-n-times? update-fn 1)))
        (is (spy/called-with? update-fn "f1.txt"))
        (utils/spit f1 "1")
        (is (utils/eventually (spy/called-n-times? update-fn 2)))))))
