(ns hyperopen.state.trading.identity-and-submit-policy-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.account.context :as account-context]
            [hyperopen.schema.order-request-contracts :as order-request-contracts]
            [hyperopen.state.trading :as trading]
            [hyperopen.state.trading.test-support :as support]))

(def base-state support/base-state)

(deftest market-identity-symbol-and-read-only-inference-test
  (testing "derives base and quote symbols from active-market and active-asset"
    (let [state {:active-asset "PURR"
                 :active-market {:symbol "PURR-USDT"
                                 :quote "USDT"
                                 :market-type :perp}}
          identity (trading/market-identity state)]
      (is (= "PURR" (:base-symbol identity)))
      (is (= "USDT" (:quote-symbol identity)))
      (is (false? (:spot? identity)))
      (is (false? (:hip3? identity)))
      (is (false? (:read-only? identity)))))

  (testing "infers spot and read-only when active-asset is spot-style"
    (let [state {:active-asset "ETH/USDC"
                 :active-market {:symbol "ETH-USDC"}}
          identity (trading/market-identity state)]
      (is (= "ETH" (:base-symbol identity)))
      (is (= "USDC" (:quote-symbol identity)))
      (is (true? (:spot? identity)))
      (is (false? (:hip3? identity)))
      (is (true? (:read-only? identity)))))

  (testing "canonical market metadata takes precedence over slash heuristics"
    (let [state {:active-asset "ETH/USDC"
                 :active-market {:symbol "ETH-USDC"
                                 :market-type :perp}}
          identity (trading/market-identity state)]
      (is (false? (:spot? identity)))
      (is (false? (:read-only? identity)))))

  (testing "infers hip3 from namespaced asset and dex market"
    (let [asset-only-state {:active-asset "hyna:GOLD"
                            :active-market {:symbol "hyna:GOLD"}}
          dex-state {:active-asset "BTC"
                     :active-market {:symbol "BTC-USD"
                                     :dex "dex-a"}}
          asset-identity (trading/market-identity asset-only-state)
          dex-identity (trading/market-identity dex-state)]
      (is (= "GOLD" (:base-symbol asset-identity)))
      (is (true? (:hip3? asset-identity)))
      (is (false? (:read-only? asset-identity)))
      (is (true? (:hip3? dex-identity)))
      (is (false? (:read-only? dex-identity))))))

(deftest market-info-and-order-draft-selectors-test
  (let [state {:active-asset "BTC"
               :active-market {:symbol "BTC-USDC"
                               :coin "BTC"
                               :quote "USDC"
                               :maxLeverage 40
                               :szDecimals 5
                               :market-type :perp}
               :orderbooks {"BTC" {:bids [{:px "99"}]
                                   :asks [{:px "101"}]}}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                               :totalMarginUsed "250"}}}
               :order-form {:type :limit
                            :side :buy
                            :price "100"
                            :size "1"}
               :order-form-ui {:pro-order-type-dropdown-open? true}}]
    (let [raw-draft (trading/raw-order-form-draft state)
          normalized-draft (trading/order-form-draft state)
          market-info (trading/market-info state)]
      (is (= :limit (:type raw-draft)))
      (is (= :limit (:type normalized-draft)))
      (is (= :buy (:side normalized-draft)))
      (is (= "BTC" (:base-symbol market-info)))
      (is (= "USDC" (:quote-symbol market-info)))
      (is (= 5 (:sz-decimals market-info)))
      (is (true? (:cross-margin-allowed? market-info)))
      (is (number? (:max-leverage market-info))))))

(deftest market-info-exposes-outcome-side-metadata-test
  (let [state {:active-asset "outcome:0"
               :active-market {:coin "outcome:0"
                               :title "BTC above 78213 on May 3 at 2:00 AM?"
                               :quote "USDC"
                               :market-type :outcome
                               :szDecimals 0
                               :outcome-sides [{:side-index 0
                                                :side-label "Yes"
                                                :coin "#0"
                                                :asset-id 100000000}
                                               {:side-index 1
                                                :side-label "No"
                                                :coin "#1"
                                                :asset-id 100000001}]}}
        market-info (trading/market-info state)]
    (is (true? (:outcome? market-info)))
    (is (false? (:read-only? market-info)))
    (is (= "USDC" (:quote-symbol market-info)))
    (is (= [{:side-index 0
             :side-label "Yes"
             :coin "#0"
             :asset-id 100000000}
            {:side-index 1
             :side-label "No"
             :coin "#1"
             :asset-id 100000001}]
           (:outcome-sides market-info)))))

