(ns fdb.reactive-test
  (:require
   [clojure.test :refer [deftest is]]
   [fdb.call :as call]
   [fdb.db :as db]
   [fdb.db-test :as db-test]
   [fdb.metadata :as metadata]
   [fdb.reactive :as sut]
   [fdb.utils :as u]
   [spy.core :as spy]
   [xtdb.api :as xt]))

(deftest call-all-triggers-test
  (let [should-trigger? (spy/spy (fn [trigger]
                                   (when (:doit trigger)
                                     {:didit true})))
        call-spy        (spy/spy)
        self            {:on-foo [{:call 1}
                                  {:doit true
                                   :call 2}
                                  3]}]
    (with-redefs [call/to-fn    (fn [_] call-spy)
                  metadata/path (spy/stub "root/folder/foo.txt")]
      (sut/call-all-triggers {:call-arg true}
                             :adoc
                             self
                             :on-foo
                             should-trigger?)
      (is (= [[{:call 1}] [{:doit true :call 2}] [3]]
             (spy/calls should-trigger?)))
      (is (= [[{:call-arg true
                :self     self
                :self-path "root/folder/foo.txt"
                :doc      :adoc
                :doc-path "root/folder/foo.txt"
                :on       [:on-foo {:doit true :call 2}]
                :didit    true}]]
             (spy/calls call-spy))))))

(deftest docs-with-k-test
  (db-test/with-db [node]
    (db/put node :one {:foo 1})
    (db/put node :two {:bar 2})
    (db/put node :three {:baz 3})
    (db/put node :four {:bar 4})
    (db/sync node)
    (is (= [{:xt/id :two :bar 2}
            {:xt/id :four :bar 4}]
           (sut/docs-with-k (xt/db node) :bar)))))

(deftest result-file-test
  (is (= "results.fdb.edn"
         (sut/results-file "/test/folder/query.fdb.edn")))
  (is (= "foo-results.fdb.edn"
         (sut/results-file "/test/folder/foo-query.fdb.edn")))
  (is (nil? (sut/results-file "/test/folder/foo.fdb.edn"))))

(deftest recursive-pull-k
  (db-test/with-db [node]
    (db/put node :1 {:foo 1 :fdb/refs #{:4}})
    (db/put node :2 {:foo 2 :fdb/refs #{:1}})
    (db/put node :3 {:foo 3 :fdb/refs #{:1}})
    (db/put node :4 {:foo 4 :fdb/refs #{:2 :3}})
    (db/put node :5 {:foo 5})
    (db/put node :6 {:foo 6 :fdb/refs #{:5}})
    (db/sync node)
    (is (= (set [{:xt/id :1 :foo 1 :fdb/refs #{:4}}
                 {:xt/id :2 :foo 2 :fdb/refs #{:1}}
                 {:xt/id :3 :foo 3 :fdb/refs #{:1}}
                 {:xt/id :4 :foo 4 :fdb/refs #{:2 :3}}])
           (set (sut/recursive-pull-k (xt/db node) :1 :fdb/_refs))))))

(deftest matches-glob?-test
  (is (sut/matches-glob? "foo.txt" "foo.txt"))
  (is (sut/matches-glob? "foo.txt" "foo.*"))
  (is (sut/matches-glob? "foo.txt" "*.*"))
  (is (sut/matches-glob? "foo.txt" "*"))
  (is (not (sut/matches-glob? "foo.txt" "*.log")))
  (is (sut/matches-glob? "/root/folder/foo.txt" "**"))
  (is (sut/matches-glob? "/root/folder/foo.txt" "/root/*/*"))
  (is (sut/matches-glob? "/root/folder/foo.txt" "/root/**"))
  (is (sut/matches-glob? "/root/folder/foo.txt" "**.txt"))
  (is (not (sut/matches-glob? "/root/folder/foo.txt" "**.log"))))

(deftest query-results-changed?-test
  (let [config-path "/root/one/two/config.edn"
        config      {:fdb/mount {:test "test"}}
        id          "/test/folder/foo.txt"]
    (with-redefs [xt/q        (spy/spy (fn [_ q] (if (= q :gimme-new)
                                                   {:v 2}
                                                   {:v 1})))
                  u/slurp-edn (spy/stub {:v 1})
                  spit        (spy/spy)]
      (is (= {:results {:v 2}}
             (sut/query-results-changed? config-path config nil id
                                         {:q    :gimme-new
                                          :path "results.edn"})))
      (is (nil? (sut/query-results-changed? config-path config nil id
                                            {:q    :foo
                                             :path "results.edn"})))

      (is (= [["/root/one/two/test/folder/results.edn" "{:v 2}"]]
             (spy/calls spit))))))

(deftest massage-ops-test
  (with-redefs [db/xtdb-id->xt-id (spy/stub "/test/deleted.txt")]
    (is (= [[:xtdb.api/put "/test/one.txt" {:foo 1, :xt/id "/test/one.txt"}]
            [:xtdb.api/delete "/test/deleted.txt"]]
           (sut/massage-ops nil [[:xtdb.api/put {:foo 1, :xt/id "/test/one.txt"}]
                                 ;; that thing is actually a #xtdb/id
                                 [:xtdb.api/delete 'c8d0e9aa0ad6ad1b22c4b232a822615a263d8099]
                                 [:xtdb.api/put {:foo 2, :xt/id "/test/one.txt"}
                                  #inst "1115-02-13T18:00:00.000-00:00"]])))))
