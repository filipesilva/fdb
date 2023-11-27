(ns fdb.core-test
  (:require
   [babashka.fs :refer [with-temp-dir] :as fs]
   [clojure.test :refer [deftest is testing]]
   [fdb.core :as fdb]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.reactive :as reactive]
   [fdb.utils :as u]
   [hashp.core]
   [xtdb.api :as xt]))

(defmacro with-temp-fdb-config
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[config-path mount] & body]
  `(with-temp-dir [dir# {}]
     (let [~mount        (str dir# "/test")
           ~config-path (str dir# "/metadata.edn")]
       (fs/create-dirs ~mount)
       (u/spit ~config-path {:fdb/db-path "./db"
                             :fdb/mount   {:test "./test"}})
       ~@body)))

(deftest make-me-a-fdb
  (with-temp-fdb-config [config-path mount]
    (let [f        (fs/path mount "file.txt")
          fm       (fs/path mount "file.txt.metadata.edn")
          snapshot (atom nil)]
      (fdb/with-fdb [config-path node]
        (is (empty? (db/all node)))
        (testing "updates from content and metadata files separately"
          (u/spit f "")
          (is (u/eventually (= #{{:xt/id        "/test/file.txt"
                                  :fdb/modified (metadata/modified f)}}
                               (db/all node))))
          (u/spit fm {:foo "bar"})
          (is (u/eventually (= #{{:xt/id        "/test/file.txt"
                                  :fdb/modified (metadata/modified fm)
                                  :foo          "bar"}}
                               (db/all node)))))
        (reset! snapshot (db/all node)))

      (u/spit f "1")

      (fdb/with-fdb [config-path node]
        (testing "updates on stale data"
          (is (u/eventually (not= @snapshot (db/all node)))))

        (testing "updates on partial delete"
          (fs/delete f)
          (is (u/eventually (= #{{:xt/id        "/test/file.txt"
                                  :fdb/modified (metadata/modified fm)
                                  :foo          "bar"}}
                               (db/all node)))))
        (testing "deletes"
          (fs/delete fm)
          (is (u/eventually (empty? (db/all node)))))))))


(def calls (atom []))

(defn log-call [call-arg]
  (swap! calls conj (-> call-arg :on first)))

(deftest make-me-a-reactive-fdb
  (with-temp-fdb-config [config-path mount]
    (reset! calls [])
    (fdb/with-fdb [config-path node]
      (u/spit mount "file.txt.metadata.edn"
              {:fdb/refs        #{"/test/one.md"
                                  "/test/folder/two.md"}
               :fdb.on/modify   ['fdb.core-test/log-call]
               :fdb.on/refs     ['fdb.core-test/log-call]
               :fdb.on/pattern  [{:glob "**/*.md"
                                  :call 'fdb.core-test/log-call}]
               :fdb.on/query    [{:q    '[:find ?e :where [?e :xt/id]]
                                  :path "./query-results.edn"
                                  :call 'fdb.core-test/log-call}]
               :fdb.on/tx       ['fdb.core-test/log-call]
               :fdb.on/schedule [{:millis 50
                                  :call   'fdb.core-test/log-call}]
               :fdb.on/startup  ['fdb.core-test/log-call]
               :fdb.on/shutdown ['fdb.core-test/log-call]})
      (is (u/eventually (db/pull node "/test/file.txt")))
      (db/pull node "/test/file.txt")
      (u/spit mount "one.md" "")
      (u/spit mount "folder/two.md" "")
      (is (u/eventually (db/pull node "/test/folder/two.md")))
      ;; Check query ran by waiting for query result.
      (is (u/eventually (= #{["/test/file.txt"]
                             ["/test/folder"]
                             ["/test/folder/two.md"]
                             ["/test/one.md"]
                             ["/test/query-results.edn"]}
                           (u/slurp-edn mount "query-results.edn"))))
      ;; Can take a bit, maybe because of the atom, or maybe it runs in the same process?
      (is (u/eventually (:fdb.on/schedule @calls) 3000)))

    ;; restart for startup/shutdown
    (fdb/with-fdb [config-path node])

    (is (= {:fdb.on/modify   1    ;; one for each modify
            :fdb.on/refs     2    ;; one.txt folder/two.txt
            :fdb.on/pattern  2    ;; one.md folder/two.md
            :fdb.on/query    5    ;; one for each tx
            :fdb.on/tx       5    ;; one for each tx
            :fdb.on/schedule true ;; positive, hard to know how many
            :fdb.on/startup  2    ;; one for each startup or write with trigger
            :fdb.on/shutdown 2    ;; one for each shutdown
            }
           (-> @calls
               frequencies
               (update :fdb.on/schedule #(when % true)))))))

(deftest make-me-a-fdb-query
  (with-temp-fdb-config [config-path mount]
    (fdb/with-fdb [config-path node]
      (u/spit mount "one.txt" "")
      (u/spit mount "folder/two.txt" "")
      (u/spit mount "all-modified-query.fdb.edn"
              '[:find ?e ?modified
                :where [?e :fdb/modified ?modified]])
      (u/eventually (u/slurp mount "all-modified-results.fdb.edn"))
      (is (= #{"/test/one.txt"
               "/test/folder"
               "/test/folder/two.txt"
               "/test/all-modified-query.fdb.edn"}
             (->> (u/slurp-edn mount "all-modified-results.fdb.edn")
                  (map first)
                  set)))
      (u/spit mount "all-modified-query.fdb.edn" "foo")
      (u/eventually (:error (u/slurp-edn mount "all-modified-results.fdb.edn")))
      (is (= "Query didn't match expected structure"
             (:error (u/slurp-edn mount "all-modified-results.fdb.edn")))))))

(comment
  (def node (db/node "/tmp/fdb-test"))

  (db/put node "/test/file.txt"
          {:fdb/refs #{"/test/one.txt"
                       "/test/folder/two.txt"}})
  (db/put node "/test/one.txt" {})
  (db/put node "/test/folder/two.txt" {})

  (db/pull node "/test/file.txt")

  (xt/pull (xt/db node)
           [:xt/id
            {(list :fdb/_refs {:limit ##Inf})
             '...}]
           "/test/one.txt")

  (reactive/recursive-pull-k (xt/db node) "/test/folder/two.txt" :fdb/_refs)


  (tree-seq map? :fdb/_refs
   '{:fdb/_refs ({:fdb/_refs ({:fdb/_refs (#:xt {:id :1}), :xt/id :4}), :xt/id :2})})

  )