(deftest cross-margin-eligibility-and-effective-mode-test
  (let [cross-state {:active-market {:coin "BTC"
                                     :marginMode "normal"}}
        no-cross-state {:active-market {:coin "xyz:NATGAS"
                                        :marginMode "noCross"}}
        strict-isolated-state {:active-market {:coin "xyz:GOLD"
                                               :marginMode :strict-isolated}}
        only-isolated-state {:active-market {:coin "xyz:GOLD"
                                             :onlyIsolated true}}]
    (is (true? (trading/cross-margin-allowed? cross-state)))
    (is (false? (trading/cross-margin-allowed? no-cross-state)))
    (is (false? (trading/cross-margin-allowed? strict-isolated-state)))
    (is (false? (trading/cross-margin-allowed? only-isolated-state)))
    (is (= :cross (trading/effective-margin-mode cross-state :cross)))
    (is (= :isolated (trading/effective-margin-mode no-cross-state :cross)))
    (is (= :isolated (trading/effective-margin-mode strict-isolated-state :cross)))
    (is (= :isolated (trading/effective-margin-mode only-isolated-state :cross)))
    (is (= :isolated (trading/effective-margin-mode cross-state :isolated)))))

(deftest submit-policy-reasons-test
  (testing "view mode reports validation reason and required fields"
    (let [form (assoc (trading/default-order-form) :type :limit :size "" :price "")
          policy (trading/submit-policy base-state form {:mode :view})]
      (is (= :validation-errors (:reason policy)))
      (is (true? (:disabled? policy)))
      (is (= ["Size"] (:required-fields policy)))))

  (testing "submit mode reports agent readiness after request validation"
    (let [state (assoc base-state
                       :asset-contexts {:BTC {:idx 0}})
          form (assoc (trading/default-order-form)
                      :type :limit
                      :side :buy
                      :size "1"
                      :price "100")
          policy (trading/submit-policy state form {:mode :submit
                                                    :agent-ready? false})]
      (is (= :agent-not-ready (:reason policy)))
      (is (= "Enable trading before submitting orders." (:error-message policy)))))

  (testing "hip3 markets are submit-eligible when canonical asset id is present"
    (let [state {:active-asset "hyna:GOLD"
                 :active-market {:coin "hyna:GOLD"
                                 :symbol "hyna:GOLD-USDC"
                                 :quote "USDC"
                                 :dex "hyna"
                                 :market-type :perp
                                 :asset-id 110005
                                 :mark 100
                                 :maxLeverage 20
                                 :szDecimals 2}
                 :orderbooks {"hyna:GOLD" {:bids [{:px "99"}]
                                           :asks [{:px "101"}]}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                                 :totalMarginUsed "250"}}}
                 :asset-contexts {}
                 :order-form (trading/default-order-form)
                 :order-form-ui (trading/default-order-form-ui)}
          form (assoc (trading/default-order-form)
                      :type :limit
                      :side :buy
                      :size "1"
                      :price "100")
          policy (trading/submit-policy state form {:mode :submit
                                                    :agent-ready? true})]
      (is (nil? (:reason policy)))
      (is (false? (:disabled? policy)))
      (is (order-request-contracts/order-request-valid? (:request policy)))
      (is (= 110005 (get-in policy [:request :action :orders 0 :a]))))))

