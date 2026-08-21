(ns hyperopen.views.account-equity-unified-metrics-test
  "Unified-account risk metrics, pinned to the venue's own definitions.

   Hyperliquid defines Unified Account Leverage as \"Total Cross Positions
   Value / Total Collateral Balance\" and renders Perps Maintenance Margin
   straight from `crossMaintenanceMarginUsed`. Isolated positions are outside
   both figures, and the collateral balance spans every token that
   collateralises a perp dex anywhere on the venue -- not only the dexes this
   wallet trades on.

   Because a cross-only lens reports 0.00x and $0.00 for a book that is
   entirely isolated, the panel discloses the excluded isolated notional
   alongside those figures rather than folding it into them."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-equity-view :as view]))

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- approx=
  [expected actual]
  (and (number? actual)
       (< (js/Math.abs (- expected actual)) 1e-9)))

(defn- spot-state
  [balances]
  {:meta {:tokens (vec (map-indexed (fn [idx {:keys [coin]}]
                                      {:index idx :name coin :weiDecimals 6})
                                    balances))
          :universe []}
   :clearinghouse-state {:balances (vec (map-indexed (fn [idx {:keys [coin total]}]
                                                       {:coin coin
                                                        :token idx
                                                        :hold "0.0"
                                                        :total total
                                                        :entryNtl "0"})
                                                     balances))}})

(defn- unified-state
  [{:keys [usdc-total balances total-ntl-pos cross-ntl-pos cross-maintenance
           positions market-by-key]}]
  {:account {:mode :unified}
   :webdata2 {:clearinghouseState {:marginSummary {:accountValue "0.0"
                                                   :totalNtlPos total-ntl-pos
                                                   :totalRawUsd "0.0"
                                                   :totalMarginUsed "0.0"}
                                   :crossMarginSummary {:accountValue "0.0"
                                                        :totalNtlPos cross-ntl-pos
                                                        :totalRawUsd "0.0"
                                                        :totalMarginUsed "0.0"}
                                   :crossMaintenanceMarginUsed cross-maintenance
                                   :assetPositions positions}
              :spotAssetCtxs []}
   :spot (spot-state (or balances [{:coin "USDC" :total usdc-total}]))
   :asset-selector {:market-by-key (or market-by-key {})}
   :perp-dex-clearinghouse {}})

(defn- isolated-position
  [coin position-value margin-used max-leverage]
  (let [position (cond-> {:coin coin
                          :marginUsed margin-used
                          :leverage {:type "isolated" :value 3}
                          :positionValue position-value
                          :unrealizedPnl "0.0"}
                   max-leverage (assoc :maxLeverage max-leverage))]
    {:type "oneWay" :position position}))

(defn- metrics-for
  [state]
  (view/reset-account-equity-metrics-cache!)
  (let [result (view/account-equity-metrics state)]
    (view/reset-account-equity-metrics-cache!)
    result))

;; $1,700 of isolated notional against $1,000 of collateral, with nothing cross.
(def ^:private all-isolated-state
  (unified-state {:usdc-total "1000.0"
                  :total-ntl-pos "1700.0"
                  :cross-ntl-pos "0.0"
                  :cross-maintenance "0.0"
                  :positions [(isolated-position "BTC" "700.0" "233.0" 40)
                              (isolated-position "PUMP" "1000.0" "500.0" 10)]}))

(deftest unified-leverage-is-cross-only-test
  (let [metrics (metrics-for all-isolated-state)]
    ;; The venue divides cross notional by collateral; this book has no cross
    ;; notional at all, so 0.00x is the venue's answer and therefore ours.
    (is (approx= 0 (:unified-account-leverage metrics)))))

(deftest unified-leverage-excludes-the-isolated-leg-test
  (let [metrics (metrics-for
                 (unified-state {:usdc-total "1000.0"
                                 :total-ntl-pos "1700.0"
                                 :cross-ntl-pos "500.0"
                                 :cross-maintenance "0.0"
                                 :positions [(isolated-position "PUMP" "1200.0" "600.0" 10)]}))]
    ;; 500 cross over 1000 collateral. The $1,200 isolated leg is present in
    ;; `marginSummary.totalNtlPos` and must not reach the numerator: reading
    ;; the whole book here is what put us 35% above the venue.
    (is (approx= 0.5 (:unified-account-leverage metrics)))
    (is (approx= 1200.0 (:isolated-notional metrics)))))

