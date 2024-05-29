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

(deftest without-random-uuid-test
  (is (= [1 2 3] (sut/without-random-uuid [(random-uuid) (random-uuid) (random-uuid)]))))

(deftest flatten-maps-test
  (is (= {"root" {:a 1}} (sut/flatten-maps {:a 1})))
  (is (= {"1"    {:e "e"
                  :f "f"}
          "2"    {:c "c"
                  :d :1}
          "root" {:a "a"
                  :b :2}}
         (sut/without-random-uuid
           (sut/flatten-maps
            {:a "a"
             :b {:c "c"
                 :d {:e "e"
                     :f "f"}}}
            :xform-uuid str
            :xform-ref (comp keyword str))))))

(deftest explode-id-test
  (with-temp-dir [dir {}]
    (let [id     "/user/foo.edn"
          path   (fs/file dir "foo.edn")
          path-k #(str path ".explode/" % ".edn")
          id-k   #(str id ".explode/" % ".edn")]
      (is (nil? (sut/explode-id id path 1)))
      (is (nil? (sut/explode-id id path {:a "a"})))
      (is (= {(path-k 1)      {:d "d"},
              (path-k 2)      {:b "b" :c (id-k 1)},
              (path-k "root") {:a (id-k 2)}}
           (sut/without-random-uuid
             (sut/explode-id id path {:a {:b "b" :c {:d "d"}}}))))
      (is (= {(path-k 1)      {:a "a"},
              (path-k 2)      {:b "b"}
              (path-k "root") {:0 (id-k 1) :1 (id-k 2)}}
           (sut/without-random-uuid
             (sut/explode-id id path [{:a "a"} {:b "b"}]))))
      (is (= {(path-k 4)      {:a "a"},
              (path-k 3)      {:b "b"}
              (path-k "root") {:1 (id-k 3) :2 (id-k 4)}}
           (sut/without-random-uuid
             (sut/explode-id id path #{{:a "a"} {:b "b"}})))))))
