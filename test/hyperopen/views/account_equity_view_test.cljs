(ns hyperopen.views.account-equity-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.markets :as asset-selector-markets]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-equity-view :as view]))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- classes-from-tag [tag]
  (if (keyword? tag)
    (let [parts (str/split (name tag) #"\.")]
      (if (> (count parts) 1)
        (rest parts)
        []))
    []))

(defn- node-class-set [node]
  (let [attrs (when (and (vector? node) (map? (second node)))
                (second node))
        classes (concat (classes-from-tag (first node))
                        (class-values (:class attrs)))]
    (set classes)))

(defn- node-attrs [node]
  (when (and (vector? node) (map? (second node)))
    (second node)))

(defn- node-children [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- direct-texts [node]
  (->> (node-children node)
       (filter string?)
       set))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- first-index-where
  [xs pred]
  (first (keep-indexed (fn [idx x]
                         (when (pred x) idx))
                       xs)))

(defn- approx=
  ([expected actual]
   (approx= expected actual 1e-9))
  ([expected actual epsilon]
   (and (number? expected)
        (number? actual)
        (<= (js/Math.abs (- expected actual)) epsilon))))

(defn- scalar-coin-value?
  [value]
  (or (string? value)
      (keyword? value)
      (number? value)))

(deftest account-equity-heading-and-label-contrast-test
  (let [view-node (view/account-equity-view {:webdata2 {}
                                             :spot {}
                                             :perp-dex-clearinghouse {}})
        title-node (find-first-node view-node #(contains? (direct-texts %) "Account Equity"))
        section-node (find-first-node view-node #(contains? (direct-texts %) "Perps Overview"))
        spot-label-node (find-first-node view-node #(contains? (direct-texts %) "Spot"))]
    (is (contains? (node-class-set title-node) "text-trading-text"))
    (is (contains? (node-class-set section-node) "text-trading-text"))
    (is (contains? (node-class-set section-node) "font-semibold"))
    (is (contains? (node-class-set spot-label-node) "text-trading-text-secondary"))))

(deftest metric-row-value-contrast-test
  (testing "default values are white and placeholders are muted"
    (let [value-node (last (view/metric-row "Balance" "$10.00"))
          placeholder-node (last (view/metric-row "Balance" "--"))]
      (is (contains? (node-class-set value-node) "text-trading-text"))
      (is (contains? (node-class-set value-node) "num"))
      (is (contains? (node-class-set placeholder-node) "text-trading-text-secondary"))
      (is (contains? (node-class-set placeholder-node) "num")))))

(deftest tooltip-position-classes-cover-default-and-explicit-directions-test
  (doseq [[position expected-panel-class expected-arrow-class]
          [[nil "bottom-full" "top-full"]
           ["bottom" "top-full" "bottom-full"]
           ["left" "right-full" "left-full"]
           ["right" "left-full" "right-full"]]]
    (let [tooltip-node (view/tooltip [:span "Label"] "Tooltip copy" position)
          panel-node (last (node-children tooltip-node))
          arrow-node (find-first-node tooltip-node #(contains? (node-class-set %) "border-4"))
          panel-classes (node-class-set panel-node)
          arrow-classes (node-class-set arrow-node)]
      (is (contains? panel-classes expected-panel-class))
      (is (contains? panel-classes "group-hover:opacity-100"))
      (is (contains? arrow-classes expected-arrow-class)))))

(deftest pnl-display-color-mapping-test
  (let [positive (view/pnl-display 10.5)
        negative (view/pnl-display -2.25)
        zero (view/pnl-display 0)
        missing (view/pnl-display nil)]
    (is (= "text-success" (:class positive)))
    (is (= "text-error" (:class negative)))
    (is (= "text-trading-text" (:class zero)))
    (is (= "text-trading-text-secondary" (:class missing)))
    (is (= "--" (:text missing)))))

(deftest unified-account-summary-renders-unified-labels-test
  (let [view-node (view/account-equity-view {:account {:mode :unified}
                                             :webdata2 {:clearinghouseState {:marginSummary {:accountValue "204.45"
                                                                                              :totalNtlPos "0.0"
                                                                                              :totalRawUsd "204.45"
                                                                                              :totalMarginUsed "0.0"}
                                                                              :crossMarginSummary {:accountValue "204.45"
                                                                                                   :totalNtlPos "0.0"
                                                                                                   :totalRawUsd "204.45"
                                                                                                   :totalMarginUsed "0.0"}
                                                                              :crossMaintenanceMarginUsed "0.0"
                                                                              :assetPositions []}}
                                             :spot {}
                                             :perp-dex-clearinghouse {}})]
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Unified Account Summary"))))
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Unified Account Value"))))
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Unified Account Ratio"))))
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Perps Maintenance Margin"))))
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Unified Account Leverage"))))
    (is (nil? (find-first-node view-node #(contains? (direct-texts %) "Perps Overview"))))))

(deftest unified-account-summary-uses-hyperliquid-ratio-tooltip-copy-test
  (let [view-node (view/account-equity-view {:account {:mode :unified}
                                             :webdata2 {:clearinghouseState {:marginSummary {:accountValue "204.45"
                                                                                              :totalNtlPos "0.0"
                                                                                              :totalRawUsd "204.45"
                                                                                              :totalMarginUsed "0.0"}
                                                                              :crossMarginSummary {:accountValue "204.45"
                                                                                                   :totalNtlPos "0.0"
                                                                                                   :totalRawUsd "204.45"
                                                                                                   :totalMarginUsed "0.0"}
                                                                              :crossMaintenanceMarginUsed "0.0"
                                                                              :assetPositions []}}
                                             :spot {}
                                             :perp-dex-clearinghouse {}})]
    (is (some? (find-first-node view-node
                                #(contains? (direct-texts %)
                                            "Represents the risk of portfolio liquidation. When the value is greater than 95%, your portfolio may be liquidated."))))))

(deftest unified-account-summary-uses-hyperliquid-leverage-tooltip-copy-test
  (let [view-node (view/account-equity-view {:account {:mode :unified}
                                             :webdata2 {:clearinghouseState {:marginSummary {:accountValue "204.45"
                                                                                              :totalNtlPos "0.0"
                                                                                              :totalRawUsd "204.45"
                                                                                              :totalMarginUsed "0.0"}
                                                                              :crossMarginSummary {:accountValue "204.45"
                                                                                                   :totalNtlPos "0.0"
                                                                                                   :totalRawUsd "204.45"
                                                                                                   :totalMarginUsed "0.0"}
                                                                              :crossMaintenanceMarginUsed "0.0"
                                                                              :assetPositions []}}
                                             :spot {}
                                             :perp-dex-clearinghouse {}})]
    (is (some? (find-first-node view-node
                                #(contains? (direct-texts %)
                                            "Unified Account Leverage = Total Cross Positions Value / Total Collateral Balance."))))))

(deftest classic-account-equity-renders-classic-account-value-label-test
  (let [view-node (view/account-equity-view {:account {:mode :classic}
                                             :webdata2 {}
                                             :spot {}
                                             :perp-dex-clearinghouse {}})]
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Account Value"))))
    (is (nil? (find-first-node view-node #(contains? (direct-texts %) "Unified Account Value"))))))

(deftest account-equity-view-can-hide-inline-funding-actions-test
  (let [view-node (view/account-equity-view {:account {:mode :classic}
                                             :webdata2 {}
                                             :spot {}
                                             :perp-dex-clearinghouse {}}
                                            {:show-funding-actions? false})
        deposit-button (find-first-node view-node #(= "funding-action-deposit"
                                                      (:data-role (node-attrs %))))
        transfer-button (find-first-node view-node #(= "funding-action-transfer"
                                                       (:data-role (node-attrs %))))
        withdraw-button (find-first-node view-node #(= "funding-action-withdraw"
                                                       (:data-role (node-attrs %))))]
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Account Equity"))))
    (is (nil? deposit-button))
    (is (nil? transfer-button))
    (is (nil? withdraw-button))))

(deftest account-equity-view-can-disable-fill-height-test
  (let [view-node (view/account-equity-view {:account {:mode :classic}
                                             :webdata2 {}
                                             :spot {}
                                             :perp-dex-clearinghouse {}}
                                            {:fill-height? false})
        root-classes (node-class-set view-node)]
    (is (contains? root-classes "w-full"))
    (is (not (contains? root-classes "h-full")))))

(deftest funding-actions-view-exposes-anchor-aware-funding-actions-test
  (let [actions-node (view/funding-actions-view {})
        deposit-button (find-first-node actions-node #(= "funding-action-deposit"
                                                         (:data-role (node-attrs %))))
        transfer-button (find-first-node actions-node #(= "funding-action-transfer"
                                                          (:data-role (node-attrs %))))
        withdraw-button (find-first-node actions-node #(= "funding-action-withdraw"
                                                          (:data-role (node-attrs %))))]
    (is (= [[:actions/open-funding-deposit-modal
             :event.currentTarget/bounds
             :event.currentTarget/data-role]]
           (get-in deposit-button [1 :on :click])))
    (is (= [[:actions/open-funding-transfer-modal
             :event.currentTarget/bounds
             :event.currentTarget/data-role]]
           (get-in transfer-button [1 :on :click])))
    (is (= [[:actions/open-funding-withdraw-modal
             :event.currentTarget/bounds
             :event.currentTarget/data-role]]
           (get-in withdraw-button [1 :on :click])))))

(deftest funding-actions-view-adds-focus-return-hook-for-matching-button-test
  (let [actions-node (view/funding-actions-view {:funding-ui {:modal {:focus-return-data-role "funding-action-deposit"
                                                                      :focus-return-token 4}}})
        deposit-button (find-first-node actions-node #(= "funding-action-deposit"
                                                         (:data-role (node-attrs %))))
        withdraw-button (find-first-node actions-node #(= "funding-action-withdraw"
                                                          (:data-role (node-attrs %))))]
    (is (fn? (get-in deposit-button [1 :replicant/on-render])))
    (is (= "focus-return:funding-action-deposit:4:true"
           (get-in deposit-button [1 :replicant/key])))
    (is (nil? (get-in withdraw-button [1 :replicant/on-render])))))

(deftest unified-account-summary-renders-funding-section-above-summary-test
  (let [view-node (view/account-equity-view {:account {:mode :unified}
                                             :webdata2 {:clearinghouseState {:marginSummary {:accountValue "204.45"
                                                                                              :totalNtlPos "0.0"
                                                                                              :totalRawUsd "204.45"
                                                                                              :totalMarginUsed "0.0"}
                                                                              :crossMarginSummary {:accountValue "204.45"
                                                                                                   :totalNtlPos "0.0"
                                                                                                   :totalRawUsd "204.45"
                                                                                                   :totalMarginUsed "0.0"}
                                                                              :crossMaintenanceMarginUsed "0.0"
                                                                              :assetPositions []}}
                                             :spot {}
                                             :perp-dex-clearinghouse {}})
        funding-section (find-first-node view-node #(= "funding-actions-section"
                                                       (:data-parity-id (node-attrs %))))
        children (vec (node-children view-node))
        funding-index (first-index-where children #(= "funding-actions-section"
                                                      (:data-parity-id (node-attrs %))))
        summary-index (first-index-where children #(contains? (direct-texts %) "Unified Account Summary"))]
    (is (some? funding-section))
    (is (some? (find-first-node funding-section #(contains? (direct-texts %) "Deposit"))))
    (is (some? (find-first-node funding-section #(contains? (direct-texts %) "Perps <-> Spot"))))
    (is (some? (find-first-node funding-section #(contains? (direct-texts %) "Withdraw"))))
    (is (number? funding-index))
    (is (number? summary-index))
    (is (< funding-index summary-index))))

(deftest unified-account-summary-hides-funding-actions-while-spectate-mode-active-test
  (let [view-node (view/account-equity-view {:account {:mode :unified}
                                             :account-context {:spectate-mode {:active? true
                                                                           :address "0x1234567890abcdef1234567890abcdef12345678"}}
                                             :webdata2 {:clearinghouseState {:marginSummary {:accountValue "204.45"
                                                                                              :totalNtlPos "0.0"
                                                                                              :totalRawUsd "204.45"
                                                                                              :totalMarginUsed "0.0"}
                                                                              :crossMarginSummary {:accountValue "204.45"
                                                                                                   :totalNtlPos "0.0"
                                                                                                   :totalRawUsd "204.45"
                                                                                                   :totalMarginUsed "0.0"}
                                                                              :crossMaintenanceMarginUsed "0.0"
                                                                              :assetPositions []}}
                                             :spot {}
                                             :perp-dex-clearinghouse {}})
        funding-section (find-first-node view-node #(= "funding-actions-section"
                                                       (:data-parity-id (node-attrs %))))
        deposit-button (find-first-node view-node #(= "funding-action-deposit"
                                                      (:data-role (node-attrs %))))
        transfer-button (find-first-node view-node #(= "funding-action-transfer"
                                                       (:data-role (node-attrs %))))
        withdraw-button (find-first-node view-node #(= "funding-action-withdraw"
                                                       (:data-role (node-attrs %))))]
    (is (nil? funding-section))
    (is (nil? deposit-button))
    (is (nil? transfer-button))
    (is (nil? withdraw-button))
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Unified Account Summary"))))))

(deftest classic-account-equity-hides-funding-actions-while-spectate-mode-active-test
  (let [view-node (view/account-equity-view {:account-context {:spectate-mode {:active? true
                                                                            :address "0x1234567890abcdef1234567890abcdef12345678"}}
                                             :webdata2 {}
                                             :spot {}
                                             :perp-dex-clearinghouse {}})
        deposit-button (find-first-node view-node #(= "funding-action-deposit"
                                                      (:data-role (node-attrs %))))
        transfer-button (find-first-node view-node #(= "funding-action-transfer"
                                                       (:data-role (node-attrs %))))
        withdraw-button (find-first-node view-node #(= "funding-action-withdraw"
                                                       (:data-role (node-attrs %))))]
    (is (nil? deposit-button))
    (is (nil? transfer-button))
    (is (nil? withdraw-button))
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Account Equity"))))))

(deftest unified-account-summary-falls-back-to-placeholders-test
  (let [view-node (view/account-equity-view {:account {:mode :unified}
                                             :webdata2 {}
                                             :spot {}
                                             :perp-dex-clearinghouse {}})
        placeholder-node (find-first-node view-node #(contains? (direct-texts %) "--"))]
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Unified Account Summary"))))
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Unified Account Value"))))
    (is (some? placeholder-node))
    (is (contains? (node-class-set placeholder-node) "text-trading-text-secondary"))))

(deftest unified-account-summary-portfolio-ignores-perps-double-count-test
  (let [view-node (view/account-equity-view {:account {:mode :unified}
                                             :webdata2 {:clearinghouseState {:marginSummary {:accountValue "3.03"
                                                                                              :totalNtlPos "0.0"
                                                                                              :totalRawUsd "3.03"
                                                                                              :totalMarginUsed "0.0"}
                                                                              :crossMarginSummary {:accountValue "3.03"
                                                                                                   :totalNtlPos "0.0"
                                                                                                   :totalRawUsd "3.03"
                                                                                                   :totalMarginUsed "0.0"}
                                                                              :crossMaintenanceMarginUsed "0.0"
                                                                              :assetPositions []}
                                                        :spotAssetCtxs [{:markPx "0.04"}]}
                                             :spot {:meta {:tokens [{:index 0
                                                                     :name "USDC"
                                                                     :weiDecimals 6}
                                                                    {:index 1
                                                                     :name "MEOW"
                                                                     :weiDecimals 6}]
                                                           :universe [{:tokens [1 0]
                                                                       :index 0}]}
                                                    :clearinghouse-state {:balances [{:coin "USDC"
                                                                                      :token 0
                                                                                      :hold "0.0"
                                                                                      :total "204.41"
                                                                                      :entryNtl "0"}
                                                                                     {:coin "MEOW"
                                                                                      :token 1
                                                                                      :hold "0.0"
                                                                                      :total "1.0"
                                                                                      :entryNtl "0"}]}}
                                             :perp-dex-clearinghouse {}})
        portfolio-value-node (find-first-node view-node #(contains? (direct-texts %) "$204.45"))
        overcount-node (find-first-node view-node #(contains? (direct-texts %) "$207.48"))]
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Unified Account Summary"))))
    (is (some? portfolio-value-node))
    (is (nil? overcount-node))
    (is (contains? (node-class-set portfolio-value-node) "text-trading-text"))))

(deftest unified-account-summary-portfolio-value-uses-usdc-fallback-when-meta-missing-test
  (let [view-node (view/account-equity-view {:account {:mode :unified}
                                             :webdata2 {:clearinghouseState {:marginSummary {:accountValue "0.0"
                                                                                              :totalNtlPos "0.0"
                                                                                              :totalRawUsd "0.0"
                                                                                              :totalMarginUsed "0.0"}
                                                                              :crossMarginSummary {:accountValue "0.0"
                                                                                                   :totalNtlPos "0.0"
                                                                                                   :totalRawUsd "0.0"
                                                                                                   :totalMarginUsed "0.0"}
                                                                              :crossMaintenanceMarginUsed "0.0"
                                                                              :assetPositions []}
                                                        :spotAssetCtxs []}
                                             :spot {:meta {:tokens []
                                                           :universe []}
                                                    :clearinghouse-state {:balances [{:coin "USDC"
                                                                                      :token 0
                                                                                      :hold "0.0"
                                                                                      :total "204.45"
                                                                                      :entryNtl "0"}]}}
                                             :perp-dex-clearinghouse {}})
        portfolio-value-node (find-first-node view-node #(contains? (direct-texts %) "$204.45"))]
    (is (some? portfolio-value-node))
    (is (contains? (node-class-set portfolio-value-node) "text-trading-text"))))

(deftest unified-account-summary-portfolio-value-matches-balance-row-usdc-sum-test
  (let [state {:account {:mode :unified}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "0.0"
                                                                :totalNtlPos "0.0"
                                                                :totalRawUsd "0.0"
                                                                :totalMarginUsed "0.0"}
                                                :crossMarginSummary {:accountValue "0.0"
                                                                     :totalNtlPos "0.0"
                                                                     :totalRawUsd "0.0"
                                                                     :totalMarginUsed "0.0"}
                                                :crossMaintenanceMarginUsed "0.0"
                                                :assetPositions []}
                          :spotAssetCtxs [{:markPx "1.0"}]}
               :spot {:meta {:tokens [{:index 0
                                       :name "USDC"
                                       :weiDecimals 6}
                                      {:index 1
                                       :name "MEOW"
                                       :weiDecimals 6}]
                             :universe [{:tokens [1 0]
                                         :index 0}]}
                      :clearinghouse-state {:balances [{:coin "USDC"
                                                        :token 0
                                                        :hold "0.0"
                                                        :total "204.41"
                                                        :entryNtl "0"}
                                                       {:coin "MEOW"
                                                        :token 1
                                                        :hold "0.0"
                                                        :total "0.04"
                                                        :entryNtl "0"}]}}
               :perp-dex-clearinghouse {}}
        balance-rows (projections/build-balance-rows (:webdata2 state) (:spot state) (:account state))
        expected-portfolio (projections/portfolio-usdc-value balance-rows)
        expected-portfolio-text (view/display-currency expected-portfolio)
        view-node (view/account-equity-view state)
        portfolio-value-node (find-first-node view-node #(contains? (direct-texts %) expected-portfolio-text))]
    (is (= 204.45 expected-portfolio))
    (is (some? portfolio-value-node))
    (is (contains? (node-class-set portfolio-value-node) "text-trading-text"))))

(deftest unified-account-summary-aggregates-named-dex-clearinghouse-states-test
  (let [state {:account {:mode :unified}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "0.0"
                                                                :totalNtlPos "0.0"
                                                                :totalRawUsd "0.0"
                                                                :totalMarginUsed "0.0"}
                                                :crossMarginSummary {:accountValue "0.0"
                                                                     :totalNtlPos "0.0"
                                                                     :totalRawUsd "0.0"
                                                                     :totalMarginUsed "0.0"}
                                                :crossMaintenanceMarginUsed "0.0"
                                                :assetPositions []}
                          :spotAssetCtxs []}
               :spot {:meta {:tokens [{:index 0
                                       :name "USDC"
                                       :weiDecimals 6}
                                      {:index 1
                                       :name "MEOW"
                                       :weiDecimals 6}]
                             :universe []}
                      :clearinghouse-state {:balances [{:coin "USDC"
                                                        :token 0
                                                        :hold "0.0"
                                                        :total "400.0"
                                                        :entryNtl "0"}
                                                       {:coin "MEOW"
                                                        :token 1
                                                        :hold "0.0"
                                                        :total "100.0"
                                                        :entryNtl "0"}]}}
               :asset-selector {:market-by-key {"perp:xyz:GOLD" {:key "perp:xyz:GOLD"
                                                                 :coin "xyz:GOLD"
                                                                 :market-type :perp
                                                                 :dex "xyz"
                                                                 :quote "USDC"}
                                                "perp:xyz:AAPL" {:key "perp:xyz:AAPL"
                                                                 :coin "xyz:AAPL"
                                                                 :market-type :perp
                                                                 :dex "xyz"
                                                                 :quote "USDC"}
                                                "spot:MEOW/USDC" {:key "spot:MEOW/USDC"
                                                                  :coin "MEOW/USDC"
                                                                  :market-type :spot
                                                                  :base "MEOW"
                                                                  :quote "USDC"
                                                                  :mark 1.0}}}
               :perp-dex-clearinghouse {"xyz" {:marginSummary {:accountValue "90.0"
                                                                :totalNtlPos "150.0"
                                                                :totalRawUsd "0.0"
                                                                :totalMarginUsed "30.0"}
                                                :crossMarginSummary {:accountValue "80.0"
                                                                     :totalNtlPos "50.0"
                                                                     :totalRawUsd "0.0"
                                                                     :totalMarginUsed "10.0"}
                                                :crossMaintenanceMarginUsed "1.0"
                                                 :assetPositions [{:position {:coin "xyz:GOLD"
                                                                             :marginUsed "20.0"
                                                                             :leverage {:type "isolated"
                                                                                        :value 20}
                                                                             :positionValue "100.0"
                                                                             :unrealizedPnl "3.0"}}
                                                                 {:position {:coin "xyz:AAPL"
                                                                             :marginUsed "10.0"
                                                                             :leverage {:type "cross"
                                                                                        :value 5}
                                                                             :positionValue "50.0"
                                                                             :unrealizedPnl "-1.0"}}]}}}
        original-resolve-market-by-coin asset-selector-markets/resolve-market-by-coin
        resolver-coins (atom [])]
    (view/reset-account-equity-metrics-cache!)
    (with-redefs [asset-selector-markets/resolve-market-by-coin
                  (fn [market-by-key coin]
                    (swap! resolver-coins conj coin)
                    (is (scalar-coin-value? coin))
                    (original-resolve-market-by-coin market-by-key coin))]
      (let [metrics (view/account-equity-metrics state)
            coins @resolver-coins]
        (is (approx= 500.0 (:portfolio-value metrics)))
        (is (approx= (/ 1.0 380.0) (:unified-account-ratio metrics)))
        (is (approx= 1.0 (:maintenance-margin metrics)))
        (is (approx= 0.125 (:unified-account-leverage metrics)))
        (is (seq coins))
        (is (every? scalar-coin-value? coins))
        (is (not-any? map? coins))
        (is (not-any? vector? coins))
        (is (not-any? #{"xyz:GOLD" "xyz:AAPL"} (map str coins)))))
    (view/reset-account-equity-metrics-cache!)))

(deftest account-equity-metrics-memoize-by-relevant-state-slices-test
  (let [webdata2 {:clearinghouseState {:marginSummary {:accountValue "25.0"
                                                       :totalNtlPos "0.0"
                                                       :totalRawUsd "25.0"
                                                       :totalMarginUsed "0.0"}
                                       :crossMarginSummary {:accountValue "25.0"
                                                            :totalNtlPos "0.0"
                                                            :totalRawUsd "25.0"
                                                            :totalMarginUsed "0.0"}
                                       :crossMaintenanceMarginUsed "0.0"
                                       :assetPositions []}}
        spot {:meta nil
              :clearinghouse-state nil}
        account {:mode :classic}
        perp-dex-clearinghouse {}
        market-by-key {}
        state-a {:webdata2 webdata2
                 :spot spot
                 :account account
                 :perp-dex-clearinghouse perp-dex-clearinghouse
                 :asset-selector {:market-by-key market-by-key}
                 :orderbooks {:BTC []}}
        state-b (assoc state-a :orderbooks {:ETH []})
        balance-row-calls (atom 0)
        positions-calls (atom 0)]
    (with-redefs [derived-cache/memoized-balance-rows (fn [_webdata2 _spot-data _account _market-by-key]
                                                        (swap! balance-row-calls inc)
                                                        [])
                  derived-cache/memoized-positions (fn [_webdata2 _perp-dex-states]
                                                     (swap! positions-calls inc)
                                                     [])]
      (view/reset-account-equity-metrics-cache!)
      (view/account-equity-metrics state-a)
      (view/account-equity-metrics state-b)
      (is (= 1 @balance-row-calls))
      (is (= 1 @positions-calls))
      (view/reset-account-equity-metrics-cache!))))
