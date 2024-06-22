;; Clojure code added here will be evaluated, output will show up in ./repl-out.fdb.clj
;; Quick help: https://clojuredocs.org https://github.com/filipesilva/fdb#call-spec-and-call-arg

#_(fdb.mac/notification "you got" "a notification")


#_(fdb.db/entity-history "/user/file.txt" :asc :with-docs? true)

#_(fdb.db/q
 '{:find [(pull ?e [*])]
    :where [[?e :tags "demo"]]})

#_(inc 3)

(require '[datascript.core :as d])
;; :db/ident is already unique
(def schema {:children {:db/cardinality :db.cardinality/many
                        :db/valueType   :db.type/ref}})
(def conn (d/create-conn schema))

(d/transact! conn [{:db/ident "uuid1"
                    :prop1 1}])
(d/touch (d/entity @conn [:db/ident "uuid1"]))
;; => {:prop1 1, :db/ident "uuid1", :db/id 1}

(d/transact! conn [{:db/ident "uuid1"
                    :prop2 2}])
(d/touch (d/entity @conn [:db/ident "uuid1"]))
;; => {:prop1 1, :prop2 2, :db/ident "uuid1", :db/id 1}


(d/transact! conn [{:db/ident "uuid1"
                    :children [{:db/ident "uuid2"
                                :prop1 1}
                               {:db/ident "uuid3"
                                :prop2 2}]}])
(d/touch (d/entity @conn [:db/ident "uuid1"]))
;; => {:children #{#:db{:id 2} #:db{:id 3}}, :prop1 1, :prop2 2, :db/ident "uuid1", :db/id 1}

(d/touch (d/entity @conn [:db/ident "uuid2"]))
;; => {:prop1 1, :db/ident "uuid2", :db/id 2}

(d/touch (d/entity @conn [:db/ident "uuid3"]))
;; => {:prop2 2, :db/ident "uuid3", :db/id 3}
