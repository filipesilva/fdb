(ns fdb.readers.edn
  (:refer-clojure :exclude [read])
  (:require
   [fdb.utils :as u]))

(defn read
  [{:keys [self-path]}]
  (u/slurp-edn self-path))
