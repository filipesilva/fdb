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

(comment
  (-> "https://nominatim.openstreetmap.org/search"
      (add-params {:q "Lisbon" :limit 1 :format "json"})
      json)
  ;; => [{"addresstype" "city",
  ;;      "class" "boundary",
  ;;      "osm_type" "relation",
  ;;      "place_id" 287082329,
  ;;      "boundingbox" ["38.6913994" "38.7967584" "-9.2298356" "-9.0863328"],
  ;;      "name" "Lisboa",
  ;;      "osm_id" 5400890,
  ;;      "lon" "-9.1365919",
  ;;      "display_name" "Lisboa, Portugal",
  ;;      "type" "administrative",
  ;;      "licence"
  ;;      "Data © OpenStreetMap contributors, ODbL 1.0. http://osm.org/copyright",
  ;;      "lat" "38.7077507",
  ;;      "place_rank" 14,
  ;;      "importance" 0.7149698324141975}]

  (-> "https://api.open-meteo.com/v1/forecast"
      (add-params {:latitude  "38.7077507"
                   :longitude "-9.1365919"
                   :daily     ["temperature_2m_max", "temperature_2m_min"] ,
                   :past_days 7})
      json)
  ;; => {"timezone" "GMT",
  ;;     "daily_units"
  ;;     {"time" "iso8601", "temperature_2m_max" "°C", "temperature_2m_min" "°C"},
  ;;     "elevation" 7.0,
  ;;     "longitude" -9.14,
  ;;     "generationtime_ms" 0.04100799560546875,
  ;;     "utc_offset_seconds" 0,
  ;;     "latitude" 38.71,
  ;;     "timezone_abbreviation" "GMT",
  ;;     "daily"
  ;;     {"time"
  ;;      ["2024-03-25"
  ;;       "2024-03-26"
  ;;       "2024-03-27"
  ;;       "2024-03-28"
  ;;       "2024-03-29"
  ;;       "2024-03-30"
  ;;       "2024-03-31"
  ;;       "2024-04-01"
  ;;       "2024-04-02"
  ;;       "2024-04-03"
  ;;       "2024-04-04"
  ;;       "2024-04-05"
  ;;       "2024-04-06"
  ;;       "2024-04-07"],
  ;;      "temperature_2m_max"
  ;;      [16.1 13.8 15.6 17.5 15.5 13.8 13.4 16.0 16.4 17.8 17.3 19.7 16.9 18.8],
  ;;      "temperature_2m_min"
  ;;      [10.1 8.7 13.3 11.7 10.7 10.2 10.5 11.7 13.2 12.5 10.6 13.2 12.7 13.0]}}

  (scrape "https://clojuredocs.org/search?q=reduce")
  (scrape "https://clojuredocs.org/search?q=reduce" [:li.arglist])
  ;; => ({:tag :li, :attrs {:class "arglist"}, :content ("(reduce f coll)")}
  ;;     {:tag :li, :attrs {:class "arglist"}, :content ("(reduce f val coll)")}
  ;;     {:tag :li, :attrs {:class "arglist"}, :content ("(reduced x)")}
  ;;     {:tag :li, :attrs {:class "arglist"}, :content ("(reduce f init ch)")}
  ;;     {:tag :li, :attrs {:class "arglist"}, :content ("(reducer coll xf)")}
  ;;     {:tag :li, :attrs {:class "arglist"}, :content ("(reduce f coll)")}
  ;;     {:tag :li, :attrs {:class "arglist"}, :content ("(reduce f init coll)")}
  ;;     {:tag :li, :attrs {:class "arglist"}, :content ("(reduced? x)")}
  ;;     {:tag :li, :attrs {:class "arglist"}, :content ("(reduce-kv f init coll)")}
  ;;     {:tag :li, :attrs {:class "arglist"}, :content ("(kv-reduce amap f init)")}
  ;;     {:tag :li, :attrs {:class "arglist"}, :content ("(coll-reduce coll f)")}
  ;;     {:tag :li, :attrs {:class "arglist"}, :content ("(coll-reduce coll f val)")}
  ;;     {:tag :li, :attrs {:class "arglist"}, :content ("(ensure-reduced x)")})
  )
