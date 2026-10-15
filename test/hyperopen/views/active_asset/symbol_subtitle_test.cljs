(ns hyperopen.views.active-asset.symbol-subtitle-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.active-asset.icon-button :as icon-button]
            [hyperopen.views.active-asset.test-support :as support]
            [hyperopen.views.active-asset.vm :as vm]))

(def ^:private spcx-description
  "SPCX references 1 share of Space Exploration Technologies Corp. Class A common stock (Nasdaq: SPCX), quoted in USD.")

(def ^:private spcx-market
  {:key "perp:xyz:SPCX"
   :coin "xyz:SPCX"
   :symbol "SPCX-USDC"
   :base "SPCX"
   :dex "xyz"
   :market-type :perp
   :maxLeverage 20})

(defn- subtitle-text
  [node]
  (some-> (support/find-node-by-role node "active-asset-symbol-subtitle")
          support/collect-strings
          first))

(deftest asset-button-renders-issuer-subtitle-and-description-tooltip-test
  (let [node (icon-button/asset-button
              (assoc spcx-market :symbol-meta {:coin "xyz:SPCX"
                                               :subtitle "Space Exploration Technologies Corp."
                                               :category-label "Stock"
                                               :description spcx-description
                                               :deployer "XYZ"})
              false #{} #{})
        tooltip (support/find-node-by-role node "active-asset-symbol-description")
        tooltip-strings (set (support/collect-strings tooltip))]
    (is (= "Space Exploration Technologies Corp. · Stock" (subtitle-text node)))
    (is (= (get-in tooltip [1 :id]) (get-in node [1 :aria-describedby])))
    (is (= "tooltip" (get-in tooltip [1 :role])))
    (is (contains? tooltip-strings spcx-description)
        "deployer text renders as a plain text node")
    (is (contains? tooltip-strings "Written by the market deployer, XYZ"))
    (is (some #{"group/symbol-meta"} (support/class-values (get-in node [1 :class]))))))

(deftest asset-button-subtitle-degrades-without-description-test
  (testing "fallback label when nothing was published"
    (let [node (icon-button/asset-button
                (assoc spcx-market :symbol-meta {:coin "cash:HOOD" :fallback-label "Market by dreamcash"})
                false #{} #{})]
      (is (= "Market by dreamcash" (subtitle-text node)))
      (is (nil? (support/find-node-by-role node "active-asset-symbol-description")))
      (is (nil? (get-in node [1 :aria-describedby])))))
  (testing "loading keeps the slot with a non-breaking space"
    (let [node (icon-button/asset-button
                (assoc spcx-market :symbol-meta {:coin "xyz:SPCX" :loading? true})
                false #{} #{})]
      (is (= " " (subtitle-text node)))))
  (testing "the description tooltip is suppressed while the selector is open"
    (let [node (icon-button/asset-button
                (assoc spcx-market :symbol-meta {:coin "xyz:SPCX"
                                                 :subtitle "Space Exploration Technologies Corp."
                                                 :description spcx-description
                                                 :deployer "XYZ"})
                true #{} #{})]
      (is (nil? (support/find-node-by-role node "active-asset-symbol-description")))))
  (testing "markets without annotations render no subtitle"
    (is (nil? (subtitle-text (icon-button/asset-button {:coin "BTC" :symbol "BTC-USDC"} false #{} #{}))))))

(deftest panel-vm-threads-annotations-into-the-header-test
  (let [state {:active-asset "xyz:SPCX"
               :active-market spcx-market
               :asset-selector {:market-by-key {"perp:xyz:SPCX" spcx-market}}
               :perp-dex-full-name-by-name {"xyz" "XYZ"}
               :perp-annotations {:concise-status :loaded
                                  :by-coin {"xyz:SPCX" {:category :stocks :display-name "SPCX"}}
                                  :detail-by-coin {"xyz:SPCX" {:status :loaded
                                                               :annotation {:category :stocks
                                                                            :description spcx-description}}}}}
        dependency-state (vm/panel-dependency-state state)
        row-vm (:row-vm (vm/active-asset-panel-vm dependency-state))]
    (is (= (:perp-annotations state) (:perp-annotations dependency-state))
        "the memoized trade-view slice carries annotations so the header repaints when they load")
    (is (= "Space Exploration Technologies Corp."
           (get-in row-vm [:icon-market :symbol-meta :subtitle])))
    (is (= "XYZ" (get-in row-vm [:symbol-meta :deployer])))))
