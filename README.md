# FileDB

FileDB is a hackable database environment for your file library.

It watches your files on disk and loads some of their data to a database.
You can use [Clojure](https://clojure.org) and [XTDB](https://xtdb.com) to interact with this data, and also add reactive triggers for automation.

Take a look at the [FileDB Reference Demo](./demo/reference/README.md) to see what that looks like.

The database is wholly determined by the files on disk, so you can replicate it wholly or partially by syncing these files.
Anything that syncs files to your disk can also trigger your automations, so it's easy to save a file on your phone to trigger some computation on your laptop.

I use to to hack together code and automations for my own usecases.
I like using markdown files on [Obsidian](https://obsidian.md) as my main readable files, but I don't think that matters.
FileDB should let you hack your own setup.


## But why?

Because I think it's silly that I own a really powerful laptop and a really powerful phone, and yet my data is sequestered away on cloud servers, where I pay for the privilege of accessing it and using it in a silo.

I want my data, and I want to fuck around with it on my terms.
I want to connect it together and try to do cool stuff with it!
And I want to sync it between my laptop and my phone, and wherever else I want to have it.

Last year I was travelling somewhere with bad connectivity and wanted to look up food nutrition data on my phone.
This is generally known data.
Surely there's an app for that.
I tried some 10 apps free and paid, and found they were mostly garbage.
I don't think I found even one that worked offline and had the 5 foods I tested.

Why is this so hard to get?!?
The USDA gives you a [24MB CSV](https://fdc.nal.usda.gov/download-datasets.html) of all foundation foods.
2.8GB if you want all foods, the bulk of it branded.
I know how to program.
My laptop has a 1TB disk, and my phone has a 512GB.
Why do I go mucking around with garbage apps instead of using this available data?

It's not terribly hard to load this into a database.
But it is hard to sync databases, and to open them in different devices.
You know what's really easy to sync and open though?

Files.

You have iCloud, Google Drive, Syncthing, and a ton of other stuff to sync.
You have apps that open your on-disk files.
Lots of these cloud services give you some way to download all of your stuff.

So that got me thinking about doing a database that was mostly a queryable layer over disk files.
I then I added more stuff to it that I thought was cool, like reactive triggers and a live system.


## Ok fine, how do I install the thing

This is a Clojure heavy so it's probably best if you're a Clojure dev already with some Datalog chops.
But you can also bite off more than you can chew, then chew it.

You need to [install Clojure](https://clojure.org/guides/install_clojure) and [Babashka](https://babashka.org) to use FileDB.
I'm using a Mac but I think this should work fine for Linux and Windows+WSL.

Then clone this repository somewhere on disk, and go into the folder. 
Add a symlink like so `ln -s "$(pwd)/src/fdb/bb/cli.clj" /usr/local/bin/fdb`.
Now you should be able to run `fdb help` from anywhere.


## Now how do I use it?

Probably a good time to look at the [FileDB Reference Demo](./demo/reference/README.md).

Start by running `fdb init --demo`.
This will create `~/fdbconfig.edn` and `~/fdb-demo/`.
If you want to create the config and demo folder in the current (or some other) dir, do `fdb init --demo .`.
If you don't want the demo files, omit `--demo`.

Then run `fdb watch`.
You can edit the config anytime, for instance to add new mounts, and the watcher will restart automatically.
It starts a [nREPL server]( https://nrepl.org/nrepl/index.html) on port 2525 that you can connect to.

It will watch folders under the `:mount` key in `fdbconfig.edn`.
Modified date, parent, and path for each file on mounts will be added to the db.
If you have `file.txt`, and add a `file.txt.meta.edn` next to it, that edn data will be added to the db's `file.txt` id.
You can put triggers and whatever else you want in this edn file.

You can also run `fdb sync` to do a one-shot sync.
This is useful when you have some automation you want to run manually.

`fdb read glob-pattern` forces a read of the (real, not mount) paths.
This is useful when you add or update readers and want to re-read those files.


## Reference

There are more examples in [FileDB Reference Demo](./demo/reference/README.md), but here's a short primer for `cmd+f`.


### `fdbconfig.edn`

``` edn
{;; where the db will be saved
 :db-path      "./db"
 
 ;; paths that will be mounted on the db
 ;; if you have /abs/path mounted as :bar, and you have /abs/path/my/file,
 ;; its id will be /bar/my/file
 :mounts       {:foo "./relative/path"
 
                ;; same as {:path "/abs/path"}
                :bar "/abs/path"
                
                :baz {:path          "/path/to/obsidian/vault"
                      ;; extra-readers will be added just to files in this mount
                      ;; you can also add :readers to overwrite the toplevel
                      :extra-readers {:md fdb.fns.obsidian/metadata}}}
 
 ;; readers are fns that will read some data from a file as edn when it changes
 ;; and add it to the db together with the metadata
 ;; called with the call-arg (see below)
 ;; call `fdb read glob-pattern` if you change readers and want to force a re-read
 :readers      {:eml fdb.fns.email.eml/metadata}
 
 ;; ids of clj files to be loaded at the start
 ;; usually repl files where you added some fns you use
 :load         ["/mount/path/to/file"]
 
 ;; clojure deps loaded dynamically at the start, and reloaded when config changes
 ;; you can add your local deps here too, and use them in triggers
 ;; see https://clojure.org/guides/deps_and_cli for more
 :extra-deps   {org.clojure/data.csv  {:mvn/version "1.1.0"}
                org.clojure/data.json {:git/url "https://github.com/clojure/data.json"
                                       :git/sha "e9e57296e12750512788b723e49ba7f9abb323f9"}
                my-local-lib          {:local/root "/path/to/lib"}}
 
 ;; default is
 ;; [".DS_Store" ".git" ".gitignore" ".obsidian" ".vscode" "node_modules" "target" ".cpcache"]
 ;; you can add to the defaults with :extra-ignore, or overwrite it with :ignore
 :extra-ignore [".obsidian"]
 
 ;; nrepl options, see https://nrepl.org/nrepl/usage/server.html#server-options
 ;; port defaults to 2525
 :repl         {}
 
 ;; you can add your own stuff here, and since the call-arg get
 ;; the config you will be able to look it up on triggers/readers
 :my-stuff     "very personal"}
```


### `call-spec` and `call-arg`

Readers and triggers take a `call-spec` that can be a few different things, and will receive `call-arg` (below):

``` edn
;; a function name, which will be required and resolved
println
clojure.core/println

;; a sexp containing a function
(fn [{:keys [self-path]}]
  (println self-path))

;; a vector uses the first kw element to decide what to do
;; you can add your own with (defmethod fdb.call/to-fn :my-thing ...)
;; the only built-in resolution is :sh, that calls a shell command
;; and can use a few bindings from call-arg
[:sh "echo" config-path doc-path self-path]

;; a map containing :call, which is any of the above
;; you can put more data in this map, and since call-arg
;; has the trigger iself in :on, you can use this data
;; parametrize the call
{:call (fn {[:keys [self-path on]]}
         (println self-path (-> on second :my-data)))
 :my-data 42}

;; call-specs can always be one or many, and are called in sequence
[println 
 {:call println}
 [:sh "echo" self-path]]
```


The `call-arg` is the single map arg that `call-spec` is called with.
It looks like this:

``` edn
 {:config      ;; fdb config value
  :config-path ;; on-disk path to config
  :node        ;; xtdb database node
  :db          ;; xtdb db value at the time of the tx
  :tx          ;; the tx
  :on          ;; the trigger being called as [fdb.on/k trigger]
  :on-path     ;; get-in path inside self for trigger as [fdb.on/k 1]
  :self        ;; the doc that has the trigger being called
  :self-path   ;; on-disk path for self
  :doc         ;; the doc the trigger is being called over, if any
  :doc-path    ;; on-disk path for doc, if any
  :results     ;; query results, if any
  :timestamp   ;; schedule timestamp, if any
  }
```


### `*.meta.edn`:

``` edn
{;; id is in /mount/ followed by relative path on mount
 ;; doc.txt.meta.edn is merged here, it won't have it's own db entry
 :xt/id           "/example/doc.txt"
 
 ;; modified is the most recent between doc.txt and doc.txt.meta.edn
 :fdb/modified    "2021-03-21T20:00:00.000-00:00"
 
 ;; the id of the parent of this id, useful for recursive queries
 :fdb/parent      "/example"
 
 ;; refs and tags are useful enough in relating docs
 ;; that they're first class
 :fdb/refs        #{"/test/two.txt"
                    "/test/three.txt"
                    "/test/folder"}
 :fdb/tags        #{"important" "not-very-important"}
 
 ;; called when this file, or its metadata, is modified
 ;; the fn will be called with a the call-arg (see above):
 :fdb.on/modify   println 
 
 ;; called when the files referenced in :fdb/refs change
 :fdb.on/refs     println
 
 ;; called when any file that matches the glob changes
 :fdb.on/pattern  {:glob "/test/*.txt"
                   :call println} 
 
 ;; called when the query results change
 ;; query-results.edn will contain the latest results
 ;; you can add triggers to the metadata, and use it as a ref to other triggers
 :fdb.on/query    {:q    [:find ?e :where [?e :fdb/tags "important"]]
                   :path "./query-results.edn"
                   :call println}
 
 ;; called every X time units or on a cron schedule
 ;; the :every syntax supports :seconds :hours :days and more,
 ;; see all of them by calling (keys tick.core/unit-map)
 ;; use https://crontab.guru/ to make your cron schedules
 :fdb.on/schedule {:every [1 :hours]
                   ;; or :cron "0 * * * *"
                   :call println}
  
 ;; called once on watch startup/shutdown
 :fdb.on/startup  println
 :fdb.on/shutdown println

 ;; called on every tx, this is how every other trigger is made
 :fdb.on/tx       println}
```


### Repl and Query files

You can run code over the db process with a file called `repl.fdb.clj`, or with any prefix e.g. `foo.repl.fdb.clj`.
`repl.fdb.md` also works if the clojure code is in a solo `clojure` codeblock.

It starts in the `user` namespace but you can add whatever namespace form you want.
You'll find the current fdb in the `fdb.state/*fdb` atom, as a map with `:config-path`, `:config`, and (xtdb) `:node`.

You can add this file to `fdbconfig.edn` under `:load` and it will be loaded at startup, and the functions you define here will be available for triggers and readers.

``` clojure
(inc 1)
```

Will append output to `repl-results.fdb.clj`:

``` clojure
(inc 1)

;; => 2
```


You can query the db with a file called `query.fdb.edn`, or with any prefix like `repl.fdb.clj`.
Also works for `query.fdb.md` if query is in a solo `edn` codeblock.
See [XTDB docs](https://v1-docs.xtdb.com/language-reference/datalog-queries/) for query syntax.

``` edn
[:find ?e 
 :where [?e :fdb/tags "important"]]
```

Will output to `query-results.fdb.edn`:

``` edn
#{"/mount/some/important/file.txt"}
```


## Hacking on FileDB

[ARCHITECTURE.md](ARCHITECTURE.md) has an overview of the main namespaces in FileDB and how they interact.

`fdb watch --debug` starts fdb with some extra debug logging.
Connect to the [nrepl server](https://nrepl.org/nrepl/1.1/index.html) on port 2525 by default, and change stuff.
Call `(clojure.tools.namespace.repl/refresh)` to reload code as you change it, and `(fdb.core/restart-watch-config!)` if you want the watcher to restart too.

