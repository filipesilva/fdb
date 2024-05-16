# FileDB

> Do not go gentle into that good SaaS,  
Users should burn and rave at close of data;  
Rage, rage against the dying of the file.

FileDB is a reactive database environment for your files.
It's main purpose is to give you an easy way to take control of your data.

It watches your files on disk and loads some of their data to a database.
You use [Clojure](https://clojure.org) and [XTDB](https://xtdb.com) to interact with this data, and add reactive triggers for automation.

Check the [Quickstart](#quickstart) and [Reference](#reference) to see what interacting with FileDB looks like.
[Demos](#demos) has examples of cool things I do with it.

The database is determined by the files on disk, so you can replicate it wholly or partially by syncing these files.
Anything that syncs files to your disk can also trigger your automations, so it's easy to save a file on your phone to trigger some computation on your laptop.

FileDB is for all those things you know you could do with code, but it's never worth the effort to set everything up.
I use it to hack together code and automations for my own usecases.
I like using markdown files on [Obsidian](https://obsidian.md) as my main readable files, but I don't think that matters.
FileDB should let you hack your own setup.


## Quickstart

This is Clojure heavy so it's probably best if you're a [Clojure](https://clojure.org) dev already with some [Datalog](https://en.wikipedia.org/wiki/Datalog) chops.
But if you're not, it's a great time to start.
Clojure and Datalog are awesome!
Bite off more than you can chew, then chew it.

Clone and start watching, make sure to have [Clojure](https://clojure.org/guides/install_clojure) and [Babashka](https://github.com/babashka/babashka#installation) installed first:

``` sh
# go into a folder where you can clone fdb into
git clone https://github.com/filipesilva/fdb
cd fdb
./symlink-fdb.sh
fdb init
fdb watch
```

[CLI](#cli) explains what these commands do.

Then in another terminal run commands for the examples below.

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

This example uses a `fdb.on/schedule` [metadata trigger](#metadata) to:
- each day
- look up temperatures in Lisbon for the past and future 7 days
- load them into FileDB by writing them to `.edn` files on disk that we have [readers](#readers) for
- then query the db using a [query file](#repl-and-query-files) that calls a function we just defined in a [repl file](#repl-and-query-files)

``` sh
# temperature tracker
echo '
{:fdb.on/schedule
 {:every [1 :days]
  :call  (fn [{:keys [self-path]}]
           (let [lisbon   (-> "https://nominatim.openstreetmap.org/search"
                              (fdb.http/add-params {:q "Lisbon" :limit 1 :format "json"})
                              fdb.http/json
                              first)
                 forecast (-> "https://api.open-meteo.com/v1/forecast"
                              (fdb.http/add-params {:daily     ["temperature_2m_max", "temperature_2m_min"] ,
                                                    :past_days 7
                                                    :latitude  (:lat lisbon)
                                                    :longitude (:lon lisbon)})
                              fdb.http/json
                              :daily)
                 temps    (map (fn [day max min]
                                 {:day day
                                  :max max
                                  :min min})
                               (:time forecast)
                               (:temperature_2m_max forecast)
                               (:temperature_2m_min forecast))]
             (run! (fn [temp]
                     (fdb.utils/spit-edn
                      (fdb.utils/sibling-path self-path (str "weather/" (:day temp) ".edn"))
                      temp))
                   temps)))}}
' > ~/fdb/user/weather.edn

# edn files for min/max temp, updated every day, for previous and next 7 days
ll ~/fdb/user/weather

# what are the max temperatures like the week around today?
echo '
(require \'[tick.core :as t])
(defn this-week? [date]
  (let [today (t/date)]
    (t/<= (t/<< today (t/of-days 3))
          (t/date date)
          (t/>> today (t/of-days 3)))))
' > ~/fdb/user/repl.fdb.clj
echo '
{:find [?day ?max]
 :where [[?e :fdb/parent "/user/weather"]
         [?e :day ?day]
         [(user/this-week? ?day)]
         [?e :max ?max]]}
' > ~/fdb/user/week-max-temp.query.fdb.edn

# query results are in this file
cat ~/fdb/user/week-max-temp.query-out.fdb.edn
# #{["2024-03-29" 15.5]
#   ["2024-03-30" 13.8]
#   ["2024-03-31" 13.4]
#   ["2024-04-01" 16.0]
#   ["2024-04-02" 16.4]
#   ["2024-04-03" 17.8]
#   ["2024-04-04" 17.3]}
```


## What are the main ideas in it?

- mount: the name a folder on disk has on the db, and that's being watched
- repl/query file: evaluates code or db queries on file save, outputs result to a sibling file
- reader: a fn that takes a file and returns data from it as edn, which is loaded into the db
- metadata: extra data about a file you add in a sibling .meta.edn file
- trigger: fn in metadata called reactively as the db changes
- call spec/arg: how fns are specified for readers and triggers, and the argument they take

```
mount --> file change --> readers+metadata -> db --> triggers
            ^                                           |
            |                                           |
            ---------------------------------------------
```


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

Why is this so hard to get?!
The USDA gives you a [24MB CSV](https://fdc.nal.usda.gov/download-datasets.html) of all foundation foods.
2.8GB if you want all foods, the bulk of it branded.
I know how to program.
My laptop has a 1TB disk, and my phone has a 512GB.
Why do I go mucking around with garbage apps instead of using this available data?

It's not terribly hard to load this into a database.
But it is hard to sync databases, and to open them in different devices.
You know what's really easy to sync and open though?

Files.

You have iCloud, Google Drive, Dropbox, Syncthing, Git, and a ton of other stuff to sync.
You have apps that open your on-disk files.
Lots of these cloud services give you some way to download all of your stuff.

So that got me thinking about doing a database that was mostly a queryable layer over disk files.
I then I added more stuff to it that I thought was cool, like reactive triggers and a live system.


## CLI

You'll need to have [Clojure](https://clojure.org/guides/install_clojure) and [Babashka](https://github.com/babashka/babashka#installation) installed first.
Installing the FileDB CLI is just cloning this repo and symlinking the CLI script.

``` sh
# go into a folder where you can clone fdb into
git clone https://github.com/filipesilva/fdb
cd fdb
./symlink-fdb.sh
```

Now you should be able to run `fdb help` from anywhere.
If you don't want to symlink the CLI script, you can also call `./src/fdb/bb/cli.clj help` from this dir.

Start using FileDB by running `fdb init`.
This will create `~/fdb/` with `fdbconfig.edn`, `user/`, and `demos/` inside.
If you want to create the `fdb` folder in the current (or some other) dir, add it at the end of init like `fdb init .`.

Then run `fdb watch`.
You can edit the config anytime, for instance to add new mounts, and the watcher will restart automatically.
It starts a [nREPL server](https://nrepl.org/nrepl/index.html) on port 2525 that you can connect to from a Clojure editor like [Emacs with Cider](https://github.com/clojure-emacs/cider) or [VSCode with Calva](https://calva.io).

It will watch folders under the `:mount` key in `fdbconfig.edn`.
Modified date, parent, and path for each file on mounts will be added to the db.
If you have `doc.md`, and add a `doc.md.meta.edn` next to it, that edn data will be added to the db's `doc.md` id.
You can put triggers and whatever else you want in this edn file.

`~/fdb/user/` has a [repl and query file](repl-and-query-files) to play with.
The [Reference](#reference) is in `~/fdb/demos/reference` and contains examples of how things work.

You can also run `fdb sync` to do a one-shot sync.
This is useful when you have some automation you want to run manually.
It doesn't run `fdb.on/schedule` though.

`fdb read glob-pattern` forces a read of the (real, not mount) paths.
This is useful when you add or update readers and want to re-read those files.


## Demos

Below is some cool stuff that you can do with FileDB.
If you want to follow these demos, add their dir as a mount.

- WIP [`~/demos/nutrition`](./demos/nutrition/README.md): make your own nutrition tracking system
- TODO `~/demos/email`: sync all of your emails locally, connect them with your notes
- TODO `~/demos/code-analysis`: read AST for clj files, query it to find what fns are affected when a given fn changes
- TODO `~/demos/webapp`: serve a webapp for your fdb, put it online, go nuts

I'm still working on more demos, mostly around my own usecases.
I'll add them here when they are done.


## Reference

Reference files are in `~/fdb/demos/reference` folder but are not mounted.
You can mount them if you want.
I've also gathered them here to give a nice overview of what you can do, and so its easy to search over them.

### Readers

FileDB comes with these default readers to make it easy to interact with common data:
- `edn`: reads all data in edn files is loaded directly into the db
- `json`: reads all data, keywordizing keys
- `md`: reads links into `:fdb/refs`, and all [yml properties](https://help.obsidian.md/Editing+and+formatting/Properties#Property+format). Property keys that start with `fdb` are read as edn.
- `eml:` reads common email keys from the email message headers, and tries to read body as text

Triggers in the return return map work the same as those in metadata files.

You can make your own readers too.
Check out how the default ones are implemented in `src/fdb/readers/`.


### Repl and Query files

You can run code over the db process with a file called `repl.fdb.clj`, with any prefix e.g. `foo.repl.fdb.clj`.
`repl.fdb.md` also works if the clojure code is in a solo `clojure` codeblock.
You can also connect your editor to the nREPL server that starts with `fdb watch`, it's on port 2525 by default.

It starts in the `user` namespace but you can add whatever namespace form you want, and that's the ns it'll be eval'd in.
You can find a call-arg like the one triggers receive in `(fdb.call/arg)` (more `call-arg` in the reference).

``` clojure
;; We'll use this fn later in triggers.
;; Add to the load vector if adding reference as a mount 
;; so it's acessible on first load.
(defn print-call-arg
  "Simple fn to see which triggers are called."
  [{:keys [on-path]}]
  (println "=== called" (first on-path) "==="))
```

Will write the evaluated code with output in a comment to `repl-out.fdb.clj`:

``` clojure
;; We'll use this fn later in triggers.
;; Add to the load vector if adding reference as a mount 
;; so it's acessible on first load.
(defn print-call-arg
  "Simple fn to see which triggers are called."
  [{:keys [on-path]}]
  (println "=== called" (first on-path) "==="))

;; => #'user/print-call-arg
```

You can add a repl file (or any clj file really) to `fdbconfig.edn` under `:load` to be loaded at startup, and the functions you define in it will be available for triggers and readers before sync.

You can query the db with a file called `query.fdb.edn`, with any prefix.
Also works for `query.fdb.md` if query is in a solo `edn` codeblock.
See [XTDB docs](https://v1-docs.xtdb.com/language-reference/datalog-queries/) for query syntax, and [Learn Datalog Today!](https://www.learndatalogtoday.org) if you want to learn about Datalog from scratch.

``` edn
{:find [?e] 
 :where [[?e :tags "important"]]}
```

Will output to `query-out.fdb.edn`:

``` edn
#{["/demos/reference/todo.md"] ["/demos/reference/doc.md"]}
```


### `fdbconfig.edn`

``` edn
;; This is the format of the fdb config file. Your real one is probably on your ~/fdb/fdbconfig.edn.
{;; Where the xtdb db files will be saved.
 ;; You can delete this at any time, and the latest state will be recreated from the mount files.
 ;; You'll lose time-travel data if you delete it though.
 ;; See more about xtdb time travel in https://v1-docs.xtdb.com/concepts/bitemporality/.
 :db-path "./xtdb"

 ;; These paths that will be mounted on the db.
 ;; If you have ~/fdb/user mounted as :user, and you have ~/fdb/user/repl.fdb.clj,
 ;; its id in the db will be /user/repl.fdb.clj.
 :mounts {;; "~/fdb/user is the same as {:path "~/fdb/user"}
          :user "~/fdb/user"}

 ;; Readers are fns that will read some data from a file as edn when it changes
 ;; and add it to the db together with the metadata.
 ;; The key is the file extension.
 ;; They are called with the call-arg (see below) just like triggers.
 ;; Call `fdb read glob-pattern` if you change readers and want to force a re-read.
 ;; Defaults to :edn, :md, and :eml readers in fdb/src/readers but can be overwritten
 ;; with :readers instead of :extra-readers.
 ;; You can also add :extra-readers to a single mount in the map notation.
 :extra-readers {:txt user/read-txt}

 ;; Disk paths of clj files to be loaded at the start.
 ;; Usually repl files where you added fns to use in triggers, or that load namespaces
 ;; you want to use without requiring them.
 :load ["/user/load-repl.fdb.clj"]

 ;; These are Clojure deps loaded dynamically at the start, and reloaded when config changes.
 ;; You can add your local deps here too, and use them in triggers.
 ;; See https://clojure.org/guides/deps_and_cli for more about deps.
 :extra-deps {org.clojure/data.csv  {:mvn/version "1.1.0"}
              org.clojure/data.json {:git/url "https://github.com/clojure/data.json"
                                     :git/sha "e9e57296e12750512788b723e49ba7f9abb323f9"}
              my-local-lib          {:local/root "/path/to/lib"}}

 ;; Serve call-specs from fdb.
 ;; Use with https://ngrok.com or https://github.com/localtunnel/localtunnel to make a public server.
 :serve {;; Map from route to call-spec, req will be within call-arg as :req.
         ;; Route format is from https://github.com/tonsky/clj-simple-router.
         :routes {"GET /"        user/get-root
                  "GET /stuff/*" user/get-stuff}
         ;; Server options for https://github.com/http-kit/http-kit.
         ;; Defaults to {:port 80}.
         :opts   {:port 8081}}

 ;; Files and folders to ignore when watching for changes.
 ;; default is [".DS_Store" ".git" ".gitignore" ".obsidian" ".vscode" "node_modules" "target" ".cpcache"]
 ;; You can add to the defaults with :extra-ignore, or overwrite it with :ignore.
 ;; You can also use :ignore and :extra-ignore on the mount map definition.
 :extra-ignore [".noisy-folder"]

 ;; nRepl options, port defaults to 2525.
 ;; Started automatically on watch, lets you connect directly from your editor to the fdb process.
 ;; Also used by the fdb cli to connnect to the background clojure process.
 ;; See https://nrepl.org/nrepl/usage/server.html#server-options for more.
 :repl {}

 ;; You can add your own stuff here, and since the call-arg gets the config you will
 ;; be able to look up your config items on triggers and readers.
 :my-stuff "personal config data I want to use in fns"}
```


### `call-spec` and `call-arg`

Readers and triggers take a `call-spec` that can be a few different things, and will receive `call-arg`:

``` edn
;; These are the different formats supported for call-spec, used in readers and triggers.

;; A function name will be required and resolved under the user ns, then called with call-arg.
println
clojure.core/println

;; A sexp containing a function, evaluated then called with call-arg.
(fn [{:keys [self-path]}]
  (println self-path))

;; A vector uses the first kw element to decide what to do.
;; The only built-in resolution is :sh, that calls a shell command and can use a few bindings from call-arg.
;; You can add your own with (defmethod fdb.call/to-fn :my-thing ...)
;; See ./src/fdb/call for existing ones.
[:sh "echo" config-path target-path self-path]

;; A map containing :call, which is any of the above
;; You can put more data in this map, and since call-arg has the trigger iself in :on, you can use
;; this data to parametrize the call.
{:call    (fn [{:keys [self-path on]}]
            (println self-path (:my-data on)))
 :my-data 42}

;; Call-specs can always be one or many, and are called in sequence.
[println 
 {:call println}
 [:sh "echo" self-path]]
```


The `call-arg` is the single map arg that `call-spec` is called with.
It looks like this:

``` edn
;; This is the format for call arg, which the function resolved for call-spec is called with.
;; It's also acessible in (fdb.call/arg).
{:config      {,,,}                         ;; fdb config value
 :config-path "~/fdb/fdbconfig.json"        ;; on-disk path to config
 :node        {,,,}                         ;; xtdb database node
 :db          {,,,}                         ;; xtdb db value at the time of the tx
 :tx          {:xtdb.api/id 1 ,,,}          ;; the tx
 :on          println                       ;; the trigger being called
 :on-path     [:fdb.on/modify]              ;; get-in path inside self for trigger
 :self        {:xt/id "/mount/foo.md" ,,,}  ;; the doc that has the trigger being called
 :self-path   "/path/foo.md"                ;; on-disk path for self
 :target      {:xt/id "/mount/bar.md" ,,,}  ;; the doc the trigger is being called over, if any
 :target-path "/path/bar.md"                ;; on-disk path for doc, if any
 :results     {,,,}                         ;; query results, if any
 :timestamp   "2024-03-22T16:52:20.995717Z" ;; schedule timestamp, if any
 :req         {:path-params ["42"] ,,,}     ;; http request, if any
 }
```


### Metadata

Metadata is any data you want to put into the DB for your files.
Then you can query it.

There's two sources of metadata:
- readers: for the file extension, edn/md/json/eml are built-in
- metadata files: `doc.md.meta.edn` is a metadata file for `doc.md`
Both are loaded into the database whenever the file changes.

Keys on the `fdb` namespace have special meaning for FileDB.
Reactive triggers are on the `fdb.on` namespace.

``` edn
;; This both the format of db and on-disk metadata files.
{;; ID is /mount/ followed by relative path on mount.
 ;; It's the unique id for XTDB.
 ;; Added automatically.
 :xt/id           "/demos/reference/doc.md"

 ;; Modified is the most recent between doc.md and doc.md.meta.edn.
 ;; Added automatically.
 :fdb/modified    "2021-03-21T20:00:00.000-00:00"

 ;; The ID of the parent of this ID, useful for recursive queries
 ;; Added automatically.
 :fdb/parent      "/demos/reference"

 ;; ID references are useful enough in relating docs that they're first class.
 :fdb/refs        #{"/demos/reference/todo.md"
                    "/demos/reference/ref-one.md"}

 ;; Called when this file, or its metadata, is modified.
 ;; The fn will be called with the call-arg.
 ;; print-call-arg is a function that we added in repl.fdb.edn, so we can use it here.
 :fdb.on/modify   print-call-arg

 ;; Called when any file that matches the glob changes.
 ;; It should match ./pattern-glob-match.md.
 :fdb.on/pattern  {:glob "/demos/reference/*glob*.md"
                   :call print-call-arg}

 ;; Called when the files referenced in :fdb/refs change.
 ;; Refs will be resolved recursively and you can have cycles, so this triggers
 ;; when ./ref-two.md or ./ref-three are modified too.
 :fdb.on/refs     print-call-arg

 ;; Called when the query results change.
 ;; The latest results will be in important-files.edn, specified in the :path key.
 ;; You can add triggers to path metadata, or use it as a ref to other triggers.
 :fdb.on/query    {:q    [:find ?e
                          :where [?e :tags "important"]]
                   :path "./important-files.edn"
                   :call print-call-arg}

 ;; Called every 1 hours.
 ;; The :every syntax supports :seconds :hours :days and more, see (keys tick.core/unit-map).
 ;; You can also use a cron schedule, use https://crontab.guru/ to make your cron schedules.
 :fdb.on/schedule {:every [1 :hours]
                   ;; or :cron "0 * * * *"
                   :call print-call-arg}

 ;; Called once on watch startup/shutdown, including restarts.
 :fdb.on/startup  print-call-arg
 :fdb.on/shutdown print-call-arg

 ;; Called on every db transaction via https://v1-docs.xtdb.com/clients/clojure/#_listen
 ;; This is how every other trigger is made, so you can make your own triggers.
 :fdb.on/tx       print-call-arg
 }
```


## Hacking on FileDB

[ARCHITECTURE.md](ARCHITECTURE.md) (TODO) has an overview of the main namespaces in FileDB and how they interact.

`fdb watch --debug` starts fdb with some extra debug logging.
Connect to the [nREPL server](https://nrepl.org/nrepl/1.1/index.html) on port 2525 by default, and change stuff.
Call `(clj-reload.core/reload)` to reload code as you change it, if you have a config watcher running it will restart as well.

I have TODOs at the end of each file that you can take a look at.
I find this easier than making issues.

`master` branch contains the latest stable code, `dev` is where I work on for new changes.
