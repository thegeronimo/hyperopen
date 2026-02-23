(ns hyperopen.state.trading.market-summary-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.domain.trading.fees :as trading-fees]
            [hyperopen.state.trading.fee-context :as fee-context-selector]
            [hyperopen.state.trading :as trading]
            [hyperopen.state.trading.test-support :as support]))

(def base-state support/base-state)

(defn- approx= [a b]
  (support/approx= a b))

(def ^:private default-fee-quote
  {:effective trading/default-fees
   :baseline nil})

(deftest select-fee-context-normalizes-fee-inputs-test
  (testing "selector normalizes market metadata and state payloads"
    (let [state {:active-market {:market-type "perp"
                                 :dex " dex-a "
                                 :growthMode "enabled"
                                 :quote "usdh"}
                 :portfolio {:user-fees {:userCrossRate 0.00045}}
                 :perp-dex-fee-config-by-name {"dex-a" {:deployer-fee-scale "0.5"}}}
          fee-context (fee-context-selector/select-fee-context state)]
      (is (= :perp (:market-type fee-context)))
      (is (= "dex-a" (:dex fee-context)))
      (is (true? (:growth-mode? fee-context)))
      (is (true? (:special-quote-fee-adjustment? fee-context)))
      (is (= 0.5 (:deployer-fee-scale fee-context)))
      (is (= {:userCrossRate 0.00045} (:user-fees fee-context)))))

  (testing "explicit market flags override inferred defaults"
    (let [state {:active-market {:market-type :perp
                                 :dex "dex-a"
                                 :growthMode "enabled"
                                 :growth-mode? false
                                 :quote "USDH"
                                 :special-quote-fee-adjustment? false}
                 :portfolio {:user-fees {:userCrossRate 0.00045}}
                 :perp-dex-fee-config-by-name {"dex-a" {:deployer-fee-scale 0.4}}}
          fee-context (fee-context-selector/select-fee-context state)]
      (is (false? (:growth-mode? fee-context)))
      (is (false? (:special-quote-fee-adjustment? fee-context))))))

(deftest domain-order-summary-consumes-explicit-fee-context-test
  (let [context {:active-asset "BTC"
                 :market {:coin "BTC"
                          :mark 100
                          :market-type :perp
                          :growth-mode? false}
                 :orderbook {:bids [{:px "99" :sz "1"}]
                             :asks [{:px "101" :sz "1"}]}
                 :clearinghouse {:marginSummary {:accountValue "1000"
                                                 :totalMarginUsed "0"}
                                 :assetPositions []}}
        fee-context {:market-type :spot
                     :stable-pair? true
                     :growth-mode? false
                     :dex nil
                     :deployer-fee-scale nil
                     :special-quote-fee-adjustment? false
                     :user-fees {:userSpotCrossRate 0.0003
                                 :userSpotAddRate 0.00012
                                 :activeReferralDiscount 0.1
                                 :activeStakingDiscount {:discount 0.25}}}
        fee-params {:market-type (:market-type fee-context)
                    :stable-pair? (:stable-pair? fee-context)
                    :deployer-fee-scale (:deployer-fee-scale fee-context)
                    :growth-mode? (:growth-mode? fee-context)
                    :extra-adjustment? (:special-quote-fee-adjustment? fee-context)}
        expected-fees (or (trading-fees/quote-fees (:user-fees fee-context) fee-params)
                          (trading-fees/default-fee-quote))
        form (assoc (trading/default-order-form)
                    :type :limit
                    :side :buy
                    :size "2"
                    :price "100")
        summary (trading-domain/order-summary context form fee-context)]
    (is (= expected-fees (:fees summary)))
    (is (not= default-fee-quote (:fees summary)))))

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
      (is (= default-fee-quote (:fees summary)))))

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
      (is (= default-fee-quote (:fees summary)))
      (is (= trading/default-max-slippage-pct (:slippage-max summary))))))

