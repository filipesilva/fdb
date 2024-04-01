;; We can use this fn later in triggers or other repl files.
;; It was added to the load vector when used `fdb init --demos`
;; so it's acessible on first load.
(defn print-call-arg
  "Simple fn to see which triggers are called."
  [{:keys [on-path]}]
  (println "=== called" (first on-path) "==="))
