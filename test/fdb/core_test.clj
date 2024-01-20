(ns fdb.core-test
  (:require
   [babashka.fs :refer [with-temp-dir] :as fs]
   [clojure.core.async :refer [<!! chan close!]]
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
  [[config-path mount-path] & body]
  `(with-temp-dir [dir# {}]
     (let [~mount-path  (str dir# "/test")
           ~config-path (str dir# "/metadata.edn")]
       (fs/create-dirs ~mount-path)
       (u/spit ~config-path {:db-path "./db"
                             :mounts  {:test "./test"}
                             :processors {:my-edn 'u/slurp-edn}})
       ~@body)))

(deftest make-me-a-fdb
  (with-temp-fdb-config [config-path mount-path]
    (let [f        (fs/path mount-path "file.txt")
          fm       (fs/path mount-path "file.txt.metadata.edn")
          snapshot (atom nil)]
      (fdb/with-watch [config-path node]
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

      (fdb/with-watch [config-path node]
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
  (reset! calls [])
  (with-temp-fdb-config [config-path mount-path]
    (fdb/with-watch [config-path node]
      (u/spit mount-path "file.txt.metadata.edn"
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
               :fdb.on/schedule [{:every [50 :millis]
                                  :call  'fdb.core-test/log-call}
                                 {:every   [50 :millis]
                                  :call    '(fn [call-arg]
                                              (fdb.utils/sleep 100)
                                              (fdb.core-test/log-call {:on [:fdb.on/schedule-timeout]}))
                                  :timeout [50 :millis]}]
               :fdb.on/startup  ['fdb.core-test/log-call]
               :fdb.on/shutdown ['fdb.core-test/log-call]})
      (is (u/eventually (db/pull node "/test/file.txt")))
      (db/pull node "/test/file.txt")
      (u/spit mount-path "one.md" "")
      (u/spit mount-path "folder/two.md" "")
      (is (u/eventually (db/pull node "/test/folder/two.md")))
      ;; Check query ran by waiting for query result.
      (is (u/eventually (= #{["/test/file.txt"]
                             ["/test/folder"]
                             ["/test/folder/two.md"]
                             ["/test/one.md"]
                             ["/test/query-results.edn"]}
                           (u/slurp-edn mount-path "query-results.edn"))))
      (is (u/eventually (some #{:fdb.on/schedule} @calls))))

    ;; restart for startup/shutdown
    (fdb/with-watch [config-path node])

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
  (with-temp-fdb-config [config-path mount-path]
    (fdb/with-watch [config-path node]
      (u/spit mount-path "one.txt" "")
      (u/spit mount-path "folder/two.txt" "")
      (u/spit mount-path "all-modified-query.fdb.edn"
              '[:find ?e ?modified
                :where [?e :fdb/modified ?modified]])
      (u/eventually (u/slurp mount-path "all-modified-results.fdb.edn"))
      (is (= #{"/test/one.txt"
               "/test/folder"
               "/test/folder/two.txt"
               "/test/all-modified-query.fdb.edn"}
             (->> (u/slurp-edn mount-path "all-modified-results.fdb.edn")
                  (map first)
                  set)))
      (u/spit mount-path "all-modified-query.fdb.edn" "foo")
      (u/eventually (:error (u/slurp-edn mount-path "all-modified-results.fdb.edn")))
      (is (= "Query didn't match expected structure"
             (:error (u/slurp-edn mount-path "all-modified-results.fdb.edn")))))))

(def ignore-calls (atom 0))

(defn ignore-log-call [_]
  (swap! ignore-calls inc))

(deftest ignore-me-a-change
  (reset! ignore-calls 0)
  (with-temp-fdb-config [config-path mount-path]
    (fdb/with-watch [config-path node]
      (let [id  "/test/one"
            f (fs/path mount-path "one.metadata.edn")]
        (u/spit-edn f {:fdb.on/modify [{:call  'fdb.core-test/ignore-log-call
                                         :count 1}]})
        (is (u/eventually (= 1 @ignore-calls)))

        (metadata/silent-swap! f config-path id update-in [:fdb.on/modify 0 :count] inc)
        (metadata/silent-swap! f config-path id update-in [:fdb.on/modify 0 :count] inc)
        (metadata/silent-swap! f config-path id update-in [:fdb.on/modify 0 :count] inc)

        ;; wait to see if it gets there
        (u/eventually (= 4 @ignore-calls))

        (is (= 1 @ignore-calls))
        (is (-> (u/slurp-edn f)
                (get-in [:fdb.on/modify 0 :count])
                (= 4)))))))

(deftest make-me-a-call
  (with-temp-fdb-config [config-path mount-path]
    (let [f     (str (fs/path mount-path "one"))
          fm    (fs/path mount-path "one.metadata.edn")
          no-db #(dissoc % :node :db) ;; doesn't compare well
          _     (u/spit fm {:foo "bar"})
          self  {:xt/id        "/test/one"
                 :fdb/modified (metadata/modified fm)
                 :foo          "bar"}]
      (fdb/sync config-path)
      ;; exists
      (is (= (no-db (fdb/call config-path f identity))
             (no-db (fdb/call config-path fm identity))
             (no-db (fdb/call config-path "/test/one" identity))
             {:config-path config-path
              :config      (u/slurp-edn config-path)
              :self        self
              :self-path   f}))
      ;; doesn't exist, but mount-path is recognized
      (is (= (no-db (fdb/call config-path "/test/two" identity))
             {:config-path config-path
              :config      (u/slurp-edn config-path)
              :self        nil
              :self-path   (str (fs/path mount-path "two"))}))
      ;; doesn't exist and mount-path is not recognized
      (is (nil? (no-db (fdb/call config-path "/foo/bar" identity)))))))

(def blocking-ch nil)

(defn blocking-fn [_]
  (<!! blocking-ch))

(deftest make-me-a-sync
  (with-temp-fdb-config [config-path mount-path]
    (let [f       (str (fs/path mount-path "one.metadata.edn"))
          f-id    "/test/one"
          all-ids #(->> % :node db/all (map :xt/id) set)]

      ;; starts empty
      (fdb/sync config-path)
      (is (empty (fdb/call config-path f all-ids)))

      ;; updates
      (u/spit f {})
      (fdb/sync config-path)
      (is (= #{f-id} (fdb/call config-path f all-ids)))

      ;; blocks on sync calls
      (with-redefs [blocking-ch (chan)]
        (u/spit-edn f {:fdb.on/modify [{:call 'fdb.core-test/blocking-fn}]})
        (let [sync-fut (future (fdb/sync config-path))]
          (is (not (future-done? sync-fut)))
          (u/sleep 100)
          (is (not (future-done? sync-fut)))
          (close! blocking-ch)
          (is (u/eventually (future-done? sync-fut)))))

      ;; deletes
      (fs/delete f)
      (is (empty (fdb/call config-path f all-ids))))))

(deftest make-me-a-processing-db
  (with-temp-fdb-config [config-path mount-path]
    (let [f     (str (fs/path mount-path "one.my-edn"))
          f-md  (str (fs/path mount-path "one.my-edn.metadata.edn"))
          f-id  "/test/one.my-edn"
          get-f (fn []
                  (fdb/sync config-path)
                  (fdb/call config-path f #(-> % :node (db/pull f-id))))]
      (is (empty? (get-f)))
      (u/spit-edn f {:one 1})
      (is (= {:xt/id        f-id
              :fdb/modified (metadata/modified f)
              :one          1}
             (get-f)))
      (u/spit-edn f-md {:two 2})
      (is (= {:xt/id        f-id
              :fdb/modified (metadata/modified f-md)
              :one          1
              :two          2}
             (get-f)))
      (u/spit-edn f-md {:one 2})
      (is (= {:xt/id        f-id
              :fdb/modified (metadata/modified f-md)
              :one          2}
             (get-f))))))

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
