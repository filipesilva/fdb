(ns fdb.watcher
  "File watching fns."
  (:require
   [babashka.fs :as fs]
   [nextjournal.beholder :as beholder]
   [taoensso.timbre :as log]))

(defn file-dir-relative
  [file-or-dir]
  (let [[file dir] (if (fs/directory? file-or-dir)
                     [nil file-or-dir]
                     [file-or-dir (fs/parent file-or-dir)])
        relative   #(-> dir (fs/relativize %) str)]
    [file dir relative]))

(defn watch
  "Watch a file or directory for changes and call update/delete-fn with their relative paths.
  File move shows up as delete-fn and update-fn call pairs, in no deterministic order.
  Returns a closeable that stops watching when closed."
  [file-or-dir update-fn delete-fn]
  (let [[_ _ relative] (file-dir-relative file-or-dir)]
    (beholder/watch (fn [{:keys [path type]}]
                      (let [path' (str (relative path))]
                        (case type
                          (:create :modify) (update-fn [path'])
                          :delete           (delete-fn [path'])
                          :overflow         (log/error "overflow" path'))))
                    file-or-dir)))

(defn watch-many
  [watch-spec]
  (->> watch-spec (map (partial apply watch)) doall))


(defn glob
  "Returns all files in file-or-dir matching glob pattern."
  [file-or-dir]
  (let [[file dir relative] (file-dir-relative file-or-dir)]
    (->> (if file
           [file]
           (fs/glob dir "**"))
         (mapv relative))))

;; TODO:
;; - include/exclude filters
;; - auto exclude using .gitignore, maybe .fdbignore
