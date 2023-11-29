(ns fdb.metadata
  (:refer-clojure :exclude [read])
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [fdb.utils :as u]
   [tick.core :as t]))

(def default-metadata-ext "metadata.edn")

(defn metadata-path? [f]
  (-> f (fs/split-ext {:ext default-metadata-ext}) second))

(defn content-path->metadata-path
  [path]
  {:pre [(not (metadata-path? path))]}
  (str path "." default-metadata-ext))

(defn metadata-path
  [id]
  (content-path->metadata-path id))

(defn metadata-path->content-path
  [path]
  {:pre [(metadata-path? path)]}
  (first (fs/split-ext path {:ext default-metadata-ext})))

(defn content-and-metadata-paths
  [& paths]
  (let [path (str (apply fs/path paths))]
    (if (metadata-path? path)
      [(metadata-path->content-path path) path]
      [path (content-path->metadata-path path)])))

(defn id
  [mount-id path]
  (str "/"
       (if (keyword? mount-id) (name mount-id) mount-id)
       (when-not (str/starts-with? path "/") "/")
       (if (metadata-path? path)
         (metadata-path->content-path path)
         path)))

(defn path
  [config-path {:fdb/keys [mount]} id]
  (when-some [[_ mount-id path] (re-find #"^/([^/]+)/(.*)$" id)]
    (when-some [mount-from (or (get mount mount-id)
                               (get mount (keyword mount-id)))]
      (u/sibling-path config-path (fs/path mount-from path)))))

(defn modified [& paths]
  (try
    (-> (apply fs/path paths) fs/last-modified-time fs/file-time->instant)
    (catch java.nio.file.NoSuchFileException _ nil)))

(defn read
  [& paths]
  (let [[content-path metadata-path] (apply content-and-metadata-paths paths)
        modifieds                    (remove nil? [(modified content-path)
                                                   (modified metadata-path)])]
    (merge
     (when (seq modifieds)
       {:fdb/modified (apply t/max modifieds)})
     (u/slurp-edn metadata-path))))
