(ns hyperopen.views.portfolio.vm.utils-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.vm.utils :as utils]))

(deftest portfolio-vm-utils-normalize-numeric-inputs-test
  (is (= 12.5 (utils/optional-number " 12.5 ")))
  (is (nil? (utils/optional-number "12px")))
  (is (= 9 (utils/number-or-zero 9)))
  (is (= 0 (utils/number-or-zero "9")))
  (is (true? (utils/finite-number? 3)))
  (is (false? (utils/finite-number? js/Infinity))))

(deftest canonical-summary-key-covers-portfolio-scope-aliases-test
  (is (= :perps (utils/canonical-summary-key "perp")))
  (is (= :perps (utils/canonical-summary-key :perps)))
  (is (= :spot (utils/canonical-summary-key "spot")))
  (is (= :vaults (utils/canonical-summary-key :vault)))
  (is (= :all (utils/canonical-summary-key "anything-else")))
  (is (nil? (utils/canonical-summary-key nil))))

(deftest max-drawdown-ratio-clamps-positive-and-reads-nested-metrics-test
  (is (= -0.42 (utils/max-drawdown-ratio {:maxDrawdown -0.42})))
  (is (= -0.15 (utils/max-drawdown-ratio {:metrics {:maxDrawdown -0.15}})))
  (is (= 0 (utils/max-drawdown-ratio {:maxDrawdown 0.2})))
  (is (nil? (utils/max-drawdown-ratio {:maxDrawdown "0.2"})))
  (is (nil? (utils/max-drawdown-ratio nil))))

(deftest metric-token-changes-with-account-address-market-data-and-request-data-test
  (let [base-state {:wallet {:address "0x1111111111111111111111111111111111111111"}
                    :market-data {:account-info {:margin-summary {:accountValue "100"}}}}
        request-data {:range :month}
        base-token (utils/metric-token base-state request-data)]
    (is (= base-token
           (utils/metric-token base-state request-data)))
    (is (not= base-token
              (utils/metric-token
               (assoc-in base-state [:wallet :address] "0x2222222222222222222222222222222222222222")
               request-data)))
    (is (not= base-token
              (utils/metric-token
               (assoc-in base-state [:market-data :account-info :margin-summary :accountValue] "200")
               request-data)))
    (is (not= base-token
              (utils/metric-token base-state {:range :week})))))
