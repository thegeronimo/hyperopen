(ns hyperopen.portfolio.optimizer.domain.returns-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.returns :as returns]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(deftest historical-mean-annualizes-return-series-and-adds-funding-carry-test
  (let [result (returns/estimate-expected-returns
                {:return-model {:kind :historical-mean}
                 :periods-per-year 10
                 :history {:return-series-by-instrument {"perp:BTC" [0.01 0.03]
                                                         "spot:PURR" [-0.02 0.04]}
                           :funding-by-instrument {"perp:BTC" {:annualized-carry 0.12
                                                               :source :market-funding-history}
                                                   "spot:PURR" {:source :not-applicable}}}})]
    (is (= :historical-mean (:model result)))
    (is (= ["perp:BTC" "spot:PURR"] (:instrument-ids result)))
    (is (near? 0.32 (get-in result [:expected-returns-by-instrument "perp:BTC"])))
    (is (near? 0.1 (get-in result [:expected-returns-by-instrument "spot:PURR"])))
    (is (= {:return-component 0.2
            :funding-component 0.12
            :funding-source :market-funding-history}
           (get-in result [:decomposition-by-instrument "perp:BTC"])))))

(deftest ew-mean-weights-recent-observations-more-heavily-test
  (let [result (returns/estimate-expected-returns
                {:return-model {:kind :ew-mean
                                :alpha 0.5}
                 :periods-per-year 10
                 :history {:return-series-by-instrument {"perp:BTC" [0.0 0.1]}
                           :funding-by-instrument {"perp:BTC" {:annualized-carry 0}}}})]
    (is (= :ew-mean (:model result)))
    (is (near? (/ 2 3) (get-in result [:expected-returns-by-instrument "perp:BTC"])))))

(deftest expected-returns-report-missing-series-as-warning-test
  (let [result (returns/estimate-expected-returns
                {:return-model {:kind :historical-mean}
                 :history {:return-series-by-instrument {"perp:BTC" []}}})]
    (is (= {} (:expected-returns-by-instrument result)))
    (is (= [{:code :missing-return-series
             :instrument-id "perp:BTC"}]
           (:warnings result)))))

(deftest expected-returns-warn-on-low-observation-samples-test
  (let [result (returns/estimate-expected-returns
                {:return-model {:kind :historical-mean}
                 :history {:return-series-by-instrument {"perp:BTC" [0.01 0.02]}}})]
    (is (= [{:code :low-return-sample-size
             :instrument-id "perp:BTC"
             :observations 2
             :recommended-observations 30}]
           (:warnings result)))))
