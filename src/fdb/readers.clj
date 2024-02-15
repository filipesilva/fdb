(ns fdb.readers
  (:refer-clojure :exclude [read])
  (:require
   [fdb.call :as call]
   [babashka.fs :as fs]
   [fdb.metadata :as metadata]
   [fdb.utils :as u]))

(defn id->readers
  [config id]
  (let [ext-k   (-> id fs/split-ext second keyword)
        mount   (metadata/id->mount config id)
        readers (->> [(or (:readers mount)
                          (:readers config))
                      (:extra-readers mount)]
                     (map #(update-vals % u/x-or-xs->xs))
                     (apply merge-with into))]
    (get readers ext-k)))

(defn read
  [config-path config id]
  (->> (id->readers config id)
       (map call/to-fn)
       (map #(% (metadata/id->path config-path config id)))
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
;; - should the shell to-fn work as a processor?
;;   - it's pretty hardwired to call-arg atm
;;   - could see it working tho...
;;   - also a bit up in the air how the output would be processed... parse edn I guess
;; - support ext like .foo.bar, fs/split-ext doesn't work for that
