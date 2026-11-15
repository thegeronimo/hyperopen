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
                      {:balances [{:coin "USDC" :total "300000" :hold "0"}
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
    (is (= 300005 (get-in snapshot [:capital :nav-usdc])))
    (is (= 300000 (get-in snapshot [:capital :cash-usdc])))
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
    (is (near? (/ 25000 300005) (:weight btc)))
    (is (= 32005 (get-in snapshot [:capital :gross-exposure-usdc])))
    (is (= 20005 (get-in snapshot [:capital :net-exposure-usdc])))
    (is (= [] (:warnings snapshot)))))

(deftest current-portfolio-snapshot-classic-keeps-perp-equity-as-nav-test
  (let [state {:wallet {:address owner-address}
               :router {:path "/portfolio"}
               :account {:mode :classic}
               :webdata2 {:clearinghouseState
                          {:marginSummary {:accountValue "100000"
                                           :totalMarginUsed "0"}
                           :assetPositions [{:position {:coin "BTC"
                                                        :szi "0.5"
                                                        :positionValue "25000"
                                                        :markPx "50000"
                                                        :leverage {:type "cross"
                                                                   :value "10"}}}]}}
               :spot {:clearinghouse-state
                      {:balances [{:coin "USDC" :total "300000" :hold "0"}]}}
               :asset-selector {:market-by-key {}}}
        snapshot (current-portfolio/current-portfolio-snapshot state)
        btc (exposure-by-id snapshot "perp:BTC")]
    (is (= 100000 (get-in snapshot [:capital :nav-usdc])))
    (is (= 300000 (get-in snapshot [:capital :cash-usdc])))
    (is (= 0 (get-in snapshot [:capital :spot-noncash-usdc])))
    (is (near? (/ 25000 100000) (:weight btc)))))

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

(def ^:private dust-spot-meta
  {:tokens [{:index 0 :name "USDC"}
            {:index 1 :name "PURR"}]
   :universe [{:name "@113" :index 7 :tokens [1 0]}]})

(deftest current-portfolio-snapshot-resolves-dust-spot-symbol-from-spot-meta-via-token-test
  (let [snapshot (current-portfolio/current-portfolio-snapshot
                  {:wallet {:address owner-address}
                   :router {:path "/portfolio"}
                   :spot {:meta dust-spot-meta
                          :clearinghouse-state
                          {:balances [{:coin "@113" :token 1 :total "10" :hold "0"}]}}
                   :asset-selector {:market-by-key
                                    {"spot:@113" {:key "spot:@113"
                                                  :market-type :spot
                                                  :coin "@113"
                                                  :symbol "@113"
                                                  :base "@113"
                                                  :mark "0.5"}}}})
        purr (exposure-by-id snapshot "spot:@113")]
    (is (= [] (:warnings snapshot)))
    (is (= "PURR" (:base purr)))
    (is (= "PURR/USDC" (:symbol purr)))
    (is (= :spot (:market-type purr)))
    (is (= "@113" (:coin purr)))))

(deftest current-portfolio-snapshot-resolves-dust-spot-symbol-from-spot-meta-via-coin-test
  (let [snapshot (current-portfolio/current-portfolio-snapshot
                  {:wallet {:address owner-address}
                   :router {:path "/portfolio"}
                   :spot {:meta dust-spot-meta
                          :clearinghouse-state
                          {:balances [{:coin "@113" :total "10" :hold "0"}]}}
                   :asset-selector {:market-by-key
                                    {"spot:@113" {:key "spot:@113"
                                                  :market-type :spot
                                                  :coin "@113"
                                                  :base "@113"
                                                  :mark "0.5"}}}})
        purr (exposure-by-id snapshot "spot:@113")]
    (is (= "PURR" (:base purr)))
    (is (= "PURR/USDC" (:symbol purr)))))

