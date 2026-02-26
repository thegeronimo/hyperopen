(ns hyperopen.runtime.validation-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.runtime.validation :as validation]
            [hyperopen.system :as system]))

(deftest wrap-action-handler-rejects-invalid-payload-arity-test
  (with-redefs [validation/validation-enabled? (constantly true)]
    (let [wrapped (validation/wrap-action-handler :actions/select-asset
                                                  (fn [_state _coin]
                                                    []))]
      (is (thrown-with-msg?
           js/Error
           #"action payload"
           (wrapped {}))))))

(deftest wrap-action-handler-rejects-invalid-emitted-effect-test
  (with-redefs [validation/validation-enabled? (constantly true)]
    (let [wrapped (validation/wrap-action-handler :actions/test-invalid-effect
                                                  (fn [_state]
                                                    [[:effects/save "not-a-path" 42]]))]
      (is (thrown-with-msg?
           js/Error
           #"effect request"
           (wrapped {}))))))

(deftest wrap-action-handler-allows-projection-before-heavy-for-covered-action-test
  (with-redefs [validation/validation-enabled? (constantly true)]
    (let [wrapped (validation/wrap-action-handler :actions/select-chart-timeframe
                                                  (fn [_state timeframe]
                                                    [[:effects/save [:chart-options :selected-timeframe] timeframe]
                                                     [:effects/fetch-candle-snapshot :interval timeframe]]))]
      (is (= [[:effects/save [:chart-options :selected-timeframe] :5m]
              [:effects/fetch-candle-snapshot :interval :5m]]
             (wrapped {} :5m))))))

(deftest wrap-action-handler-rejects-heavy-before-projection-for-covered-action-test
  (with-redefs [validation/validation-enabled? (constantly true)]
    (let [wrapped (validation/wrap-action-handler :actions/select-chart-timeframe
                                                  (fn [_state timeframe]
                                                    [[:effects/fetch-candle-snapshot :interval timeframe]
                                                     [:effects/save [:chart-options :selected-timeframe] timeframe]]))]
      (is (thrown-with-msg?
           js/Error
           #"rule=heavy-before-projection-phase"
           (wrapped {} :5m))))))

(deftest wrap-action-handler-enforces-projection-before-heavy-for-portfolio-benchmark-selection-test
  (with-redefs [validation/validation-enabled? (constantly true)]
    (let [wrapped (validation/wrap-action-handler :actions/select-portfolio-returns-benchmark
                                                  (fn [_state coin]
                                                    [[:effects/save [:portfolio-ui :returns-benchmark-coin] coin]
                                                     [:effects/fetch-candle-snapshot :coin coin :interval :1h :bars 800]]))]
      (is (= [[:effects/save [:portfolio-ui :returns-benchmark-coin] "SPY"]
              [:effects/fetch-candle-snapshot :coin "SPY" :interval :1h :bars 800]]
             (wrapped {} "SPY"))))))

(deftest wrap-action-handler-rejects-heavy-before-projection-for-portfolio-benchmark-selection-test
  (with-redefs [validation/validation-enabled? (constantly true)]
    (let [wrapped (validation/wrap-action-handler :actions/select-portfolio-returns-benchmark
                                                  (fn [_state coin]
                                                    [[:effects/fetch-candle-snapshot :coin coin :interval :1h :bars 800]
                                                     [:effects/save [:portfolio-ui :returns-benchmark-coin] coin]]))]
      (is (thrown-with-msg?
           js/Error
           #"rule=heavy-before-projection-phase"
           (wrapped {} "SPY"))))))

(deftest wrap-action-handler-rejects-duplicate-heavy-effects-for-covered-action-test
  (with-redefs [validation/validation-enabled? (constantly true)]
    (let [wrapped (validation/wrap-action-handler :actions/select-orderbook-price-aggregation
                                                  (fn [_state mode]
                                                    [[:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                                     [:effects/subscribe-orderbook "BTC"]
                                                     [:effects/subscribe-orderbook (str mode)]]))]
      (is (thrown-with-msg?
           js/Error
           #"rule=duplicate-heavy-effect"
           (wrapped {} "BTC"))))))

(deftest wrap-action-handler-does-not-apply-order-contract-to-uncovered-actions-test
  (with-redefs [validation/validation-enabled? (constantly true)]
    (let [wrapped (validation/wrap-action-handler :actions/refresh-asset-markets
                                                  (fn [_state]
                                                    [[:effects/fetch-asset-selector-markets]
                                                     [:effects/save [:asset-selector :visible-dropdown] nil]]))]
      (is (= [[:effects/fetch-asset-selector-markets]
              [:effects/save [:asset-selector :visible-dropdown] nil]]
             (wrapped {}))))))

(deftest wrap-effect-handler-rejects-invalid-save-args-test
  (with-redefs [validation/validation-enabled? (constantly true)]
    (let [wrapped (validation/wrap-effect-handler :effects/save
                                                  (fn [_ctx _store _path _value]
                                                    nil))]
      (is (thrown-with-msg?
           js/Error
           #"effect request"
           (wrapped nil (atom {}) "not-a-path" 1))))))

(deftest install-store-state-validation-rejects-invalid-transition-test
  (with-redefs [validation/validation-enabled? (constantly true)]
    (let [store (atom (system/default-store-state))]
      (validation/install-store-state-validation! store)
      (is (thrown-with-msg?
           js/Error
           #"app state"
           (swap! store assoc :active-market {:coin "BTC"}))))))
