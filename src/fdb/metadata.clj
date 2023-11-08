(ns fdb.metadata
  (:refer-clojure :exclude [read])
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def default-metadata-ext "fdb.edn")

(defn metadata-path? [f]
  (-> f (fs/split-ext {:ext default-metadata-ext}) second))

(defn content-path->metadata-path
  [path]
  {:pre [(not (metadata-path? path))]}
  (str path "." default-metadata-ext))

(defn metadata-path->content-path
  [path]
  {:pre [(metadata-path? path)]}
  (first (fs/split-ext path {:ext default-metadata-ext})))

(defn id
  [host path]
  (str "file://"
       (if (keyword? host) (name host) host)
       (when-not (str/starts-with? path "/") "/")
       (if (metadata-path? path)
         (metadata-path->content-path path)
         path)))

(defn modified [f]
  (-> f fs/last-modified-time fs/file-time->instant))

(defn read
  [path]
  (let [[content-path metadata-path]
        (if (metadata-path? path)
          [(metadata-path->content-path path) path]
          [path (content-path->metadata-path path)])]
    (merge
     {:content/modified (modified content-path)
      :metadata/modified (modified metadata-path)}
     (-> metadata-path slurp edn/read-string))))
