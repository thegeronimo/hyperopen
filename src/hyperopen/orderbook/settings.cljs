(ns hyperopen.orderbook.settings
  (:require [hyperopen.orderbook.price-aggregation :as price-agg]))

(def ^:private orderbook-size-units
  #{:base :quote})

(def ^:private orderbook-tabs
  #{:orderbook :trades})

(defn- load-orderbook-size-unit
  []
  (let [v (keyword (or (js/localStorage.getItem "orderbook-size-unit") "base"))]
    (if (contains? orderbook-size-units v) v :base)))

(defn- load-orderbook-active-tab
  []
  (let [v (keyword (or (js/localStorage.getItem "orderbook-active-tab") "orderbook"))]
    (if (contains? orderbook-tabs v) v :orderbook)))

(defn- normalize-price-aggregation-by-coin
  [raw-map]
  (if (map? raw-map)
    (into {}
          (keep (fn [[coin raw-mode]]
                  (let [mode (cond
                               (keyword? raw-mode) raw-mode
                               (string? raw-mode) (keyword raw-mode)
                               :else nil)]
                    (when (and (string? coin)
                               (seq coin)
                               (contains? price-agg/valid-modes mode))
                      [coin mode]))))
          raw-map)
    {}))

(defn- load-orderbook-price-aggregation-by-coin
  []
  (try
    (let [raw (js/localStorage.getItem "orderbook-price-aggregation-by-coin")]
      (if (seq raw)
        (normalize-price-aggregation-by-coin (js->clj (js/JSON.parse raw)))
        {}))
    (catch :default _
      {})))

(defn restore-orderbook-ui!
  [store]
  (swap! store update :orderbook-ui merge
         {:size-unit (load-orderbook-size-unit)
          :price-aggregation-by-coin (load-orderbook-price-aggregation-by-coin)
          :active-tab (load-orderbook-active-tab)}))

(defn select-orderbook-size-unit
  [state unit]
  (let [size-unit (if (= unit :quote) :quote :base)]
    [[:effects/save [:orderbook-ui :size-unit] size-unit]
     [:effects/save [:orderbook-ui :size-unit-dropdown-visible?] false]
     [:effects/local-storage-set "orderbook-size-unit" (name size-unit)]]))

(defn select-orderbook-price-aggregation
  [state mode]
  (let [coin (:active-asset state)
        mode* (price-agg/normalize-mode mode)
        current-by-coin (get-in state [:orderbook-ui :price-aggregation-by-coin] {})
        next-by-coin (if (seq coin)
                       (assoc current-by-coin coin mode*)
                       current-by-coin)]
    (cond-> [[:effects/save [:orderbook-ui :price-aggregation-by-coin] next-by-coin]
             [:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] false]
             [:effects/local-storage-set-json
              "orderbook-price-aggregation-by-coin"
              (normalize-price-aggregation-by-coin next-by-coin)]]
      (seq coin)
      (conj [:effects/subscribe-orderbook coin]))))

(defn select-orderbook-tab
  [state tab]
  (let [tab* (cond
               (keyword? tab) tab
               (string? tab) (keyword tab)
               :else :orderbook)
        normalized-tab (if (contains? orderbook-tabs tab*) tab* :orderbook)]
    [[:effects/save [:orderbook-ui :active-tab] normalized-tab]
     [:effects/save [:orderbook-ui :size-unit-dropdown-visible?] false]
     [:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] false]
     [:effects/local-storage-set "orderbook-active-tab" (name normalized-tab)]]))
