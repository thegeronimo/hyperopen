(ns hyperopen.views.account-equity.venues
  "The perps account broken down by venue.

   A standard account holds margin per perp dex: the main Perps balance does
   not back a HIP-3 market, and one dex's balance does not back another's.
   The classic panel's Perps row is the sum across all of them, which hides
   where the margin actually is. This namespace derives one row per venue
   from the same clearinghouse records the sum is built from.

   Accounts with one pooled balance (unified, portfolio margin, the
   deprecated dex abstraction) get no breakdown: there is nothing to tell
   apart. Neither does an account whose only venue is the main one."
  (:require [clojure.string :as str]
            [hyperopen.views.account-equity.format :refer [parse-num safe-div]]
            [hyperopen.views.account-equity.pricing :as pricing]))

(def main-venue-id "main")

(defn pooled-collateral?
  [state]
  (or (= :unified (get-in state [:account :mode]))
      (= "dexabstraction"
         (some-> (get-in state [:account :abstraction-raw]) str str/trim str/lower-case))))

(defn active-venue-id
  "The venue of the market on screen: a HIP-3 coin's dex (`xyz:NVDA`), the
   main venue for a plain perp coin, nil for spot and outcome markets."
  [active-asset]
  (let [coin (some-> active-asset str str/trim)
        i (if coin (.indexOf coin ":") -1)]
    (cond
      (str/blank? coin) nil
      (pos? i) (subs coin 0 i)
      (or (str/includes? coin "/") (str/starts-with? coin "@") (str/starts-with? coin "#")) nil
      :else main-venue-id)))

(defn- unrealized-pnl
  "Nil when the snapshot does not list its positions: an unread book is not
   a flat one."
  [state]
  (let [positions (:assetPositions state)]
    (when (sequential? positions)
      (reduce + 0 (keep #(parse-num (get-in % [:position :unrealizedPnl])) positions)))))

(defn- venue-row
  [{:keys [dex state] :as record} balance-row-by-token market-by-key active-id]
  (let [aggregate (pricing/aggregate-clearinghouse-usd [record] balance-row-by-token market-by-key)
        price (pricing/token-price-usd balance-row-by-token market-by-key (:quote-token record))
        usd (fn [value] (when (and (number? value) (number? price)) (* value price)))
        value (:account-value aggregate)
        pnl (usd (unrealized-pnl state))
        cross-value (:cross-account-value aggregate)
        id (or dex main-venue-id)]
    {:id id
     :dex dex
     :label (or dex "Main")
     :value value
     :funded? (and (number? value) (pos? value))
     :this-market? (= id active-id)
     :balance (when (and (number? value) (number? pnl)) (- value pnl))
     :unrealized-pnl pnl
     :cross-margin-ratio (safe-div (:maintenance-margin aggregate) cross-value)
     :maintenance-margin (:maintenance-margin aggregate)
     :cross-account-leverage (safe-div (:cross-total-ntl-pos aggregate) cross-value)
     :available (usd (parse-num (:withdrawable state)))}))

(defn perp-venues
  "`{:rows :hidden-count :funded-count}` for an account that holds margin per
   venue, else nil. Rows are the main venue, every venue with a balance and
   the venue of the market on screen, in that order of appearance; the rest
   are only counted."
  [state records balance-row-by-token market-by-key]
  (when-not (pooled-collateral? state)
    (let [active-id (active-venue-id (:active-asset state))
          rows (mapv #(venue-row % balance-row-by-token market-by-key active-id) records)
          shown (filterv #(or (nil? (:dex %)) (:funded? %) (:this-market? %)) rows)]
      (when (some :dex shown)
        {:rows shown
         :hidden-count (- (count rows) (count shown))
         :funded-count (count (filter :funded? shown))}))))
