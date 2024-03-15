(ns fdb.watcher-test
  (:require
   [babashka.fs :refer [with-temp-dir] :as fs]
   [clojure.test :refer [deftest is]]
   [fdb.utils :as u]
   [fdb.watcher :as sut]
   [spy.core :as spy]))

(deftest ignore?-test
  (is (not (sut/ignore? sut/default-ignore-list "foo")))
  (is (sut/ignore? sut/default-ignore-list ".git"))
  (is (sut/ignore? sut/default-ignore-list ".git/a"))
  (is (sut/ignore? sut/default-ignore-list "a/.git/a")))

(deftest watch-me-a-dir
  (with-temp-dir [dir {}]
    (let [f1        (u/spit dir "f1.txt" "")
          f2        (u/spit dir "f2.txt" "")
          f3        (u/spit dir "folder/f3.txt" "")
          update-fn (spy/spy)]
      (with-open [_watcher (sut/watch {} (str dir) update-fn)]
        (u/spit f1 "1")
        (is (u/eventually (spy/called-n-times? update-fn 1))
            "calls update for file changes")
        (u/spit f1 "11")
        (u/spit f2 "2")
        (u/spit f3 "3")
        (is (u/eventually (spy/called-n-times? update-fn 4))
            "calls update multiple times")
        (fs/delete f1)
        (is (u/eventually (spy/called-n-times? update-fn 5))
            "calls update on delete")
        (u/spit dir "folder2/f4.txt" "")
        (is (u/eventually (spy/called-n-times? update-fn 7))
            "calls update for new files and folders")))))

(deftest watch-me-a-file
  (with-temp-dir [dir {}]
    (let [f1        (u/spit dir "f1.txt" "")
          update-fn (spy/spy)]
      (with-open [_watcher (sut/watch {} (str dir) update-fn)]
        (u/spit f1 "1")
        (is (u/eventually (spy/called-with? update-fn "f1.txt")))
        (is (u/eventually (spy/called-n-times? update-fn 1)))))))

(deftest glob
  (with-temp-dir [dir {}]
    (u/spit dir "f1.txt" "")
    (u/spit dir "f2.txt" "")
    (is (= ["f1.txt" "f2.txt"] (sut/glob {} dir)))
    (is (= ["f1.txt"] (sut/glob {} dir :pattern "*1.txt")))))
