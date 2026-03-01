(ns hyperopen.funding-comparison.actions
  (:require [clojure.string :as str]))

(def default-timeframe
  :8hour)

(def default-sort-column
  :open-interest)

(def default-sort-direction
  :desc)

(def ^:private funding-route-kinds
  #{"/funding-comparison"
    "/fundingcomparison"})

(def ^:private valid-timeframes
  #{:hour :8hour :day :week :year})

(def ^:private valid-sort-columns
  #{:coin
    :open-interest
    :hyperliquid
    :binance
    :binance-hl-arb
    :bybit
    :bybit-hl-arb})

(def ^:private valid-sort-directions
  #{:asc :desc})

(defn- split-path-from-query-fragment
  [path]
  (let [path* (if (string? path) path (str (or path "")))]
    (or (first (str/split path* #"[?#]" 2))
        "")))

(defn- trim-trailing-slashes
  [path]
  (loop [path* path]
    (if (and (> (count path*) 1)
             (str/ends-with? path* "/"))
      (recur (subs path* 0 (dec (count path*))))
      path*)))

(defn normalize-route-path
  [path]
  (-> path
      split-path-from-query-fragment
      str/trim
      trim-trailing-slashes))

(defn parse-funding-comparison-route
  [path]
  (let [path* (normalize-route-path path)
        path-lower (str/lower-case path*)]
    (if (contains? funding-route-kinds path-lower)
      {:kind :page
       :path path*}
      {:kind :other
       :path path*})))

(defn funding-comparison-route?
  [path]
  (= :page (:kind (parse-funding-comparison-route path))))

(defn normalize-funding-comparison-timeframe
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :hourly :hour
                     :8h :8hour
                     :eight-hour :8hour
                     :eight-hours :8hour
                     :8-hour :8hour
                     :8-hours :8hour
                     token)]
    (if (contains? valid-timeframes normalized)
      normalized
      default-timeframe)))

(defn normalize-funding-comparison-sort-column
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :symbol :coin
                     :hl :hyperliquid
                     :hl-perp :hyperliquid
                     :binperp :binance
                     :bybitperp :bybit
                     :openinterest :open-interest
                     :binancehlarb :binance-hl-arb
                     :bybithlarb :bybit-hl-arb
                     token)]
    (if (contains? valid-sort-columns normalized)
      normalized
      default-sort-column)))

(defn normalize-funding-comparison-sort-direction
  [value]
  (let [direction (cond
                    (keyword? value) value
                    (string? value) (-> value str/trim str/lower-case keyword)
                    :else nil)]
    (if (contains? valid-sort-directions direction)
      direction
      default-sort-direction)))

(defn load-funding-comparison
  [_state]
  [[:effects/save [:funding-comparison-ui :loading?] true]
   [:effects/api-fetch-predicted-fundings]])

(defn load-funding-comparison-route
  [state path]
  (if (funding-comparison-route? path)
    (let [markets-empty? (empty? (or (get-in state [:asset-selector :markets]) []))]
      (cond-> (load-funding-comparison state)
        markets-empty?
        (conj [:effects/fetch-asset-selector-markets {:phase :full}])))
    []))

(defn set-funding-comparison-query
  [_state query]
  [[:effects/save [:funding-comparison-ui :query] (str (or query ""))]])

(defn set-funding-comparison-timeframe
  [_state timeframe]
  [[:effects/save [:funding-comparison-ui :timeframe]
    (normalize-funding-comparison-timeframe timeframe)]])

(defn set-funding-comparison-sort
  [state sort-column]
  (let [column* (normalize-funding-comparison-sort-column sort-column)
        current-sort (or (get-in state [:funding-comparison-ui :sort])
                         {:column default-sort-column
                          :direction default-sort-direction})
        current-column (normalize-funding-comparison-sort-column (:column current-sort))
        current-direction (normalize-funding-comparison-sort-direction (:direction current-sort))
        next-direction (if (= column* current-column)
                         (if (= :asc current-direction) :desc :asc)
                         (if (= :coin column*) :asc :desc))]
    [[:effects/save [:funding-comparison-ui :sort]
      {:column column*
       :direction next-direction}]]))
