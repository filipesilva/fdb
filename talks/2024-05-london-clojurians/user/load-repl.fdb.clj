;; Like repl.fdb.clj, but loaded at startup. Put functions you want to always have loaded here.

(defn audit [{:keys [self-path tx]}]
  (spit (str self-path ".audit")
        (-> tx :xtdb.api/tx-time .toInstant (str "\n"))
        :append true))
