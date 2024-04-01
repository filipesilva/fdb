(ns fdb.readers
  (:refer-clojure :exclude [read])
  (:require
   [babashka.fs :as fs]
   [fdb.call :as call]
   [fdb.metadata :as metadata]
   [fdb.readers.edn :as readers-edn]
   [fdb.readers.eml :as readers-eml]
   [fdb.readers.md :as readers-md]
   [fdb.utils :as u]))

(def default-readers
  {:edn #'readers-edn/read
   :eml #'readers-eml/read
   :md  #'readers-md/read})

(defn id->readers
  [config id]
  (let [ext-k      (-> id fs/split-ext second keyword)
        mount-spec (metadata/id->mount-spec config id)
        readers    (->> [(or (:readers mount-spec)
                             (:readers config)
                             default-readers)
                         (:extra-readers config)
                         (:extra-readers mount-spec)]
                        (map #(update-vals % call/specs))
                        (apply merge-with into))]
    (get readers ext-k)))

(defn read
  [config id]
  (->> (id->readers config id)
       (map (fn [call-spec]
              (call/with-arg {:on [:fdb.on/read call-spec]}
                (u/catch-log
                 (call/apply call-spec)))))
       (remove (comp not map?))
       (reduce merge {})))

;; TODO:
;; - glob reader ks
;;   - when id matches ks+globs, what happens?
;;   - would be easier if readers were vectors of [k-or-glob f-or-fns], order matters then
;;   - even nicer: support maps and vecs, use vecs when order matters only
;; - content reader
;;   - puts file content on :content k
;; - metadata could be a reader too... but that's going a bit meta atm
;;   - would make it easy to have json and other formats tho
;;   - would have to distinguish between edn reader and our edn built-in reader
;; - should the shell to-fn work as a reader?
;;   - a bit up in the air how the output would be processed... parse edn I guess
;; - support ext like .foo.bar, fs/split-ext doesn't work for that
;; - fdb.on/read on metadata can add more readers, used for testing