(deftest submit-policy-additional-branch-coverage-test
  (testing "submit mode returns spot-read-only for spot market identity"
    (let [state {:active-asset "ETH/USDC"
                 :active-market {:symbol "ETH-USDC"}
                 :orderbooks {"ETH/USDC" {:bids [{:px "99"}]
                                        :asks [{:px "101"}]}}
                 :webdata2 {}}
          form (assoc (trading/default-order-form)
                      :type :limit
                      :side :buy
                      :size "1"
                      :price "100")
          policy (trading/submit-policy state form {:mode :submit
                                                    :agent-ready? true})]
      (is (= :spot-read-only (:reason policy)))
      (is (= "Spot trading is not supported yet." (:error-message policy)))))

  (testing "submit mode returns market-price-missing when market price cannot be derived"
    (let [state {:active-asset "BTC"
                 :active-market {}
                 :orderbooks {}
                 :webdata2 {}}
          form (assoc (trading/default-order-form)
                      :type :market
                      :side :buy
                      :size "1")
          policy (trading/submit-policy state form {:mode :submit
                                                    :agent-ready? true})]
      (is (= :market-price-missing (:reason policy)))
      (is (= "Market price unavailable. Load order book first." (:error-message policy)))))

  (testing "submit mode returns request-unavailable when validation passes but request cannot be built"
    (let [form (assoc (trading/default-order-form)
                      :type :limit
                      :side :buy
                      :size "1"
                      :price "100")
          policy (trading/submit-policy base-state form {:mode :submit
                                                         :agent-ready? true})]
      (is (= :request-unavailable (:reason policy)))
      (is (= "Select an asset and ensure market data is loaded." (:error-message policy)))))

  (testing "view mode returns submitting when runtime indicates submit in progress"
    (let [state (assoc base-state
                       :order-form-runtime {:submitting? true})
          form (assoc (trading/default-order-form)
                      :type :limit
                      :side :buy
                      :size "1"
                      :price "100")
          policy (trading/submit-policy state form {:mode :view})]
      (is (= :submitting (:reason policy)))
      (is (true? (:disabled? policy)))
      (is (nil? (:error-message policy)))))

  (testing "spectate mode blocks submit policy with explicit stop guidance"
    (let [state (assoc base-state
                       :asset-contexts {:BTC {:idx 0}}
                       :account-context {:spectate-mode {:active? true
                                                      :address "0x1234567890abcdef1234567890abcdef12345678"}})
          form (assoc (trading/default-order-form)
                      :type :limit
                      :side :buy
                      :size "1"
                      :price "100")
          view-policy (trading/submit-policy state form {:mode :view})
          submit-policy (trading/submit-policy state form {:mode :submit
                                                           :agent-ready? true})]
      (is (= :spectate-mode-read-only (:reason view-policy)))
      (is (= account-context/spectate-mode-read-only-message
             (:error-message view-policy)))
      (is (= :spectate-mode-read-only (:reason submit-policy)))
      (is (= account-context/spectate-mode-read-only-message
             (:error-message submit-policy))))))

(deftest prepare-order-form-for-submit-formats-market-price-per-hyperliquid-rules-test
  (testing "perp pricing enforces max decimals from szDecimals and 5 sig-fig truncation"
    (let [state {:active-asset "MON"
                 :active-market {:coin "MON"
                                 :asset-id 215
                                 :mark 0.020397
                                 :maxLeverage 20
                                 :szDecimals 0}
                 :orderbooks {"MON" {:bids [{:px "0.020397"}]
                                     :asks [{:px "0.02045"}]}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                                 :totalMarginUsed "250"}}}}
          form (assoc (trading/default-order-form)
                      :type :market
                      :side :sell
                      :size "98"
                      :slippage "0.5")
          prepared (trading/prepare-order-form-for-submit state form)
          request (trading/build-order-request state (:form prepared))]
      (is (false? (:market-price-missing? prepared)))
      (is (order-request-contracts/order-request-valid? request))
      (is (= "0.020295" (get-in request [:action :orders 0 :p])))))

  (testing "market price truncates to 5 significant figures when decimals alone are insufficient"
    (let [state {:active-asset "BTC"
                 :active-market {:coin "BTC"
                                 :asset-id 0
                                 :mark 123.456789
                                 :maxLeverage 20
                                 :szDecimals 0}
                 :orderbooks {"BTC" {:bids [{:px "123.456789"}]
                                     :asks [{:px "123.5"}]}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                                 :totalMarginUsed "250"}}}}
          form (assoc (trading/default-order-form)
                      :type :market
                      :side :sell
                      :size "1"
                      :slippage "0")
          prepared (trading/prepare-order-form-for-submit state form)
          request (trading/build-order-request state (:form prepared))]
      (is (false? (:market-price-missing? prepared)))
      (is (order-request-contracts/order-request-valid? request))
      (is (= "123.45" (get-in request [:action :orders 0 :p]))))))

(deftest prepare-order-form-for-submit-uses-limit-fallback-when-price-is-blank-test
  (let [form (assoc (trading/default-order-form)
                    :type :limit
                    :side :buy
                    :size "1"
                    :price "")
        prepared (trading/prepare-order-form-for-submit base-state form)]
    (is (false? (:market-price-missing? prepared)))
    (is (= "100" (get-in prepared [:form :price])))))

(deftest prepare-order-form-for-submit-leaves-limit-price-blank-when-no-fallback-exists-test
  (let [state {:active-asset "BTC"
               :active-market {}
               :orderbooks {}
               :webdata2 {}}
        form (assoc (trading/default-order-form)
                    :type :limit
                    :side :buy
                    :size "1"
                    :price "")
        prepared (trading/prepare-order-form-for-submit state form)]
    (is (false? (:market-price-missing? prepared)))
    (is (= "" (get-in prepared [:form :price])))))