(deftest unified-leverage-denominator-counts-untraded-collateral-tokens-test
  (let [metrics (metrics-for
                 (unified-state {:balances [{:coin "USDC" :total "1000.0"}
                                            {:coin "USDH" :total "200.0"}]
                                 :total-ntl-pos "600.0"
                                 :cross-ntl-pos "600.0"
                                 :cross-maintenance "0.0"
                                 :positions []
                                 ;; A dex settling in USDH that this wallet has
                                 ;; never traded on, and so has no clearinghouse
                                 ;; snapshot for. The venue still counts the
                                 ;; USDH holding as collateral.
                                 :market-by-key {"perp:flx:GOLD" {:market-type :perp
                                                                  :dex "flx"
                                                                  :coin "flx:GOLD"
                                                                  :base "GOLD"
                                                                  :quote "USDH"}}}))]
    ;; 600 / (1000 USDC + 200 USDH), not 600 / 1000.
    (is (approx= 0.5 (:unified-account-leverage metrics)))))

(deftest unified-maintenance-margin-is-cross-only-test
  (let [metrics (metrics-for all-isolated-state)]
    ;; The venue renders `crossMaintenanceMarginUsed` unmodified. Deriving an
    ;; isolated contribution from `positionValue / (2 * maxLeverage)` put ~30%
    ;; more on screen than the venue reports.
    (is (approx= 0 (:maintenance-margin metrics)))))

(deftest unified-ratio-is-nil-when-every-position-is-isolated-test
  (let [metrics (metrics-for all-isolated-state)]
    ;; Isolated positions liquidate individually, so a portfolio-liquidation
    ;; ratio has nothing to say here; "--" beats a 0% that reads as no risk.
    ;; This is the one cell where we diverge from the venue, which shows 0.00%.
    (is (nil? (:unified-account-ratio metrics)))))

(deftest unified-all-isolated-panel-discloses-excluded-notional-test
  (let [view-node (view/account-equity-view all-isolated-state)
        texts (set (collect-strings view-node))]
    ;; Cross-only figures, matching the venue exactly...
    (is (contains? texts "0.00x"))
    (is (contains? texts "$0.00"))
    ;; ...and the disclosure that stops them reading as "no exposure".
    (is (contains? texts "Excludes $1,700.00 isolated"))
    (is (contains? texts "--"))))

(deftest unified-panel-omits-the-disclosure-without-isolated-positions-test
  (let [view-node (view/account-equity-view
                   (unified-state {:usdc-total "1000.0"
                                   :total-ntl-pos "500.0"
                                   :cross-ntl-pos "500.0"
                                   :cross-maintenance "25.0"
                                   :positions []}))
        texts (collect-strings view-node)]
    ;; Nothing is excluded, so the note must not say "Excludes $0.00 isolated".
    (is (not (some #(re-find #"^Excludes " %) texts)))))

(deftest unified-flat-account-still-reports-zero-ratio-test
  (let [metrics (metrics-for
                 (unified-state {:usdc-total "1000.0"
                                 :total-ntl-pos "0.0"
                                 :cross-ntl-pos "0.0"
                                 :cross-maintenance "0.0"
                                 :positions []}))]
    ;; No positions at all: 0% is the truth, not a placeholder.
    (is (approx= 0 (:unified-account-ratio metrics)))
    (is (approx= 0 (:unified-account-leverage metrics)))
    (is (nil? (:isolated-notional metrics)))))

(deftest unified-cross-book-ratio-unchanged-test
  (let [metrics (metrics-for
                 (unified-state {:usdc-total "1000.0"
                                 :total-ntl-pos "500.0"
                                 :cross-ntl-pos "500.0"
                                 :cross-maintenance "25.0"
                                 :positions []}))]
    ;; Cross exposure present: maintenance over collateral net of isolated margin.
    (is (approx= 0.025 (:unified-account-ratio metrics)))
    (is (approx= 25.0 (:maintenance-margin metrics)))
    (is (approx= 0.5 (:unified-account-leverage metrics)))))

(deftest classic-account-metrics-keep-cross-only-shape-test
  (let [metrics (metrics-for
                 (-> (unified-state {:usdc-total "1000.0"
                                     :total-ntl-pos "1700.0"
                                     :cross-ntl-pos "0.0"
                                     :cross-maintenance "0.0"
                                     :positions [(isolated-position "BTC" "700.0" "233.0" 40)]})
                     (assoc :account {:mode :classic})
                     (assoc-in [:webdata2 :clearinghouseState :crossMarginSummary :accountValue]
                               "1000.0")))]
    ;; Classic accounts keep the cross-only reading they always had: the
    ;; isolated book is deliberately absent from both figures.
    (is (approx= 0 (:cross-account-leverage metrics)))
    (is (approx= 0 (:maintenance-margin metrics)))
    ;; The disclosure is a unified-panel affordance only.
    (is (nil? (:isolated-notional metrics)))))
