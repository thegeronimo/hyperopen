(ns hyperopen.views.account-equity-unified-metrics-test
  "Unified-account risk metrics when the book is isolated rather than cross.

   Hyperliquid reports isolated positions inside `marginSummary` but leaves
   `crossMarginSummary.totalNtlPos` and `crossMaintenanceMarginUsed` at zero.
   Reading only the cross fields showed 0.00x leverage, 0.00% ratio and $0.00
   maintenance margin for an account carrying a seven-figure isolated book."
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
  [usdc-total]
  {:meta {:tokens [{:index 0 :name "USDC" :weiDecimals 6}]
          :universe []}
   :clearinghouse-state {:balances [{:coin "USDC"
                                     :token 0
                                     :hold "0.0"
                                     :total usdc-total
                                     :entryNtl "0"}]}})

(defn- unified-state
  [{:keys [usdc-total total-ntl-pos cross-ntl-pos cross-maintenance positions]}]
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
   :spot (spot-state usdc-total)
   :asset-selector {:market-by-key {}}
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

(deftest unified-leverage-counts-isolated-positions-test
  (let [metrics (metrics-for all-isolated-state)]
    ;; 1700 / 1000, not the 0.00x a cross-only numerator produced.
    (is (approx= 1.7 (:unified-account-leverage metrics)))))

(deftest unified-maintenance-margin-counts-isolated-positions-test
  (let [metrics (metrics-for all-isolated-state)]
    ;; Rate is 1 / (2 * maxLeverage): 700/80 + 1000/20.
    (is (approx= 58.75 (:maintenance-margin metrics)))))

(deftest unified-ratio-is-nil-when-every-position-is-isolated-test
  (let [metrics (metrics-for all-isolated-state)]
    ;; Isolated positions liquidate individually, so a portfolio-liquidation
    ;; ratio has nothing to say here; "--" beats a 0% that reads as no risk.
    (is (nil? (:unified-account-ratio metrics)))))

(deftest unified-all-isolated-panel-shows-no-fake-zeros-test
  (let [view-node (view/account-equity-view all-isolated-state)
        texts (set (collect-strings view-node))]
    (is (contains? texts "1.70x"))
    (is (contains? texts "$58.75"))
    (is (contains? texts "--"))
    (is (not (contains? texts "0.00x")))
    (is (not (contains? texts "0.00%")))))

(deftest unified-maintenance-margin-is-nil-when-max-leverage-missing-test
  (let [metrics (metrics-for
                 (unified-state {:usdc-total "1000.0"
                                 :total-ntl-pos "1700.0"
                                 :cross-ntl-pos "0.0"
                                 :cross-maintenance "0.0"
                                 :positions [(isolated-position "BTC" "700.0" "233.0" 40)
                                             (isolated-position "PUMP" "1000.0" "500.0" nil)]}))]
    ;; An under-count would understate risk, so the row reports nothing at all.
    (is (nil? (:maintenance-margin metrics)))
    ;; Leverage does not depend on the maintenance rate and still resolves.
    (is (approx= 1.7 (:unified-account-leverage metrics)))))

(deftest unified-flat-account-still-reports-zero-ratio-test
  (let [metrics (metrics-for
                 (unified-state {:usdc-total "1000.0"
                                 :total-ntl-pos "0.0"
                                 :cross-ntl-pos "0.0"
                                 :cross-maintenance "0.0"
                                 :positions []}))]
    ;; No positions at all: 0% is the truth, not a placeholder.
    (is (approx= 0 (:unified-account-ratio metrics)))
    (is (approx= 0 (:unified-account-leverage metrics)))))

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
    (is (approx= 0 (:maintenance-margin metrics)))))
