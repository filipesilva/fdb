(ns fdb.utils-test
  (:require
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
                 (let [v (swap! foo inc)]
                   false))]
    (sut/eventually (foo-fn))
    (is (>= 100 @foo))))
