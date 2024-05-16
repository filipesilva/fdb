(ns fdb.readers.json
  (:refer-clojure :exclude [read])
  (:require
   [clojure.data.json :as json]
   [fdb.utils :as u]))

(defn read
  [{:keys [self-path]}]
  (u/catch-nil
   (-> self-path
       slurp
       (json/read-str :key-fn keyword))))