(deftest order-summary-computes-perp-fees-from-user-fees-test
  (let [state (-> base-state
                  (assoc :portfolio {:user-fees {:userCrossRate 0.00045
                                                 :userAddRate 0.00015
                                                 :activeReferralDiscount 0.1
                                                 :activeStakingDiscount {:discount 0.25}}})
                  (assoc :perp-dex-fee-config-by-name {"dex-a" {:deployer-fee-scale 0.5}})
                  (assoc :active-market {:coin "BTC"
                                         :mark 100
                                         :quote "USDC"
                                         :market-type :perp
                                         :dex "dex-a"
                                         :growth-mode? false
                                         :maxLeverage 40
                                         :szDecimals 4}))
        form (assoc (trading/default-order-form)
                    :type :limit
                    :size "2"
                    :price "100")
        summary (trading/order-summary state form)
        fees (:fees summary)]
    (is (approx= 0.06075 (get-in fees [:effective :taker])))
    (is (approx= 0.02025 (get-in fees [:effective :maker])))
    (is (nil? (:baseline fees)))))

(deftest order-summary-computes-spot-stable-pair-baseline-fees-test
  (let [state (-> base-state
                  (assoc :portfolio {:user-fees {:userSpotCrossRate 0.0003
                                                 :userSpotAddRate 0.00012
                                                 :activeReferralDiscount 0.1
                                                 :activeStakingDiscount {:discount 0.25}}})
                  (assoc :active-market {:coin "USDT/USDC"
                                         :mark 1
                                         :quote "USDC"
                                         :market-type :spot
                                         :stable-pair? true
                                         :maxLeverage nil
                                         :szDecimals 4}))
        form (assoc (trading/default-order-form)
                    :type :limit
                    :size "2"
                    :price "1")
        summary (trading/order-summary state form)
        fees (:fees summary)]
    (is (approx= 0.0054 (get-in fees [:effective :taker])))
    (is (approx= 0.00216 (get-in fees [:effective :maker])))
    (is (approx= 0.04 (get-in fees [:baseline :taker])))
    (is (approx= 0.016 (get-in fees [:baseline :maker])))))

(deftest order-summary-projects-liquidation-price-for-flat-position-test
  (let [state {:active-asset "SOL"
               :active-market {:coin "SOL"
                               :mark 100
                               :maxLeverage 50
                               :szDecimals 4}
               :orderbooks {}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "100"
                                                               :totalMarginUsed "0"}
                                               :assetPositions []}}}
        long-form (assoc (trading/default-order-form)
                         :type :limit
                         :side :buy
                         :size "2"
                         :price "100")
        short-form (assoc long-form :side :sell)
        long-summary (trading/order-summary state long-form)
        short-summary (trading/order-summary state short-form)]
    (is (approx= 52 (:liquidation-price long-summary)))
    (is (approx= 148 (:liquidation-price short-summary)))))

(deftest order-summary-prefers-position-liquidation-over-projected-liquidation-test
  (let [form (assoc (trading/default-order-form)
                    :type :limit
                    :side :buy
                    :size "2"
                    :price "100")
        summary (trading/order-summary base-state form)]
    (is (= 80 (:liquidation-price summary)))))

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
  (testing "classic mode uses withdrawable as the canonical available balance when present"
    (let [state (assoc-in base-state [:webdata2 :clearinghouseState :withdrawable] "170.58")]
      (is (approx= 170.58 (trading/available-to-trade state)))
      (is (= (trading/available-to-trade state)
             (trading/available-to-trade state)))))

  (testing "falls back to account value minus margin used when withdrawable is missing"
    (is (approx= 750 (trading/available-to-trade base-state))))

  (testing "unified mode uses spot USDC available balance when present"
    (let [state (-> base-state
                    (assoc :account {:mode :unified})
                    (assoc-in [:spot :clearinghouse-state :balances]
                              [{:coin "USDC"
                                :total "204.41936500"
                                :hold "3.03000000"}])
                    (assoc-in [:webdata2 :clearinghouseState :withdrawable] "0.03"))]
      (is (approx= 201.389365 (trading/available-to-trade state)))))

  (testing "unified mode falls back when spot USDC balance is unavailable"
    (let [state (-> base-state
                    (assoc :account {:mode :unified})
                    (assoc-in [:webdata2 :clearinghouseState :withdrawable] "170.58"))]
      (is (approx= 170.58 (trading/available-to-trade state)))))

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
