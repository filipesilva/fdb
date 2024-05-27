(ns fdb.metadata
  (:refer-clojure :exclude [read swap!])
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [fdb.call :as call]
   [fdb.utils :as u]
   [tick.core :as t]))

(def default-metadata-ext "meta.edn")

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

(defn in-mount?
  [id mount-id]
  (str/starts-with? id (str "/" (name mount-id) "/")))

(defn id->mount
  [{:keys [mounts]} id]
  (some (fn [[mount-id mount-spec]]
          (when (in-mount? id mount-id)
            [mount-id mount-spec]))
        mounts))

(defn id->mount-spec
  [config id]
  (second (id->mount config id)))

(defn mount-path
  [config-path mount-spec]
  (str (u/sibling-path config-path (cond-> mount-spec
                                     (map? mount-spec) :path))))

(defn id
  [mount-id path]
  (str "/"
       (name mount-id)
       (when-not (str/starts-with? path "/") "/")
       (if (metadata-path? path)
         (metadata-path->content-path path)
         path)))

(defn id->path
  ([id]
   (id->path (:config-path call/*arg*) (:config call/*arg*) id))
  ([config-path {:keys [mounts]} id]
   (when-some [[_ mount-id path] (re-find #"^/([^/]+)/(.*)$" id)]
     (when-some [mount-spec (or (get mounts mount-id)
                                (get mounts (keyword mount-id)))]
       (str (fs/path (mount-path config-path mount-spec) path))))))

(defn path->id
  [config-path {:keys [mounts]} path]
  (some (fn [[mount-id mount-spec]]
          (let [mount-path' (mount-path config-path mount-spec)]
            (cond
              ;; Path looks like it's already an id.
              (str/starts-with? path (str "/" (name mount-id) "/"))
              path
              ;; Path is an absolute path under a mount.
              (str/starts-with? path mount-path')
              (id mount-id (fs/relativize mount-path' path)))))
        mounts))

(defn modified [& paths]
  (try
    (-> (apply fs/path paths) fs/last-modified-time fs/file-time->instant)
    (catch java.nio.file.NoSuchFileException _ nil)))

(defn read
  [& paths]
  (let [[content-path metadata-path] (apply content-and-metadata-paths paths)
        modifieds                    (remove nil? [(modified content-path)
                                                   (modified metadata-path)])]
    (when (seq modifieds)
      (merge (u/slurp-edn metadata-path)
             {:fdb/modified (apply t/max modifieds)}))))

(defn swap!
  "Like clojure.core/swap! but over metadata file for path.
  See fdb.utils/swap-edn-file! docstring for more."
  [path f & args]
  (let [[_ metadata-path] (content-and-metadata-paths path)]
    (apply u/swap-edn-file! metadata-path f args)))

;; TODO:
;; - lots of stuff here, mount stuff, should be in a config ns
;;   - cleaned up too, mount vs mount-spec
;; - could probably do silent-swap with a tx-id on a :ignore-next k
;;   - next time doc is tx'd, ignore and cache that you shouldn't ignore again
;;   - weird for users to have that key tho
;;   - could set on db directly
