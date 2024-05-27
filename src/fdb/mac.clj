(ns fdb.mac
  (:require [babashka.process :as process]))

(defn notification
  [title message]
  (process/shell (format "osascript -e 'display notification \"%s\" with title \"%s\"'" message title)))


(comment
  (notification "you got" "mail")

  )
