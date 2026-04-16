(ns hyperopen.runtime.effect-order-contract-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.runtime.effect-order-contract :as contract]))

(deftest assert-action-effect-order-rejects-phase-order-regression-after-heavy-test
  (let [effects [[:effects/save [:portfolio-ui :returns-benchmark-coin] "SPY"]
                 [:effects/fetch-candle-snapshot :coin "SPY" :interval :1h :bars 800]
                 [:effects/local-storage-set "portfolio-returns-benchmark" "SPY"]]]
    (is (thrown-with-msg?
         js/Error
         #"rule=phase-order-regression"
         (contract/assert-action-effect-order!
          :actions/select-portfolio-returns-benchmark
          effects
          {:phase :test})))))

(deftest assert-action-effect-order-allows-duplicate-heavy-effects-when-policy-permits-test
  (let [effects [[:effects/save [:portfolio-ui :summary-time-range] :1w]
                 [:effects/fetch-candle-snapshot :interval :1h]
                 [:effects/fetch-candle-snapshot :interval :1h]]
        summary (contract/effect-order-summary
                 :actions/select-portfolio-summary-time-range
                 effects)]
    (is (= effects
           (contract/assert-action-effect-order!
            :actions/select-portfolio-summary-time-range
            effects
            {:phase :test})))
    (is (= [:effects/fetch-candle-snapshot]
           (:duplicate-heavy-effect-ids summary)))
    (is (true? (:phase-order-valid summary)))))

(deftest assert-action-effect-order-classifies-shareable-query-replacement-as-persistence-test
  (let [effects [[:effects/save [:portfolio-ui :chart-tab] :returns]
                 [:effects/replace-shareable-route-query]
                 [:effects/fetch-candle-snapshot :interval :1h]]
        summary (contract/effect-order-summary
                 :actions/select-portfolio-chart-tab
                 effects)]
    (is (= [:projection :persistence :heavy-io]
           (:phases summary)))
    (is (= effects
           (contract/assert-action-effect-order!
            :actions/select-portfolio-chart-tab
            effects
            {:phase :test})))))

(deftest effect-order-summary-can-report-projection-before-heavy-true-while-phase-order-invalid-test
  (let [effects [[:effects/save [:portfolio-ui :returns-benchmark-coin] "SPY"]
                 [:effects/fetch-candle-snapshot :coin "SPY" :interval :1h :bars 800]
                 [:effects/local-storage-set "portfolio-returns-benchmark" "SPY"]]
        summary (contract/effect-order-summary
                 :actions/select-portfolio-returns-benchmark
                 effects)]
    (testing "Projection still occurs before heavy work"
      (is (true? (:projection-before-heavy summary))))
    (testing "The later persistence effect still invalidates the tracked phase order"
      (is (false? (:phase-order-valid summary)))
      (is (= [:projection :heavy-io :persistence]
             (:phases summary))))
    (testing "No duplicate heavy effect is involved in this regression"
      (is (empty? (:duplicate-heavy-effect-ids summary))))))
