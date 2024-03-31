(ns fdb.readers-test
  (:refer-clojure :exclude [read])
  (:require
   [babashka.fs :as fs :refer [with-temp-dir]]
   [clojure.test :refer [deftest is]]
   [fdb.call :as call]
   [fdb.readers :as sut]
   [fdb.utils :as u]))

(deftest id->readers
  (with-redefs [sut/default-readers {}]
    (let [fn1      #()
          fn2      #()
          fn3      #()
          id       "/test/f.md"
          id->rdrs #(sut/id->readers % id)]
      (is (nil? (id->rdrs {})))
      (is (= [fn1] (id->rdrs {:readers {:md fn1}})))
      (is (= [fn1 fn2] (id->rdrs {:readers {:md [fn1 fn2]}})))
      (is (= [fn2] (id->rdrs {:readers {:md [fn1]}
                              :mounts  {:test     {:readers {:md fn2}}
                                        :not-test {:readers {:md fn3}}}})))
      (is (= [fn1 fn2] (id->rdrs {:readers {:md [fn1]}
                                  :mounts  {:test {:extra-readers {:md fn2}}}})))
      (is (= [fn1] (id->rdrs {:mounts {:test {:extra-readers {:md fn1}}}}))))))

(deftest read
  (with-temp-dir [dir {}]
    (let [f        (fs/file dir "./test/f.edn")
          config   {:mounts {:test {:path    "./test"
                                    :readers {:edn [#(-> % :self-path u/slurp-edn)
                                                    {:call (fn [x] {:bar (-> x :on second :str)})
                                                     :str "baz"}]}}}}
          call-arg {:config-path (fs/file dir "fdbconfig.edn")
                    :config      config
                    :self        {:xt/id "/test/f.edn"}
                    :self-path   f}]
      (u/spit-edn f {:foo "bar"})
      (is (= {:foo "bar"
              :bar "baz"}
             (call/with-arg call-arg
               (sut/read config "/test/f.edn")))))))
