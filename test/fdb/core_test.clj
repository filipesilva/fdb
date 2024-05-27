(ns fdb.core-test
  (:require
   [babashka.fs :as fs :refer [with-temp-dir]]
   [clojure.core.async :refer [<!! chan close!]]
   [clojure.test :refer [deftest is testing]]
   [fdb.core :as fdb]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.triggers :as triggers]
   [fdb.utils :as u]
   [org.httpkit.client :as http]))

(defmacro with-temp-fdb-config
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[config-path mount-path] & body]
  `(with-temp-dir [dir# {}]
     (let [~mount-path  (str dir# "/test")
           ~config-path (str dir# "/fdbconfig.edn")]
       (fs/create-dirs ~mount-path)
       (u/spit ~config-path {:db-path "./fdb"
                             :mounts  {:test "./test"}
                             :repl    false})
       ~@body)))

(deftest make-me-a-fdb
  (with-temp-fdb-config [config-path mount-path]
    (let [f        (fs/path mount-path "file.txt")
          fm       (fs/path mount-path "file.txt.meta.edn")
          snapshot (atom nil)]
      (fdb/with-watch [config-path node]
        (is (empty? (db/all node)))
        (testing "updates from content and metadata files separately"
          (u/spit f "")
          (is (u/eventually (= #{{:xt/id        "/test/file.txt"
                                  :fdb/modified (metadata/modified f)
                                  :fdb/parent   "/test"}}
                               (db/all node))))
          (u/spit fm {:foo "bar"})
          (is (u/eventually (= #{{:xt/id        "/test/file.txt"
                                  :fdb/modified (metadata/modified fm)
                                  :fdb/parent   "/test"
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
                                  :fdb/parent   "/test"
                                  :foo          "bar"}}
                               (db/all node)))))
        (testing "deletes"
          (fs/delete fm)
          (is (u/eventually (empty? (db/all node)))))))))

(def calls (atom []))

(defn log-call [call-arg]
  (swap! calls conj (-> call-arg :on-path first)))

(deftest make-me-a-reactive-fdb
  (reset! calls [])
  (with-temp-fdb-config [config-path mount-path]
    (fdb/with-watch [config-path node]
      (u/spit mount-path "file.txt.meta.edn"
              {:fdb/refs        #{"/test/one.md"
                                  "/test/folder/two.md"}
               :fdb.on/modify   'fdb.core-test/log-call
               :fdb.on/refs     'fdb.core-test/log-call
               :fdb.on/pattern  {:glob "**/*.md"
                                 :call 'fdb.core-test/log-call}
               :fdb.on/query    {:q    '[:find ?e :where [?e :xt/id]]
                                 :path "./query-out.edn"
                                 :call 'fdb.core-test/log-call}
               :fdb.on/tx       'fdb.core-test/log-call
               :fdb.on/schedule [{:every [50 :millis]
                                  :call  'fdb.core-test/log-call}
                                 {:every   [50 :millis]
                                  :call    '(fn [call-arg]
                                              (fdb.utils/sleep 100)
                                              (fdb.core-test/log-call {:on [:fdb.on/schedule-timeout]}))
                                  :timeout [50 :millis]}]
               :fdb.on/startup  'fdb.core-test/log-call
               :fdb.on/shutdown 'fdb.core-test/log-call})
      (is (u/eventually (db/entity "/test/file.txt")))
      (db/entity "/test/file.txt")
      (u/spit mount-path "one.md" "")
      (u/spit mount-path "folder/two.md" "")
      (is (u/eventually (db/entity "/test/folder/two.md")))
      ;; Check query ran by waiting for query result.
      (is (u/eventually (= #{["/test/file.txt"]
                             ["/test/folder"]
                             ["/test/folder/two.md"]
                             ["/test/one.md"]
                             ["/test/query-out.edn"]}
                           (u/slurp-edn mount-path "query-out.edn"))))
      (is (u/eventually (some #{:fdb.on/schedule} @calls))))

    ;; restart for startup/shutdown
    (fdb/with-watch [config-path node])

    (is (= {:fdb.on/modify   1    ;; one for each modify
            :fdb.on/refs     2    ;; one.txt folder/two.txt
            :fdb.on/pattern  2    ;; one.md folder/two.md
            :fdb.on/query    5    ;; one for each tx, but sometimes I see 6, I guess from watcher stuff
            :fdb.on/tx       5    ;; one for each tx
            :fdb.on/schedule true ;; positive, hard to know how many
            :fdb.on/startup  2    ;; one for each startup or write with trigger
            :fdb.on/shutdown 2    ;; one for each shutdown
            }
           (-> @calls
               frequencies
               (update :fdb.on/schedule #(when % true))
               (update :fdb.on/tx #(min 5 %)))))))

(deftest make-me-a-query-fdb
  (with-temp-fdb-config [config-path mount-path]
    (fdb/with-watch [config-path node]
      (u/spit mount-path "one.txt" "")
      (u/spit mount-path "folder/two.txt" "")
      (u/spit mount-path "all-modified.query.fdb.edn"
              '[:find ?e ?modified
                :where [?e :fdb/modified ?modified]])
      (u/eventually (u/slurp mount-path "all-modified.query-out.fdb.edn"))
      (is (= #{"/test/one.txt"
               "/test/folder"
               "/test/folder/two.txt"
               "/test/all-modified.query.fdb.edn"}
             (->> (u/slurp-edn mount-path "all-modified.query-out.fdb.edn")
                  (map first)
                  set)))
      (u/spit mount-path "all-modified.query.fdb.edn" "foo")
      (u/eventually (:error (u/slurp-edn mount-path "all-modified.query-out.fdb.edn")))
      (is (= "Query didn't match expected structure"
             (:error (u/slurp-edn mount-path "all-modified.query-out.fdb.edn"))))
      (u/spit mount-path "all-modified.query.fdb.md" "
```edn
[:find ?e ?modified
 :where [?e :fdb/modified ?modified]]
