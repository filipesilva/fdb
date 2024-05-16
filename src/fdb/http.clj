(ns fdb.http
  (:require
   [net.cgrand.enlive-html :as enlive-html]
   [clojure.data.json :as json]
   [clojure.string :as str])
  (:import
   (java.net URL URLDecoder URLEncoder)))

(defn encode-url [s]
  (URLEncoder/encode s))

(defn decode-url [s]
  (URLDecoder/decode s))

(defn encode-uri
  "Like JS encodeURI https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/encodeURI"
  [s]
  (-> s
      str
      (URLEncoder/encode)
      (.replace "+" "%20")
      (.replace "%2F" "/")))

(defn decode-uri
  "Like JS decodeURI https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/decodeURI"
  [s]
  (decode-url s))

(defn add-params
  [url param-map]
  (->> param-map
       (mapcat (fn [[k v]]
                 (if (sequential? v)
                   (map vector (repeat k) v)
                   [[k v]])))
       (map (fn [[k v]]
              (str (encode-uri (name k)) "=" (encode-uri v))))
       (str/join "&")
       (str url "?")))

(defn json
  "Get url and parse it as json."
  [url]
  (some-> url slurp (json/read-str :key-fn keyword)))

(defn scrape
  "Get url and scrap it to edn, using selector if any.
  Uses Enlive selectors https://github.com/cgrand/enlive?tab=readme-ov-file#selectors"
  ([url]
   (-> url URL. enlive-html/html-resource))
  ([url selector]
   (-> url scrape (enlive-html/select selector))))

;; TODO:
;; - could sync a page to client when it changes, via SSE
;;   - client can ask for static page, or self-updating page
;;   - you change the page on disk, new version is pushed to client
;;   - cool for sync in general, maybe even reified sessions
;; - where does that headers-in-the-metadata idea fit in now?
;;   - I guess it's just a namespaced k, for headers
;; - have to make it easier for third party libs to add routes
;; - blog is a cool example of a subset of your file library you might want to expose online
;; - Is rss just a folder file listing ordered by date and content, and content negotiated to be rss format?
;;   - The folder could be real, or be full of symlinks, or even be a synthetic listing from the db.
;; - can I make some auto-PWA thing work?
;;   - that'd make these real personal apps
;; - need to be able to serve an existing ref
;;   - e.g. serve trigger query result
;;   - data needed: fn to render it, ref id, watch semantics
;;   - watch semantics needs a model of server syncing a component first
;;   - watch both the ref id, and the fn, or the file the fn is in
;;   - probably always watch really... turning it off is the special case
;; - reload on handler change
;;   - maybe only relevant for the htmx live env case...
;;     - normally you don't want to force-reload endpoint results when editing
;;   - A bit of disconnect between the notion of "server handler is fn" and "live edit the file for a server fn".
;;     - The link between the two is not obvious.
;;     - But handler is deff a fn, not a file.
;;     - Does the eval for the fn preserve the file?
;; - first class htmx components
;;   - demo should be like http://reagent-project.github.io demos
;; - turn the htmx problem into a content negotiation problem
;;   - always return the plain data from endpoints, then render it
;;   - edn, json, http for htmx, partial http for htmx partials
;;   - maybe full html can be nested routes?
;;     - /api/email/1 is just the email partial
;;     - /inbox/email/1 is the inbox with the email partial in
;;
