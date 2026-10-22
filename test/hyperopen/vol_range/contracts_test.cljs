(ns hyperopen.vol-range.contracts-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.contracts :as c]))

(deftest scope-key-test
  (is (= "hyperliquid-mainnet|0xabc|107"
         (c/scope-key {:network :hyperliquid-mainnet :account-address "0xabc" :spot-pair-index 107})))
  (is (= "hyperliquid-mainnet|none|107"
         (c/scope-key {:network :hyperliquid-mainnet :account-address nil :spot-pair-index 107}))))

(deftest status-for-errors-test
  (is (= :ready (c/status-for-errors [])))
  (is (= :invalid (c/status-for-errors [(c/error :input/no-side-enabled {})])))
  (is (= :unavailable (c/status-for-errors [(c/error :input/no-side-enabled {})
                                            (c/error :data/bbo-unavailable {})])))
  (is (= :invalid (c/status-for-errors [(c/error :data/volatility-zero {})]))))

(deftest registry-test
  (doseq [code [:input/no-side-enabled :input/quantity-required :input/quantity-not-lot-multiple
                :input/invalid-number :input/count-out-of-range :input/distance-out-of-range
                :input/start-above-end :input/skew-out-of-range :input/max-debit-required
                :input/maximum-required :input/inventory-limits-order :input/manual-vol-out-of-range
                :input/custom-anchor-invalid :input/tilt-strength-out-of-range
                :data/volatility-unavailable :data/volatility-zero :data/anchor-unavailable
                :data/bbo-unavailable :data/bbo-crossed :data/balances-unavailable
                :data/fee-quote-unavailable :data/market-metadata-unavailable
                :capability/market-unsupported :capability/account-mode-unsupported
                :capability/preview-disabled :capability/account-required
                :plan/nonpositive-price :plan/zero-size-rung :plan/duplicate-price
                :plan/post-only-cross :plan/self-cross :plan/distance-guard :plan/min-notional
                :plan/max-inventory-breach :plan/protected-core-breach :plan/usdc-cap-exceeded
                :plan/insufficient-usdc :plan/insufficient-base :plan/corrective-direction
                :plan/unbounded-commitment
                :capability/fee-settlement-unverified :capability/raw-account-mode-unverified
                :hint/nothing-to-sell :data/volatility-stale]]
    (let [entry (get c/error-registry code)]
      (is (some? (:category entry)) (str code))
      (is (contains? #{:unavailable :invalid} (:status entry)) (str code))
      (is (string? (:message entry)) (str code))))
  (is (= "14 completed daily candles required"
         (:message (c/error :data/volatility-unavailable {}))))
  (is (= :unavailable (get-in c/error-registry [:data/volatility-unavailable :status])))
  (is (= :invalid (get-in c/error-registry [:data/volatility-zero :status])))
  (is (= "custom" (:message (c/error :input/invalid-number {:message "custom"}))))
  (is (= :capability/fee-settlement-unverified
         (:code (c/warning :capability/fee-settlement-unverified {:a 1})))))

(def sample-leg
  {:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "7"
   :notional "534.8" :actual-sigma "-0.75" :raw-price "76.4"})

(deftest spec-test
  (is (c/leg-valid? sample-leg))
  (is (c/leg-valid? (assoc sample-leg :actual-sigma nil)))
  (is (not (c/leg-valid? (assoc sample-leg :extra 1))))
  (is (not (c/leg-valid? (assoc sample-leg :price "1e5"))))
  (is (not (c/leg-valid? (assoc sample-leg :side :hold))))
  (let [plan {:status :ready :plan-id "p" :scope "s" :input-fingerprint "f" :model {}
              :legs [sample-leg] :totals {} :inventory-outcomes {} :fee-reserves {}
              :source-versions {} :remediations [] :errors [] :warnings []}]
    (is (c/plan-result-valid? plan))
    (is (not (c/plan-result-valid? (assoc plan :status :bogus))))
    (is (not (c/plan-result-valid? (assoc plan :extra 1)))))
  (is (c/remediation-valid? {:id :r :label "x" :patch [[[:buy :distance :start] "0.5"]]}))
  (is (not (c/remediation-valid? {:id :r :label "x" :patch [["bad" 1]]})))
  (is (c/error-valid? (c/error :plan/min-notional {})))
  (is (c/market-snapshot-valid?
       {:status :ready :errors [] :coin "@107" :market-type :spot :base-symbol "HYPE"
        :quote-symbol "USDC" :sz-decimals 2 :bbo {:bid "79" :ask "80" :observed-at-ms 1}
        :anchors {:vwap-24h {:price "80" :observed-at-ms 1} :utc-open nil :market-price nil}
        :auto-volatility nil :observed-at-ms 1}))
  (is (c/account-snapshot-valid?
       {:status :ready :errors [] :account-address "0x1" :account-mode :classic :read-only? false
        :base {:total "1" :hold "0" :available "1"} :quote {:total "1" :hold "0" :available "1"}
        :open-orders [{:oid 1 :side :buy :price "1" :remaining "1"}] :observed-at-ms 1}))
  (is (c/fee-capability-valid?
       {:status :ready :maker-fraction "0.0004" :buy-fee-token :quote :sell-fee-token :quote
        :settlement-verified? true :source :test :loaded-at-ms 1})))

(deftest policy-and-capability-test
  (testing "every decimal string in product-policy parses"
    (doseq [v (tree-seq map? vals c/product-policy)
            :when (string? v)]
      (is (some? (precision/parse-decimal v)) v)))
  (is (= 12 (get-in c/product-policy [:rung-count :max])))
  (is (= "10.50" (:min-leg-notional-usdc c/product-policy)))
  (is (= 900000 (:daily-fetch-ttl-ms c/product-policy)))
  (is (true? (c/preview-enabled?)))
  (is (false? (:execution? c/capability-matrix)))
  (is (= [1 1 "parkinson-v1" 1]
         [c/model-version c/precision-version c/estimator-version c/draft-schema-version])))
