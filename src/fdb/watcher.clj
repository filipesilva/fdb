(ns fdb.watcher
  "File watching fns."
  (:require
   [babashka.fs :as fs]
   [nextjournal.beholder :as beholder]))

;; TODO: include/exclude filters
(defn watch
  "Watch a file or directory for changes and call update/delete-fn with their relative paths.
  stale-fn is called on startup with each file, and if it returns true then update-fn is
  called for that file.
  File move shows up as delete-fn and update-fn call pairs, in no deterministic order.
  Returns a closeable that stops watching when closed."
  [file-or-dir update-fn delete-fn stale-fn]
  (let [relative-path #(-> (if (fs/directory? file-or-dir)
                             file-or-dir
                             (fs/parent file-or-dir))
                           (fs/relativize %)
                           str)
        watcher       (beholder/watch (fn [{:keys [path type] :as aaa}]

                                        (let [path' (str (relative-path path))]
                                          (case type
                                            (:create :modify) (update-fn path')
                                            :delete           (delete-fn path')
                                            :overflow         nil)))
                                      file-or-dir)]
    (->> (fs/glob file-or-dir "**")
         (map relative-path)
         (filter stale-fn)
         (map update-fn)
         doall)
    watcher))

(defn watch-many
  [watch-spec]
  (->> watch-spec (map (partial apply watch)) doall))
