;; We'll use this fn later in triggers.
;; If you're used fdb init --demo, it's already added to the load vector.
(defn print-call-arg
  "Simple fn to see what triggers are doing."
  [{:keys [doc-path self-path on]}]
  (println self-path "called" on "over" doc-path))
