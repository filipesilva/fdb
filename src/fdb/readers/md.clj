(ns fdb.readers.md
  (:refer-clojure :exclude [read])
  (:require
   [babashka.fs :as fs]
   [clj-yaml.core :as yaml]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [fdb.metadata :as metadata]
   [fdb.utils :as u]
   [fdb.watcher :as watcher]))

;; https://help.obsidian.md/Editing+and+formatting/Tags#Tag+format
(defn tags [md]
  (->> md
       (re-seq #"(?<=#)[\p{IsAlphabetic}\d-_/]+")
       ;; need to have at least one letter
       (filter (partial re-find #"[\p{IsAlphabetic}]"))
       (map str/lower-case)))

;; https://help.obsidian.md/Linking+notes+and+files/Internal+links#Supported+formats+for+internal+links
(defn mdlinks [md self mount]
  (->> md
       ;; [] without [ inside followed by ()
       (re-seq #"\[[^\[]+\]\(([^)]+)\)")
       (map second)
       (map u/url-decode)
       (map #(->> %
                  (fs/path (fs/parent self))
                  (fs/relativize mount)
                  fs/normalize
                  str))))

;; https://help.obsidian.md/Linking+notes+and+files/Internal+links#Supported+formats+for+internal+links
(defn wikilinks [md]
  ;; Obsidian doesn't support links with ^#[]| in the filename proper, if you make a new file and input
  ;; those characters, it will warn you.
  (->> md
       ;; not [] surrounded by [[]] via lookbehind and lookahead
       (re-seq #"(?<=\[\[)[^\[\]]+(?=\]\])")
       ;; cut off alias and anchor
       (map (partial re-find #"[^#|]*"))))

(defn shorter?
  [x y]
  (> (count y) (count x)))

;; Obsidian wikilinks only contain full path when ambiguous, and omit .md by default
(defn link->id
  [vault-files mount-id wikilink]
  (some #(when (or (str/ends-with? % wikilink)
                   (str/ends-with? % (str wikilink ".md")))
           (metadata/id mount-id %))
        vault-files))

(defn refs
  [{:keys [config config-path self self-path]} md]
  (let [id                    (:xt/id self)
        [mount-id mount-spec] (metadata/id->mount config id)
        mount-path            (metadata/mount-path config-path mount-spec)
        vault-files           (->> mount-path
                                   (watcher/glob config)
                                   ;; ambiguous long-form paths at root don't have folder
                                   (sort shorter?))]
    ;; use md to get links in body and front-matter
    (disj (->> (mdlinks md self-path mount-path)
               (concat (wikilinks md))
               (into #{})
               (map (partial link->id vault-files mount-id))
               (into #{}))
          nil)))

;; https://help.obsidian.md/Editing+and+formatting/Properties#Property+format
(defn front-matter [yml]
  (some-> yml
          yaml/parse-string
          (update-vals #(if (seq? %) (vec %) %))))

(defn read-edn-for-fdb-keys
  [m]
  (let [ks (filter #(and (qualified-keyword? %)
                         (or (= "fdb" (namespace %))
                             (str/starts-with? (namespace %) "fdb.")))
                   (keys m))]
    (merge m
           (-> (select-keys m ks)
               (update-vals #(cond
                               (string? %) (u/read-edn %)
                               (vector? %) (mapv u/read-edn %)))
               u/strip-nil-empty))))

(defn read
  [{:keys [self-path] :as call-arg}]
  (let [md            (slurp self-path)
        [_ yml body]  (re-find #"(?s)^(?:---\n(.*?)\n---\n)?(.*)" md)
        front-matter' (front-matter yml)
        tags          (-> #{}
                          (into (:tags front-matter'))
                          (into (tags body)))
        refs'         (refs call-arg md)]
    (merge (read-edn-for-fdb-keys front-matter')
           (when (seq tags)
             {:fdb/tags tags})
           (when (seq refs')
             {:fdb/refs refs'}))))

(comment
  @(def file (slurp (io/resource "md/file.md")))

  (wikilinks file)

  (mdlinks file "foo/bar/file.md" "foo")
)

;; TODO:
;; - make vault path configurable within a mount, via reader call-spec k
;; - markdown with yaml is a pretty good catch-all viz format
;;   - would be nice to ouptut it for stuff
;;   - don't have a great way to show things aside from query results, maybe server
