(ns hyperopen.asset-selector.market-info-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.market-info :as market-info]
            [hyperopen.trade.layout-actions :as layout-actions]))

;; Descriptions copied verbatim from Hyperliquid `perpAnnotation` on 2026-10-15.
(def ^:private spcx
  "SPCX references 1 share of Space Exploration Technologies Corp. Class A common stock (Nasdaq: SPCX), quoted in USD. SpaceX develops launch vehicles, spacecraft, satellite communications, and AI technology.")

(def ^:private arm
  "ARM references 1 Arm Holdings plc ADS (Nasdaq: ARM), representing 1 ordinary share, quoted in USD. Arm designs and licenses processor architectures and related computing technology.")

(def ^:private gold
  "GOLD references the USD spot price of 1 troy ounce of gold (XAU/USD).")

(def ^:private ten-year
  "10Y tracks the yield on the most recently issued (on-the-run) 10-year US Treasury note, expressed in percent. The standard reference for long-term US dollar borrowing costs.")

(def ^:private spacex-valuation
  "SPACEX tracks the total valuation of SpaceX, in billions. E.g. if 1 SPACEX = $500, then the implied total valuation of the company is $500B.")

(defn- facts->map [model]
  (into {} (map (juxt :label :value)) (:facts model)))

(deftest listing-and-unit-parsing-test
  (is (= "Nasdaq: SPCX" (market-info/listing-from-description spcx)))
  (is (= "1 share" (market-info/contract-unit-from-description spcx)))
  (is (= "1 ADS" (market-info/contract-unit-from-description arm)))
  (is (= "1 troy ounce" (market-info/contract-unit-from-description gold)))
  (is (nil? (market-info/listing-from-description gold)) "(XAU/USD) is a pair, not a listing")
  (is (nil? (market-info/contract-unit-from-description ten-year)))
  (is (= "1 share" (market-info/contract-unit-from-description
                    "GDX tracks the value of one share of the VanEck Gold Miners ETF."))))

(deftest single-share-model-builds-headline-from-parsed-parts-test
  (let [model (market-info/market-info-model
               {:coin "xyz:SPCX" :dex "xyz" :deployer "XYZ" :description spcx
                :issuer "Space Exploration Technologies Corp." :category :stocks
                :category-label "Stock" :keywords ["spacex" "ai"]}
               {:symbol "SPCX-USDC" :maxLeverage 20 :only-isolated? false})]
    (is (= "1 share of Space Exploration Technologies Corp." (:headline model))
        "\"Corp.\" does not cut the headline short")
    (is (= spcx (:details model)) "the full description follows a parsed headline")
    (is (= :described (:status model)))
    (is (nil? (:warning model)))
    (is (= {"Underlying" "Space Exploration Technologies Corp."
            "Listing" "Nasdaq: SPCX"
            "Contract size" "1 share"
            "Category" "Stock"
            "Deployer" "XYZ (xyz)"
            "Max leverage" "20x"
            "Margin" "Cross or isolated"}
           (facts->map model)))
    (is (= ["spacex" "ai"] (:keywords model)))))

(deftest unit-warnings-flag-prices-that-are-not-share-prices-test
  (let [rates (market-info/market-info-model
               {:coin "para:10Y" :dex "para" :deployer "Paragon" :description ten-year
                :category :rates :category-label "Rates"}
               {:maxLeverage 20 :only-isolated? true})
        preipo (market-info/market-info-model
                {:coin "vntl:SPACEX" :dex "vntl" :deployer "Ventuals" :description spacex-valuation
                 :category :preipo :category-label "Pre-IPO"}
                {:maxLeverage 3 :only-isolated? true})]
    (is (= "Price is a yield in percent, not a bond price" (:warning rates)))
    (is (= "The yield on the most recently issued (on-the-run) 10-year US Treasury note, expressed in percent"
           (:headline rates)))
    (is (= "The standard reference for long-term US dollar borrowing costs." (:details rates)))
    (is (= "Isolated only" (get (facts->map rates) "Margin")))
    (is (= "Price is a company valuation, not a share price" (:warning preipo)))))

(deftest undescribed-and-loading-states-test
  (testing "a deployer that published nothing still gets venue facts"
    (let [model (market-info/market-info-model
                 {:coin "cash:HOOD" :dex "cash" :deployer "dreamcash" :loading? false}
                 {:maxLeverage 20})]
      (is (= :undescribed (:status model)))
      (is (nil? (:headline model)))
      (is (= {"Deployer" "dreamcash (cash)" "Max leverage" "20x" "Margin" "Cross or isolated"}
             (facts->map model)))))
  (testing "display name stands in for a missing description"
    (is (= "OPENAI" (:headline (market-info/market-info-model
                                {:coin "io:OAI" :dex "io" :deployer "EntropyIO" :display-name "OPENAI"}
                                {})))))
  (is (= :loading (:status (market-info/market-info-model {:coin "xyz:NVDA" :dex "xyz" :loading? true} {}))))
  (is (nil? (market-info/market-info-model nil {:coin "BTC"}))))

(deftest open-panel-model-follows-trade-ui-flag-test
  (let [state {:perp-annotations {:concise-status :loaded
                                  :by-coin {"xyz:SPCX" {:category :stocks}}
                                  :detail-by-coin {"xyz:SPCX" {:status :loaded
                                                               :annotation {:description spcx}}}}
               :perp-dex-full-name-by-name {"xyz" "XYZ"}}
        market {:symbol "SPCX-USDC" :maxLeverage 20}]
    (is (nil? (market-info/open-panel-model state "xyz:SPCX" market)))
    (is (= "1 share of Space Exploration Technologies Corp."
           (:headline (market-info/open-panel-model (assoc-in state [:trade-ui :market-info-open?] true)
                                                    "xyz:SPCX" market))))
    (is (nil? (market-info/open-panel-model (assoc-in state [:trade-ui :market-info-open?] true) "BTC" {}))
        "main-dex markets have no panel")))

(deftest panel-actions-toggle-and-close-test
  (is (= [[:effects/save [:trade-ui :market-info-open?] true]]
         (layout-actions/toggle-market-info-panel {})))
  (is (= [[:effects/save [:trade-ui :market-info-open?] false]]
         (layout-actions/toggle-market-info-panel {:trade-ui {:market-info-open? true}})))
  (is (= [[:effects/save [:trade-ui :market-info-open?] false]]
         (layout-actions/close-market-info-panel {}))))
