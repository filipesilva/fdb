# FileDB

> Do not go gentle into that good SaaS,  
Users should burn and rave at close of data;  
Rage, rage against the dying of the file.

FileDB is a reactive database environment for your files.
It's main purpose is to give you an easy way to take control of your data.

It watches files on disk and loads their data to a database.
You use [Clojure](https://clojure.org) and [XTDB](https://xtdb.com) to interact with this data, and add reactive triggers for automation.

Check the [Demo](#demo) and [Reference](#reference) to see what interacting with FileDB looks like.
[More Demos](#more-demos) has examples of cool things I do with it.

The database is determined by the files on disk, so you can replicate it wholly or partially by syncing these files.
Anything that syncs files to your disk can trigger your automations, so it's easy to save a file on your phone to trigger computation on your laptop.

FileDB is for all those things you know you could do with code, but it's never worth the effort to set everything up.
I use it to hack together code and automations for my own use cases.
I like using markdown files on [Obsidian](https://obsidian.md) as my main readable files, but I don't think that matters.
FileDB should let you hack your own setup.


## Demo

This is Clojure heavy so it's probably best if you're a [Clojure](https://clojure.org) dev with [Datalog](https://en.wikipedia.org/wiki/Datalog) chops.

But if you're not, it's a great time to start.
Clojure and Datalog are awesome!
Courage Wolf offers great advice here: bite off more than you can chew, then chew it.

First step is to clone and start watching.
Make sure to have [Clojure](https://clojure.org/guides/install_clojure) and [Babashka](https://github.com/babashka/babashka#installation) installed first.

```sh
# go into a folder where you can clone fdb into
git clone https://github.com/filipesilva/fdb
cd fdb
./symlink-fdb.sh
fdb init
fdb watch
```

[CLI](#cli) explains what these commands do.

Then in another terminal go to `~/fdb/user`, and run the code in the Data, Code, and Network sections below.
Run the `cat` commands separately, since it will take some ms for FileDB to act on file changes.
Some `echo` commands are using `>>` instead of `>` to append so existing content isn't lost.

The `~/fdb/user` folder is created by FileDB and automatically watched.
It's where you can play with code and data without thinking too much about it.

```sh
cd ~/fdb/user
```

You can use an editor instead of `echo`/`cat`.
I'm using shell commands here for demo brevity.


### Data

FileDB loads data on watched files that it can [read](#readers). [EDN](https://en.wikipedia.org/wiki/Clojure#Extensible_Data_Notation) map data in watched folders is automatically added to the database.

```sh
echo '{:tags #{"demo"}}' > data.edn
```

You can query for the data in it with a query file.
Query files end with `query.fdb.edn`.
The result of the query will be in `query-out.fdb.edn`.
Queries are in [XTDB datalog](https://v1-docs.xtdb.com/language-reference/datalog-queries/), and this one means "get me all data for files with demo in the tags property".

```sh
echo '
{:find [(pull ?e [*])] 
 :where [[?e :tags "demo"]]}
' > query.fdb.edn

cat query-out.fdb.edn
```

```edn
#{[{:tags #{"demo"}
    :fdb/modified #inst "2024-04-23T22:49:27.271946426Z"
    :fdb/parent "/user"
    :xt/id "/user/data.edn"}]}
```

Data in Markdown [yml properties](https://help.obsidian.md/Editing+and+formatting/Properties#Property+format) and JSON maps is also automatically loaded.

```sh
echo '---
tags:
  - demo
---
Markdown body is not loaded
' > data.md
echo '{"tags": ["demo"]}' > data.json
touch query.fdb.edn

cat query-out.fdb.edn
```

```edn
#{[{:tags ["demo"]
    :fdb/modified #inst "2024-04-23T22:50:12.791335391Z"
    :fdb/parent "/user"
    :xt/id "/user/data.md"}]
  [{:tags ["demo"]
    :fdb/modified #inst "2024-04-23T22:50:12.791511140Z"
    :fdb/parent "/user"
    :xt/id "/user/data.json"}]
  [{:tags #{"demo"}
    :fdb/modified #inst "2024-04-23T22:49:27.271946426Z"
    :fdb/parent "/user"
    :xt/id "/user/data.edn"}]}
```

Files without a reader only get `:xt/id`, `:fdb/modified`, and `:fdb/parent` in the db.

```sh
echo 'just a txt' > file.txt
echo '
{:find [(pull ?e [*])] 
 :where [[?e :xt/id "/user/file.txt"]]}
' > file-query.fdb.edn

cat file-query-out.fdb.edn
```

```edn
#{[{:fdb/modified #inst "2024-04-23T22:50:32.953604479Z"
    :fdb/parent "/user"
    :xt/id "/user/file.txt"}]}
```

But you can add metadata to any file via a sibling file that ends in `.meta.edn`.

```sh
echo '{:tags #{"demo"}}' > file.txt.meta.edn
touch query.fdb.edn

cat query-out.fdb.edn
```

```edn
#{[{:tags ["demo"]
    :fdb/modified #inst "2024-04-23T22:50:12.791335391Z"
    :fdb/parent "/user"
    :xt/id "/user/data.md"}]
  [{:tags ["demo"]
    :fdb/modified #inst "2024-04-23T22:50:12.791511140Z"
    :fdb/parent "/user"
    :xt/id "/user/data.json"}]
  [{:tags #{"demo"}
    :fdb/modified #inst "2024-04-23T22:49:27.271946426Z"
    :fdb/parent "/user"
    :xt/id "/user/data.edn"}]
  [{:tags #{"demo"}
    :fdb/modified #inst "2024-04-23T22:51:32.639683791Z"
    :fdb/parent "/user"
    :xt/id "/user/file.txt"}]}
```


### Code

Clojure code in repl files is evaluated in the Clojure process under the `user` namespace.
REPL files end with `repl.fdb.clj`.
The result of the execution will be in a comment in `repl-out.fdb.clj`, together with the executed code.

FileDB starts a [nREPL server](https://nrepl.org/nrepl/index.html) on port 2525 that you can connect to.
In this demo we're going to focus on the file watcher use, but feel free to use both ways.

```sh
echo '(inc 1)' > repl.fdb.clj

cat repl-out.fdb.clj
```

```clojure
(inc 1)

;; 2
```

Since the code is evaluated into a persistent process, you can define a function in one file and use it in another.

```sh
echo '(defn my-inc [x] (inc x))' > repl.fdb.clj

cat repl-out.fdb.clj 
```

```clojure
(defn my-inc [x] (inc x))

;; => #'user/my-inc
```

You can call this function by it's namespaced name (`user/my-inc`) or by `my-inc`, since all repl files start in the `user` namespace.

``` sh
echo '(+ (user/my-inc 1) (my-inc 1))' > another-repl.fdb.clj

cat another-repl-out.fdb.clj
```

``` clojure
(+ (user/my-inc 1) (my-inc 1))

;; => 4
```

Code in a repl file was loaded into the process but if you kill the process it's gone.
Repl files aren't automatically loaded on startup because then you'd have to watch out what you write in them to avoid slowing down startup.

In `fdbconfig.edn` there's a load vector where you can put `.clj` files that will be loaded on startup.
There's `["load-repl.fdb.clj" "server-repl.fdb.clj"]` in there.
They are repl files so any changes are immediately loaded.

You have full access to the database from code files, so you can use the [XTDB API](https://v1-docs.xtdb.com/language-reference/datalog-queries/) directly.
You can get the current node via `(fdb.db/node)`.

``` sh
echo '
(xtdb.api/q
 (xtdb.api/db (fdb.db/node))
 \'{:find [(pull ?e [*])]
    :where [[?e :tags "demo"]]})
' > repl.fdb.clj

cat repl-out.fdb.clj
```

```clojure
(xtdb.api/q
 (xtdb.api/db (fdb.db/node))
 '{:find [(pull ?e [*])]
   :where [[?e :tags "demo"]]})

;; => #{[{:tags #{"demo"},
;;        :fdb/modified #time/instant "2024-05-16T14:52:32.352959185Z",
;;        :fdb/parent "/user",
;;        :xt/id "/user/data.edn"}]
;;      [{:tags ["demo"],
;;        :fdb/modified #time/instant "2024-05-16T14:52:41.886827051Z",
;;        :fdb/parent "/user",
;;        :xt/id "/user/data.md"}]
;;      [{:tags ["demo"],
;;        :fdb/modified #time/instant "2024-05-16T14:52:41.886982426Z",
;;        :fdb/parent "/user",
;;        :xt/id "/user/data.json"}]}
```

You were able to call `xtdb.api/q` directly via its fully qualified name because the library was already loaded into the FileDB process.
But you can do `(require '[xtdb.api :as xt])` instead if you want.

The `fdb.db` namespace contains a convenience function `xtdb.api/q` that uses the current db so you don't have to call `(xtdb.api/db (fdb.db/node))` all the time.

``` sh
echo '
(fdb.db/q
 \'{:find [(pull ?e [*])]
    :where [[?e :tags "demo"]]})
' > repl.fdb.clj

cat repl-out.fdb.clj
```

```clojure
(fdb.db/q
 '{:find [(pull ?e [*])]
    :where [[?e :tags "demo"]]})

;; => #{[{:tags #{"demo"},
;;        :fdb/modified #time/instant "2024-05-16T14:52:32.352959185Z",
;;        :fdb/parent "/user",
;;        :xt/id "/user/data.edn"}]
;;      [{:tags ["demo"],
;;        :fdb/modified #time/instant "2024-05-16T14:52:41.886827051Z",
;;        :fdb/parent "/user",
;;        :xt/id "/user/data.md"}]
;;      [{:tags ["demo"],
;;        :fdb/modified #time/instant "2024-05-16T14:52:41.886982426Z",
;;        :fdb/parent "/user",
;;        :xt/id "/user/data.json"}]}
```

You have convenience functions for `pull`, `pull-many`, `entity` and `entity-history`.
`entity-history` is a cool one because it gives you all past versions of that id, as long as the database wasn't deleted.
Past content is in `xtdb.api/doc`.

```sh
echo '{:tags #{"demo"} :new-k 1}' > data.edn
echo '(fdb.db/entity-history "/user/data.edn" :asc :with-docs? true)' > repl.fdb.clj

cat repl-out.fdb.clj
```

```clojure
(fdb.db/entity-history "/user/data.edn" :asc :with-docs? true)

;; => [{:xtdb.api/tx-time #inst "2024-05-16T14:52:32.889-00:00",
;;      :xtdb.api/tx-id 32,
;;      :xtdb.api/valid-time #inst "2024-05-16T14:52:32.889-00:00",
;;      :xtdb.api/content-hash
;;      #xtdb/id "1d10305d968b37f961fb664490fd69165c775440",
;;      :xtdb.api/doc
;;      {:tags #{"demo"},
;;       :fdb/modified #time/instant "2024-05-16T14:52:32.352959185Z",
;;       :fdb/parent "/user",
;;       :xt/id "/user/data.edn"}}
;;     {:xtdb.api/tx-time #inst "2024-05-16T15:31:59.275-00:00",
;;      :xtdb.api/tx-id 47,
;;      :xtdb.api/valid-time #inst "2024-05-16T15:31:59.275-00:00",
;;      :xtdb.api/content-hash
;;      #xtdb/id "d7bde02d1000fb3444558d26994b8ac30b93b295",
;;      :xtdb.api/doc
;;      {:tags #{"demo"},
;;       :new-k 1,
;;       :fdb/modified #time/instant "2024-05-16T15:31:58.744451751Z",
;;       :fdb/parent "/user",
;;       :xt/id "/user/data.edn"}}]
```

You can add code that will be run reactively on data and metadata.
These are called triggers, and you can read more about different triggers in in [Metadata](#metadata).
You can read more about the function format in triggers and the arguments it takes in [call-spec and call-arg](#call-spec-and-call-arg).

This trigger will be called every time the file is modified and keep an audit log file of all modification dates.

```sh
echo '
{:tags #{"demo"}
 :fdb.on/modify (fn [{:keys [self-path tx]}]
                  (spit (str self-path ".audit")
                        (-> tx :xtdb.api/tx-time .toInstant (str "\n"))
                        :append true))}
' > file.txt.meta.edn
touch file.txt
touch file.txt

cat file.txt.audit
```

```
2024-05-16T20:52:50.845Z
2024-05-16T20:52:59.819Z
2024-05-16T20:53:00.820Z
```

You don't have to code in metadata though.
You can make a function in a repl file and then use it in a trigger.
Add functions you want to use in triggers to a loaded file like `load-repl.fdb.clj` so that they are always available.

```sh
echo '
(defn audit [{:keys [self-path tx]}]
  (spit (str self-path ".audit")
        (-> tx :xtdb.api/tx-time .toInstant (str "\n"))
        :append true))
' >> load-repl.fdb.clj
echo '
{:tags #{"demo"}
 :fdb.on/modify user/audit}
' > file.txt.meta.edn
```


### Network

Anything that can sync files over network can interact with FileDB.
You can sync data files from one machine to another that is running FileDB, and if that file is watched, it will be loaded.
You can sync repl files, which will cause them to be evaluated, and then sync back the `repl-out.fdb.clj` to see the result.

FileDB has a built-in [http-kit](https://github.com/http-kit/http-kit) server that maps routes to functions.
Handlers receive a [call-arg](#call-spec-and-call-arg) with `:req`.

```sh
echo '
(defn foo [{:keys [req]}]
  {:body {:bar "baz"}})
' >> server-repl.fdb.clj
# set fdbconfig.edn :server :routes to {"GET /foo" user/foo}

curl localhost:80/foo
```

```json
{"bar":"baz"}
```

Content is negotiated automatically via [Muuntaja](https://github.com/metosin/muuntaja).
Routes are order independent thanks to [clj-simple-router](https://github.com/tonsky/clj-simple-router).

There's a convenience function to render [Hiccup](https://github.com/escherize/huff) in `fdb.http/render` that you can use without having to import Hiccup.
You can use Hiccup together with [HTMX](https://htmx.org) to quickly whip up UI for FileDB.

```sh
echo '
(defn clicker [_]
  {:body
   (fdb.http/render
    [:<>
     [:script {:src "https://unpkg.com/htmx.org@1.9.12"}]
     [:button {:hx-post "/clicked" :hx-swap "outerHTML"}
      "You know what they call a Quarter Pounder with Cheese in Paris?"]])})

(defn clicked [_]
  {:body
   (fdb.http/render
    [:div "They call it Royale with Cheese."])})
' >> server-repl.fdb.clj
# set fdbconfig.edn :server :routes to 
# {"GET /" user/clicker "POST /clicked" user/clicked}
```

Go to http://localhost:80 to learn about the little differences between the US and Europe.
You can use [ngrok](https://ngrok.com) for free to share this server with others. 
Run `ngrok http 80` after setting ngrok up, and share the link it gives you under `Forwarding`.
In the [ngrok dashboard](https://dashboard.ngrok.com/get-started/setup) you have the CLI args to use a static domain so your server is always up at the same address.

The `fdb.http` namespace has helpers to interact with existing APIs.
This code will get you geo data for the city of Lisbon, Portugal.

```sh
echo '
(-> "https://nominatim.openstreetmap.org/search"
    (fdb.http/add-params {:q "Lisbon" :limit 1 :format "json"})
    fdb.http/json
    first)
' > repl.fdb.clj

cat repl-out.fdb.clj
```

```
(-> "https://nominatim.openstreetmap.org/search"
    (fdb.http/add-params {:q "Lisbon" :limit 1 :format "json"})
    fdb.http/json
    first)

;; => {:osm_type "relation",
;;     :boundingbox ["38.6913994" "38.7967584" "-9.2298356" "-9.0863328"],
;;     :name "Lisboa",
;;     :type "administrative",
;;     :licence
;;     "Data © OpenStreetMap contributors, ODbL 1.0. http://osm.org/copyright",
;;     :place_id 256327888,
;;     :class "boundary",
;;     :lon "-9.1365919",
;;     :lat "38.7077507",
;;     :addresstype "city",
;;     :display_name "Lisboa, Portugal",
;;     :osm_id 5400890,
;;     :place_rank 14,
;;     :importance 0.7149698324141975}
```


## What are the main ideas in it?

The main idea in FileDB is that you can use files as both data and code for a long lived process that you build over time.

This process is yours, and you can do cool stuff with it.

Here's what the terms your see in this README mean:
- mount: the name a watched folder on disk has on the db
- repl/query file: file that evaluates code or db queries on save, outputs result to a sibling file
- reader: a fn that takes a file and returns data from it as edn, which is loaded into the db
- metadata: extra data about a file you add in a sibling .meta.edn file
- trigger: fn in metadata called reactively as the db changes
- call spec/arg: how fns are specified for readers and triggers, and the argument they take

```
            ------------> repl/query -------> clojure process
            |
mount --> file change --> readers+metadata -> db --> triggers
            ^                                           |
            |                                           |
            ---------------------------------------------
```


## More Demos

Below is cool stuff that you can do with FileDB.
If you want to follow these demos, add their dir as a mount.

- [~/demos/clojuredocs](./demos/clojuredocs/README.md): query and scrape [clojuredocs](https://clojuredocs.org) results whenever you write to a file
- [~/demos/temp](./demos/temp/README.md): keep track of max/min temperature and query for the hottest day in the week
- WIP [`~/demos/nutrition`](./demos/nutrition/README.md): make your own nutrition tracking system
- TODO `~/demos/email`: sync all of your emails locally, connect them with your notes
- TODO `~/demos/code-analysis`: read AST for clj files, query it to find what fns are affected when a given fn changes
- TODO `~/demos/webapp`: serve a webapp for your fdb, put it online, go nuts

I'm working on more demos around my own usecases.
I'll add them here when they are done.
If you have cool demos you'd like to list here, make a PR!


## But why?

Because I think it's silly that I own a powerful laptop and a powerful phone, and yet my data is sequestered away on cloud servers, where I pay for the privilege of accessing it and using it in a silo.

I want my data, and I want to fuck around with it on my terms.
I want to connect it together and try to do cool stuff with it!
And I want to sync it between my laptop and my phone, and wherever else I want to have it.

Last year I was travelling somewhere with bad connectivity and wanted to look up food nutrition data on my phone.
This is known data.
Surely there's an app for that.
I tried 10 apps free and paid, and found they were mostly garbage.
I don't think I found even one that worked offline and had the 5 foods I tested.

Why is this hard to get?!
The USDA gives you a [24MB CSV](https://fdc.nal.usda.gov/download-datasets.html) of all foundation foods.
2.8GB if you want all foods, the bulk of it branded.
I know how to program.
My laptop has a 1TB disk, and my phone has a 512GB.
Why do I go mucking around with garbage apps instead of using this available data?

It's not terribly hard to load this into a database.
But it is hard to sync databases, and to open them in different devices.
You know what's really easy to sync and open though?

Files.

You have iCloud, Google Drive, Dropbox, Syncthing, Git, and a ton of other apps to sync.
You have apps that open your on-disk files.
Lots of these cloud services give you a way to download all of your data.

So that got me thinking about doing a database that was mostly a queryable layer over disk files.
I then I added more stuff to it that I thought was cool, like reactive triggers, a live system, and a http server.


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
If you don't want to symlink the CLI script, you can call `./src/fdb/bb/cli.clj help` from this dir. That's what `./symlink-fdb.sh` is linking.

Start using FileDB by running `fdb init`.
This will create `~/fdb/` with `fdbconfig.edn`, `user/`, and `demos/` inside.
If you want to create the `fdb` folder in the current (or other) dir, add it at the end of init like `fdb init .`.

Then run `fdb watch`.
You can edit the config anytime, for instance to add new mounts, and the watcher will restart automatically.
It starts a [nREPL server](https://nrepl.org/nrepl/index.html) on port 2525 that you can connect to from a Clojure editor like [Emacs with Cider](https://github.com/clojure-emacs/cider) or [VSCode with Calva](https://calva.io).

It will watch folders under the `:mount` key in `fdbconfig.edn`.
Modified date, parent, and path for each file on mounts will be added to the db.
If there's a reader for that file type, the extracted data will be added.
If you have `doc.md`, and add a `doc.md.meta.edn` next to it, that edn data will be added to the db's `doc.md` id.
You can put triggers and whatever else you want in this edn file.

Deleted files are removed from the database.
But since XTDB is a [bitemporal](https://v1-docs.xtdb.com/concepts/bitemporality/) database, you can query for past versions of the database.
`fdb watch` will pick up files that changed since it was last running, including deletions.

`~/fdb/user/` has a [repl and query file](repl-and-query-files) to play with.
The [Reference](#reference) is in `~/fdb/demos/reference` and contains examples of how things work.

You can also run `fdb sync` to do a one-shot sync.
This is useful when you have automation you want to run manually.
It doesn't run `fdb.on/schedule` though.

`fdb read glob-pattern` forces a read of the (real, not mount) paths.
This is useful when you add or update readers and want to re-read those files.


## Reference

Reference files are in `~/fdb/demos/reference` folder but are not mounted.
You can mount them if you want.
I've gathered them here to give a nice overview of what you can do, and so its easy to search over them.

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
You can connect your editor to the nREPL server that starts with `fdb watch`, it's on port 2525 by default.

It starts in the `user` namespace but you can add whatever namespace form you want, and that's the ns it'll be eval'd in.
You can find a call-arg like the one triggers receive in `(fdb.call/arg)` (more on `call-arg` in the later in the reference).

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

You can add a repl file (or any clj file) to `fdbconfig.edn` under `:load` to be loaded at startup, and the functions you define in it will be available for triggers and readers before sync.

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

 ;; Mount or real paths of clj files to be loaded at the start.
 ;; Usually repl files where you added fns to use in triggers, or that load namespaces
 ;; you want to use without requiring them, or server handlers.
 :load ["/user/load-repl.fdb.clj"
        "/user/server-repl.fdb.clj"]

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

`fdb watch --debug` starts fdb with extra debug logging.
Connect to the [nREPL server](https://nrepl.org/nrepl/1.1/index.html) on port 2525 by default, and change stuff.
Call `(clj-reload.core/reload)` to reload code as you change it, if you have a config watcher running it will restart as well.

I have TODOs at the end of each file that you can take a look at.
I find this easier than making issues.

`master` branch contains the latest stable code, `dev` is where I work on for new changes.