```")
      (u/eventually (u/slurp mount-path "all-modified.query-out.fdb.md"))
      (is (= #{"/test/one.txt"
               "/test/folder"
               "/test/folder/two.txt"
               "/test/all-modified.query.fdb.edn"
               "/test/all-modified.query-out.fdb.edn"
               "/test/all-modified.query.fdb.md"}
             (->> (u/slurp mount-path "all-modified.query-out.fdb.md")
                  (triggers/unwrap-md-codeblock "edn")
                  u/read-edn
                  (map first)
                  set))))))

(deftest make-me-a-repl-fdb
  (with-temp-fdb-config [config-path mount-path]
    (fdb/with-watch [config-path node]
      (u/spit mount-path "repl.fdb.clj" "(inc 1)")
      (u/eventually (u/slurp mount-path "repl-out.fdb.clj"))
      (is (= "(inc 1)\n;; => 2\n\n"
             (u/slurp mount-path "repl-out.fdb.clj")))
      (let [form-with-out-and-err "
(println 1)
(binding [*out* *err*]
  (println 2))
(inc 2)"]
        (u/spit mount-path "2repl.fdb.clj" form-with-out-and-err)
        (u/eventually (u/slurp mount-path "2repl-out.fdb.clj"))
        (is (= (str form-with-out-and-err "\n;; 1\n;; 2\n;; => 3\n\n")
               (u/slurp mount-path "2repl-out.fdb.clj")))))))

(def ignore-calls (atom 0))

(defn ignore-log-call [_]
  (swap! ignore-calls inc))

(deftest ignore-me-a-change
  (reset! ignore-calls 0)
  (with-temp-fdb-config [config-path mount-path]
    (fdb/with-watch [config-path node]
      (let [f (fs/path mount-path "one.meta.edn")]
        (u/spit-edn f {:fdb.on/modify {:call  'fdb.core-test/ignore-log-call
                                       :count 1}})
        (is (u/eventually (= 1 @ignore-calls)))

        (metadata/swap! f assoc :fdb.on/ignore true)
        (metadata/swap! f update-in [:fdb.on/modify :count] inc)
        (metadata/swap! f update-in [:fdb.on/modify :count] inc)
        (metadata/swap! f update-in [:fdb.on/modify :count] inc)

        ;; wait to see if it gets there
        (u/eventually (= 4 @ignore-calls))

        (is (= 1 @ignore-calls))
        (is (-> (u/slurp-edn f)
                (get-in [:fdb.on/modify :count])
                (= 4)))))))

(def blocking-ch nil)

(defn blocking-fn [_]
  (<!! blocking-ch))

(deftest make-me-a-sync
  (with-temp-fdb-config [config-path mount-path]
    (let [f       (str (fs/path mount-path "one.meta.edn"))
          f-id    "/test/one"
          all-ids #(fdb/with-fdb [config-path _ node]
                     (->> node db/all (map :xt/id) set))]

      ;; starts empty
      (fdb/sync config-path)
      (is (empty (all-ids)))

      ;; updates
      (u/spit f {})
      (fdb/sync config-path)
      (is (= #{f-id} (all-ids)))

      ;; blocks on sync calls
      (with-redefs [blocking-ch (chan)]
        (u/spit-edn f {:fdb.on/modify {:call 'fdb.core-test/blocking-fn}})
        (let [sync-fut (future (fdb/sync config-path))]
          (is (not (future-done? sync-fut)))
          (u/sleep 100)
          (is (not (future-done? sync-fut)))
          (close! blocking-ch)
          (is (u/eventually (future-done? sync-fut)))))

      ;; deletes
      (fs/delete f)
      (is (empty (all-ids))))))

(deftest make-me-a-reader-db
  (with-temp-fdb-config [config-path mount-path]
    (let [f     (str (fs/path mount-path "one.my-edn"))
          f-md  (str (fs/path mount-path "one.my-edn.meta.edn"))
          f-id  "/test/one.my-edn"
          get-f (fn []
                  (fdb/sync config-path)
                  (fdb/with-fdb [config-path _ _]
                    (db/entity f-id)))]
      (u/swap-edn-file! config-path assoc
                        :readers {:my-edn '(fn [x#] (-> x# :self-path fdb.utils/slurp-edn))})
      (is (empty? (get-f)))
      (u/spit-edn f {:one 1})
      (is (= {:xt/id        f-id
              :fdb/modified (metadata/modified f)
              :fdb/parent   "/test"
              :one          1}
             (get-f)))
      (u/spit-edn f-md {:two 2})
      (is (= {:xt/id        f-id
              :fdb/modified (metadata/modified f-md)
              :fdb/parent   "/test"
              :one          1
              :two          2}
             (get-f)))
      (u/spit-edn f-md {:one 2})
      (is (= {:xt/id        f-id
              :fdb/modified (metadata/modified f-md)
              :fdb/parent   "/test"
              :one          2}
             (get-f))))))

(deftest make-me-a-loader-db
  (with-temp-fdb-config [config-path mount-path]
    (let [script (str (fs/path mount-path "script.clj"))
          f      (str (fs/path mount-path "f.meta.edn"))]
      (u/swap-edn-file! config-path assoc :load [script])
      (u/spit-edn f {:fdb.on/modify 'foo})
      (spit script "
(def *load-test (atom nil))
(defn foo [{:keys [self-path]}] (reset! *load-test self-path))")
      (fdb/sync config-path)
      (is (= (metadata/metadata-path->content-path f)
             (-> 'user/*load-test resolve deref deref))))))

(deftest make-me-a-server
  (with-temp-fdb-config [config-path mount-path]
    (let [script (str (fs/path mount-path "script.clj"))]
      (u/swap-edn-file! config-path assoc :load [script] :serve {:routes {"GET /" 'user/endpoint}})
      (spit script "(defn endpoint [_] {:status 200 :body {:a 1}})")
      (fdb/with-fdb [config-path _ node]
        (is (= {:status 200 :body "{\"a\":1}"}
               (select-keys @(http/get "http://localhost:80/") [:status :body])))))))

;; TODO
;; - speed up tests if I can, it's 15s now because of the watch stuff
;;   - on tests that don't start/stop fdb I can use in-memory xtdb, but that doesn't persist
;;   - might be able to stop it from closing during fdb usage and just leave it open all the time
;;   - got it open all the time but couldn't prevent it from being closed in fdb while
;;     still being able to use it as a xtdb node
