# ClojureDocs

This example uses a `fdb.on/modify` [metadata trigger](#metadata) to search in [ClojureDocs](https://clojuredocs.org) whenever a file changes, and writes the scraped results to another file:

``` sh
# clojuredocs search
echo "reduce" > ~/fdb/user/clojuredocs.txt
echo '
{:fdb.on/modify
 (fn [{:keys [self-path]}]
   (-> "https://clojuredocs.org/search"
       (fdb.http/add-params {:q (slurp self-path)})
       (fdb.http/scrape [:li.arglist])
       (->> (mapcat :content)
            (clojure.string/join "\n")
            (spit (fdb.utils/sibling-path self-path "clojuredocs-out.txt")))))}
' > ~/fdb/user/clojuredocs.txt.meta.edn

# results for any query in clojuredocs.txt are this file
cat ~/fdb/user/clojuredocs-out.txt
# (reduce f coll)
# (reduce f val coll)
# (reduced x)
# (reduce f init ch)
# (reducer coll xf)
# (reduce f coll)
# (reduce f init coll)
# (reduced? x)
# (reduce-kv f init coll)
# (kv-reduce amap f init)
# (coll-reduce coll f)
# (coll-reduce coll f val)
# (ensure-reduced x)

# search for something else
echo "map" > ~/fdb/user/clojuredocs.txt
cat ~/fdb/user/clojuredocs-out.txt
```

