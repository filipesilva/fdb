(ns fdb.metadata
  (:refer-clojure :exclude [read swap!])
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [fdb.reactive.ignore :as r.ignore]
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

(defn mount-id->str
  [mount-id]
  (if (keyword? mount-id)
    (name mount-id)
    mount-id))

(defn in-mount?
  [mount-id path]
  (str/starts-with? path (str "/" (mount-id->str mount-id) "/")))

(defn id
  [mount-id path]
  (str "/"
       (mount-id->str mount-id)
       (when-not (str/starts-with? path "/") "/")
       (if (metadata-path? path)
         (metadata-path->content-path path)
         path)))

(defn id->path
  [config-path {:fdb/keys [mount]} id]
  (when-some [[_ mount-id path] (re-find #"^/([^/]+)/(.*)$" id)]
    (when-some [mount-from (or (get mount mount-id)
                               (get mount (keyword mount-id)))]
      (u/sibling-path config-path (fs/path mount-from path)))))

(defn path->id
  [config-path {:fdb/keys [mount]} path]
  (some (fn [[mount-id mount-path]]
          (let [abs-mount-path (str (u/sibling-path config-path mount-path))]
            (cond
              ;; Path looks like it's already an id.
              (str/starts-with? path (str "/" (mount-id->str mount-id) "/"))
              path
              ;; Path is an absolute path under a mount.
              (str/starts-with? path abs-mount-path)
              (id mount-id (fs/relativize abs-mount-path path)))))
        mount))

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

(defn swap!
  "Like clojure.core/swap! but over metadata file for path.
  See fdb.utils/swap-edn-file! docstring for more."
  [path f & args]
  (let [[_ metadata-path] (content-and-metadata-paths path)]
    (apply u/swap-edn-file! metadata-path f args)))

(defn silent-swap!
  "Like fdb.metadata/swap!, but change will be ignored by reactive triggers."
  [path config-path id f & args]
  (let [[_ metadata-path] (content-and-metadata-paths path)]
    (r.ignore/add config-path id)
    (apply u/swap-edn-file! metadata-path f args)))
