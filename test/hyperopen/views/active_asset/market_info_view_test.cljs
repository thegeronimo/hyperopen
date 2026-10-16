(ns hyperopen.views.active-asset.market-info-view-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.active-asset.market-info :as market-info]
            [hyperopen.views.active-asset.test-support :as support]
            [hyperopen.views.active-asset.vm :as vm]))

(def ^:private model
  {:coin "xyz:SPCX"
   :symbol "SPCX-USDC"
   :status :described
   :headline "1 share of Space Exploration Technologies Corp."
   :details "SPCX references 1 share of Space Exploration Technologies Corp. Class A common stock (Nasdaq: SPCX)."
   :category-label "Stock"
   :warning nil
   :deployer "XYZ"
   :keywords ["spacex" "ai"]
   :facts [{:label "Underlying" :value "Space Exploration Technologies Corp."}
           {:label "Max leverage" :value "20x"}]})

(defn- strings-under [node role]
  (set (support/collect-strings (support/find-node-by-role node role))))

(deftest panel-renders-desktop-panel-and-mobile-sheet-test
  (let [node (market-info/market-info-panel model)
        panel (support/find-node-by-role node "market-info-panel")
        sheet (support/find-node-by-role node "market-info-sheet")]
    (is (some? panel))
    (is (some? sheet))
    (is (= "dialog" (get-in panel [1 :role])))
    (is (contains? (strings-under panel "market-info-headline")
                   "1 share of Space Exploration Technologies Corp."))
    (is (contains? (strings-under panel "market-info-facts") "Max leverage"))
    (is (contains? (set (support/collect-strings panel)) "Published on-chain by XYZ"))
    (is (contains? (set (support/collect-strings panel)) "#spacex"))
    (is (= [[:actions/close-market-info-panel]]
           (get-in (support/find-node-by-role panel "market-info-close") [1 :on :click])))
    (is (not= (get-in panel [1 :aria-labelledby])
              (get-in sheet [3 1 :aria-labelledby]))
        "desktop and mobile titles keep distinct ids")))

(deftest panel-states-and-closed-test
  (is (nil? (market-info/market-info-panel nil)))
  (testing "undescribed markets explain the gap"
    (let [node (market-info/market-info-panel (assoc model :status :undescribed :headline nil :details nil
                                                     :deployer "dreamcash"))]
      (is (contains? (strings-under (support/find-node-by-role node "market-info-panel") "market-info-status")
                     "dreamcash hasn't published a description for this market."))))
  (testing "a unit warning renders as a chip"
    (is (contains? (set (support/collect-strings
                         (market-info/market-info-panel (assoc model :warning "Price is a yield in percent, not a bond price"))))
                   "Price is a yield in percent, not a bond price"))))

(deftest triggers-dispatch-the-toggle-test
  (let [desktop (market-info/info-trigger true :desktop)
        mobile (market-info/mobile-details-entry)]
    (is (= "About this market" (get-in desktop [1 :aria-label])))
    (is (true? (get-in desktop [1 :aria-expanded])))
    (is (= [[:actions/toggle-market-info-panel]] (get-in desktop [1 :on :click])))
    (is (= [[:actions/toggle-market-info-panel]] (get-in mobile [1 :on :click])))
    (is (some #{"min-h-[44px]"} (support/class-values (get-in mobile [1 :class]))))))

(deftest panel-vm-builds-model-only-when-open-and-selector-closed-test
  (let [market {:key "perp:xyz:SPCX" :coin "xyz:SPCX" :symbol "SPCX-USDC" :base "SPCX" :dex "xyz"
                :market-type :perp :maxLeverage 20}
        state {:active-asset "xyz:SPCX"
               :active-market market
               :asset-selector {:market-by-key {"perp:xyz:SPCX" market}}
               :trade-ui {:market-info-open? true}
               :perp-dex-full-name-by-name {"xyz" "XYZ"}
               :perp-annotations {:concise-status :loaded
                                  :by-coin {"xyz:SPCX" {:category :stocks}}
                                  :detail-by-coin {}}}
        panel-vm (vm/active-asset-panel-vm (vm/panel-dependency-state state))]
    (is (= "xyz:SPCX" (get-in panel-vm [:market-info :coin]))
        "the memoized dependency slice carries the open flag")
    (is (true? (get-in panel-vm [:row-vm :market-info-open?])))
    (is (nil? (:market-info (vm/active-asset-panel-vm
                             (assoc-in state [:asset-selector :visible-dropdown] :asset-selector))))
        "the selector takes the space under the strip")
    (is (nil? (:market-info (vm/active-asset-panel-vm (assoc-in state [:trade-ui :market-info-open?] false)))))))
