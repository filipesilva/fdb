(ns fdb.fns.obsidian
  (:require
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
(defn wikilink->id
  [vault-files mount-id wikilink]
  (some #(when (or (str/ends-with? % wikilink)
                   (str/ends-with? % (str wikilink ".md")))
           (metadata/id mount-id %))
        vault-files))

(defn refs
  [{:keys [config config-path self]} md]
  (let [id                    (:xt/id self)
        [mount-id mount-spec] (metadata/id->mount config id)
        vault-files           (->> mount-spec
                                   (metadata/mount-path config-path)
                                   watcher/glob
                                   ;; ambiguous long-form paths at root don't have folder
                                   (sort shorter?))]
    (disj (->> md ;; get wikilinks in body and front-matter
               wikilinks
               (into #{})
               (map (partial wikilink->id vault-files mount-id))
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

(defn metadata
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
  (require 'hashp.core)
  @(def file (slurp (io/resource "obsidian/file.md")))

  (metadata (io/resource "obsidian/file.md"))

  (wikilinks file)

  (re-seq #"(?<=\[\[)[^\[\]]+(?=\]\])" file)

  (io/file (io/resource "obsidian")))

;; TODO:
;; - make vault path configurable within a mount, via reader call-spec k
