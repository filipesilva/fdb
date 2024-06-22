# FileDB
####	 a reactive database environment for your files

Hi everyone, thank you for having me.

My name is Filipe and I'm here to talk about a passion project I've been working on, FileDB.

---
## Demo

I feel what FileDB isn't very familiar so maybe the best way  to start is by showing some cool things I can do with it.

---
## The What

So what is FileDB?
I describe it as a "reactive database environment for your files".

The main idea is that your files on disk are loaded into a database and you can do cool stuff with them.

---
### Watcher
	data -> db
	code -> process

I think the most familiar way of looking at it is as a file watcher.

Specifically, the type of file watcher you find in most frontend build systems.
You have some watched files, when one changes the code gets compiled, and your browser live reloads the code.
Nice and ergonomic feedback loop.

FileDB watches files, and if it's data then it's loaded into the database, if it's code then it's loaded into the process.
And then you get to play with it.

---
### Data
	edn/json/md/eml
	*.meta.edn
	add your own
	*query.fdb.edn

Out of the box FileDB supports loading edn, json, md, and eml data.

Markdown data is yml front matter, at the start of the md file, fenced between three dashes.
Obsidian (a markdown editor) uses this format and I find it pretty good.

Eml is email in MIME format.
A series of header key-colon-value followed by a newline and email body.
It's a great format that supports a LOT of things by virtue of being essentially a map, but also having support for attachments.

You can add edn metadata to any file by adding a .meta.edn sibling file too.

You can also support your own by adding a reader for whatever file format you want.
Think image metadata or pdf text.

Then you query your data using query.fdb.edn files, where you put XTDB queries.
Results will be output to query-out.fdb.edn.

---
### Code
	*repl.fdb.clj
	load, deps, nrepl

Code is Clojure.
It's a clojure process and it's what I like working on.

Like the query files for data, there's repl.fdb.clj file, and it's evaluated under the user namespace.
But you can add any ns form there, and that's where it will end up.
Output is in repl-out.fdb.clj.

You can add clj files to be loaded when the watcher starts, add dependencies without restarting the process, and connect a nrepl client.

---
### Triggers
	modify, pattern, refs, query, schedule, startup, shutdown
	tx aka add your own

You can add code calls on database updates, called triggers.
This is the reactive part.

There's lots of built-in update triggers.
You add them directly on metadata.
Refs and query are especially interesting.

Ref triggers are ran when their references change, or their references, and so on.
Recursively, which is something that people that have used graph databases can appreciate.

Query triggers run when the result of their query change.
It's the sort of thing you'd never do on a "real" system, but it's fine to do on "your" system.

You also have the tx trigger, that lets you add whatever trigger you want.

---
### Server
	standard ring stuff
	go public with ngrok

There's also an HTTP server, because it's a great way to make UIs and interop with other things.

It's mostly what you expect from a ring server.
There's a request there, and you respond with a map using a handler that you loaded into the process.
No fuss.

Put it online via ngrok or the sort.
Doing some code and showing it to someone shouldn't be hard.

---
### Email
	sync to disk as .eml
	send

FileDB has a email schedule trigger to sync email to disk as `.eml` files.

These `.eml` files are loaded into the database as EDN through the default `.eml` reader, and then you can query across senders, receivers, dates, threads, labels, etc.

I've loaded my 10gb gmail history into FileDB and it works pretty well.
Was a bit worried it would get slow but hasn't yet.

You can also send emails, which is handy for self notifications.

---
## But why?

Ok, but what is it all for?
Why would you want to be watching your files and doing things?
What benefit can you get out of it?
Why did I spend time coding this?

---
### My data
	their storage

I use a lot of data.
There's email, services, app data.

I think of this as my data, but at this point it's not really mine.
Even when I can download it, it's not that easy to use it.

So I'm paying for the privilege of using my own data.
Sometimes I pay in money, sometimes in time, sometimes in attention.

---
### My devices
	their compute

I have a powerful laptop, and a powerful phone.
They have large disks.
In my house I have about 30 CPU cores, 80GB RAM, and 3TB of disk total among all devices.

I'm a programmer.
I know a bunch of different programming languages.
I can do cool stuff with computers.

But if I want to put a shitty TODO app online I need to pay some megacorp 5 euros a month for a server.

How many computers do I need to own before I don't need to pay companies to do things online?
This feels ridiculous.

---
### My use cases
	their business model

On one hand, I have that programmer power trip where I can imagine how something I want to do can be done with computers.

On the other hand, when I need to do something I go search for the "app for that" that surely exists.
But then it doesn't, or it's crap, or it has loads of ads, or it's a subscription.

