(ns fdb.config
  (:require
   [babashka.fs :as fs]
   [fdb.utils :as u]))

(def filename "fdbconfig.edn")

(defn new-path
  "Returns file path for a new config file."
  [path]
  (-> (if (u/catch-nil (fs/directory? path))
        path
        (fs/path (fs/home) "fdb"))
      (fs/path filename)
      fs/absolutize
      fs/normalize
      str))

(defn path
  "Returns path if it's a file, otherwise looks for the config file in
  path, current dir, and ~/fdb/. Returns nil if none was found"
  [path]
  (some->> [path
            (str (fs/file path filename))
            (str (fs/file (fs/cwd) filename))
            (str (fs/file (fs/home) "fdb" filename))]
           (filter #(u/catch-nil (fs/regular-file? %)))
           first
           fs/absolutize
           str))

;; TODO:
;; - move all the config fns scattered around here instead
