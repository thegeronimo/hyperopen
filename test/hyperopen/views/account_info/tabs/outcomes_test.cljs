(ns hyperopen.views.account-info.tabs.outcomes-test
  (:require [cljs.test :refer-macros [deftest is]]
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
    (is (= #{"BTC above 78213 on May 3 at 2:00 AM?"} outcome-cell-strings))
    (is (not (contains? row-strings "Outcome")))
    (is (not (contains? row-strings "#0 / outcome:0")))
    (is (contains? row-strings "19 Yes"))
    (is (contains? row-strings "10.84 USDH"))
    (is (contains? row-strings "0.58090"))
    (is (contains? row-strings "0.57042"))
    (is (contains? row-strings "-$0.20 (-1.8%)"))))

(deftest outcomes-tab-empty-state-is-specific-test
  (let [content (outcomes-tab/outcomes-tab-content {:outcomes []})
        strings (set (hiccup/collect-strings content))]
    (is (contains? strings "No active outcomes"))))
