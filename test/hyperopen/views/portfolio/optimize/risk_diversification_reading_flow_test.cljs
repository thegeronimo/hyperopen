(ns hyperopen.views.portfolio.optimize.risk-diversification-reading-flow-test
  "Reading-flow semantics for the Equal Risk DIVERSIFICATION tab (2026-07-16
  comprehension pass): the verdict sentence leads the tab, the volatility
  bridge explains the recommended book between verdict and matrix, and
  sub-threshold changes tone neutral with '≈ unchanged' copy instead of
  red/green noise. Split from risk-diversification-semantics-edge-test for
  the namespace-size cap; fixtures mirror that namespace's base-result."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.risk-contributions-card :as card]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-nodes collect-strings data-role-order index-of
                     node-by-role solved-result]]))

(def ^:private base-result
  (assoc solved-result
         :solver {:strategy :sequential-equal-risk :objective-kind :equal-risk}
         :risk-contributions
         {:method :signed-euler-volatility
          :instrument-ids ["perp:BTC" "spot:PURR"]
          :relative-contributions [0.6 0.4]
          :target-relative-contributions [0.5 0.5]
          :relative-contributions-by-instrument {"perp:BTC" 0.6
                                                 "spot:PURR" 0.4}
          :target-relative-contributions-by-instrument {"perp:BTC" 0.5
                                                        "spot:PURR" 0.5}
          :sum-relative-contributions 1.0
          :rms-error 0.1
          :max-absolute-error 0.1
          :negative-contribution-count 0
          :quality :approximate}
         :risk-structure
         {:method :signed-euler-decomposition
          :portfolio-volatility 0.4
          :target-diversification
          {:modeled-volatility 0.4
           :all-move-together-volatility 0.6
           :zero-correlation-volatility 0.35
           :reduction-vs-all-move-together 0.2
           :reduction-ratio-vs-all-move-together (/ 1 3)
           :modeled-minus-zero-correlation 0.05}
          :standalone-share-by-instrument {"perp:BTC" 0.4 "spot:PURR" 0.5}
          :diversification-share-by-instrument {"perp:BTC" 0.2
                                                "spot:PURR" -0.1}
          :pnl-portfolio-correlation-by-instrument {"perp:BTC" 0.8
                                                    "spot:PURR" 0.4}
          :correlation {:instrument-ids ["perp:BTC" "spot:PURR"]
                        :matrix [[1.0 0.5] [0.5 1.0]]
                        :hidden-count 0}}))

(defn- render
  [result]
  (card/risk-contributions-card result))

(defn- comparison-row
  [view role attr value]
  (first (collect-nodes
          view
          #(and (= role (get-in % [1 :data-role]))
                (= value (get-in % [1 attr]))))))

(defn- render-comparison
  [current target]
  (render (-> base-result
              (assoc-in [:risk-structure :current-diversification] current)
              (assoc-in [:risk-structure :target-diversification] target))))

(deftest sub-threshold-changes-tone-neutral-and-read-unchanged-test
  (let [current {:modeled-volatility 0.4
                 :all-move-together-volatility 0.6
                 :zero-correlation-volatility 0.3
                 :reduction-vs-all-move-together 0.2
                 :reduction-ratio-vs-all-move-together 0.5
                 :modeled-minus-zero-correlation 0.1}
        sub-view (render-comparison current
                                    (assoc current :modeled-volatility 0.404))
        material-view (render-comparison current
                                         (assoc current
                                                :modeled-volatility 0.406))
        modeled-row (fn [view]
                      (comparison-row
                       view
                       "portfolio-optimizer-risk-diversification-benchmark-row"
                       :data-benchmark "modeled"))
        sub-row (modeled-row sub-view)
        material-row (modeled-row material-view)]
    (testing "+0.4 pts: factual direction, neutral tone, '≈ unchanged' copy"
      (is (= "rises" (get-in sub-row [1 :data-direction])))
      (is (= "neutral" (get-in sub-row [1 :data-tone])))
      (is (some #{"+0.4 pts"} (collect-strings sub-row))
          "the exact signed value is never rounded away")
      (is (some #{"≈ unchanged"} (collect-strings sub-row)))
      (is (not-any? #{"rises"} (collect-strings sub-row))))
    (testing "+0.6 pts: the direction verdict and unfavorable tone return"
      (is (= "rises" (get-in material-row [1 :data-direction])))
      (is (= "unfavorable" (get-in material-row [1 :data-tone])))
      (is (some #{"rises"} (collect-strings material-row))))))

(deftest verdict-leads-and-the-bridge-explains-the-recommended-book-test
  (let [view (render base-result)
        order (data-role-order view)
        bridge (node-by-role view "portfolio-optimizer-risk-divbridge")
        bridge-strings (collect-strings bridge)
        note-text (str/join " " (collect-strings
                                 (node-by-role
                                  view
                                  "portfolio-optimizer-risk-divbridge-note")))]
    (testing "reading order: verdict sentence, bridge, then the matrix"
      (is (< (index-of order
                       "portfolio-optimizer-risk-diversification-decision-summary")
             (index-of order "portfolio-optimizer-risk-divbridge")
             (index-of order "portfolio-optimizer-risk-diversification-matrix"))))
    (testing "the bridge carries the recommended book's three benchmarks"
      (is (some #{"Modeled 40.0%"} bridge-strings))
      (is (some #{"All move together 60.0%"} bridge-strings))
      (is (str/includes? note-text "Diversification cuts 20.0 pts"))
      (is (str/includes? note-text "add 5.0 pts")
          "the +5-pt correlation effect is named as added risk")
      (is (str/includes? note-text "independent baseline (35.0%")))
    (testing "no bridge without a valid recommended summary"
      (is (nil? (node-by-role
                 (render (update base-result :risk-structure
                                 dissoc :target-diversification
                                 :current-diversification))
                 "portfolio-optimizer-risk-divbridge"))))))
