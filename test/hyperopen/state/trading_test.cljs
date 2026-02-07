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

(deftest default-order-form-uses-limit-entry-mode-test
  (is (= :limit (:entry-mode (trading/default-order-form)))))

(deftest normalize-order-form-defaults-pro-order-type-dropdown-open-flag-to-false-test
  (let [without-flag (-> (trading/default-order-form)
                         (dissoc :pro-order-type-dropdown-open?))
        nil-flag (assoc (trading/default-order-form) :pro-order-type-dropdown-open? nil)
        normalized-without-flag (trading/normalize-order-form base-state without-flag)
        normalized-nil-flag (trading/normalize-order-form base-state nil-flag)]
    (is (= false (:pro-order-type-dropdown-open? normalized-without-flag)))
    (is (= false (:pro-order-type-dropdown-open? normalized-nil-flag)))))

(deftest normalize-order-form-keeps-entry-mode-and-type-consistent-test
  (let [market-form (trading/normalize-order-form base-state {:entry-mode :market
                                                               :type :limit
                                                               :size-percent 0
                                                               :ui-leverage 20})
        limit-form (trading/normalize-order-form base-state {:entry-mode :limit
                                                              :type :market
                                                              :size-percent 0
                                                              :ui-leverage 20})
        pro-form (trading/normalize-order-form base-state {:entry-mode :pro
                                                            :type :limit
                                                            :size-percent 0
                                                            :ui-leverage 20})]
    (is (= :market (:entry-mode market-form)))
    (is (= :market (:type market-form)))
    (is (= :limit (:entry-mode limit-form)))
    (is (= :limit (:type limit-form)))
    (is (= :pro (:entry-mode pro-form)))
    (is (= :stop-market (:type pro-form)))))

(deftest normalize-order-form-derives-entry-mode-from-type-when-entry-mode-is-missing-test
  (let [form-without-entry-mode (-> (trading/default-order-form)
                                    (dissoc :entry-mode)
                                    (assoc :type :take-limit))
        normalized (trading/normalize-order-form base-state form-without-entry-mode)]
    (is (= :pro (:entry-mode normalized)))
    (is (= :take-limit (:type normalized)))
    (is (= normalized
           (trading/normalize-order-form base-state form-without-entry-mode)))))

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

(deftest market-slippage-estimate-uses-orderbook-depth-for-buy-side-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC"}
               :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}
                                          {:px "100" :sz "2"}]
                                   :asks [{:px "102" :sz "1"}
                                          {:price "101" :size "2"}
                                          {:p "103" :s "5"}]}}
               :webdata2 {}}
        form (assoc (trading/default-order-form)
                    :type :market
                    :side :buy
                    :size "2.5")
        summary (trading/order-summary state form)]
    (is (approx= 0.6965174129353312 (:slippage-est summary)))))

(deftest market-slippage-estimate-uses-orderbook-depth-for-sell-side-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC"}
               :orderbooks {"BTC" {:bids [{:px "99" :sz "1"}
                                          {:price "100" :size "1.5"}
                                          {:p "98.5" :s "4"}]
                                   :asks [{:px "101" :sz "3"}]}}
               :webdata2 {}}
        form (assoc (trading/default-order-form)
                    :type :market
                    :side :sell
                    :size "1.8")
        summary (trading/order-summary state form)]
    (is (approx= 0.6633499170812619 (:slippage-est summary)))))

(deftest market-slippage-estimate-increases-with-larger-size-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC"}
               :orderbooks {"BTC" {:bids [{:px "99" :sz "10"}]
                                   :asks [{:px "101" :sz "1"}
                                          {:px "103" :sz "10"}]}}
               :webdata2 {}}
        small-form (assoc (trading/default-order-form)
                          :type :market
                          :side :buy
                          :size "0.5")
        large-form (assoc (trading/default-order-form)
                          :type :market
                          :side :buy
                          :size "2")
        small-est (:slippage-est (trading/order-summary state small-form))
        large-est (:slippage-est (trading/order-summary state large-form))]
    (is (number? small-est))
    (is (number? large-est))
    (is (< small-est large-est))))

(deftest market-slippage-estimate-is-nil-when-visible-depth-is-insufficient-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC"}
               :orderbooks {"BTC" {:bids [{:px "99" :sz "3"}]
                                   :asks [{:px "101" :sz "1"}
                                          {:px "102" :sz "1"}]}}
               :webdata2 {}}
        form (assoc (trading/default-order-form)
                    :type :market
                    :side :buy
                    :size "5")
        summary (trading/order-summary state form)]
    (is (nil? (:slippage-est summary)))))

(deftest market-slippage-estimate-is-nil-when-midpoint-is-unavailable-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC"}
               :orderbooks {"BTC" {:bids []
                                   :asks [{:px "101" :sz "5"}]}}
               :webdata2 {}}
        form (assoc (trading/default-order-form)
                    :type :market
                    :side :buy
                    :size "1")
        summary (trading/order-summary state form)]
    (is (nil? (:slippage-est summary)))))

(deftest market-slippage-estimate-is-deterministic-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC"}
               :orderbooks {"BTC" {:bids [{:px "99" :sz "5"}]
                                   :asks [{:px "101" :sz "5"}
                                          {:px "102" :sz "5"}]}}
               :webdata2 {}}
        form (assoc (trading/default-order-form)
                    :type :market
                    :side :buy
                    :size "3")
        first-summary (trading/order-summary state form)
        second-summary (trading/order-summary state form)]
    (is (= first-summary second-summary))))

(deftest order-summary-uses-canonical-size-for-order-value-test
  (let [form (assoc (trading/default-order-form)
                    :type :limit
                    :size "2"
                    :size-display "1"
                    :price "100"
                    :ui-leverage 20)
        summary (trading/order-summary base-state form)]
    (is (= 200 (:order-value summary)))
    (is (= 10 (:margin-required summary)))))

(deftest base-size-string-truncates-to-market-step-test
  (let [state {:active-market {:szDecimals 5}}]
    (is (= "0.00002" (trading/base-size-string state 0.0000285)))
    (is (nil? (trading/base-size-string state 0.0000099)))))

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

(deftest mid-price-string-requires-true-midpoint-test
  (testing "returns midpoint string when bid and ask are present"
    (let [form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (= "100" (trading/mid-price-string base-state form)))))

  (testing "returns nil when midpoint is unavailable"
    (let [state (assoc base-state :orderbooks {})
          form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (nil? (trading/mid-price-string state form)))))

  (testing "midpoint uses max bid and min ask regardless of level ordering"
    (let [state {:active-asset "BTC"
                 :active-market {:coin "BTC" :mark 70000}
                 :orderbooks {"BTC" {:bids [{:px "70120"} {:px "70150"} {:px "70090"}]
                                     :asks [{:px "70240"} {:px "70160"} {:px "70210"}]}}
                 :webdata2 {}}
          form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (= "70155" (trading/mid-price-string state form)))
      (is (= 70160 (trading/reference-price state (assoc form :side :buy))))
      (is (= 70150 (trading/reference-price state (assoc form :side :sell)))))))
