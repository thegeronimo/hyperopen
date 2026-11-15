(ns hyperopen.views.portfolio.optimize.equal-risk-impact-strip-test
  "The WHAT CHANGES IF YOU EXECUTE strip: current → target chips for risk
  imbalance, modeled volatility, and (when the leverage gate passes) modeled
  one-year outcome — plus the honest degradations: no strip for
  non-equal-risk results or when fewer than two chips have data."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-structure
             :as structure-model]
            [hyperopen.views.portfolio.optimize.equal-risk-impact-strip :as strip]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-strings node-by-role solved-result]]))

(def ^:private equal-risk-result
  (-> solved-result
      (assoc :solver {:strategy :sequential-equal-risk
                      :objective-kind :equal-risk})
      (assoc :risk-contributions
             {:instrument-ids ["perp:BTC" "spot:PURR"]
              :relative-contributions [0.62 0.38]
              :target-relative-contributions [0.5 0.5]
              :relative-contributions-by-instrument {"perp:BTC" 0.62
                                                     "spot:PURR" 0.38}
              :rms-error 0.12
              :max-absolute-error 0.12
              :negative-contribution-count 0
              :quality :approximate})
      (assoc :current-risk-contributions
             {:relative-contributions-by-instrument {"perp:BTC" 0.9
                                                     "spot:PURR" 0.1}
              :rms-error 0.4
              :max-absolute-error 0.4})))

(defn- strings-of
  [node]
  (set (collect-strings node)))

(deftest strip-renders-imbalance-and-volatility-chips-test
  (let [node (strip/equal-risk-impact-strip equal-risk-result)
        strings (strings-of node)]
    (is (some? (node-by-role node
                             "portfolio-optimizer-equal-risk-impact-strip")))
    (is (contains? strings "What changes if you execute"))
    (testing "the imbalance chip reads current → target and links to the balance tab"
      (let [chip (node-by-role node
                               "portfolio-optimizer-equal-risk-impact-balance")]
        (is (= :label (first chip))
            "the chip is a <label> deep-linking a tab radio")
        (is (= (structure-model/risk-view-radio-id equal-risk-result
                                                   "contribution")
               (get-in chip [1 :for])))
        (is (contains? (strings-of chip) "40.0 → 12.0 pts"))))
    (testing "the volatility chip reads current → target"
      (let [chip (node-by-role node
                               "portfolio-optimizer-equal-risk-impact-volatility")]
        (is (contains? (strings-of chip) "24.0% → 42.0%"))
        (is (= :div (first chip))
            "no :risk-structure section → no Diversification tab to link")
        (is (some #(str/includes? % "more total volatility")
                  (collect-strings chip))
            "an 18-pt rise is named as taking more volatility")))
    (testing "the outcome chip respects the leverage gate (0.9x gross, 42% σ)"
      (is (nil? (node-by-role node
                              "portfolio-optimizer-equal-risk-impact-outcome"))))))

(deftest strip-outcome-chip-appears-with-the-leverage-panel-test
  (let [levered (assoc-in equal-risk-result
                          [:diagnostics :gross-exposure] 2.5)
        node (strip/equal-risk-impact-strip levered)
        chip (node-by-role node
                           "portfolio-optimizer-equal-risk-impact-outcome")
        chip-strings (collect-strings chip)]
    (is (some? chip))
    (is (some #(str/includes? % "$") chip-strings)
        "median ending wealth renders in dollars with $100k capital")
    (is (some #(str/includes? % " → ") chip-strings))
    (is (some #(str/includes? % "Touch −50% odds") chip-strings)
        "the sub line carries the ruin-floor odds current → target")))

(deftest strip-volatility-chip-links-the-diversification-tab-when-present-test
  (let [structured (assoc equal-risk-result
                          :risk-structure
                          {:target-diversification
                           {:modeled-volatility 0.40
                            :all-move-together-volatility 0.60
                            :zero-correlation-volatility 0.35
                            :reduction-vs-all-move-together 0.20
                            :reduction-ratio-vs-all-move-together (/ 1 3)
                            :modeled-minus-zero-correlation 0.05}
                           :current-diversification
                           {:modeled-volatility 0.24
                            :all-move-together-volatility 0.32
                            :zero-correlation-volatility 0.20
                            :reduction-vs-all-move-together 0.08
                            :reduction-ratio-vs-all-move-together 0.25
                            :modeled-minus-zero-correlation 0.04}
                           :standalone-share-by-instrument {"perp:BTC" 0.75
                                                            "spot:PURR" 0.5}
                           :diversification-share-by-instrument
                           {"perp:BTC" -0.13 "spot:PURR" -0.12}
                           :pnl-portfolio-correlation-by-instrument
                           {"perp:BTC" 0.9 "spot:PURR" 0.55}
                           :correlation {:instrument-ids ["perp:BTC"
                                                          "spot:PURR"]
                                         :matrix [[1.0 0.6] [0.6 1.0]]
                                         :hidden-count 0}})
        chip (node-by-role (strip/equal-risk-impact-strip structured)
                           "portfolio-optimizer-equal-risk-impact-volatility")]
    (is (= :label (first chip)))
    (is (= (structure-model/risk-view-radio-id structured "breakdown")
           (get-in chip [1 :for])))
    (is (contains? (strings-of chip) "24.0% → 40.0%")
        "the persisted structure benchmarks win over the top-level volatilities")))

(deftest strip-degrades-to-nil-without-a-story-test
  (testing "non-equal-risk results have no strip"
    (is (nil? (strip/equal-risk-impact-strip solved-result))))
  (testing "one data-bearing chip is noise, not a summary"
    (is (nil? (strip/equal-risk-impact-strip
               (-> equal-risk-result
                   (dissoc :current-risk-contributions)
                   (assoc :current-volatility nil))))
        "no current contributions and no current volatility → nothing to compare"))
  (testing "missing current volatility leaves only the imbalance chip — hide"
    (let [node (strip/equal-risk-impact-strip
                (assoc equal-risk-result :current-volatility nil))]
      (is (nil? node)
          "imbalance alone is one chip — the strip stays honest and hides")))
  (testing "volatility + imbalance without leverage keeps the strip"
    (is (some? (strip/equal-risk-impact-strip equal-risk-result)))))