Oh well.
Too bad.
Guess I won't do what I want, even though I know how to do it, and have all the tools to do it.
It's just too hard.

---
### Simple things should be simple
	let's go shopping instead

I feel it's too hard to do simple things with my data.

My day-to-day is to make code that makes computers do what people want to do.
But when there's stuff I need to do I feel a sort of learned helplessness.

I need to export the data.
Then I need to ETL it into a database.
Then make some script.
It doesn't cover all the edge cases.
It's not following the best practices.
It's not very secure.
What about backups?
I need to learn the right libraries.
The code isn't very clean.
Have to set up CI.
Have to deploy.
I need a database.

I don't want to feel like that.
I want it to be simple to do simple things with my data.

---
### Example: nutrition
	free data, garbage apps

I have a concrete example.
Last year I was travelling with my in-laws somewhere where there as bad internet connection and I wanted an offline app to track some nutrition data.

I know this is the sort of data that just exists.
I know the iOS App Store has infinite apps of this kind.
I spent a few hours, a couple hundred MBs of data, and a few free trials trying some 10 apps, free and paid.

I could not find a single one that worked offline and had the 5 basic foods I tried.

I feel this is a ridiculous state of affairs.

--- 
### Database vs Files
	share, sync, copy

I think databases are at the core of this odd state of affairs.

When I think of my data, I think of using my data on all my devices.
And I think of connecting my data in powerful ways.
I think of a database.

But it's hard to share, sync, or copy a database.
So I have to centralize access.
Then I have to make clients for that centralized place.

You know what's really easy to share, sync, and copy?
Files.
There's a lot of ways to share, sync, and copy files.

Files go even further.
You can choose what app to read your files with, and people you share them with can use other apps.
You can sync all or some of your files between your devices trivially, over the internet or local network.

---
### Database over Files
	¿Por qué no los dos?

I settled on trying to make a database layer over files on disk.
Files are the source of truth, and the db just extracts data from them.
Delete the database and it just gets recreated from the files.

FileDB is made to run in many different machines, each with their own set of files, syncing fully or partially, running code on their own process.

You can even have machines that don't run FileDB, but because they sync with one that does, you can run queries and code remotely through file sync!
I do this with my phone, where I write query and repl files in a note taking app, it syncs via iCloud to my laptop that's running FileDB, and then I get the result out files synced back.

When I started using my data this way I wanted to do other things I thought were cool, like having a live system, reactive triggers, and easy http handlers.

I picked XTDB as the database because I love time-travel shenanigans, and because it doesn't need a schema which is great for freeform data.

---
### Bring it together
	More than the sum of its parts

When your data is separated between a bunch of different services, it's only as good as what the services want to give you.

Most services offer exports.
You can make a schedule that syncs your data down from the cloud to your disk, and into FileDB.

Then you can bring it together.
You can make your own weekly mail reports for contacts in your notes.
You can sync your kindle highlights and grep through them.

You don't need to wait for your services to serve your use cases.
Get your data and get what you want out of it.

---
## The How

I hope I've given you an overall idea of what I'm trying to do, and why I'm trying to do it.

Let's look at in more detail at some of things I demoed earlier.

---
### config
```clojure
;; fdbconfig.edn
{:db-path "./xtdb"
 :extra-deps {}
 :extra-readers {}
 :load ["/user/load-repl.fdb.clj" "/user/server-repl.fdb.clj"]
 :mounts {:user "/Users/filipesilva/fdb/user"}
 :serve {:routes {"GET /foo" user/foo
                  "GET /" user/clicker
                  "POST /clicked" user/clicked}}}
```

When you initialize FileDB, it creates a configuration file that looks like this.
FileDB reloads the config on edit without restarting the process.
This includes loading new deps dynamically via the new clojure.repl.deps/add-libs.

Extra-deps is a deps map like deps.edn projects have.
So it's easy to add maven, git, and local deps to use on your data.

Extra-readers is where you add your own file readers.
You pick what data you want to load into the db.

The load vector are code files you want to load at startup.
Useful to load deps eagerly and to define functions you want to use elsewhere.

Mounts are the watched folders.
The real folder users/filipesilve/fdb/user is loaded on FileDB as /user.
This is an important part of syncing between devices because it lets you keep the mount

---
### Data
```clojure
{:tags #{"demo"}}
```
```json
{"tags": ["demo"]}
```
```md
---
tags:
  - demo
---
Markdown body is not loaded
```

Here you have some data files in edn, json, and md.
Same thing in all of them, a tag set.
Well mostly same thing as json and md don't have sets.

