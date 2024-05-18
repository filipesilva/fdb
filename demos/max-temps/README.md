# Temps

This demo uses a `fdb.on/schedule` [metadata trigger](../../README.md#metadata) to:
- each day
- look up temperatures in Lisbon for the past and future 7 days
- load them into FileDB by writing them to `.edn` files on disk that we have [readers](../../README.md#readers) for
- then query the db using a [query file](../../README.md#repl-and-query-files) that calls a function we just defined in a [repl file](../../README.md#repl-and-query-files)

Start by adding the schedule trigger.

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
```

This will create edn files in `~/fdb/user/weather` for min/max temp, updated every day, for previous and next 7 days

```sh
ll ~/fdb/user/weather
```

```sh
total 112
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-11.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-12.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-13.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-14.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-15.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-16.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-17.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-18.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-19.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-20.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-21.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-22.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-23.edn
-rw-r--r--  1 filipesilva  staff    40B May 18 21:44 2024-05-24.edn
```

These files are loaded into FileDB automatically because they are edn files.
Their data looks like `{:day "2024-05-11" :max 23.6 :min 15.7}`.

Now we can query for it.
The [tick](https://github.com/juxt/tick) library is already included in FileDB.

What are the max temperatures like the week around today?
Lets make a date that checks if a given date is in the current week, then use it in a query file.

```sh
echo '
(require \'[tick.core :as t])
(defn this-week? [date]
  (let [today (t/date)]
    (t/<= (t/<< today (t/of-days 3))
          (t/date date)
          (t/>> today (t/of-days 3)))))
' >> ~/fdb/user/load-repl.fdb.clj
echo '
{:find [?day ?max]
 :where [[?e :fdb/parent "/user/weather"]
         [?e :day ?day]
         [(user/this-week? ?day)]
         [?e :max ?max]]}
' > ~/fdb/user/week-max-temp.query.fdb.edn

cat ~/fdb/user/week-max-temp.query-out.fdb.edn
```

```clojure
#{["2024-05-15" 19.2]
  ["2024-05-16" 19.9]
  ["2024-05-17" 20.6]
  ["2024-05-18" 20.4]
  ["2024-05-19" 20.6]
  ["2024-05-20" 21.0]
  ["2024-05-21" 20.4]}
```

Just `touch ~/fdb/user/week-max-temp.query.fdb.edn` to run the query again.
Or add a schedule to do it automatically.

```sh
echo '
{:fdb.on/schedule {:every [1 :days]
                   :call  [:sh "touch" "week-max-temp.query.fdb.edn"]}}
' > ~/fdb/user/touch-query.edn
```

Fun fact: if you added this trigger on `touch ~/fdb/user/week-max-temp.query.fdb.edn.meta.edn` you'd make a infinite loop.
Touch would load both query and metatada, and schedule changes are immediately triggered, which would touch again, etc.
So maybe don't.
But if you do, just stop the watch process, remove the problematic files, and then start it again.
