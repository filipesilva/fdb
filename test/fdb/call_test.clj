(ns fdb.call-test
  (:require
   [clojure.test :refer [deftest is]]
   [fdb.call :as sut]))

(deftest symbol-test
  (is (= 43 ((sut/to-fn 'clojure.core/inc) 42)))
  (is (= 43 ((sut/to-fn 'inc) 42))))

(deftest sexp-test
  (is (= 43 ((sut/to-fn '(fn [x] (inc x))) 42)))
  (is (= "foo" ((sut/to-fn
                 '(fn [x]
                    (clojure.string/lower-case x)))
                "FOO"))))

(deftest shell-text
  (is (= "1 2 3\n"
         (with-out-str
           ((sut/to-fn '["echo" config-path doc-path self-path])
            {:config-path "1" :doc-path "2" :self-path "3"})))))
