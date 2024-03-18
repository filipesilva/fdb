(ns fdb.state
  (:require
   [clojure.tools.namespace.repl :as ns]))

;; Don't reload this ns when refreshing all files to keep *fdb state.
(ns/disable-reload!)

;; Used by fdb.core/watch
(defonce *fdb (atom nil))

;; Used by fdb.core/watch-config!
(defonce *config-watcher (atom nil))

;; Used by fdb.triggers schedule fns
(defonce *schedules (atom {}))

;; TODO:
;; - use clj-reload, move state to ns that use it
