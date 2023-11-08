(ns fdb.watcher
  (:require
   [babashka.fs :as fs]
   [nextjournal.beholder :as beholder]))

;; TODO: include/exclude filters
(defn watch
  [file-or-dir update-fn delete-fn stale-fn]
  (let [relative-path #(-> (if (fs/directory? file-or-dir)
                             file-or-dir
                             (fs/parent file-or-dir))
                           (fs/relativize %)
                           str)
        watcher       (beholder/watch (fn [{:keys [path type]}]
                                        (let [path' (str (relative-path path))]
                                          (case type
                                            (:create :modify) (update-fn path')
                                            :delete           (delete-fn path')
                                            :overflow         nil)))
                                      file-or-dir)]
    ;; don't do anything about files that were deleted while not watching
    (->> (fs/glob file-or-dir "**")
         (filter (comp not fs/directory?))
         (map relative-path)
         (filter stale-fn)
         (map update-fn)
         doall)
    watcher))

(defn watch-many
  [watch-spec]
  (->> watch-spec (map (partial apply watch)) doall))
