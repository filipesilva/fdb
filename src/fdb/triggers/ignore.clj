(ns fdb.triggers.ignore
  "Ignore list for reactive triggers.
  Reactive triggers for ids in the ignore list will be ignored once on the current process.
  Useful with fdb.utils/swap-edn-file! to avoid triggering on your own changes.")

;; config-path -> #{ids}
(def *ignores (atom {}))

(defn clear
  "Clear ignore list for config-path."
  [config-path]
  (swap! *ignores dissoc config-path))

(defn add
  "Add id under config-path to the ignore list.
  Reactive triggers for that id will be ignored once."
  [config-path id]
  (swap! *ignores update config-path #(conj (or % #{}) id)))

(defn ignore?
  "Returns true if id under config-path is in the ignore list."
  [config-path [_ id]]
    (let [ids (get @*ignores config-path #{})
          should-ignore? (ids id)]
      (when should-ignore?
        (swap! *ignores update config-path disj id))
      should-ignore?))

;; TODO:
;; - what are the semantics over multiple processes?
;;   - I guess it goes back to some sort of process affinity
;;   - if you have a cron or whatever that's running, you don't
;;     really want it to run in multiple processes anyway
