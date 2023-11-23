(ns fdb.notifier-test
  (:require
   [clojure.core.async :refer []]
   [clojure.test :refer [deftest is]]
   [fdb.notifier :as sut]
   [fdb.utils :as u]))

(deftest make-me-a-notifier
  (with-open [*ntf (u/closeable (sut/get-or-create :test)
                                (fn [_] (sut/destroy! :test)))]
    (let [ntf @*ntf]
      (is ntf)
      (is (= ntf (sut/get-or-create :test)))
      (sut/notify! ntf)
      (is (= true (sut/wait ntf))))))

(comment
  (sut/destroy-all!))
