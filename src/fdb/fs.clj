(ns fdb.fs
  (:require
   [babashka.fs :as bb-fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def default-metadata-ext "fdb.edn")

(defn spit-edn [f content]
  (-> f bb-fs/parent bb-fs/create-dirs)
  (spit f content)
  f)

(defn slurp-edn [f]
  (-> f
      slurp
      read-string))

(defn metadata-path? [f]
  (-> f (bb-fs/split-ext {:ext default-metadata-ext}) second))

(bb-fs/split-ext "file.txt.fdb.en" {:ext default-metadata-ext})

(defn content-path->metadata-path
  [path]
  {:pre [(not (metadata-path? path))]}
  (str path "." default-metadata-ext))

(defn metadata-path->content-path
  [path]
  {:pre [(metadata-path? path)]}
  (first (bb-fs/split-ext path {:ext default-metadata-ext})))

(defn id
  [ns path]
  (str "file://"
       (if (keyword? ns) (name ns) ns)
       (when-not (str/starts-with? path "/") "/")
       (if (metadata-path? path)
         (metadata-path->content-path path)
         path)))

(defn modified [f]
  (-> f bb-fs/last-modified-time bb-fs/file-time->instant))

(defn read-content
  [path]
  {:content/modified (modified path)})

(defn read-metadata
  [path]
  (merge
   {:metadata/modified (modified path)}
   (-> path slurp edn/read-string)))
