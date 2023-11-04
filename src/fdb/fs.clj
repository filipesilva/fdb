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
       path))

(defn created [f]
  (-> f bb-fs/creation-time bb-fs/file-time->instant))

(defn modified [f]
  (-> f bb-fs/last-modified-time bb-fs/file-time->instant))

(defn created+modified-map
  [f]
  (let [{:keys [lastModifiedTime creationTime]}
        (bb-fs/read-attributes f "basic:lastModifiedTime,creationTime")]
    {:modified (bb-fs/file-time->instant lastModifiedTime)
     :created  (bb-fs/file-time->instant creationTime)}))

(defn- ns-keys
  [m ns]
  (update-keys m #(keyword ns (name %))))

(defn read-content
  [path]
  (merge
   (-> path created+modified-map (ns-keys "file"))
   {:local/path path}))

(defn read-metadata
  [path]
  (merge
   {:local/path path}
   (-> path created+modified-map (ns-keys "metadata"))
   (-> path slurp edn/read-string)))
