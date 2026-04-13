(ns hyperopen.views.active-asset.funding-tooltip-popover-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.active-asset.funding-tooltip :as funding-tooltip]
            [hyperopen.views.active-asset.test-support :as support]))

(deftest tooltip-click-pinnable-dismiss-target-clears-visible-state-test
  (let [pin-id (support/funding-tooltip-pin-id "BTC")
        tooltip-node (funding-tooltip/funding-tooltip-popover
                      {:trigger [:span "Funding"]
                       :body [:div "Body"]
                       :position "top"
                       :open? true
                       :pin-id pin-id
                       :pinned? true})
        dismiss-target (support/find-first-node tooltip-node
                                                #(contains? (set (support/class-values (get-in % [1 :class])))
                                                            "fixed"))]
    (is (= [[:actions/set-funding-tooltip-visible pin-id false]]
           (rest (get-in dismiss-target [1 :on :click]))))
    (is (= [[:actions/set-funding-tooltip-pinned pin-id false]]
           [(first (get-in dismiss-target [1 :on :click]))]))))

(deftest tooltip-click-pinnable-trigger-toggles-pinned-state-test
  (let [pin-id (support/funding-tooltip-pin-id "BTC")
        closed-tooltip (funding-tooltip/funding-tooltip-popover
                        {:trigger [:span "Funding"]
                         :body [:div "Body"]
                         :position "top"
                         :open? false
                         :pin-id pin-id
                         :pinned? false})
        open-tooltip (funding-tooltip/funding-tooltip-popover
                      {:trigger [:span "Funding"]
                       :body [:div "Body"]
                       :position "top"
                       :open? true
                       :pin-id pin-id
                       :pinned? true})
        closed-trigger (support/find-first-node closed-tooltip #(= :button (first %)))
        open-trigger (support/find-first-node open-tooltip #(= :button (first %)))]
    (is (= [[:actions/set-funding-tooltip-pinned pin-id true]
            [:actions/set-funding-tooltip-visible pin-id true]]
           (get-in closed-trigger [1 :on :click])))
    (is (= [[:actions/set-funding-tooltip-pinned pin-id false]
            [:actions/set-funding-tooltip-visible pin-id false]]
           (get-in open-trigger [1 :on :click])))))

(deftest tooltip-renders-stable-browser-targeting-roles-test
  (let [pin-id (support/funding-tooltip-pin-id "BTC")
        closed-tooltip (funding-tooltip/funding-tooltip-popover
                        {:trigger [:span "Funding"]
                         :body [:div "Body"]
                         :position "top"
                         :open? false
                         :pin-id pin-id
                         :pinned? false})
        open-tooltip (funding-tooltip/funding-tooltip-popover
                      {:trigger [:span "Funding"]
                       :body [:div "Body"]
                       :position "top"
                       :open? true
                       :pin-id pin-id
                       :pinned? true})
        closed-trigger (support/find-node-by-role closed-tooltip "active-asset-funding-trigger")
        open-trigger (support/find-node-by-role open-tooltip "active-asset-funding-trigger")
        closed-panel (support/find-node-by-role closed-tooltip "active-asset-funding-tooltip")
        open-panel (support/find-node-by-role open-tooltip "active-asset-funding-tooltip")]
    (is (= :button (first closed-trigger)))
    (is (= :button (first open-trigger)))
    (is (nil? closed-panel))
    (is (= :div (first open-panel)))))

(deftest tooltip-click-pinnable-renders-body-when-open-test
  (let [pin-id (support/funding-tooltip-pin-id "BTC")
        closed-tooltip (funding-tooltip/funding-tooltip-popover
                        {:trigger [:span "Funding"]
                         :body [:div "Body"]
                         :position "top"
                         :open? false
                         :pin-id pin-id
                         :pinned? false})
        open-tooltip (funding-tooltip/funding-tooltip-popover
                      {:trigger [:span "Funding"]
                       :body [:div "Body"]
                       :position "top"
                       :open? true
                       :pin-id pin-id
                         :pinned? true})]
    (is (not (contains? (set (support/collect-strings closed-tooltip)) "Body")))
    (is (contains? (set (support/collect-strings open-tooltip)) "Body"))))

(deftest tooltip-popover-does-not-render-mobile-sheet-roles-test
  (let [pin-id (support/funding-tooltip-pin-id "BTC")
        open-tooltip (funding-tooltip/funding-tooltip-popover
                      {:trigger [:span "Funding"]
                       :body [:div "Body"]
                       :position "bottom"
                       :open? true
                       :pin-id pin-id
                       :pinned? true})]
    (is (nil? (support/find-node-by-role open-tooltip "active-asset-funding-mobile-sheet-layer")))
    (is (nil? (support/find-node-by-role open-tooltip "active-asset-funding-mobile-sheet-backdrop")))
    (is (nil? (support/find-node-by-role open-tooltip "active-asset-funding-mobile-sheet")))))

(deftest tooltip-mobile-sheet-renders-stable-browser-targeting-roles-test
  (let [pin-id (support/funding-tooltip-pin-id "BTC")
        closed-sheet (funding-tooltip/funding-tooltip-mobile-sheet
                      {:trigger [:span "Funding"]
                       :body [:div {:data-role "active-asset-funding-mobile-sheet"
                                    :role "dialog"
                                    :aria-modal true}
                              "Body"]
                       :open? false
                       :pin-id pin-id
                       :pinned? false})
        open-sheet (funding-tooltip/funding-tooltip-mobile-sheet
                    {:trigger [:span "Funding"]
                     :body [:div {:data-role "active-asset-funding-mobile-sheet"
                                  :role "dialog"
                                  :aria-modal true}
                            "Body"]
                     :open? true
                     :pin-id pin-id
                     :pinned? true})
        trigger-node (support/find-node-by-role open-sheet "active-asset-funding-trigger")
        layer-node (support/find-node-by-role open-sheet "active-asset-funding-mobile-sheet-layer")
        backdrop-node (support/find-node-by-role open-sheet "active-asset-funding-mobile-sheet-backdrop")
        sheet-node (support/find-node-by-role open-sheet "active-asset-funding-mobile-sheet")]
    (is (= :button (first trigger-node)))
    (is (nil? (support/find-node-by-role closed-sheet "active-asset-funding-mobile-sheet-layer")))
    (is (some? layer-node))
    (is (some? backdrop-node))
    (is (some? sheet-node))
    (is (= [[:actions/set-funding-tooltip-pinned pin-id false]
            [:actions/set-funding-tooltip-visible pin-id false]]
           (get-in backdrop-node [1 :on :click])))
    (is (= "dialog" (get-in sheet-node [1 :role])))
    (is (= true (get-in sheet-node [1 :aria-modal])))
    (is (contains? (set (support/collect-strings open-sheet)) "Body"))))
