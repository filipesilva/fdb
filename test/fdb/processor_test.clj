(ns fdb.processor-test
  (:refer-clojure :exclude [read])
  (:require
   [babashka.fs :refer [with-temp-dir] :as fs]
   [clojure.test :refer [deftest is]]
   [fdb.processor :as sut]
   [fdb.utils :as u]))

(deftest file->processors
  (let [fn1    #()
        fn2    #()
        fn3    #()
        id     "/test/f.md"
        id->ps #(sut/id->processors % id)]
    (is (nil? (id->ps {})))
    (is (= [fn1] (id->ps {:processors {:md fn1}})))
    (is (= [fn1 fn2] (id->ps {:processors {:md [fn1 fn2]}})))
    (is (= [fn2] (id->ps {:processors {:md [fn1]}
                          :mounts     {:test     {:processors {:md fn2}}
                                       :not-test {:processors {:md fn3}}}})))
    (is (= [fn1 fn2] (id->ps {:processors {:md [fn1]}
                              :mounts     {:test {:extra-processors {:md fn2}}}})))
    (is (= [fn1] (id->ps {:mounts {:test {:extra-processors {:md fn1}}}})))))

(deftest read
  (with-temp-dir [dir {}]
    (let [f           (fs/file dir "./test/f.edn")
          id          "/test/f.edn"
          m           {:foo "bar"}
          config-path (fs/file dir "fdbconfig.edn")
          config      {:mounts {:test {:path       "./test"
                                       :processors {:edn [u/slurp-edn
                                                          (fn [_] {:bar "baz"})]}}}}]
      (u/spit-edn f m)
      (is (= {:foo "bar"
              :bar "baz"}
             (sut/read config-path config id))))))
