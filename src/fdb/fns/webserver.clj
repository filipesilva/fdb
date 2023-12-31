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
