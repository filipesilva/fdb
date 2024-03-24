# Nutrition

Following up from [But why?](../../README.md#but-why) lets look at the USDA foundation foods [24MB CSV](https://fdc.nal.usda.gov/download-datasets.html), together with the PDF description also mentioned in the website.
I've added them both here in the demo folder.


## Look around

I looked around with Apple Numbers. A couple of observations:
- looks like db tables with ids to other tables
- the main tables seem to be food.csv, nutrient.csv, and food_nutrient.csv
- in nutrient.csv the most interesting ids seem to be 1003 (protein), 1004 (fat), 1005 (carbohydrates), 1063 (sugars total), 1235 (added sugars), 1079 (fibre), 1008 (energy), but there's lots of entries and some like energy and carbohydrates seem to be repeated
- in food.csv, the important ones seem to be data_type=foundation_food
- food_nutrient.csv links food.csv and nutrient.csv and the PDF says amount of nutrient is per 100g
- filtering food_nutrient.csv by food freezes my Numbers app

At this point I think it'd be pretty easy to load up data from these CSVs into memory and do some basic filtering to only get foundation food nutrient for the ones I identified.

But I don't really know if the nutrients I picked are the right ones.
And I'm a bit resentful of Numbers crashing on some basic filtering over a 8mb CSV, which meant I had to eyeball stuff and scroll back and forth a lot.
Surely I can do better than scrolling and cmd+f with my high powered laptop.

Lets load this data up into a in-memory database and take a look around.

First thing we'll need is a CSV parsing lib, and clojure has a first party one.
Add it to `fdbconfig.edn` under `extra-deps`.
There's no need to restart `fdb watch`, it will see that `fdbconfig.edn` changed and reload itself.


``` edn
 :extra-deps {org.clojure/data.csv {:mvn/version "1.1.0"}}
```

Lets take it for a spin in `~/fdb-demo/reference/nutrition/fdb.repl.edn`:

``` clojure
(require '[babashka.fs :as fs]
         '[clojure.data.csv :as csv]
         '[clojure.java.io :as io]
         '[fdb.call :as call])

;; From https://github.com/clojure/data.csv?tab=readme-ov-file#parsing-into-maps
(defn csv-data->maps [csv-data]
  (map zipmap
       (->> (first csv-data) ;; First row is the header
            (map keyword) ;; Drop if you want string keys instead
            repeat)
       (rest csv-data)))

(def csv-dir (-> (call/arg)
                 :self-path
                 fs/parent
                 (fs/path "FoodData_Central_foundation_food_csv_2023-10-26")))

(defn read-csv [filename]
  (with-open [reader (->> filename (fs/path csv-dir) str io/reader)]
    (doall
     (csv-data->maps (csv/read-csv reader)))))

;; defonce so we don't do this again each time we eval
(defonce food          (read-csv "food.csv"))
(defonce nutrient      (read-csv "nutrient.csv"))
(defonce food-nutrient (read-csv "food-nutrient.csv"))

;; Take a peek at the data we got
(take 5 food)
```

You should see a some data printed in `~/fdb-demo/reference/nutrition/repl-outputs.fdb.clj`.
If you have a code editor configured for Clojure development you can connect to the `fdb watch` nREPL server on port 2525 and eval the `fdb.repl.edn` there instead.

``` clojure
;; => ({:fdc_id "319874",
;;      :data_type "sample_food",
;;      :description "HUMMUS, SABRA CLASSIC",
;;      :food_category_id "16",
;;      :publication_date "2019-04-01"}
;;     {:fdc_id "319875",
;;      :data_type "market_acquisition",
;;      :description "HUMMUS, SABRA CLASSIC",
;;      :food_category_id "16",
;;      :publication_date "2019-04-01"}
;;     {:fdc_id "319876",
;;      :data_type "market_acquisition",
;;      :description "HUMMUS, SABRA CLASSIC",
;;      :food_category_id "16",
;;      :publication_date "2019-04-01"}
;;     {:fdc_id "319877",
;;      :data_type "sub_sample_food",
;;      :description "Hummus",
;;      :food_category_id "16",
;;      :publication_date "2019-04-01"}
;;     {:fdc_id "319878",
;;      :data_type "sub_sample_food",
;;      :description "Hummus",
;;      :food_category_id "16",
;;      :publication_date "2019-04-01"})
```

Results look good enough to shove into a db and query.
Let's make a in-memory XTDB node and just push everything there.
This isn't going to be in the FileDB database itself - it's just a temporary db we're making to explore the data.
Making temporary in-memory databases in Clojure is pretty easy.

We're going to have to pay some attention to ids.
We want to map them to `:xt/id` so we can use [pull](https://v1-docs.xtdb.com/language-reference/datalog-queries/#pull) to scoop up referenced data.
`food` and `food-nutrient` have their own ids and they don't seem to collide, so we can use those directly.
`food` has fdc_id, and also doesn't doesn't seem to collide with the other ids.

Add this to the end of the repl file:

``` clojure
(require '[xtdb.api :as xt])

(defonce node (xt/start-node {}))

(defn add-xtid-and-submit
  [coll from-k]
  (->> coll
       (mapv (fn [m]
               [::xt/put (assoc m :xt/id (get m from-k))]))
       (xt/submit-tx node)))

(defonce food-tx          (add-xtid-and-submit food :fdc_id))
(defonce nutrient-tx      (add-xtid-and-submit nutrient :id))
(defonce food-nutrient-tx (add-xtid-and-submit food-nutrient :id))

;; Wait for the db to catch up to the txs before querying
(xt/sync node)

(xt/q (xt/db node)
      '{:find [(pull ?e [*])]
        :where [[?e :data_type "foundation_food"]]
        :limit 3})
```

And in the outputs you should see:

``` clojure
;; => [[{:fdc_id "1104647",
;;       :data_type "foundation_food",
;;       :description "Garlic, raw",
;;       :food_category_id "11",
;;       :publication_date "2020-10-30",
;;       :xt/id "1104647"}]
;;     [{:fdc_id "1104705",
;;       :data_type "foundation_food",
;;       :description "Flour, soy, defatted",
;;       :food_category_id "16",
;;       :publication_date "2020-10-30",
;;       :xt/id "1104705"}]
;;     [{:fdc_id "1104766",
;;       :data_type "foundation_food",
;;       :description "Flour, soy, full-fat",
;;       :food_category_id "16",
;;       :publication_date "2020-10-30",
;;       :xt/id "1104766"}]]
```

Let's now get all the nutrients too.
XTDB is pretty good at this with the pull projection.

``` clojure
(xt/q (xt/db node)
      '{:find [(pull ?e [:description
                         {:_fdc_id [:amount
                                    {:nutrient_id [:name
                                                   :unit_name]}]}])]
        :where [[?e :data_type "foundation_food"]]
        :limit 1})
```

Which gets us:

``` clojure
;; => [[{:description "Garlic, raw",
;;       :_fdc_id
;;       ({}
;;        {:amount "2.7",
;;         :nutrient_id {:name "Fiber, total dietary", :unit_name "G"}}
;;        {:amount "63.1", :nutrient_id {:name "Water", :unit_name "G"}}
;;        {:amount "9.8",
;;         :nutrient_id {:name "Selenium, Se", :unit_name "UG"}}
;;        {:amount "1.06", :nutrient_id {:name "Nitrogen", :unit_name "G"}}
;;        {:amount "1.71", :nutrient_id {:name "Ash", :unit_name "G"}}
;;        {:amount "0.38",
;;         :nutrient_id {:name "Total lipid (fat)", :unit_name "G"}}
;;        {:amount "10.0",
;;         :nutrient_id
;;         {:name "Vitamin C, total ascorbic acid", :unit_name "MG"}}
;;        {:amount "6.62", :nutrient_id {:name "Protein", :unit_name "G"}}
;;        {:amount "28.2",
;;         :nutrient_id
;;         {:name "Carbohydrate, by difference", :unit_name "G"}}
;;        {:amount "143.0", :nutrient_id {:name "Energy", :unit_name "KCAL"}}
;;        {:amount "597.0", :nutrient_id {:name "Energy", :unit_name "kJ"}}
;;        {:amount "130",
;;         :nutrient_id
;;         {:name "Energy (Atwater Specific Factors)", :unit_name "KCAL"}}
;;        {:amount "143",
;;         :nutrient_id
;;         {:name "Energy (Atwater General Factors)", :unit_name "KCAL"}})}]]
```

The pull projection is a bit gnarly:

``` edn
(pull ?e [:description
          {:_fdc_id [:amount
                     {:nutrient_id [:name
                                   :unit_name]}]}])
```

It means:
- for the entity (each foundation food) get `:description` and what's in the `{}`
- `:_fdc_id` pulls all references back to this entity through the `:fdc_id` key
- for those we're getting `:amount` what's in the `{}`
- `:nutrient_id` pulls what's referenced in this entity in the `:nutrient_id` key
- for those we're getting `:name` and `:amount`

It might feel daunting but at the end of the day it's an impressive amount of power and expressiveness in 4 lines.
This is exactly what I'm looking for when hacking together my own tools.

At this point we've ascertained we can get the data we want from the CSV sources, in about 60 lines of code and sub-second feedback cycles.
I didn't make the code I put here right on the first try, but I did iterate and debug it with these fast feedback cycles until it was doing what I wanted, and it didn't take a long time.


## Chart a course

Now I feel pretty confident about what I want to extract from these CSVs.
What do I want to do with the data though?

I want to:
1. look it up offline on both desktop and mobile
2. reference it
3. calculate meal, daily, and weekly nutrition totals

I've been able to do some of these things from apps but never all of these things.
Point 2 is particularly hard since apps are so isolated and I don't really own my data in most of them.

I'm a heavy [Obsidian](https://obsidian.md) user so my thinking right now is that I should make a markdown file for each foundation food with its nutrition data in [YML properties](https://help.obsidian.md/Editing+and+formatting/Properties#Property%20format).
Then I can reference it easily (point 1), and I have a little food database on desktop and mobile (point 2).

I don't see any obvious way of calculating stuff (point 3) in Obsidian from YML properties though, nor from any data stored in markdown.
But we have triggers so we should be able to somehow trigger the calculation and write back to the file.
I'm a bit fuzzy on this part right now, but it seems doable, and it's a starting point.

You don't have to use Obsidian if you don't want to.
You can use just markdown files on disk.
Or something else. 
You do you.
Even the most budget computer or phone around right now is more than capable of working through all of the personal data you can accumulate during your whole lifetime.
Make it work for you.


## Make it happen

TODO: lean into obsidian, put something that can be used as vault in demo, but work over md to allow everyone to follow
TODO: put in md, reference, trigger compute
TODO: some cool datalog queries for nutrition data, like foods with low carbs or high protein