(deftest current-portfolio-snapshot-keeps-resolved-market-base-over-spot-meta-test
  (let [snapshot (current-portfolio/current-portfolio-snapshot
                  {:wallet {:address owner-address}
                   :router {:path "/portfolio"}
                   :spot {:meta {:tokens [{:index 0 :name "USDC"}
                                          {:index 1 :name "WRONG"}]
                                 :universe [{:name "@113" :index 7 :tokens [1 0]}]}
                          :clearinghouse-state
                          {:balances [{:coin "@113" :token 1 :total "10" :hold "0"}]}}
                   :asset-selector {:market-by-key
                                    {"spot:@113" {:key "spot:@113"
                                                  :market-type :spot
                                                  :coin "@113"
                                                  :symbol "HYPE/USDC"
                                                  :base "HYPE"
                                                  :mark "0.5"}}}})
        hype (exposure-by-id snapshot "spot:@113")]
    (is (= "HYPE" (:base hype)))
    (is (= "HYPE/USDC" (:symbol hype)))))

(deftest current-derived-constraints-center-gross-and-net-on-current-book-test
  (let [snapshot {:capital {:nav-usdc 10000
                            :gross-exposure-usdc 16000
                            :net-exposure-usdc 10000}
                  :exposures [{:instrument-id "perp:BTC"
                               :weight 1.3
                               :signed-notional-usdc 13000
                               :abs-notional-usdc 13000
                               :side :long}
                              {:instrument-id "perp:ETH"
                               :weight -0.3
                               :signed-notional-usdc -3000
                               :abs-notional-usdc 3000
                               :side :short}]}
        existing {:long-only? true
                  :gross-max 2.0
                  :net-min 1.0
                  :net-max 1.0
                  :max-asset-weight 0.5
                  :dust-usdc 0.0
                  :max-turnover 1.0
                  :rebalance-tolerance 0.03}
        constraints (current-portfolio/current-derived-constraints
                     snapshot
                     existing)]
    (is (= false (:long-only? constraints)))
    (is (= 1.6 (:gross-min constraints)))
    (is (= 1.6 (:gross-max constraints)))
    (is (= 1.0 (:net-min constraints)))
    (is (= 1.0 (:net-max constraints)))
    (is (= 0.03125 (:net-band-pct constraints)))
    (is (= 1.3 (:max-asset-weight constraints)))
    (is (nil? (:max-turnover constraints)))
    (is (= 0.03 (:rebalance-tolerance constraints)))))

(deftest current-derived-constraints-keep-partial-exposure-near-current-test
  (let [snapshot {:capital {:nav-usdc 100005
                            :gross-exposure-usdc 32005
                            :net-exposure-usdc 20005}
                  :exposures [{:instrument-id "perp:BTC"
                               :weight (/ 25000 100005)
                               :signed-notional-usdc 25000
                               :abs-notional-usdc 25000}
                              {:instrument-id "perp:ETH"
                               :weight (/ -6000 100005)
                               :signed-notional-usdc -6000
                               :abs-notional-usdc 6000}
                              {:instrument-id "spot:PURR"
                               :weight (/ 5 100005)
                               :signed-notional-usdc 5
                               :abs-notional-usdc 5}]}
        constraints (current-portfolio/current-derived-constraints
                     snapshot
                     {:gross-max 2.0
                      :net-min 1.0
                      :net-max 1.0
                      :max-asset-weight 0.5
                      :max-turnover 1.0})]
    (is (= 0.32 (:gross-min constraints)))
    (is (= 0.33 (:gross-max constraints)))
    (is (= 0.2 (:net-min constraints)))
    (is (= 0.2 (:net-max constraints)))
    (is (< (js/Math.abs (- (:net-band-pct constraints)
                           (/ 0.05 (/ 32005 100005))))
           1e-6)
        "the band preserves the old ±0.05x absolute width at the current gross")
    (is (= 0.5 (:max-asset-weight constraints)))
    (is (= false (:long-only? constraints)))
    (is (nil? (:max-turnover constraints)))))

(deftest current-derived-constraints-return-nil-without-positive-capital-test
  (is (nil? (current-portfolio/current-derived-constraints
             {:capital {:nav-usdc 0
                        :gross-exposure-usdc 0
                        :net-exposure-usdc 0}
              :exposures []}
             {:gross-max 2.0}))))

