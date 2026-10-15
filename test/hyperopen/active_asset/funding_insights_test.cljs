(ns hyperopen.active-asset.funding-insights-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.active-asset.funding-insights :as insights]))

(defn- approx=
  [a b]
  (< (js/Math.abs (- a b)) 1e-9))

(deftest payer-verdict-follows-rate-sign-and-side-test
  (is (= :pays (insights/payer-verdict :long 0.001)))
  (is (= :receives (insights/payer-verdict :short 0.001)))
  (is (= :receives (insights/payer-verdict :long -0.001)))
  (is (= :pays (insights/payer-verdict :short -0.001)))
  (is (= :neutral (insights/payer-verdict :long 0)))
  (is (nil? (insights/payer-verdict :flat 0.001)))
  (is (nil? (insights/payer-verdict :long nil))))

(deftest margin-model-uses-venue-margin-for-live-and-order-leverage-otherwise-test
  (is (= {:leverage 10 :margin 100}
         (insights/margin-model {:live? true
                                 :position {:leverage {:value 10} :marginUsed "100"}
                                 :position-value 1000})))
  (is (= {:leverage 5 :margin 200}
         (insights/margin-model {:live? false
                                 :position-value 1000
                                 :order-leverage 5})))
  (is (nil? (insights/margin-model {:live? false :position-value 1000 :order-leverage nil})))
  (is (nil? (insights/margin-model {:live? false :position-value 1000 :order-leverage 0}))))

(deftest share-of-margin-and-since-open-test
  (is (approx= 0.3 (insights/share-of-margin-pct -0.3 100)))
  (is (nil? (insights/share-of-margin-pct -0.3 0)))
  (testing "HL cumFunding is a cost; display is received funding"
    (is (= -1.84 (insights/since-open-funding {:cumFunding {:sinceOpen "1.84"}})))
    (is (= 2.5 (insights/since-open-funding {:cumFunding {:sinceOpen "-2.5"}})))
    (is (nil? (insights/since-open-funding {})))))

(deftest breakeven-describes-cover-and-cushion-moves-test
  (let [long-pays (insights/breakeven :long 0.00125 100)
        short-pays (insights/breakeven :short -0.00125 100)
        short-receives (insights/breakeven :short 0.00125 100)]
    (is (= :cover (:kind long-pays)))
    (is (= :rise (:move long-pays)))
    (is (approx= 0.9 (:pct long-pays)))
    (is (approx= 100.9 (:target-price long-pays)))
    (is (= [:cover :fall] [(:kind short-pays) (:move short-pays)]))
    (is (= [:cushion :rise] [(:kind short-receives) (:move short-receives)]))
    (is (nil? (insights/breakeven :long 0 100)))
    (is (nil? (insights/breakeven :long 0.001 nil)))))

(deftest rate-breakdown-only-for-core-perps-test
  (let [{:keys [total-apy baseline-apy premium-apy]} (insights/rate-breakdown "BTC" 0.00125)]
    (is (approx= 10.95 total-apy))
    (is (approx= 10.95 baseline-apy))
    (is (zero? premium-apy)))
  (is (approx= 8.76 (:premium-apy (insights/rate-breakdown "ETH" 0.00225))))
  (is (nil? (insights/rate-breakdown "xyz:GOLD" 0.00125)))
  (is (nil? (insights/rate-breakdown "BTC" nil))))

(deftest history-chart-model-orders-oldest-first-and-summarizes-test
  (let [model (insights/history-chart-model [{:day-index 2 :day-start-ms 2000 :daily-rate -0.0001}
                                             {:day-index 1 :day-start-ms 1000 :daily-rate 0.0003}
                                             {:day-index 3 :day-start-ms 3000 :daily-rate nil}])]
    (is (= [1 2 3] (mapv :day-index (:points model))))
    (is (approx= 10.95 (get-in model [:points 0 :apy])))
    (is (true? (get-in model [:points 2 :undefined?])))
    (is (= 3 (:day-count model)))
    (is (= 2 (:days-with-data model)))
    (is (= 1 (:positive-days model)))
    (is (approx= -3.65 (:min-apy model)))
    (is (approx= 3.65 (:mean-apy model))))
  (is (nil? (insights/history-chart-model []))))

(deftest lag-strip-keeps-supported-lags-and-reads-verdict-from-one-day-lag-test
  (let [series [{:lag-days 1 :value 0.1 :pair-count 29}
                {:lag-days 3 :value -0.5 :pair-count 27}
                {:lag-days 7 :value 0.9 :pair-count 2}
                {:lag-days 14 :value nil :pair-count 16 :undefined? true}
                {:lag-days 5 :value 0.9 :pair-count 25}]
        {:keys [verdict rows]} (insights/lag-strip series)]
    (is (= :barely verdict))
    (is (= [1 3] (mapv :lag-days rows)))
    (is (= ["Tomorrow" "3 days out"] (mapv :label rows)))
    (is (false? (:significant? (first rows))))
    (is (true? (:significant? (second rows))))
    (is (approx= (/ 1.96 (js/Math.sqrt 29)) (:noise (first rows))))
    (is (= :strongly (:verdict (insights/lag-strip [{:lag-days 1 :value 0.8 :pair-count 29}]))))
    (is (= :somewhat (:verdict (insights/lag-strip [{:lag-days 1 :value 0.45 :pair-count 29}]))))
    (is (nil? (insights/lag-strip [])))))
