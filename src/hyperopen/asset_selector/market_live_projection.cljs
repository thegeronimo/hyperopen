(ns hyperopen.asset-selector.market-live-projection
  (:require [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.domain.display :as outcome-display]
            [hyperopen.utils.formatting :as fmt]))

(defn- parse-number [value]
  (cond
    (number? value) (when-not (js/isNaN value) value)
    (string? value) (let [num (js/parseFloat value)]
                      (when-not (js/isNaN num) num))
    :else nil))

(defn- patch-outcome-side-from-active-asset-ctx
  [coin
   {:keys [mark-raw mark mid-present? mid-raw prev-day-raw volume24h base-volume24h change24h change24h-pct
           circulating-supply] :as _ctx-values}
   side]
  (if-not (= coin (:coin side))
    side
    (cond-> side
      (some? mark-raw)
      (assoc :markRaw mark-raw)

      ;; A null midPx means the book lost a side: the chance becomes "No market".
      mid-present?
      (assoc :midRaw mid-raw :mid (parse-number mid-raw))

      (number? mark)
      (assoc :mark mark)

      (some? prev-day-raw)
      (assoc :prevDayRaw prev-day-raw)

      (number? volume24h)
      (assoc :volume24h volume24h)

      (number? base-volume24h)
      (assoc :baseVolume24h base-volume24h)

      ;; A previous-day price of 0 is the never-traded placeholder: no change.
      (and (number? change24h)
           (number? change24h-pct))
      (assoc :change24h change24h
             :change24hPct change24h-pct)

      (and (some? prev-day-raw)
           (not (number? change24h-pct)))
      (assoc :change24h nil
             :change24hPct nil)

      (number? circulating-supply)
      (assoc :circulatingSupply circulating-supply))))

(defn- patch-outcome-sides-from-active-asset-ctx
  [market coin ctx-values]
  (mapv #(patch-outcome-side-from-active-asset-ctx coin ctx-values %)
        (or (:outcome-sides market) [])))

(defn- option-primary-side
  [option]
  (or (some (fn [side]
              (when (zero? (or (:side-index side) 0))
                side))
            (:sides option))
      (first (:sides option))))

(defn- patch-question-option-from-active-asset-ctx
  [coin ctx-values option]
  (let [sides (mapv #(patch-outcome-side-from-active-asset-ctx coin ctx-values %)
                    (or (:sides option) []))
        primary-side (option-primary-side (assoc option :sides sides))
        primary-side-updated? (= coin (:coin primary-side))]
    (cond-> (assoc option :sides sides)
      primary-side-updated? (assoc :mark (:mark primary-side)
                                   :volume24h (:volume24h primary-side)
                                   :openInterest (:circulatingSupply primary-side)))))

(defn- recompute-outcome-totals
  "Event totals are sums over contracts, recomputed after every tick: one
   side's tick must never overwrite the whole event's volume or interest."
  [market fallback-open-interest]
  (let [{:keys [shares24h open-interest]} (outcome-display/event-totals market)
        ;; A market without side data (a cold-start cache entry) has nothing
        ;; to sum; its own contract's supply is the best it has.
        open-interest* (or open-interest
                           (when (and (number? fallback-open-interest) (pos? fallback-open-interest))
                             fallback-open-interest))]
    (cond-> market
      (number? shares24h) (assoc :volume24h shares24h)
      (number? open-interest*) (assoc :openInterest open-interest*))))

(defn- patch-question-options-from-active-asset-ctx
  [market coin ctx-values]
  (mapv #(patch-question-option-from-active-asset-ctx coin ctx-values %)
        (or (:question-options market) [])))

