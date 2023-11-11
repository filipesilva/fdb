(ns fdb.notifier-test
  (:require
   [clojure.core.async :refer []]
   [clojure.test :refer [deftest is]]
   [fdb.notifier :as sut]
   [fdb.utils :as utils]))

(deftest make-me-a-server
  (with-open [*ntf (utils/closeable (sut/create :test)
                                    (fn [_] (sut/destroy! :test)))]
    (let [ntf @*ntf]
      (is ntf)
      (is (nil? (sut/create :test)))
      (sut/notify! ntf)
      (is (= true (sut/wait ntf))))))

(comment
  (sut/destroy-all!))
