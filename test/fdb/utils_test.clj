(ns fdb.utils-test
  (:require
   [babashka.fs :refer [with-temp-dir] :as fs]
   [fdb.utils :as sut]
   [clojure.test :refer [deftest is]]))

(deftest eventually-early
  (let [foo (atom 0)
        foo-fn (fn []
                 (let [v (swap! foo inc)]
                   (= v 3)))]
    (sut/eventually (foo-fn))
    (is (= 3 @foo))))

(deftest eventually-late
  (let [foo (atom 0)
        foo-fn (fn []
                 (let [v (swap! foo inc)]
                   (= v 150)))]
    (sut/eventually (foo-fn))
    ;; not guaranteed to hit 100 before timeout
    (is (>= 100 @foo))))

(deftest eventually-never
  (let [foo (atom 0)
        foo-fn (fn []
                 (let [_v (swap! foo inc)]
                   false))]
    (sut/eventually (foo-fn))
    (is (>= 100 @foo))))

(deftest lockfile-test
  (with-temp-dir [dir {}]
    (with-open [lock (sut/lockfile dir "lockfile-test")
                lock2 (sut/lockfile dir "lockfile-test")]
      (is @lock)
      (is (nil? @lock2)))
    (let [lock3 (sut/lockfile dir "lockfile-test")]
      (is @lock3)
      (.close lock3))))

(deftest swap-edn-file-test
  (with-temp-dir [dir {}]
    (let [f (fs/file dir "foo.edn")]
      (is (thrown? Exception (sut/swap-edn-file! f :foo inc)))
      (sut/spit-edn f {:foo 1})
      (sut/swap-edn-file! f update :foo inc)
      (is (= {:foo 2} (sut/slurp-edn f))))))

(deftest filename-inst-test
  (is (= "2023-11-30T14.20.23Z"
         (sut/filename-inst #inst "2023-11-30T14:20:23.000-00:00"))))

(deftest filename-str-test
  (is (= "foo bar-_,%  !!àè(baz)[foo]{bar}"
         (sut/filename-str "  foo:bar-_,%:?!!àè(baz)[foo]{bar}  "))))

(deftest maybe-timeout-test
  (let [f #(sut/sleep 100)]
    (is (= ::sut/timeout (sut/maybe-timeout 0 f)))
    (is (= ::sut/timeout (sut/maybe-timeout [0 :seconds] f)))
    (is (= nil (sut/maybe-timeout nil f)))
    (is (= nil (sut/maybe-timeout 200 f)))))
