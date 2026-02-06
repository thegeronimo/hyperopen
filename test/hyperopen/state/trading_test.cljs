(ns hyperopen.state.trading-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.state.trading :as trading]))

(def base-state
  {:active-asset "BTC"
   :active-market {:coin "BTC"
                   :mark 100
                   :maxLeverage 40
                   :szDecimals 4}
   :orderbooks {"BTC" {:bids [{:px "99"}]
                       :asks [{:px "101"}]}}
   :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                   :totalMarginUsed "250"}
                                   :assetPositions [{:position {:coin "BTC"
                                                                :szi "0.5"
                                                                :liquidationPx "80"}}]}}})

(defn- approx= [a b]
  (<= (js/Math.abs (- a b)) 0.000001))

(deftest validate-order-form-test
  (testing "size is required"
    (is (seq (trading/validate-order-form (trading/default-order-form)))))

  (testing "limit order requires price"
    (let [form (assoc (trading/default-order-form)
                      :size "1"
                      :type :limit
                      :price "")]
      (is (seq (trading/validate-order-form form)))))

  (testing "market order does not require price"
    (let [form (assoc (trading/default-order-form)
                      :size "1"
                      :type :market
                      :price "")]
      (is (empty? (trading/validate-order-form form)))))

  (testing "twap requires minutes"
    (let [form (assoc (trading/default-order-form)
                      :size "1"
                      :type :twap
                      :twap {:minutes 0 :randomize true})]
      (is (seq (trading/validate-order-form form))))))

(deftest order-entry-mode-and-pro-type-normalization-test
  (is (= :market (trading/entry-mode-for-type :market)))
  (is (= :limit (trading/entry-mode-for-type :limit)))
  (is (= :pro (trading/entry-mode-for-type :stop-market)))
  (is (= :pro (trading/entry-mode-for-type :scale)))
  (is (= :stop-market (trading/normalize-pro-order-type :market)))
  (is (= :take-limit (trading/normalize-pro-order-type :take-limit))))

(deftest size-percent-conversion-roundtrip-test
  (let [form (assoc (trading/default-order-form)
                    :type :limit
                    :price "100"
                    :ui-leverage 20)
        from-percent (trading/apply-size-percent base-state form 50)
        derived-size (trading/parse-num (:size from-percent))
        roundtrip (trading/sync-size-percent-from-size base-state from-percent)]
    (is (= 50 (:size-percent from-percent)))
    (is (= 75 derived-size))
    (is (<= (js/Math.abs (- 50 (:size-percent roundtrip))) 0.01))))

(deftest size-percent-zero-clears-size-test
  (let [form (assoc (trading/default-order-form)
                    :type :limit
                    :price "100"
                    :size "5"
                    :size-percent 30)
        reset-form (trading/apply-size-percent base-state form 0)]
    (is (= 0 (:size-percent reset-form)))
    (is (= "" (:size reset-form)))))

(deftest order-summary-and-fallbacks-test
  (testing "summary uses available account data"
    (let [form (assoc (trading/default-order-form)
                      :type :limit
                      :size "2"
                      :price "100"
                      :ui-leverage 20)
          summary (trading/order-summary base-state form)]
      (is (= 750 (:available-to-trade summary)))
      (is (= 200 (:order-value summary)))
      (is (= 10 (:margin-required summary)))
      (is (= 80 (:liquidation-price summary)))
      (is (= trading/default-fees (:fees summary)))))

  (testing "summary falls back deterministically when data is missing"
    (let [state {:active-asset "BTC"
                 :active-market {}
                 :orderbooks {}
                 :webdata2 {}}
          summary (trading/order-summary state (trading/default-order-form))]
      (is (= 0 (:available-to-trade summary)))
      (is (nil? (:order-value summary)))
      (is (nil? (:margin-required summary)))
      (is (nil? (:liquidation-price summary)))
      (is (= trading/default-max-slippage-pct (:slippage-max summary))))))

(deftest available-to-trade-withdrawable-precedence-and-dex-isolation-test
  (testing "withdrawable is the canonical available balance when present"
    (let [state (assoc-in base-state [:webdata2 :clearinghouseState :withdrawable] "170.58")]
      (is (approx= 170.58 (trading/available-to-trade state)))
      (is (= (trading/available-to-trade state)
             (trading/available-to-trade state)))))

  (testing "falls back to account value minus margin used when withdrawable is missing"
    (is (approx= 750 (trading/available-to-trade base-state))))

  (testing "dex-scoped market reads only dex-scoped clearinghouse data"
    (let [state {:active-market {:coin "BTC" :dex "dex-a"}
                 :perp-dex-clearinghouse {"dex-a" {:withdrawable "170.58"}}
                 :webdata2 {:clearinghouseState {:withdrawable "975.90"}}}]
      (is (approx= 170.58 (trading/available-to-trade state)))))

  (testing "missing dex-scoped clearinghouse does not fall back to default webdata2"
    (let [state {:active-market {:coin "BTC" :dex "dex-missing"}
                 :perp-dex-clearinghouse {}
                 :webdata2 {:clearinghouseState {:withdrawable "975.90"}}}]
      (is (= 0 (trading/available-to-trade state))))))

(deftest mid-price-summary-determinism-and-fallback-test
  (testing "mid source is used when bid/ask are present"
    (let [form (assoc (trading/default-order-form) :type :limit :price "100")
          summary (trading/mid-price-summary base-state form)]
      (is (= :mid (:source summary)))
      (is (approx= 100 (:mid-price summary)))
      (is (= summary (trading/mid-price-summary base-state form)))))

  (testing "reference source is used when mid cannot be computed"
    (let [state (assoc base-state :orderbooks {})
          form (assoc (trading/default-order-form) :type :limit :price "")
          summary (trading/mid-price-summary state form)]
      (is (= :reference (:source summary)))
      (is (approx= 100 (:mid-price summary)))))

  (testing "none source is used when no price data exists"
    (let [state {:active-asset "BTC"
                 :active-market {}
                 :orderbooks {}
                 :webdata2 {}}
          summary (trading/mid-price-summary state (trading/default-order-form))]
      (is (= :none (:source summary)))
      (is (nil? (:mid-price summary))))))

(deftest effective-limit-price-and-string-fallback-test
  (testing "effective limit price prefers mid when bid/ask are present"
    (let [form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (approx= 100 (trading/effective-limit-price base-state form)))))

  (testing "effective limit price falls back to reference when mid is unavailable"
    (let [state (assoc base-state :orderbooks {})
          form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (approx= 100 (trading/effective-limit-price state form)))))

  (testing "effective limit price returns nil when no usable sources exist"
    (let [state {:active-asset "BTC"
                 :active-market {}
                 :orderbooks {}
                 :webdata2 {}}
          form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (nil? (trading/effective-limit-price state form)))
      (is (nil? (trading/effective-limit-price-string state form)))))

  (testing "effective limit price string formatting is deterministic"
    (let [state (assoc-in base-state [:orderbooks "BTC" :asks 0 :px] "101.25")
          form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (= "100.125" (trading/effective-limit-price-string state form)))
      (is (= (trading/effective-limit-price-string state form)
             (trading/effective-limit-price-string state form))))))
