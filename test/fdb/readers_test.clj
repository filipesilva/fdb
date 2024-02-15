(ns fdb.readers-test
  (:refer-clojure :exclude [read])
  (:require
   [babashka.fs :as fs :refer [with-temp-dir]]
   [clojure.test :refer [deftest is]]
   [fdb.readers :as sut]
   [fdb.utils :as u]))

(deftest id->readers
  (let [fn1    #()
        fn2    #()
        fn3    #()
        id     "/test/f.md"
        id->ps #(sut/id->readers % id)]
    (is (nil? (id->ps {})))
    (is (= [fn1] (id->ps {:readers {:md fn1}})))
    (is (= [fn1 fn2] (id->ps {:readers {:md [fn1 fn2]}})))
    (is (= [fn2] (id->ps {:readers {:md [fn1]}
                          :mounts     {:test     {:readers {:md fn2}}
                                       :not-test {:readers {:md fn3}}}})))
    (is (= [fn1 fn2] (id->ps {:readers {:md [fn1]}
                              :mounts     {:test {:extra-readers {:md fn2}}}})))
    (is (= [fn1] (id->ps {:mounts {:test {:extra-readers {:md fn1}}}})))))

(deftest read
  (with-temp-dir [dir {}]
    (let [f           (fs/file dir "./test/f.edn")
          id          "/test/f.edn"
          m           {:foo "bar"}
          config-path (fs/file dir "fdbconfig.edn")
          config      {:mounts {:test {:path       "./test"
                                       :readers {:edn [u/slurp-edn
                                                          (fn [_] {:bar "baz"})]}}}}]
      (u/spit-edn f m)
      (is (= {:foo "bar"
              :bar "baz"}
             (sut/read config-path config id))))))
