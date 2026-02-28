(ns hyperopen.asset-selector.market-live-projection
  (:require [hyperopen.asset-selector.markets :as markets]
            [hyperopen.utils.formatting :as fmt]))

(defn- parse-number [value]
  (cond
    (number? value) (when-not (js/isNaN value) value)
    (string? value) (let [num (js/parseFloat value)]
                      (when-not (js/isNaN num) num))
    :else nil))

(defn- patch-market-from-active-asset-ctx
  [market ctx]
  (let [mark-raw (:markPx ctx)
        prev-day-raw (:prevDayPx ctx)
        mark (parse-number mark-raw)
        prev-day (parse-number prev-day-raw)
        volume24h (parse-number (:dayNtlVlm ctx))
        funding (parse-number (:funding ctx))
        open-interest-raw (parse-number (:openInterest ctx))
        change24h (when (and (number? mark) (number? prev-day))
                    (- mark prev-day))
        change24h-pct (when (and (number? change24h)
                                 (number? prev-day)
                                 (not= prev-day 0))
                        (* 100 (/ change24h prev-day)))
        perp? (= :perp (:market-type market))
        open-interest-usd (when (and perp?
                                     (number? open-interest-raw)
                                     (number? mark))
                            (fmt/calculate-open-interest-usd open-interest-raw mark))]
    (cond-> market
      (contains? ctx :markPx)
      (assoc :markRaw mark-raw)

      (number? mark)
      (assoc :mark mark)

      (contains? ctx :prevDayPx)
      (assoc :prevDayRaw prev-day-raw)

      (number? volume24h)
      (assoc :volume24h volume24h)

      (and (number? change24h)
           (number? change24h-pct))
      (assoc :change24h change24h
             :change24hPct change24h-pct)

      (and perp? (number? funding))
      (assoc :fundingRate funding)

      (and perp? (number? open-interest-usd))
      (assoc :openInterest open-interest-usd)

      (not perp?)
      (assoc :openInterest nil
             :fundingRate nil))))

(defn- patch-selector-market-by-key
  [market-by-key market-keys ctx]
  (reduce (fn [acc market-key]
            (if-let [market (get acc market-key)]
              (assoc acc market-key (patch-market-from-active-asset-ctx market ctx))
              acc))
          market-by-key
          market-keys))

(defn- patch-selector-markets
  [selector-markets patched-market-by-key]
  (mapv (fn [market]
          (or (get patched-market-by-key (:key market))
              market))
        (if (sequential? selector-markets)
          (vec selector-markets)
          [])))

(defn apply-active-asset-ctx-update
  [state coin ctx]
  (if-not (and (map? state)
               (string? coin)
               (map? ctx))
    state
    (let [market-by-key (get-in state [:asset-selector :market-by-key] {})
          candidate-keys (markets/candidate-market-keys coin)
          selector-market-keys (filterv #(contains? market-by-key %) candidate-keys)]
      (if (empty? selector-market-keys)
        state
        (let [patched-market-by-key (patch-selector-market-by-key market-by-key
                                                                  selector-market-keys
                                                                  ctx)
              patched-markets (patch-selector-markets (get-in state [:asset-selector :markets] [])
                                                      patched-market-by-key)]
          (-> state
              (assoc-in [:asset-selector :market-by-key] patched-market-by-key)
              (assoc-in [:asset-selector :markets] patched-markets)))))))
