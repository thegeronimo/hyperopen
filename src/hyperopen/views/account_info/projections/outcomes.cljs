(ns hyperopen.views.account-info.projections.outcomes
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.projections.parse :as parse]))

(def parse-num parse/parse-num)
(def parse-optional-num parse/parse-optional-num)

(def ^:private outcome-asset-id-base 100000000)

(def ^:private outcome-token-pattern
  #"^[+#](\d+)$")

(def ^:private outcome-entry-notional-keys
  [:entryNtl :entryNotional :entryNtlUsd :entryNtlUSDC :entryValue :entry])

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt (str/trim value) 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- token-encoding
  [value]
  (when-let [[_ encoding] (and (string? value)
                               (re-matches outcome-token-pattern
                                           (str/trim value)))]
    (parse-int-value encoding)))

(defn- asset-id-encoding
  [value]
  (when-let [token-id (parse-int-value value)]
    (let [encoding (- token-id outcome-asset-id-base)]
      (when (not (neg? encoding))
        encoding))))

(defn outcome-token?
  [value]
  (boolean (token-encoding value)))

(defn outcome-balance?
  [{:keys [coin token]}]
  (or (outcome-token? coin)
      (some? (asset-id-encoding token))))

(defn- side-coin-from-encoding
  [encoding]
  (when (number? encoding)
    (str "#" encoding)))

(defn- token-name-from-encoding
  [encoding]
  (when (number? encoding)
    (str "+" encoding)))

(defn- balance-encoding
  [{:keys [coin token]}]
  (or (token-encoding coin)
      (asset-id-encoding token)))

(defn- balance-side-coin
  [balance]
  (side-coin-from-encoding (balance-encoding balance)))

(defn- outcome-side-entry
  [market side]
  (let [side-coin (:coin side)
        encoding (token-encoding side-coin)]
    (when (and (= :outcome (:market-type market))
               (number? encoding))
      (merge side
             {:market-key (:key market)
              :market-title (or (:title market)
                                (:symbol market)
                                side-coin)
              :market-symbol (:symbol market)
              :quote (or (:quote market) "USDH")
              :token-name (token-name-from-encoding encoding)}))))

(defn- assoc-side-lookup
  [lookup side-entry]
  (cond-> lookup
    (seq (:coin side-entry))
    (assoc (:coin side-entry) side-entry)

    (seq (:token-name side-entry))
    (assoc (:token-name side-entry) side-entry)))

(defn- outcome-side-lookup
  [market-by-key]
  (reduce (fn [lookup market]
            (if (= :outcome (:market-type market))
              (reduce (fn [lookup* side]
                        (if-let [side-entry (outcome-side-entry market side)]
                          (assoc-side-lookup lookup* side-entry)
                          lookup*))
                      lookup
                      (or (:outcome-sides market) []))
              lookup))
          {}
          (vals (or market-by-key {}))))

(defn- balance-entry-notional
  [balance]
  (some (fn [k]
          (let [parsed (parse-optional-num (get balance k))]
            (when (number? parsed)
              parsed)))
        outcome-entry-notional-keys))

(defn- fallback-side-name
  [side-coin]
  (str "Side " (or (token-encoding side-coin) side-coin)))

(defn- outcome-row
  [side-by-coin balance]
  (let [side-coin (balance-side-coin balance)
        side-entry (or (get side-by-coin side-coin)
                       (get side-by-coin (:coin balance)))
        size (parse-num (:total balance))
        entry-notional (or (balance-entry-notional balance) 0)
        mark-price (or (parse-optional-num (:mark side-entry)) 0)
        position-value (* size mark-price)
        entry-price (if (zero? size)
                      0
                      (/ entry-notional size))
        pnl-value (- position-value entry-notional)
        roe-pct (when (pos? entry-notional)
                  (* 100 (/ pnl-value entry-notional)))]
    {:key (str "outcome-" side-coin)
     :title (or (:market-title side-entry) side-coin)
     :market-key (:market-key side-entry)
     :raw-coin (:coin balance)
     :side-coin side-coin
     :side-name (or (:side-name side-entry)
                    (:sideName side-entry)
                    (fallback-side-name side-coin))
     :side-index (:side-index side-entry)
     :type-label "Outcome"
     :size size
     :position-value position-value
     :quote (or (:quote side-entry) "USDH")
     :entry-price entry-price
     :mark-price mark-price
     :pnl-value pnl-value
     :roe-pct roe-pct
     :entry-notional entry-notional}))

(defn build-outcome-rows
  [spot-data market-by-key]
  (let [side-by-coin (outcome-side-lookup market-by-key)]
    (->> (get-in spot-data [:clearinghouse-state :balances])
         (filter outcome-balance?)
         (map #(outcome-row side-by-coin %))
         vec)))
