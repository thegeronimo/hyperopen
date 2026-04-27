(ns hyperopen.portfolio.optimizer.application.current-portfolio-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]))

(def ^:private owner-address
  "0x1111111111111111111111111111111111111111")

(def ^:private trader-address
  "0x2222222222222222222222222222222222222222")

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.000001))

(defn- exposure-by-id
  [snapshot instrument-id]
  (get (:by-instrument snapshot) instrument-id))

(deftest current-portfolio-snapshot-builds-unified-signed-exposures-test
  (let [state {:wallet {:address owner-address}
               :router {:path "/portfolio"}
               :account {:mode :unified}
               :webdata2 {:clearinghouseState
                          {:marginSummary {:accountValue "100000"
                                           :totalMarginUsed "12000"}
                           :assetPositions [{:position {:coin "BTC"
                                                        :szi "0.5"
                                                        :positionValue "25000"
                                                        :markPx "50000"
                                                        :leverage {:type "cross"
                                                                   :value "10"}}}
                                            {:position {:coin "ETH"
                                                        :szi "-2"
                                                        :markPx "3000"
                                                        :leverage {:type "isolated"
                                                                   :value "5"}}}]}}
               :perp-dex-clearinghouse {"dex-a" {:assetPositions
                                                 [{:position {:coin "SOL"
                                                              :szi "10"
                                                              :markPx "100"}}]}}
               :spot {:clearinghouse-state
                      {:balances [{:coin "USDC" :total "100000" :hold "0"}
                                  {:coin "PURR" :total "10" :hold "2"}]}}
               :asset-selector {:market-by-key {"spot:PURR/USDC" {:key "spot:PURR/USDC"
                                                                  :market-type :spot
                                                                  :coin "PURR/USDC"
                                                                  :symbol "PURR/USDC"
                                                                  :base "PURR"
                                                                  :quote "USDC"
                                                                  :mark "0.5"}}}}
        snapshot (current-portfolio/current-portfolio-snapshot state)
        btc (exposure-by-id snapshot "perp:BTC")
        eth (exposure-by-id snapshot "perp:ETH")
        sol (exposure-by-id snapshot "perp:dex-a:SOL")
        purr (exposure-by-id snapshot "spot:PURR/USDC")]
    (is (= owner-address (:address snapshot)))
    (is (= :unified (get-in snapshot [:account :mode])))
    (is (true? (:loaded? snapshot)))
    (is (true? (:snapshot-loaded? snapshot)))
    (is (true? (:capital-ready? snapshot)))
    (is (true? (:execution-ready? snapshot)))
    (is (= 4 (count (:exposures snapshot))))
    (is (= 100005 (get-in snapshot [:capital :nav-usdc])))
    (is (= 100000 (get-in snapshot [:capital :cash-usdc])))
    (is (= 5 (get-in snapshot [:capital :spot-noncash-usdc])))
    (is (= 25000 (:signed-notional-usdc btc)))
    (is (= :long (:side btc)))
    (is (= -6000 (:signed-notional-usdc eth)))
    (is (= :short (:side eth)))
    (is (= 1000 (:signed-notional-usdc sol)))
    (is (= "dex-a" (:dex sol)))
    (is (= 5 (:signed-notional-usdc purr)))
    (is (= :spot (:market-type purr)))
    (is (= "PURR/USDC" (:symbol purr)))
    (is (= "PURR" (:base purr)))
    (is (= "USDC" (:quote purr)))
    (is (near? (/ 25000 100005) (:weight btc)))
    (is (= 32005 (get-in snapshot [:capital :gross-exposure-usdc])))
    (is (= 20005 (get-in snapshot [:capital :net-exposure-usdc])))
    (is (= [] (:warnings snapshot)))))

(deftest current-portfolio-snapshot-uses-inspected-account-and-read-only-state-test
  (let [snapshot (current-portfolio/current-portfolio-snapshot
                  {:wallet {:address owner-address}
                   :router {:path (str "/portfolio/trader/" trader-address)}
                   :account {:mode :classic}
                   :webdata2 {:clearinghouseState {:marginSummary {:accountValue "0"}}}})]
    (is (= trader-address (:address snapshot)))
    (is (true? (get-in snapshot [:account :read-only?])))
    (is (= account-context/trader-portfolio-read-only-message
           (get-in snapshot [:account :read-only-message])))))

(deftest current-portfolio-snapshot-separates-loaded-capital-and-execution-readiness-test
  (let [zero-capital (current-portfolio/current-portfolio-snapshot
                      {:wallet {:address owner-address}
                       :router {:path "/portfolio"}
                       :webdata2 {:clearinghouseState
                                  {:marginSummary {:accountValue "0"}}}})
        read-only (current-portfolio/current-portfolio-snapshot
                   {:wallet {:address owner-address}
                    :router {:path (str "/portfolio/trader/" trader-address)}
                    :webdata2 {:clearinghouseState
                               {:marginSummary {:accountValue "1000"}}}})]
    (is (true? (:snapshot-loaded? zero-capital)))
    (is (false? (:capital-ready? zero-capital)))
    (is (false? (:execution-ready? zero-capital)))
    (is (true? (:snapshot-loaded? read-only)))
    (is (true? (:capital-ready? read-only)))
    (is (false? (:execution-ready? read-only)))))

(deftest current-portfolio-snapshot-warns-and-skips-unpriced-spot-rows-test
  (let [snapshot (current-portfolio/current-portfolio-snapshot
                  {:wallet {:address owner-address}
                   :router {:path "/portfolio"}
                   :spot {:clearinghouse-state
                          {:balances [{:coin "MISSING" :total "10" :hold "0"}]}}
                   :asset-selector {:market-by-key {}}})]
    (is (= [] (:exposures snapshot)))
    (is (= [{:code :missing-spot-price
             :instrument-id "spot:MISSING"
             :coin "MISSING"}]
           (:warnings snapshot)))))
