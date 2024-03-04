(ns fdb.watcher
  "File watching fns."
  (:require
   [clojure.string :as str]
   [babashka.fs :as fs]
   [nextjournal.beholder :as beholder]
   [taoensso.timbre :as log]))

(def default-ignore-list
  [".DS_Store" ".git" ".gitignore" ".obsidian" ".vscode" "node_modules" "target" ".cpcache"])

(defn config->ignore-list
  [{:keys [ignore extra-ignore]}]
  (into (or ignore default-ignore-list) extra-ignore))

(def re-escape-cmap
  (->> "()&^%$#!?*."
      (map (fn [c] [c (str \\ c)]))
      (into {})))

(defn ignore-re
  [ignore-list]
  (let [ignore-ors (->> ignore-list
                        (map #(str/escape % re-escape-cmap))
                        (str/join "|"))]
    (re-pattern (str "(?:^|\\/)(?:" ignore-ors ")(?:\\/|$)"))))

(def memo-ignore-re (memoize ignore-re))

(defn ignore?
  [ignore-list path]
  (boolean (re-find (memo-ignore-re ignore-list) path)))

(defn file-dir-relative
  [file-or-dir]
  (let [[file dir] (if (fs/directory? file-or-dir)
                     [nil file-or-dir]
                     [file-or-dir (fs/parent file-or-dir)])
        relative   #(-> dir (fs/relativize %) str)]
    [file dir relative]))

(defn watch
  "Watch a file or directory for changes and call update-fn with their relative paths.
  File move shows up as two update calls, in no deterministic order.
  Returns a closeable that stops watching when closed."
  [config file-or-dir update-fn]
  (let [[_ _ relative] (file-dir-relative file-or-dir)
        ignore-list    (config->ignore-list config)]
    (beholder/watch (fn [{:keys [path type]}]
                      (let [path' (str (relative path))]
                        (when-not (ignore? ignore-list path')
                          (case type
                            (:create :modify :delete) (update-fn path')
                            :overflow                 (log/error "overflow" path')))))
                    file-or-dir)))

(defn watch-many
  [watch-spec]
  (->> watch-spec (map (partial apply watch)) doall))

(defn glob
  "Returns all files in file-or-dir matching glob pattern."
  [config file-or-dir]
  (when (fs/exists? file-or-dir)
    (let [[file dir relative] (file-dir-relative file-or-dir)
          ignore-list         (config->ignore-list config)]
      (->> (if file
             [file]
             (fs/glob dir "**"))
           (mapv relative)
           (filterv (complement (partial ignore? ignore-list)))))))
