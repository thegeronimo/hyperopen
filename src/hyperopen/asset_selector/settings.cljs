(ns hyperopen.asset-selector.settings
  (:require [hyperopen.platform :as platform]))

(def valid-sort-keys #{:name :price :volume :change :openInterest :funding})
(def valid-sort-directions #{:asc :desc})
(def valid-tabs #{:all :perps :spot :outcome :outcome-15m :outcome-1d :crypto :tradfi :hip3})

(defn- load-sort-setting
  "Read `ls-key` from localStorage, default to `default`,
   coerce to keyword, and only accept it if `valid?` returns true."
  [ls-key default valid?]
  (let [v (keyword (or (platform/local-storage-get ls-key) default))]
    (if (valid? v) v (keyword default))))

(defn- load-bool-setting [ls-key default]
  (let [v (platform/local-storage-get ls-key)]
    (if (nil? v)
      default
      (= v "true"))))

(defn- load-json-set [ls-key]
  (try
    (let [raw (platform/local-storage-get ls-key)
          parsed (when (seq raw) (js->clj (js/JSON.parse raw)))]
      (if (sequential? parsed) (set parsed) #{}))
    (catch :default _
      #{})))

(defn restore-asset-selector-sort-settings! [store]
  (let [sort-by        (load-sort-setting "asset-selector-sort-by"        "volume" valid-sort-keys)
        sort-direction (load-sort-setting "asset-selector-sort-direction" "desc"   valid-sort-directions)
        strict?        (load-bool-setting "asset-selector-strict" false)
        favorites      (load-json-set "asset-selector-favorites")
        active-tab     (load-sort-setting "asset-selector-active-tab" "all" valid-tabs)]
    (swap! store
      update-in
      [:asset-selector]
      merge
      {:sort-by        sort-by
       :sort-direction sort-direction
       :strict?        strict?
       :favorites      favorites
       :active-tab     active-tab}))) 
