(ns fdb.triggers
  (:require
   [babashka.fs :as fs]
   [chime.core :as chime]
   [cronstar.core :as cron]
   [fdb.call :as call]
   [fdb.db :as db]
   [fdb.metadata :as metadata]
   [fdb.repl :as repl]
   [fdb.state :as state]
   [fdb.triggers.ignore :as tr.ignore]
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
  [self on-k trigger trigger-idx {:keys [config-path config] :as call-arg} & more]
  (let [call-arg' (apply merge
                         call-arg
                         {:self      self
                          :self-path (metadata/id->path config-path config (:xt/id self))
                          :on        [on-k trigger]
                          ;; trigger-idx might be 0, but on-k might not be a vec because
                          ;; call-all-triggers and update-schedules do map-indexed over x-or-xs
                          :on-path   (if (vector? (get self on-k))
                                       [on-k trigger-idx]
                                       [on-k])}
                         more)
        log-str   (str (:xt/id self) " " on-k " " (u/ellipsis (str trigger))
                       (if-some [doc-id (-> call-arg' :doc :xt/id)]
                         (str " over " doc-id)
                         ""))
        f         (fn []
                    (u/maybe-timeout (:timeout trigger)
                                     (fn []
                                       (u/with-time [t-ms #(log/debug "call" log-str "took" (t-ms) "ms")]
                                         (u/catch-log
                                          (call/apply trigger call-arg'))))))]

    (log/info "calling" log-str)
    (if *sync*
      (f)
      (future-call f))))

(defn call-all-triggers
  "Call all on-k triggers in self if should-trigger? returns truthy.
  Merges call-arg with {:self self, :on-k on-k :doc doc} and return from should-trigger?."
  ([call-arg doc self on-k]
   (call-all-triggers call-arg doc self on-k (constantly true)))
  ([{:keys [config-path config] :as call-arg} doc self on-k should-trigger?]
   (run! (fn [[trigger-idx trigger]]
           (when-let [maybe-map (u/catch-log (should-trigger? trigger))]
             (call self on-k trigger trigger-idx call-arg
                   (when doc
                     {:doc      doc
                      :doc-path (metadata/id->path config-path config (:xt/id self))})
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
  [config-path config node k]
  (u/with-time [t-ms #(log/debug "call all" k "took" (t-ms) "ms")]
    (let [db       (xt/db node)
          call-arg {:config-path config-path
                    :config      config
                    :node        node
                    :db          db}]
      (run! #(call-all-triggers call-arg % % k)
            (docs-with-k db k)))))

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

(defn update-schedules
  "Updates schedules for doc, scoped under config-path."
  [{:keys [config-path] :as call-arg} [op id doc]]
  (swap! state/*schedules
         (fn [schedules]
           ;; Start by stopping all schedules for this id, if any.
           (run! u/close (get-in schedules [config-path id]))
           (cond
             ;; Remove schedule if id was deleted.
             (and (= op ::xt/delete)
                  (get-in schedules [config-path id]))
             (do (log/debug "removing schedules for" id)
                 (update schedules config-path dissoc id))

             ;; Add new schedules for this id if any.
             (:fdb.on/schedule doc)
             (do (log/debug "adding schedules for" id)
                 (assoc-in schedules [config-path id]
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
                                                   (call doc :fdb.on/schedule trigger trigger-idx
                                                         call-arg {:timestamp (str timestamp)})
                                                   ;; Never cancel schedule from fn.
                                                   true))))
                             (-> doc :fdb.on/schedule call/specs)))))

             ;; There's no schedules for this doc, nothing to do.
             :else
             schedules))))

(defn start-all-schedules
  [config-path config node]
  (let [db       (xt/db node)
        call-arg {:config-path config-path
                  :config      config
                  :node        node
                  :db          db}]
    (run! #(update-schedules call-arg [nil (:xt/id %) %])
          (docs-with-k db :fdb.on/schedule))))

(defn stop-config-path-schedules
  "Stop schedules for config-path."
  [config-path]
  (swap! state/*schedules
         (fn [schedules]
           (run! u/close (-> schedules (get config-path) vals flatten))
           (dissoc schedules config-path))))

(defn stop-all-schedules
  "Stop all schedules."
  []
  (swap! state/*schedules
         (fn [schedules]
           (run! u/close (mapcat #(vals %) (vals schedules)))
           {})))


;; Triggers

(defn out-file
  [id in out ext]
  (when-some[[_ prefix] (re-matches
                         (re-pattern (str ".*/([^/]*)" in "\\.fdb\\." ext "$"))
                         id)]
    (str prefix out ".fdb." ext)))

(defn call-on-query-file
  "If id matches in /*query.fdb.edn, query with content and output
  results to sibling /*results.fdb.edn file."
  [{:keys [db config-path config]} [op id]]
  (when (= op ::xt/put)
    (when-some [results-file (out-file id "query" "results" "edn")]
      (log/info "querying" id "to" results-file)
      (let [query-path   (metadata/id->path config-path config id)
            results-path (u/sibling-path query-path results-file)
            results      (try (xt/q db (u/slurp-edn query-path))
                              (catch Exception e
                                {:error (ex-message e)}))]
        (u/spit-edn results-path results)))))

(defn call-on-repl-file
  "If id matches in /*repl.fdb.clj, call repl with content and print
  output to sibling /*outputs.fdb.clj file."
  [{:keys [config-path config]} [op id]]
  (when (= op ::xt/put)
    (when-some [outputs-file (out-file id "repl" "outputs" "clj")]
      (log/info "sending" id "to repl, outputs in" outputs-file)
      (let [file-path   (metadata/id->path config-path config id)
            outputs-path (u/sibling-path file-path outputs-file)]
        (repl/load config-path file-path outputs-path)))))

(defn call-on-modify
  "Call all :fdb.on/modify triggers in doc."
  [call-arg [_op _id {:fdb.on/keys [modify] :as doc}]]
  (when modify
    (call-all-triggers call-arg doc doc :fdb.on/modify)))

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
  [{:keys [db] :as call-arg} [_op _id doc]]
  (->> (recursive-pull-k db (:xt/id doc) :fdb/_refs)
       (filter :fdb.on/refs)
       (run! #(call-all-triggers call-arg doc % :fdb.on/refs))))

(defn matches-glob?
  "Returns true if id matches glob."
  [id glob]
  (-> (FileSystems/getDefault)
      (.getPathMatcher (str "glob:" glob))
      (.matches (fs/path id))))

(defn call-on-pattern
  "Call all existing :fdb.on/pattern triggers that match id."
  [{:keys [db] :as call-arg} [_op id doc]]
  (run! #(call-all-triggers call-arg doc % :fdb.on/pattern
                            (fn [trigger]
                              (matches-glob? id (:glob trigger))))
        (docs-with-k db :fdb.on/pattern)))

(defn call-on-startup
  "Call all :fdb.on/startup triggers in doc.
  These run on startup but also must be ran when the doc changes."
  [call-arg [_op _id {:fdb.on/keys [startup] :as doc}]]
  (when startup
    (call-all-triggers call-arg doc doc :fdb.on/startup)))

(defn query-results-changed?
  "Returns {:results ...} if query results changed compared to file at path."
  [config-path config db id {:keys [q path]}]
  (let [doc-path     (metadata/id->path config-path config id)
        results-path (u/sibling-path doc-path path)]
    (if (= doc-path results-path)
      (log/warn "skipping query on" id "because path is the same as file, which would cause an infinite loop")
      (let [new-results (u/catch-log (xt/q db q))
            old-results (u/catch-log (u/slurp-edn results-path))]
        (when (not= new-results old-results)
          (spit results-path (pr-str new-results))
          {:results new-results})))))

(defn call-all-on-query
  "Call all existing :fdb.on/query triggers, updating their results if changed."
  [{:keys [config-path config db] :as call-arg}]
  (run! #(call-all-triggers call-arg nil % :fdb.on/query
                            (partial query-results-changed?
                                     config-path config db (:xt/id %)))
        (docs-with-k db :fdb.on/query)))

(defn call-all-on-tx
  "Call all existing :fdb.on/tx triggers."
  [{:keys [db] :as call-arg}]
  (run! #(call-all-triggers call-arg nil % :fdb.on/tx)
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
   :on-path     get path inside self for trigger as [fdb.on/k 1]
   :self        the doc that has the trigger being called
   :self-path   on-disk path for self
   :doc         the doc the tigger is being called over, if any
   :doc-path    on-disk path for doc, if any
   :results     query results, if any
   :timestamp   schedule timestamp, if any}"
  [config-path config node tx]
  (u/catch-log
   (when-not (false? (:committed? tx)) ;; can be nil for txs retried from log directly
     (u/with-time [time-ms]
       (log/debug "processing tx" (::xt/tx-id tx))
       (let [call-arg {:config-path config-path
                       :config      config
                       :node        node
                       :db          (xt/db node {::xt/tx tx})
                       :tx          tx}
             ops      (->> tx
                           ::xt/tx-ops
                           (massage-ops node)
                           (remove (partial tr.ignore/ignore? config-path)))]

         ;; Update schedules
         (run! (partial update-schedules call-arg) ops)

         ;; Call triggers in order of "closeness"
         (run! (partial call-on-repl-file call-arg) ops) ;; content
         (run! (partial call-on-query-file call-arg) ops) ;; content
         (run! (partial call-on-modify call-arg) ops)     ;; self metadata
         (run! (partial call-on-refs call-arg) ops)       ;; direct ref
         (run! (partial call-on-pattern call-arg) ops)    ;; pattern
         (run! (partial call-on-startup call-arg) ops) ;; application lifecycle

         ;; Don't need ops, just needs to be called after every tx
         (call-all-on-query call-arg)
         (call-all-on-tx call-arg))
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
;; - maybe :fdb/tags and :fdb.on/tags? cool with readers adding tags
