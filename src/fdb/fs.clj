(ns fdb.fs
  (:require
   [babashka.fs :as bb-fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defn spit-edn [f content]
  (-> f bb-fs/parent bb-fs/create-dirs)
  (spit f content)
  f)

(defn slurp-edn [f]
  (-> f
      slurp
      read-string))

(defn metadata-path? [f]
  (-> f bb-fs/extension (= "fdb")))

(defn content-path->metadata-path
  [path]
  {:pre [(not (metadata-path? path))]}
  (str path ".fdb"))

(defn metadata-path->content-path
  [path]
  {:pre [(metadata-path? path)]}
  (first (bb-fs/split-ext path {:ext "fdb"})))

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
