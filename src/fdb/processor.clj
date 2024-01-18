(ns fdb.processor
  (:refer-clojure :exclude [read])
  (:require [fdb.call :as call]
            [babashka.fs :as fs]
            [fdb.metadata :as metadata]
            [fdb.utils :as u]))

(defn id->processors
  [config id]
  (let [ext-k      (-> id fs/split-ext second keyword)
        mount      (metadata/id->mount config id)
        processors (->> [(or (:processors mount)
                             (:processors config))
                         (:extra-processors mount)]
                        (map #(update-vals % u/x-or-xs->xs))
                        (apply merge-with into))]
    (get processors ext-k)))

(defn read
  [config-path config id]
  (->> (id->processors config id)
       (map call/to-fn)
       (map #(% (metadata/id->path config-path config id)))
       (reduce merge {})))


;; TODO:
;; - glob processor ks
;;   - when id matches ks+globs, what happens?
;;   - would be easier if processors were vectors of [k-or-glob f-or-fns], order matters then
;;   - even nicer: support maps and vecs, use vecs when order matters only
;; - content processor
;;   - puts file content on :content k
;; - metadata could be a processor too... but that's going a bit meta atm
;;   - would make it easy to have json and other formats tho
;;   - would have to distinguish between edn processor and our edn built-in processor
;; - should the shell to-fn work as a processor?
;;   - it's pretty hardwired to call-arg atm
;;   - could see it working tho...
;;   - also a bit up in the air how the output would be processed... parse edn I guess
