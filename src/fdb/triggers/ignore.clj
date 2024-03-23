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

(defn ignoring?
  "Returns true if id under config-path is in the ignore list."
  [config-path id]
  (let [ids (get @*ignores config-path #{})]
    (ids id)))

(defn ignore-and-remove?
  "Returns true if id under config-path is in the ignore list,
  and removes it from the ignore list."
  [config-path id]
  (let [ignoring?' (ignoring? config-path id)]
    (when ignoring?'
      (swap! *ignores update config-path disj id))
    ignoring?'))

;; TODO:
;; - what are the semantics over multiple processes?
;;   - I guess it goes back to some sort of process affinity
;;   - if you have a cron or whatever that's running, you don't
;;     really want it to run in multiple processes anyway
;; - maybe get the watcher ignore here too, and move this to toplevel ns
