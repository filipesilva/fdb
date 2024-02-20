(ns fdb.fns.webserver)

;; TODO:
;; - serve directly
;; - push to cdn
;; - web server
;;   - serves mounted paths
;;   - has fdb.server/get and fdb.server/put etc metadata for fns
;;   - does auto content negotiation
;;   - maybe plays well with templates or htmx or whatever
;;   - hicckup files with scripts as a inside-out web-app, kinda like php, code driven by template
;;   - clj repl-like files, eval it, respond with eval result
;; - try https://github.com/tonsky/clj-simple-router
;; - https://github.com/babashka/http-server for static assets?
;; - could sync a page to client when it changes, via SSE
;;   - client can ask for static page, or self-updating page
;;   - you change the page on disk, new version is pushed to client
;;   - cool for sync in general, maybe even reified sessions
;; - where does that headers-in-the-metadata idea fit in now?
;;   - I guess it's just a namespaced k, for headers
;; - fs structure for routes, each clj file has patch/post/get/delete fns
;; - have to make it easier for third party libs to add routes
