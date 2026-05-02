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
        circulating-supply (parse-number (:circulatingSupply ctx))
        change24h (when (and (number? mark) (number? prev-day))
                    (- mark prev-day))
        change24h-pct (when (and (number? change24h)
                                 (number? prev-day)
                                 (not= prev-day 0))
                        (* 100 (/ change24h prev-day)))
        perp? (= :perp (:market-type market))
        spot? (= :spot (:market-type market))
        outcome? (= :outcome (:market-type market))
        open-interest-usd (when (and perp?
                                     (number? open-interest-raw)
                                     (number? mark))
                            (fmt/calculate-open-interest-usd open-interest-raw mark))
        outcome-open-interest (or circulating-supply open-interest-raw)]
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

      (and outcome? (number? outcome-open-interest) (pos? outcome-open-interest))
      (assoc :openInterest outcome-open-interest)

      outcome?
      (assoc :fundingRate nil)

      spot?
      (assoc :openInterest nil
             :fundingRate nil))))

(defn- normalize-selector-markets
  [selector-markets]
  (if (sequential? selector-markets)
    (vec selector-markets)
    []))

(defn- build-market-index-by-key
  [selector-markets]
  (reduce-kv (fn [acc idx market]
               (if-let [market-key (:key market)]
                 (assoc acc market-key idx)
                 acc))
             {}
             selector-markets))

(defn- patch-selector-market-by-key
  [market-by-key market-keys ctx]
  (reduce (fn [{:keys [patched-market-by-key changed-markets]} market-key]
            (if-let [market (get patched-market-by-key market-key)]
              (let [patched-market (patch-market-from-active-asset-ctx market ctx)]
                (if (= market patched-market)
                  {:patched-market-by-key patched-market-by-key
                   :changed-markets changed-markets}
                  {:patched-market-by-key (assoc patched-market-by-key market-key patched-market)
                   :changed-markets (assoc changed-markets market-key patched-market)}))
              {:patched-market-by-key patched-market-by-key
               :changed-markets changed-markets}))
          {:patched-market-by-key market-by-key
           :changed-markets {}}
          market-keys))

(defn- valid-row-index?
  [idx market-count]
  (and (int? idx)
       (<= 0 idx)
       (< idx market-count)))

(defn- apply-indexed-market-patches
  [selector-markets market-index-by-key changed-markets]
  (reduce-kv
   (fn [{:keys [markets changed? stale?]} market-key patched-market]
     (let [idx (get market-index-by-key market-key)
           market-count (count markets)]
       (if-not (valid-row-index? idx market-count)
         {:markets markets
          :changed? changed?
          :stale? true}
         (let [current-market (nth markets idx nil)]
           (if (not= market-key (:key current-market))
             {:markets markets
              :changed? changed?
              :stale? true}
             (if (= current-market patched-market)
               {:markets markets
                :changed? changed?
                :stale? stale?}
               {:markets (assoc markets idx patched-market)
                :changed? true
                :stale? stale?}))))))
   {:markets selector-markets
    :changed? false
    :stale? false}
   changed-markets))

(defn- patch-selector-markets-by-index
  [selector-markets market-index-by-key changed-markets]
  (if (empty? changed-markets)
    {:patched-markets selector-markets
     :patched-market-index-by-key (if (map? market-index-by-key)
                                    market-index-by-key
                                    {})
     :changed? false}
    (let [market-index-by-key* (if (map? market-index-by-key)
                                 market-index-by-key
                                 {})
          first-pass (apply-indexed-market-patches selector-markets
                                                   market-index-by-key*
                                                   changed-markets)]
      (if (:stale? first-pass)
        (let [rebuilt-index (build-market-index-by-key selector-markets)
              second-pass (apply-indexed-market-patches selector-markets
                                                        rebuilt-index
                                                        changed-markets)]
          {:patched-markets (:markets second-pass)
           :patched-market-index-by-key rebuilt-index
           :changed? (:changed? second-pass)})
        {:patched-markets (:markets first-pass)
         :patched-market-index-by-key market-index-by-key*
         :changed? (:changed? first-pass)}))))

(defn apply-active-asset-ctx-update
  [state coin ctx]
  (if-not (and (map? state)
               (string? coin)
               (map? ctx))
    state
    (let [market-by-key (get-in state [:asset-selector :market-by-key] {})
          original-selector-markets (get-in state [:asset-selector :markets] [])
          selector-markets (normalize-selector-markets original-selector-markets)
          market-index-by-key (get-in state [:asset-selector :market-index-by-key] {})
          original-market-index-by-key market-index-by-key
          resolved-market-key (some-> (markets/resolve-market-by-coin market-by-key coin) :key)
          candidate-keys (cond-> (markets/candidate-market-keys coin)
                           (some? resolved-market-key) (conj resolved-market-key))
          selector-market-keys (filterv #(contains? market-by-key %) candidate-keys)]
      (if (empty? selector-market-keys)
        state
        (let [{:keys [patched-market-by-key changed-markets]} (patch-selector-market-by-key market-by-key
                                                                                             selector-market-keys
                                                                                             ctx)
              {:keys [patched-markets patched-market-index-by-key]} (patch-selector-markets-by-index selector-markets
                                                                                                      market-index-by-key
                                                                                                      changed-markets)
              market-by-key-changed? (not= patched-market-by-key market-by-key)
              selector-markets-changed? (not= patched-markets original-selector-markets)
              market-index-changed? (not= patched-market-index-by-key original-market-index-by-key)]
          (if-not (or market-by-key-changed?
                      selector-markets-changed?
                      market-index-changed?)
            state
            (cond-> state
              market-by-key-changed?
              (assoc-in [:asset-selector :market-by-key] patched-market-by-key)

              selector-markets-changed?
              (assoc-in [:asset-selector :markets] patched-markets)

              market-index-changed?
              (assoc-in [:asset-selector :market-index-by-key] patched-market-index-by-key))))))))
