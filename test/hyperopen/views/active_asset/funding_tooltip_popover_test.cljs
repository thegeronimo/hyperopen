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
