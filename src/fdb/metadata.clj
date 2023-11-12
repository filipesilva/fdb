(ns fdb.metadata
  (:refer-clojure :exclude [read])
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [tick.core :as t]))

(def default-metadata-ext "metadata.edn")

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

(defn modified [& paths]
  (try
    (-> (apply fs/path paths) fs/last-modified-time fs/file-time->instant)
    (catch java.nio.file.NoSuchFileException _ nil)))

(defn read
  [path]
  (let [[content-path metadata-path]
        (if (metadata-path? path)
          [(metadata-path->content-path path) path]
          [path (content-path->metadata-path path)])
        modifieds
        (remove nil? [(modified content-path)
                      (modified metadata-path)])]
    (merge
     (when (seq modifieds)
       {:metadata/modified (apply t/max modifieds)})
     (try
       (-> metadata-path slurp edn/read-string)
       (catch java.io.FileNotFoundException _ nil)))))
