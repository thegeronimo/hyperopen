(ns hyperopen.views.account-info.tabs.outcomes-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tabs.outcomes :as outcomes-tab]))

(def ^:private sample-row
  {:key "outcome-#0"
   :title "BTC above 78213 on May 3 at 2:00 AM?"
   :market-key "outcome:0"
   :raw-coin "+0"
   :side-coin "#0"
   :side-name "Yes"
   :type-label "Outcome"
   :size 19
   :position-value 10.83798
   :quote "USDH"
   :entry-price 0.5809
   :mark-price 0.57042
   :pnl-value -0.20
   :roe-pct -1.8})

(deftest outcomes-tab-renders-hyperliquid-style-outcome-columns-without-chips-test
  (let [content (outcomes-tab/outcomes-tab-content {:outcomes [sample-row]})
        header-strings (set (hiccup/collect-strings (hiccup/tab-header-node content)))
        row (hiccup/first-viewport-row content)
        outcome-cell (first (hiccup/node-children row))
        outcome-cell-strings (set (hiccup/collect-strings outcome-cell))
        row-strings (set (hiccup/collect-strings row))]
    (is (contains? header-strings "Outcome"))
    (is (contains? header-strings "Size"))
    (is (contains? header-strings "Position Value"))
    (is (contains? header-strings "Entry Price"))
    (is (contains? header-strings "Mark Price"))
    (is (contains? header-strings "PNL (ROE %)"))
    (is (contains? header-strings "Actions"))
    (is (= #{"BTC above 78213 on May 3 at 2:00 AM?"} outcome-cell-strings))
    (is (not (contains? row-strings "Outcome")))
    (is (not (contains? row-strings "#0 / outcome:0")))
    (is (contains? row-strings "19 Yes"))
    (is (contains? row-strings "10.84 USDH"))
    (is (contains? row-strings "0.58090"))
    (is (contains? row-strings "0.57042"))
    (is (contains? row-strings "-$0.20 (-1.8%)"))))

(deftest outcomes-tab-renders-reduce-action-and-active-popover-test
  (let [popover (assoc (position-reduce/default-popover-state)
                       :open? true
                       :position-key (:key sample-row)
                       :position-side :outcome
                       :position-side-label "Yes"
                       :position-size 19
                       :size-percent-input "100")
        content (outcomes-tab/outcomes-tab-content {:outcomes [sample-row]
                                                    :reduce-popover popover})
        row (hiccup/first-viewport-row content)
        reduce-button (hiccup/find-first-node
                       row
                       #(and (= :button (first %))
                             (contains? (hiccup/direct-texts %) "Reduce")))
        reduce-actions (get-in reduce-button [1 :on :click])
        panel-node (hiccup/find-first-node
                    row
                    #(= "true" (get-in % [1 :data-position-reduce-surface])))]
    (is (some? reduce-button))
    (is (= :actions/open-position-reduce-popover
           (first (first reduce-actions))))
    (is (= sample-row
           (second (first reduce-actions))))
    (is (= :event.currentTarget/bounds
           (nth (first reduce-actions) 2)))
    (is (= "true" (get-in reduce-button [1 :data-position-reduce-trigger])))
    (is (some? panel-node))))

(deftest outcomes-tab-read-only-mode-omits-reduce-actions-test
  (let [content (outcomes-tab/outcomes-tab-content {:outcomes [sample-row]
                                                    :read-only? true})
        row (hiccup/first-viewport-row content)
        reduce-button (hiccup/find-first-node
                       row
                       #(and (= :button (first %))
                             (contains? (hiccup/direct-texts %) "Reduce")))]
    (is (nil? reduce-button))))

(deftest outcomes-tab-title-selects-and-navigates-to-outcome-market-test
  (let [content (outcomes-tab/outcomes-tab-content {:outcomes [sample-row]})
        row (hiccup/first-viewport-row content)
        outcome-button (hiccup/find-first-node row #(= "outcome-market-select"
                                                       (get-in % [1 :data-role])))]
    (is (some? outcome-button))
    (is (= [[:actions/select-asset-by-market-key "outcome:0"]
            [:actions/navigate "/trade/%230"]]
           (get-in outcome-button [1 :on :click])))))

(deftest outcomes-tab-title-highlight-follows-outcome-side-test
  (let [no-row (assoc sample-row
                      :key "outcome-#1"
                      :raw-coin "+1"
                      :side-coin "#1"
                      :side-name "No"
                      :side-index 1)
        content (outcomes-tab/outcomes-tab-content {:outcomes [sample-row no-row]})
        rows (vec (hiccup/node-children (hiccup/tab-rows-viewport-node content)))
        yes-cell (first (hiccup/node-children (first rows)))
        no-cell (first (hiccup/node-children (second rows)))
        yes-label (hiccup/find-first-node yes-cell #(contains? (hiccup/direct-texts %)
                                                               (:title sample-row)))
        no-label (hiccup/find-first-node no-cell #(contains? (hiccup/direct-texts %)
                                                             (:title no-row)))]
    (is (= "linear-gradient(90deg, rgb(31, 166, 125) 0px, rgb(31, 166, 125) 4px, rgb(11, 50, 38) 4px, transparent 100%) transparent"
           (get-in yes-cell [1 :style :background])))
    (is (contains? (hiccup/node-class-set yes-label) "text-emerald-300"))
    (is (= "transparent linear-gradient(90deg, rgb(237, 112, 136) 0px, rgb(237, 112, 136) 4px, rgba(52, 36, 46, 1) 0%, transparent 100%)"
           (get-in no-cell [1 :style :background])))
    (is (contains? (hiccup/node-class-set no-label) "text-red-300"))))

(deftest outcomes-tab-empty-state-is-specific-test
  (let [content (outcomes-tab/outcomes-tab-content {:outcomes []})
        strings (set (hiccup/collect-strings content))]
    (is (contains? strings "No active outcomes"))))