(deftest spot-balance-never-adopts-the-perp-instrument-identity-test
  (let [state {:wallet {:address owner-address}
               :router {:path "/portfolio"}
               :webdata2 {:clearinghouseState
                          {:marginSummary {:accountValue "100000"}
                           :assetPositions [{:position {:coin "HYPE"
                                                        :szi "-100"
                                                        :markPx "40"
                                                        :leverage {:type "cross"
                                                                   :value "5"}}}]}}
               :spot {:clearinghouse-state
                      {:balances [{:coin "HYPE" :total "50" :hold "0"}]}}
               :asset-selector {:market-by-key
                                {"perp:HYPE" {:key "perp:HYPE"
                                              :market-type :perp
                                              :coin "HYPE"
                                              :symbol "HYPE-USDC"
                                              :base "HYPE"
                                              :quote "USDC"
                                              :mark "40"}
                                 "spot:HYPE/USDC" {:key "spot:HYPE/USDC"
                                                   :market-type :spot
                                                   :coin "HYPE/USDC"
                                                   :symbol "HYPE/USDC"
                                                   :base "HYPE"
                                                   :quote "USDC"
                                                   :mark "39.8"}}}}
        snapshot (current-portfolio/current-portfolio-snapshot state)
        perp (exposure-by-id snapshot "perp:HYPE")
        spot (exposure-by-id snapshot "spot:HYPE/USDC")]
    (is (= 2 (count (:exposures snapshot))))
    (is (= :perp (:market-type perp)))
    (is (= -4000 (:signed-notional-usdc perp)))
    (is (some? spot))
    (is (= :spot (:market-type spot)))
    (is (= "HYPE/USDC" (:symbol spot)))
    (is (near? 1990 (:signed-notional-usdc spot)))
    (is (near? 39.8 (:mark-price spot)))))

(deftest spot-balance-without-a-spot-market-keeps-a-spot-id-test
  (let [state {:wallet {:address owner-address}
               :router {:path "/portfolio"}
               :webdata2 {:clearinghouseState
                          {:marginSummary {:accountValue "100000"}
                           :assetPositions []}}
               :spot {:clearinghouse-state
                      {:balances [{:coin "XYZ" :total "10" :hold "0"}]}}
               :asset-selector {:market-by-key
                                {"perp:XYZ" {:key "perp:XYZ"
                                             :market-type :perp
                                             :coin "XYZ"
                                             :symbol "XYZ-USDC"
                                             :base "XYZ"
                                             :quote "USDC"
                                             :mark "2"}}}}
        snapshot (current-portfolio/current-portfolio-snapshot state)
        spot (exposure-by-id snapshot "spot:XYZ")]
    (is (some? spot))
    (is (= :spot (:market-type spot)))
    (is (nil? (exposure-by-id snapshot "perp:XYZ")))
    (is (near? 20 (:signed-notional-usdc spot)))))

(deftest dex-namespaced-position-coin-is-not-double-prefixed-test
  (let [state {:wallet {:address owner-address}
               :router {:path "/portfolio"}
               :webdata2 {:clearinghouseState
                          {:marginSummary {:accountValue "100000"}
                           :assetPositions []}}
               :perp-dex-clearinghouse
               {"xyz" {:assetPositions [{:position {:coin "xyz:ORCL"
                                                    :szi "0.131"
                                                    :markPx "143.75"}}]}
                "dex-a" {:assetPositions [{:position {:coin "SOL"
                                                      :szi "10"
                                                      :markPx "100"}}]}}
               :spot {:clearinghouse-state {:balances []}}
               :asset-selector {:market-by-key
                                {"perp:xyz:ORCL" {:key "perp:xyz:ORCL"
                                                  :market-type :perp
                                                  :coin "xyz:ORCL"
                                                  :symbol "ORCL"
                                                  :mark "143.75"}}}}
        snapshot (current-portfolio/current-portfolio-snapshot state)
        orcl (exposure-by-id snapshot "perp:xyz:ORCL")]
    (is (some? orcl) "namespaced coin resolves to the canonical catalog id")
    (is (nil? (exposure-by-id snapshot "perp:xyz:xyz:ORCL"))
        "the doubled-prefix id must never be synthesized")
    (is (near? (* 0.131 143.75) (:signed-notional-usdc orcl)))
    (is (some? (exposure-by-id snapshot "perp:dex-a:SOL"))
        "a bare per-dex coin still gets the dex prefix exactly once")))
