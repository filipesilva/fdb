(ns fdb.triggers
  (:require
   [babashka.fs :as fs]
   [chime.core :as chime]
   [clojure.string :as str]
   [cronstar.core :as cron]
   [fdb.call :as call]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.utils :as u]
   [taoensso.timbre :as log]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   (java.nio.file FileSystems)))

;; Helpers

;; Whether to call triggers synchronously or asynchronously.
(def ^:dynamic *sync* false)

(defn call
  "Call trigger with call-arg.
  Merges call-arg with {:self self, :on-k on-k} and any extra args."
  [self on-k trigger trigger-idx & more]
  (let [log-str (str (:xt/id self) " " on-k " " (u/ellipsis (str trigger))
                     (if-some [target-id (-> call/*arg* :target :xt/id)]
                       (str " over " target-id)
                       ""))
        f       (fn []
                  (call/with-arg (apply
                                  merge
                                  {:self      self
                                   :self-path (metadata/id->path (:xt/id self))
                                   :on        trigger
                                   ;; trigger-idx might be 0, but on-k might not be a vec because
                                   ;; call-all-triggers and update-schedules do map-indexed over one-or-many
                                   :on-path   (if (vector? (get self on-k))
                                                [on-k trigger-idx]
                                                [on-k])}
                                  more)
                    (u/maybe-timeout (:timeout trigger)
                                     (fn []
                                       (u/with-time [t-ms #(log/debug "call" log-str "took" (t-ms) "ms")]
                                         (u/catch-log
                                          (call/apply trigger)))))))]

    (log/info "calling" log-str)
    (if *sync*
      (f)
      (future-call f))))

(defn call-all-triggers
  "Call all on-k triggers in self if should-trigger? returns truthy.
  Merges call-arg with {:self self, :on-k on-k :target target} and return from should-trigger?."
  ([target self on-k]
   (call-all-triggers target self on-k (constantly true)))
  ([target self on-k should-trigger?]
   (run! (fn [[trigger-idx trigger]]
           (when-let [maybe-map (u/catch-log (should-trigger? trigger))]
             (call self on-k trigger trigger-idx
                   (when target
                     {:target      target
                      :target-path (metadata/id->path (:xt/id target))})
                   (when (map? maybe-map)
                     maybe-map))))
         (->> self on-k call/specs (map-indexed vector)))))

(defn docs-with-k
  "Get all docs with k in db.
  Optionally filter with more filters."
  [db k]
  ;; quote isn't in its normal place so we can use variable k
  (->> {:find  '[(pull ?e [*])]
        :where [['?e k]]}
       (xt/q db)
       (map first)))

(defn call-all-k
  "Call all existing k triggers.
  Mainly for :fdb.on/startup and :fdb.on/shutdown."
  [node k]
  (u/with-time [t-ms #(log/debug "call all" k "took" (t-ms) "ms")]
    (let [db (xt/db node)]
      (call/with-arg {:db db}
        (run! #(call-all-triggers % % k)
              (docs-with-k db k))))))

(defn massage-ops
  "Process ops to pass in to call handlers.
  Drop ops at a time, drop anything but puts and deletes,
  put :xt/id on second element for puts, and convert xtdb-id to xt-id for deletes."
  [node tx-ops]
  (->> tx-ops
       ;; Ignore ops with valid-time
       (filter #(-> % count (= 2)))
       ;; We only care about puts and deletes
       (filter #(-> % first #{::xt/put ::xt/delete}))
       ;; Convert xtdb-id to xt-id for deletes, and pull out id
       (map (fn [[op id-or-doc]]
              (if (= ::xt/delete op)
                [op (db/xtdb-id->xt-id node id-or-doc)]
                [op (:xt/id id-or-doc) id-or-doc])))))


;; Schedules

;; Reset to {} during watch start.
(defonce *schedules (atom {}))

(defn update-schedules
  "Updates schedules for doc, scoped under config-path."
  [[op id doc]]
  (swap! *schedules
         (fn [schedules]
           ;; Start by stopping all schedules for this id, if any.
           (run! u/close (get schedules id))
           (cond
             ;; Remove schedule if id was deleted.
             (and (= op ::xt/delete)
                  (get schedules id))
             (do (log/debug "removing schedules for" id)
                 (dissoc schedules id))

             ;; Add new schedules for this id if any.
             (:fdb.on/schedule doc)
             (do (log/debug "adding schedules for" id)
                 (assoc schedules id
                        (doall
                         (map-indexed
                          (fn [trigger-idx {:keys [cron every] :as trigger}]
                            (when-some [time-seq (u/catch-log
                                                  (cond
                                                    cron  (cron/times cron)
                                                    every (chime/periodic-seq
                                                           (t/now) (-> every u/duration-ms t/of-millis))))]
                              (chime/chime-at time-seq
                                              (fn [timestamp]
                                                (call/with-arg {:timestamp (str timestamp)}
                                                  (call doc :fdb.on/schedule trigger trigger-idx))
                                                ;; Never cancel schedule from fn.
                                                true))))
                          (-> doc :fdb.on/schedule call/specs)))))

             ;; There's no schedules for this doc, nothing to do.
             :else
             schedules))))

(defn start-schedules!
  [node]
  (let [db (xt/db node)]
    (call/with-arg {:db db}
      (run! #(update-schedules [nil (:xt/id %) %])
            (docs-with-k db :fdb.on/schedule)))))

(defn stop-schedules!
  []
  (swap! *schedules
         (fn [schedules]
           (run! u/close (mapcat identity (vals schedules)))
           {})))


;; Triggers

(defn out-file
  [id in ext]
  (when-some [[_ prefix] (re-matches
                          (re-pattern (str ".*/([^/]*)" in "\\.fdb\\." ext "$"))
                          id)]
    (str prefix in "-out" ".fdb." ext)))

(defn unwrap-md-codeblock
  [lang s]
  (->> s
       (str/trim)
       (re-find (re-pattern (str "(?s)^```" lang "\\n(.*)\\n```$")))
       second))

(defn wrap-md-codeblock
  [lang s]
  (str "```" lang "\n" (str/trim s) "\n```\n\n"))

(def ext->codeblock-lang
  {"clj" "clojure"
   "edn" "edn"})

(defn rep-ext-or-codeblock
  "Read eval print helper for query and repl files."
  [id in ext log-f f]
  (let [codeblock? (str/ends-with? id ".md")]
    (when-some [out-file' (out-file id in (if codeblock? "md" ext))]
      (log-f out-file')
      (let [in-path  (metadata/id->path id)
            out-path (u/sibling-path in-path out-file')
            content  (u/slurp in-path)
            lang     (ext->codeblock-lang ext)]
        (when-not (-> content str/trim empty?)
          (let [ret (if codeblock?
                      (some->> content
                               (unwrap-md-codeblock lang)
                               f
                               (wrap-md-codeblock lang))
                      (f content))]
            (cond
              ret (spit out-path ret)
              codeblock? (log/info "no solo" lang "codeblock found in" id))))))))

(defn call-on-query-file
  "If id matches in /*query.fdb.edn, query with content and output
  results to sibling /*results.fdb.edn file."
  [[op id]]
  (when (= op ::xt/put)
    (rep-ext-or-codeblock
     id "query" "edn"
     #(log/info "querying" id "to" %)
     #(try (some->> % u/read-edn (xt/q (:db call/*arg*)) u/edn-str)
           (catch Exception e
             {:error (ex-message e)})))))

(defn call-on-repl-file
  "If id matches in /*repl.fdb.clj, call repl with content and print
  output to sibling /*outputs.fdb.clj file."
  [[op id]]
  (when (= op ::xt/put)
    (rep-ext-or-codeblock
     id "repl" "clj"
     #(log/info "sending" id "to repl, outputs in" %)
     #(str % "\n"
           (binding [*ns* (create-ns 'user)]
             (call/with-arg {:self-path (metadata/id->path id)}
               (u/eval-to-comment %)))
           "\n"))))

(defn call-on-modify
  "Call all :fdb.on/modify triggers in doc."
  [[_op _id {:fdb.on/keys [modify] :as doc}]]
  (when modify
    (call-all-triggers doc doc :fdb.on/modify)))

(defn recursive-pull-k
  "Recursively pull k from doc.
  Returns all pulled docs."
  [db id k]
  (let [pulled (xt/pull db
                        [:xt/id
                         ;; No result limit
                         {(list k {:limit ##Inf})
                          ;; Unbounded recursion
                          '...}]
                        id)]
    (->> (dissoc pulled :xt/id) ;; don't include the root doc
         (tree-seq map? k)
         (map :xt/id)
         (remove nil?)
         (into #{})
         (xt/pull-many db '[*]))))

(defn call-on-refs
  "Call all :fdb.on/refs triggers in docs that have doc in :fdb.on/refs."
  [db [_op _id doc]]
  (->> (recursive-pull-k db (:xt/id doc) :fdb/_refs)
       (filter :fdb.on/refs)
       (run! #(call-all-triggers doc % :fdb.on/refs))))

(defn matches-glob?
  "Returns true if id matches glob."
  [id glob]
  (-> (FileSystems/getDefault)
      (.getPathMatcher (str "glob:" glob))
      (.matches (fs/path id))))

(defn call-on-pattern
  "Call all existing :fdb.on/pattern triggers that match id."
  [db [_op id doc]]
  (run! #(call-all-triggers doc % :fdb.on/pattern
                            (fn [trigger]
                              (matches-glob? id (:glob trigger))))
        (docs-with-k db :fdb.on/pattern)))

(defn call-on-startup
  "Call all :fdb.on/startup triggers in doc.
  These run on startup but also must be ran when the doc changes."
  [[_op _id {:fdb.on/keys [startup] :as doc}]]
  (when startup
    (call-all-triggers doc doc :fdb.on/startup)))

(defn query-results-changed?
  "Returns {:results ...} if query results changed compared to file at path."
  [db id {:keys [q path]}]
  (let [target-path  (metadata/id->path id)
        results-path (u/sibling-path target-path path)]
    (if (= target-path results-path)
      (log/warn "skipping query on" id "because path is the same as file, which would cause an infinite loop")
      (let [new-results (u/catch-log (xt/q db q))
            old-results (u/catch-log (u/slurp-edn results-path))]
        (when (not= new-results old-results)
          (spit results-path (pr-str new-results))
          {:results new-results})))))

(defn call-all-on-query
  "Call all existing :fdb.on/query triggers, updating their results if changed."
  [db]
  (run! #(call-all-triggers nil % :fdb.on/query
                            (partial query-results-changed? db (:xt/id %)))
        (docs-with-k db :fdb.on/query)))

(defn call-all-on-tx
  "Call all existing :fdb.on/tx triggers."
  [db]
  (run! #(call-all-triggers nil % :fdb.on/tx)
        (docs-with-k db :fdb.on/tx)))

;; tx listener

(defn on-tx
  "Call all applicable triggers over tx.
  Triggers will be called with a map arg containing:
  {:config      fdb config value
   :config-path on-disk path to config
   :node        xtdb database node
   :db          xtdb db value at the time of the tx
   :tx          the tx
   :on          the trigger being called as [fdb.on/k trigger]
   :on-path     get-in path inside self for trigger as [fdb.on/k 1]
   :self        the doc that has the trigger being called
   :self-path   on-disk path for self
   :target      the doc the trigger is being called over, if any
   :target-path on-disk path for doc, if any
   :results     query results, if any
   :timestamp   schedule timestamp, if any}"
  [call-arg node tx]
  (u/catch-log
   (when-not (false? (:committed? tx)) ;; can be nil for txs retried from log directly
     (u/with-time [time-ms]
       (log/debug "processing tx" (::xt/tx-id tx))
       (let [db  (xt/db node {::xt/tx tx})
             ops (->> tx
                      ::xt/tx-ops
                      (massage-ops node)
                      (remove (fn [[_ _ doc]] (:fdb.on/ignore doc))))]
         (call/with-arg (merge call-arg
                               {:db db
                                :tx tx})

           ;; Update schedules
           (run! update-schedules ops)

           ;; Call triggers in order of "closeness"
           (run! call-on-repl-file ops)            ;; content
           (run! call-on-query-file ops)           ;; content
           (run! call-on-modify ops)               ;; self metadata
           (run! (partial call-on-refs db) ops)    ;; direct ref
           (run! (partial call-on-pattern db) ops) ;; pattern
           (run! call-on-startup ops)              ;; application lifecycle

           ;; Don't need ops, just needs to be called after every tx
           (call-all-on-query db)
           (call-all-on-tx db)))
       (log/debug "processed tx-id" (::xt/tx-id tx) "in" (time-ms) "ms")))))

;; TODO:
;; - make schedules play nice with sync
;;   - every runs once immediately
;;   - cron saves last execution and runs immediately if missed using cron/times arity 2
;;   - need to make sure to wait on all listeners before exiting
;;     - or don't try to wait, load all files in a big tx, then just call trigger on tx one by one
;;     - this batch load mode is probably better anyway for stale check
;;   - would make tests much easier
;; - fdb.on/modify receives nil for delete, or dedicated fdb.on/delete
;; - is *sync* important enough for callers that it should be part of call-arg?
;;   - probably not, as they are ran async by default
;; - some way to replay the log for repl filesq
;; - would be cool to render ids in markdown for markdown query
