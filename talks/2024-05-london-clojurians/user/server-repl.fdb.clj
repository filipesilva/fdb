;; Like load-repl.fdb.clj, but handy to have all server handlers in one place.
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
