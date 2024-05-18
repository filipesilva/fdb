# ClojureDocs

This demo uses a `fdb.on/modify` [metadata trigger](../../README.md#metadata) to search in [ClojureDocs](https://clojuredocs.org) whenever a file changes, and writes the scraped results to another file:

Start by making `~/fdb/user/clojuredocs.txt`. 
This is where you'll write the query in.

```sh
echo "reduce" > ~/fdb/user/clojuredocs.txt
```

Then add metadata with the trigger.
This trigger will:
- call https://clojuredocs.org/search with the file contents in the `q` param
- scrape the response for `li` elements with the `arglist` class
- write them to `clojuredocs-out.txt`

```sh
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
```

Read the results
```sh
cat ~/fdb/user/clojuredocs-out.txt
```

```sh
(reduce f coll)
(reduce f val coll)
(reduced x)
(reduce f init ch)
(reducer coll xf)
(reduce f coll)
(reduce f init coll)
(reduced? x)
(reduce-kv f init coll)
(kv-reduce amap f init)
(coll-reduce coll f)
(coll-reduce coll f val)
(ensure-reduced x)
```

Search for something else.

```sh
# search for something else
echo "map" > ~/fdb/user/clojuredocs.txt
cat ~/fdb/user/clojuredocs-out.txt
```