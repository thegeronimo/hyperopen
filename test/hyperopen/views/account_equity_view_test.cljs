(ns hyperopen.views.account-equity-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
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
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Unified Account Ratio"))))
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Portfolio Value"))))
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Perps Maintenance Margin"))))
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Unified Account Leverage"))))
    (is (nil? (find-first-node view-node #(contains? (direct-texts %) "Perps Overview"))))))

(deftest unified-account-summary-falls-back-to-placeholders-test
  (let [view-node (view/account-equity-view {:account {:mode :unified}
                                             :webdata2 {}
                                             :spot {}
                                             :perp-dex-clearinghouse {}})
        placeholder-node (find-first-node view-node #(contains? (direct-texts %) "--"))]
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Unified Account Summary"))))
    (is (some? (find-first-node view-node #(contains? (direct-texts %) "Portfolio Value"))))
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