---
### Query
```clojure
;; tags-query.fdb.edn
{:find [(pull ?e [*])] 
 :where [[?e :tags "demo"]]}
```
```clojure
;; tags-query-out.fdb.edn
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

And this is what it looks like querying for those files.
That XTDB query means "I want to get everything for each file that has the demo tag".

Result goes in the out file.
There's three other properties there that we didn't add but all files get:
- the file id in xtdb/id, which is the mount followed by relative path
- modified time
- parent id, which is useful for listings and recursive queries

---
### Metadata
```md
# cool.txt
Very.
```
```clojure
;; cool.txt.meta.edn
{:cool true}
```
```clojure
;; cool-query.fdb.edn
{:find [(pull ?e [*])] 
 :where [[?e :cool true]]}
```
```clojure
;; tags-query-out.fdb.edn
#{[{:cool true
    :fdb/modified #inst "2024-04-23T22:51:32.639683791Z"
    :fdb/parent "/user"
    :xt/id "/user/cool.txt"}]}
```

This is what it looks like adding metadata to any file.
cool.txt will have an entry in the db with id/modified/parent regardless, but you can add more to its entry via the meta.edn file.

---
### Code
```clojure
;; repl.fdb.clj
(defn my-inc [x] (inc x))
```
```clojure
;; repl-out.fdb.clj
(defn my-inc [x] (inc x))

;; => #'user/my-inc
```
```clojure
;; another-repl.fdb.clj
(+ (user/my-inc 1) (my-inc 1))
```
```clojure
;; another-repl-out.fdb.clj
(+ (user/my-inc 1) (my-inc 1))

;; => 4
```

Repl files eval into the process directly.
No sandbox or anything.
NRepl too on port 2525 by default, configurable.

I'm not here to tell you what you can do with your data, I'm here to give you cool ways of using your data.
Go nuts.

---
### XTDB time travel
```clojure
;; repl.fdb.clj
(fdb.db/entity-history "/user/data.edn" :asc :with-docs? true)
```
```clojure
;; repl-out.fdb.clj
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

You can access the DB from the repl files, so one super cool thing you can do is time travel.
Old versions of files, as-of queries, etc.
Super neat stuff.
If you delete and recreate the DB the history is gone though.

---
### Triggers
```clojure
;; file.txt.meta.edn
{:fdb.on/modify 
  (fn [{:keys [self-path tx]}]
    (spit (str self-path ".audit")
          (-> tx :xtdb.api/tx-time .toInstant (str "\n"))
          :append true))}
```
```md
2024-05-16T20:52:50.845Z
2024-05-16T20:52:59.819Z
2024-05-16T20:53:00.820Z
```

This is a on-modify trigger that adds an audit file next to file.txt.
Whenever it changes, you get a line with the change time.

Straight up clojure code on the edn.
You put this function in a repl.fdb.clj file and call it by it's namespaced function name.
Preferably one on the load vector though, like the default load-repl.fdb.clj, so it's always there after a restart.

---
### HTTP
```clojure
;; server-repl.fdb.clj
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
```
```clojure
;; fdbconfig.edn
{:serve {:routes {"GET /" user/clicker
                  "POST /clicked" user/clicked}}}
```

There's another default repl file on the load vector, so you have an easy place to put some http handlers.
Just reference the fn in fdbconfig routes and you're good to go.
Route definitions are order independent thanks to Tonsky's clj-simple-router.

Add in HTMX you have some super easy UIs over your data.
Add in a tunnel like ngrok and you can give your friends that UI, then take it down just as easily.

---
### Email
```clojure
;; email.meta.edn
{:fdb.on/schedule
  {:every  [5 :minutes]
   :call   fdb.email/sync
   :folder "INBOX"
   :since  #inst "2024-05-15"}}
```
```clojure
;; repl.fdb.clj
(fdb.email/send
  (fdb.call/arg)
  {:to "them@email.com"
   :subject "you got"
   :text "mail"})
```

On top there's a schedule trigger.
It syncs new emails from your inbox every 5 minutes to the `email` folder.

Sending email is also pretty straightforward.
That `fdb.call/arg` is how you get the same arguments triggers get, but from the repl files.
It has lots of things, like the config, db node, file name you're on, etc.

---
## https://github.com/filipesilva/fdb
	#filedb on clojurians slack

So that's FileDB.
I hope you found it interesting.

I have a few demos in the README, and I'm adding more as I make them.
If you want to share something cool you did with it I'd love to add it to the demos.

I'm pretty happy with where it's at right now, but have been thinking a lot about ingesting collection data like CSV, and prototypical inheritance between loaded data.

Any questions?
