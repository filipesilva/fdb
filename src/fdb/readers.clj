(ns fdb.readers
  (:refer-clojure :exclude [read])
  (:require
   [babashka.fs :as fs]
   [fdb.call :as call]
   [fdb.metadata :as metadata]
   [fdb.readers.edn :as readers-edn]
   [fdb.readers.eml :as readers-eml]
   [fdb.readers.json :as readers-json]
   [fdb.readers.md :as readers-md]
   [fdb.utils :as u]))

(def default-readers
  {:edn #'readers-edn/read
   :eml #'readers-eml/read
   :json #'readers-json/read
   :md  #'readers-md/read})

(defn id->readers
  [config id]
  (let [ext-k      (-> id fs/split-ext second keyword)
        mount-spec (metadata/id->mount-spec config id)
        readers    (->> [(or (:readers mount-spec)
                             (:readers config)
                             default-readers)
                         (:extra-readers config)
                         (:extra-readers mount-spec)]
                        (map #(update-vals % call/specs))
                        (apply merge-with into))]
    (get readers ext-k)))

(defn read
  [config id]
  (->> (id->readers config id)
       (map (fn [call-spec]
              (call/with-arg {:on [:fdb.on/read call-spec]}
                (u/catch-log
                 (call/apply call-spec)))))
       (remove (comp not map?))
       (reduce merge {})))

;; TODO:
;; - glob reader ks
;;   - when id matches ks+globs, what happens?
;;   - would be easier if readers were vectors of [k-or-glob f-or-fns], order matters then
;;   - even nicer: support maps and vecs, use vecs when order matters only
;; - content reader
;;   - puts file content on :content k
;; - metadata could be a reader too... but that's going a bit meta atm
;;   - would make it easy to have json and other formats tho
;;   - would have to distinguish between edn reader and our edn built-in reader
;; - should the shell to-fn work as a reader?
;;   - a bit up in the air how the output would be processed... parse edn I guess
;; - support ext like .foo.bar, fs/split-ext doesn't work for that
;; - fdb.on/read on metadata can add more readers, used for testing
;; - default collection readers too? csv, json array, mbox
;; - think the :on val up call-arg is wrong
;; - explode flow
;;   - user adds a file, doesn't see what they want in the db
;;     - e.g. json array or mbox puts nothing in db
;;     - e.g. nested edn isn't useful for graph queries
;;   - user calls `fdb explode id-or-path`
;;     - explode calls fdb.readers/read followed by explode id
;;     - read must be able to coerce sets/sequential to map
;;       - get converted to {:coll #{}} or the other type
;;       - then on explode that will be root
;;       - TODO (coerce-to-map is not doing this atm, doesn't need to do any id stuff)
;;     - read must tell readers if it accepts just maps or also other colls
;;       - so that mbox/csv doesn't try to read for a long time just to get ignored
;;       - maybe pass in the coerce fn? weird, either do or don't
;;       - this read will go *.explode/root.edn
;;       - doesn't make a lot of sense to coerce if you want extra data, you need to return a map
;;       - maybe just tell the reader fn this is a explode read, and let it do the coercion
;;         - if it doesn't return a map, gets removed by the read
;;         - feels weird still, conflates read with what you're gonna do with it
;;         - interesting that you could have several readers and then could all add explode stuff
;;           - if all return :coll they will get overwritten
;;           - if we coerge returns, they will get overwritten as well
;;       - TODO
;;   - if user doesn't find that data good enough, they can configure explode
;;     - set file metadate
;;       - this means explode must read metadata
;;       - :fdb/explode {:id k-or-fn, :refs {k folder-id}}
;;       - id mapping
;;         - use k-or-fn during flatten on xform-uuid
;;           - explode-id is doing the wrong thing
;;           - just needs to xform-id
;;           - then uses lightweight id->path to write to disk
;;           - TODO
;;         - so if k-or-fn returns nil, use uuid anyway
;;       - ref mapping
;;         - after explode, not the same as flatten xform-uuid
;;         - update-vals for k to folder-id+v
;;         - TODO
;;     - call explode again
;;     - existing explode data gets deleted
