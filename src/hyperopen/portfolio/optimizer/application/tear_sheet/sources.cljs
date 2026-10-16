(ns hyperopen.portfolio.optimizer.application.tear-sheet.sources
  "Price rows ({:time-ms :close}) for each leg of an optimizer target and for
  the tear sheet's benchmarks, read from state the optimizer and the vault
  pages already load."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.history-loader.normalization :as normalization]
            [hyperopen.portfolio.optimizer.application.tear-sheet.backtest :as backtest]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.ids :as ids]))

(def hlp-address
  "Hyperliquidity Provider, the protocol vault used as a vault benchmark."
  "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303")

(def btc-candle-interval :1d)

(def btc-candle-bars
  ;; Covers the 2Y range and all but the oldest vaults' full lives.
  1100)

(defn- history-data
  [state]
  (get-in state contracts/history-data-path))

(defn stitched-vault-rows
  "A vault's share-price index over its whole life: the all-time window's
  sparse samples, then the month window's dense samples rescaled to meet the
  all-time index where the month window starts."
  [details]
  (let [portfolio (:portfolio details)
        all-rows (normalization/summary-price-history (:all-time portfolio))
        month-rows (normalization/summary-price-history (:month portfolio))]
    (cond
      (empty? month-rows) all-rows
      (empty? all-rows) month-rows
      :else
      (let [t0 (:time-ms (first month-rows))
            base (backtest/value-at all-rows t0)
            scale (if (and (number? base) (pos? base))
                    (/ base (:close (first month-rows)))
                    1)]
        (into (filterv #(< (:time-ms %) t0) all-rows)
              (map #(update % :close * scale))
              month-rows)))))

(defn fee-rate
  "The leader's profit share as a fraction (0.1 = 10%), or 0 when unknown."
  [details]
  (let [v (:leader-commission details)]
    (cond
      (not (number? v)) 0
      (> v 1) (/ v 100)
      (neg? v) 0
      :else v)))

(defn- vault-details
  [state address]
  (or (get-in (history-data state) [:vault-details-by-address address])
      (get-in state [:vaults :benchmark-details-by-address address])
      (get-in state [:vaults :details-by-address address])))

(defn- api-rows
  [state instrument-id]
  (->> (get-in (history-data state) [:api-v2-history :series-by-instrument instrument-id :points])
       (keep (fn [{:keys [time-ms close index-value]}]
               (let [v (or close index-value)]
                 (when (and (number? time-ms) (number? v) (pos? v))
                   {:time-ms time-ms :close v}))))
       (sort-by :time-ms)
       vec))

(defn- coin-of
  [instrument-id]
  (when-let [[_ market coin] (re-matches #"^(perp|spot):(.+)$" (str instrument-id))]
    (if (= market "perp")
      (let [parts (str/split coin #":")]
        (if (> (count parts) 1) coin (first parts)))
      coin)))

(defn instrument-rows
  "{:rows :vault? :address :fee-rate} for one instrument id."
  [state instrument-id]
  (if-let [address (ids/vault-address-from-value instrument-id)]
    (let [details (vault-details state address)
          rows (if details (stitched-vault-rows details) [])]
      {:vault? true
       :address address
       :rows (if (seq rows) rows (api-rows state instrument-id))
       :fee-rate (fee-rate details)})
    (let [rows (api-rows state instrument-id)]
      {:vault? false
       :rows (if (seq rows)
               rows
               (normalization/normalize-candle-history
                (get-in (history-data state) [:candle-history-by-coin (coin-of instrument-id)])))
       :fee-rate 0})))

(defn hlp-rows
  [state]
  (if-let [details (vault-details state hlp-address)]
    (stitched-vault-rows details)
    []))

(defn hlp-fee-rate
  [state]
  (fee-rate (vault-details state hlp-address)))

(defn- candle-rows
  [value]
  (loop [v value depth 0]
    (cond
      (sequential? v) v
      (and (map? v) (< depth 4)) (recur (or (:rows v) (:data v) (:candles v)) (inc depth))
      :else [])))

(defn btc-rows
  [state]
  (let [rows (normalization/normalize-candle-history
              (candle-rows (get-in state [:candles "BTC" btc-candle-interval])))]
    (if (seq rows)
      rows
      (normalization/normalize-candle-history
       (get-in (history-data state) [:candle-history-by-coin "BTC"])))))

(defn benchmark-fetch-effects
  "Fetches for benchmarks that are not in state yet."
  [state]
  (cond-> []
    (empty? (hlp-rows state))
    (conj [:effects/api-fetch-vault-benchmark-details hlp-address])

    (empty? (get-in state [:candles "BTC" btc-candle-interval]))
    (conj [:effects/fetch-candle-snapshot
           :coin "BTC"
           :interval btc-candle-interval
           :bars btc-candle-bars])))
