;; We'll use this fn later in triggers.
;; It was added to the load vector when used `fdb init --demo`
;; so it's acessible on first load.
(defn print-call-arg
  "Simple fn to see which triggers are called."
  [{:keys [on-path]}]
  (println "=== called" (first on-path) "==="))