(defn- patch-market-from-active-asset-ctx
  [market coin ctx]
  (let [mark-raw (:markPx ctx)
        prev-day-raw (:prevDayPx ctx)
        mark (parse-number mark-raw)
        prev-day (parse-number prev-day-raw)
        volume24h (parse-number (:dayNtlVlm ctx))
        base-volume24h (parse-number (:dayBaseVlm ctx))
        funding (parse-number (:funding ctx))
        open-interest-raw (parse-number (:openInterest ctx))
        circulating-supply (parse-number (:circulatingSupply ctx))
        change24h (when (and (number? mark) (number? prev-day)
                             ;; Outcomes: a 0 previous-day price is a placeholder.
                             (or (not= :outcome (:market-type market)) (pos? prev-day)))
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
        primary-outcome-side? (and outcome?
                                   (= coin (:coin market)))
        update-market-price? (or (not outcome?)
                                 primary-outcome-side?)
        ctx-values {:mark-raw mark-raw
                    :mark mark
                    :mid-present? (contains? ctx :midPx)
                    :mid-raw (:midPx ctx)
                    :prev-day-raw prev-day-raw
                    :volume24h volume24h
                    :base-volume24h base-volume24h
                    :change24h change24h
                    :change24h-pct change24h-pct
                    :circulating-supply circulating-supply}
        patched-question-options (when (seq (:question-options market))
                                   (patch-question-options-from-active-asset-ctx market coin ctx-values))
        question-options-changed? (and (some? patched-question-options)
                                       (not= patched-question-options (:question-options market)))]
    (cond-> market
      (and update-market-price?
           (contains? ctx :markPx))
      (assoc :markRaw mark-raw)

      (and update-market-price?
           (number? mark))
      (assoc :mark mark)

      (and update-market-price?
           (contains? ctx :prevDayPx))
      (assoc :prevDayRaw prev-day-raw)

      (and update-market-price?
           (not outcome?)
           (number? volume24h))
      (assoc :volume24h volume24h)

      (and update-market-price?
           (number? change24h)
           (number? change24h-pct))
      (assoc :change24h change24h
             :change24hPct change24h-pct)

      (and primary-outcome-side?
           (contains? ctx :prevDayPx)
           (not (number? change24h-pct)))
      (assoc :change24h nil
             :change24hPct nil)

      (and perp? (number? funding))
      (assoc :fundingRate funding)

      (and perp? (number? open-interest-usd))
      (assoc :openInterest open-interest-usd)

      outcome?
      (assoc :fundingRate nil)

      outcome?
      (assoc :outcome-sides (patch-outcome-sides-from-active-asset-ctx market coin ctx-values))

      question-options-changed?
      (assoc :question-options patched-question-options
             :outcome-summary (markets/outcome-option-summary patched-question-options))

      outcome?
      (recompute-outcome-totals (when (and primary-outcome-side?
                                           (empty? (:outcome-sides market))
                                           (empty? (:question-options market)))
                                  circulating-supply))

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
  [market-by-key market-keys patch-market]
  (reduce (fn [{:keys [patched-market-by-key changed-markets]} market-key]
            (if-let [market (get patched-market-by-key market-key)]
              (let [patched-market (patch-market market)]
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

(defn- apply-market-update
  "Patch every selector market `coin` belongs to with `patch-market`, keeping
   the list, the by-key map and the index in step."
  [state coin patch-market]
  (if-not (and (map? state) (string? coin))
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
                                                                                             patch-market)
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

(defn apply-active-asset-ctx-update
  [state coin ctx]
  (if-not (map? ctx)
    state
    (apply-market-update state coin #(patch-market-from-active-asset-ctx % coin ctx))))

(defn- mirror-top
  "The other side of one contract trades the same book mirrored: its bid is
   1 - ask and its ask is 1 - bid."
  [{:keys [bid ask]}]
  {:bid (when (number? ask) (- 1 ask))
   :ask (when (number? bid) (- 1 bid))})

(defn- patch-sides-top-of-book
  "Write `top` onto the side of `sides` whose coin is `coin`, and its mirror
   onto the other side of the same two-sided contract, so both sides read one
   book."
  [sides coin top]
  (let [sides* (vec (or sides []))
        index (first (keep-indexed (fn [i side] (when (= coin (:coin side)) i)) sides*))]
    (if (nil? index)
      sides*
      (let [mirrored (mirror-top top)]
        (vec (map-indexed (fn [i side]
                            (cond
                              (= i index) (assoc side :bid (:bid top) :ask (:ask top))
                              (= 2 (count sides*)) (assoc side :bid (:bid mirrored) :ask (:ask mirrored))
                              :else side))
                          sides*))))))

(defn- patch-market-top-of-book
  [market coin top]
  (if-not (= :outcome (:market-type market))
    market
    (cond-> (update market :outcome-sides patch-sides-top-of-book coin top)
      (seq (:question-options market))
      (update :question-options
              (fn [options]
                (mapv (fn [option]
                        (update option :sides patch-sides-top-of-book coin top))
                      options))))))

(defn- patch-active-market-top-of-book
  "The ticket and header read `:active-market`; keep its sides on the same
   book as the catalog so every surface quotes one source."
  [state coin top]
  (let [active (:active-market state)]
    (if (and (map? active) (= :outcome (:market-type active)))
      (let [patched (patch-market-top-of-book active coin top)]
        (if (= patched active) state (assoc state :active-market patched)))
      state)))

(defn apply-outcome-top-of-book-update
  "Write an outcome side's best bid and ask (`{:bid :ask}`, nil for an empty
   side) onto every selector market that holds it and onto the active market,
   so list rows, header and ticket read the same book through the one chance
   rule. The sibling side gets the mirror (bid = 1 - ask, ask = 1 - bid): one
   contract trades one book. Pure; fed by the `bbo` subscription runtime
   (`hyperopen.websocket.outcome-bbo`)."
  [state coin {:keys [bid ask]}]
  (let [price (fn [value]
                (let [number (parse-number value)]
                  (when (and (number? number) (pos? number)) number)))
        top {:bid (price bid) :ask (price ask)}]
    (-> state
        (apply-market-update coin #(patch-market-top-of-book % coin top))
        (patch-active-market-top-of-book coin top))))
