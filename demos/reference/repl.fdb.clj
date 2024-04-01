;; We'll use this fn later in triggers.
;; Add to the load vector if adding reference as a mount
;; so it's acessible on first load.
(defn print-call-arg
  "Simple fn to see which triggers are called."
  [{:keys [on-path]}]
  (println "=== called" (first on-path) "==="))
