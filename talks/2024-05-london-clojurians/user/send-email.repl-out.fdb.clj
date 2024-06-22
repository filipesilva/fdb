(fdb.email/send
  (fdb.call/arg)
  {:to :self
   :subject "you got"
   :text "mail"})

;; => nil

