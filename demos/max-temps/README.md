# Temps

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

