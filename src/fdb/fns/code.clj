(ns fdb.fns.code)

;; TODO:
;; - code ast
;;   - maybe fine grained function-level deps like in speculation
;;   - code loader for clojure
;; - use dependencies between fns to look up what code paths changed
;;   - recursive reverse dep lookup since a certain time
