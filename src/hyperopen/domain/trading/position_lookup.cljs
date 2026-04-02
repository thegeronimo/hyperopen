(ns hyperopen.domain.trading.position-lookup
  (:require [hyperopen.domain.market.instrument :as instrument]
            [clojure.string :as str]))

(defn- normalized-coin-token
  [value]
  (some-> value
          str
          str/trim
          str/upper-case))

(defn- position-entry
  [entry]
  (or (:position entry) entry))

(defn- position-coin-token
  [entry]
  (some-> (or (:coin (position-entry entry))
              (:coin entry))
          normalized-coin-token))

(defn- position-base-token
  [entry]
  (some-> (or (:coin (position-entry entry))
              (:coin entry))
          instrument/base-symbol-from-value
          normalized-coin-token))

(defn- active-coin-candidates
  [active-asset market]
  (->> [active-asset
        (:coin market)
        (:symbol market)]
       (keep normalized-coin-token)
       distinct
       vec))

(defn- active-base-candidates
  [active-asset market]
  (->> [active-asset
        (:coin market)
        (:symbol market)]
       (keep instrument/base-symbol-from-value)
       (keep normalized-coin-token)
       distinct
       vec))

(defn position-matches-active-asset?
  [active-asset market entry]
  (let [coin-token (position-coin-token entry)
        base-token (position-base-token entry)
        coin-candidates (active-coin-candidates active-asset market)
        base-candidates (active-base-candidates active-asset market)]
    (or (and coin-token
             (some #{coin-token} coin-candidates))
        (and base-token
             (some #{base-token} base-candidates)))))

(defn position-for-market
  [context]
  (let [active-asset (:active-asset context)
        market (or (:market context) {})
        clearinghouse (or (:clearinghouse context) {})
        positions (or (:assetPositions clearinghouse)
                      (get-in clearinghouse [:clearinghouseState :assetPositions]))]
    (some (fn [entry]
            (let [position (position-entry entry)]
              (when (and (map? position)
                         (position-matches-active-asset? active-asset market position))
                position)))
          positions)))
