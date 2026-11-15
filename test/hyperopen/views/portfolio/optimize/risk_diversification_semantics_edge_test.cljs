(ns hyperopen.views.portfolio.optimize.risk-diversification-semantics-edge-test
  "User-visible semantics for final-weight covariance attribution."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.risk-contributions-card :as card]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-nodes collect-strings node-by-role
                     solved-result]]))

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

(defn- row-for
  [view instrument-id]
  (first (collect-nodes
          view
          #(and (= "portfolio-optimizer-risk-breakdown-row"
                   (get-in % [1 :data-role]))
                (= instrument-id (get-in % [1 :data-instrument-id]))))))

(defn- comparison-row
  [view role attr value]
  (first (collect-nodes
          view
          #(and (= role (get-in % [1 :data-role]))
                (= value (get-in % [1 attr]))))))

(defn- nodes-with-class
  [view class-name]
  (collect-nodes view #(some #{class-name} (get-in % [1 :class]))))

(defn- render-comparison
  [current target]
  (render (-> base-result
              (assoc-in [:risk-structure :current-diversification] current)
              (assoc-in [:risk-structure :target-diversification] target))))

(deftest diversification-uses-one-shared-matrix-with-semantic-row-tones-test
  (let [current {:modeled-volatility 0.4
                 :all-move-together-volatility 0.6
                 :zero-correlation-volatility 0.4
                 :reduction-vs-all-move-together 0.2
                 :reduction-ratio-vs-all-move-together (/ 1 3)
                 :modeled-minus-zero-correlation 0.0}
        target {:modeled-volatility 0.4
                :all-move-together-volatility 0.8
                :zero-correlation-volatility 0.3
                :reduction-vs-all-move-together 0.4
                :reduction-ratio-vs-all-move-together 0.5
                :modeled-minus-zero-correlation 0.1}
        view (render (-> base-result
                         (assoc-in [:risk-structure :current-diversification]
                                   current)
                         (assoc-in [:risk-structure :target-diversification]
                                   target)))
        matrices (collect-nodes
                  view
                  #(= "portfolio-optimizer-risk-diversification-matrix"
                      (get-in % [1 :data-role])))
        matrix (first matrices)
        benchmark-rows (collect-nodes
                        matrix
                        #(= "portfolio-optimizer-risk-diversification-benchmark-row"
                            (get-in % [1 :data-role])))
        outcome-rows (collect-nodes
                      matrix
                      #(= "portfolio-optimizer-risk-diversification-outcome-row"
                          (get-in % [1 :data-role])))
        all-move (comparison-row matrix
                                 "portfolio-optimizer-risk-diversification-benchmark-row"
                                 :data-benchmark "all-move-together")
        zero-corr (comparison-row matrix
                                  "portfolio-optimizer-risk-diversification-benchmark-row"
                                  :data-benchmark "zero-correlation")
        modeled (comparison-row matrix
                                "portfolio-optimizer-risk-diversification-benchmark-row"
                                :data-benchmark "modeled")
        benefit (comparison-row matrix
                                "portfolio-optimizer-risk-diversification-outcome-row"
                                :data-outcome "diversification-benefit")
        effect (comparison-row matrix
                               "portfolio-optimizer-risk-diversification-outcome-row"
                               :data-outcome "correlation-effect")
        current-markers (collect-nodes
                         matrix
                         #(= "portfolio-optimizer-risk-diversification-current-marker"
                             (get-in % [1 :data-role])))
        recommended-markers (collect-nodes
                             matrix
                             #(= "portfolio-optimizer-risk-diversification-recommended-marker"
                                 (get-in % [1 :data-role])))]
    (is (= 1 (count matrices)))
    (is (= 3 (count benchmark-rows)))
    (is (= 2 (count outcome-rows)))
    (is (nil? (node-by-role view
                            "portfolio-optimizer-risk-diversification-current")))
    (is (nil? (node-by-role view
                            "portfolio-optimizer-risk-diversification-target")))
    (is (= ["rises" "falls" "unchanged" "rises" "rises"]
           (mapv #(get-in % [1 :data-direction])
                 [all-move zero-corr modeled benefit effect])))
    (is (= ["unfavorable" "favorable" "neutral" "favorable" "unfavorable"]
           (mapv #(get-in % [1 :data-tone])
                 [all-move zero-corr modeled benefit effect])))
    (is (= 3 (count current-markers)))
    (is (= 3 (count recommended-markers)))
    (is (every? #(= "neutral" (get-in % [1 :data-tone])) current-markers))
    (is (every? #(= "recommended" (get-in % [1 :data-tone]))
                recommended-markers))
    (is (some #(str/includes? % "larger diversification benefit")
              (collect-strings benefit)))
    (is (some #(str/includes? % "more amplifying")
              (collect-strings effect)))))

(deftest shared-matrix-exposes-complete-table-semantics-test
  (let [current {:modeled-volatility 0.4
                 :all-move-together-volatility 0.6
                 :zero-correlation-volatility 0.3
                 :reduction-vs-all-move-together 0.2
                 :reduction-ratio-vs-all-move-together (/ 1 3)
                 :modeled-minus-zero-correlation 0.1}
        view (render-comparison current
                                (assoc current :modeled-volatility 0.35))
        matrix (node-by-role
                view "portfolio-optimizer-risk-diversification-matrix")
        nodes-for-role (fn [role]
                         (collect-nodes matrix #(= role (get-in % [1 :role]))))
        rows (nodes-for-role "row")
        data-rows (filterv #(some #{"rowheader"}
                                  (map (fn [child]
                                         (get-in child [1 :role]))
                                       (drop 2 %)))
                           rows)
        lanes (nodes-with-class matrix "optimizer-risk-diversification-lane")
        rails (nodes-with-class matrix "optimizer-risk-diversification-rail")
        connectors (nodes-with-class
                    matrix "optimizer-risk-diversification-connector")
        markers (nodes-with-class matrix "optimizer-risk-diversification-marker")]
    (is (= "table" (get-in matrix [1 :role])))
    (is (= 3 (count (nodes-for-role "rowgroup"))))
    (is (= 6 (count rows)))
    (is (= 5 (count (nodes-for-role "columnheader"))))
    (is (some #{"Shared scale"}
              (mapcat collect-strings (nodes-for-role "columnheader"))))
    (is (= 5 (count (nodes-for-role "rowheader"))))
    (is (= 20 (count (nodes-for-role "cell"))))
    (is (= 5 (count data-rows)))
    (is (every? #(= ["rowheader" "cell" "cell" "cell" "cell"]
                    (mapv (fn [child] (get-in child [1 :role]))
                          (drop 2 %)))
                data-rows))
    (is (= 3 (count lanes)))
    (is (every? #(= "cell" (get-in % [1 :role])) lanes))
    (doseq [lane lanes]
      (let [graphics (collect-nodes lane #(= "img" (get-in % [1 :role])))
            graphic (first graphics)]
        (is (= 1 (count graphics)))
        (is (string? (get-in graphic [1 :aria-label])))
        (is (str/includes? (get-in graphic [1 :aria-label]) "Current"))
        (is (str/includes? (get-in graphic [1 :aria-label]) "Recommended"))))
    (is (every? #(= "cell" (get-in % [1 :role]))
                (nodes-with-class
                 matrix "optimizer-risk-diversification-outcome-spacer")))
    (is (every? true? (map #(get-in % [1 :aria-hidden])
                           (concat rails connectors markers))))))

(deftest visible-changes-use-signed-points-and-match-rounded-direction-test
  (let [current {:modeled-volatility 0.4
                 :all-move-together-volatility 0.6
                 :zero-correlation-volatility 0.3
                 :reduction-vs-all-move-together 0.2
                 :reduction-ratio-vs-all-move-together 0.5
                 :modeled-minus-zero-correlation 0.1}
        below-threshold (assoc current
                               :modeled-volatility 0.40049
                               :all-move-together-volatility 0.7
                               :zero-correlation-volatility 0.2
                               :reduction-ratio-vs-all-move-together 0.6
                               :modeled-minus-zero-correlation 0.05)
        above-threshold (assoc below-threshold :modeled-volatility 0.40051)
        below-view (render-comparison current below-threshold)
        above-view (render-comparison current above-threshold)
        below-matrix (node-by-role
                      below-view "portfolio-optimizer-risk-diversification-matrix")
        modeled-below (comparison-row
                       below-matrix
                       "portfolio-optimizer-risk-diversification-benchmark-row"
                       :data-benchmark "modeled")
        modeled-above (comparison-row
                       above-view
                       "portfolio-optimizer-risk-diversification-benchmark-row"
                       :data-benchmark "modeled")
        change-cells (nodes-with-class
                      below-matrix "optimizer-risk-diversification-change")
        numeric-change-strings (mapv (comp first collect-strings) change-cells)]
    (is (= ["+10.0 pts" "-10.0 pts" "0.0 pts" "+10.0 pts" "-5.0 pts"]
           numeric-change-strings))
    (is (not-any? #(str/includes? % "%") numeric-change-strings))
    (is (= "unchanged" (get-in modeled-below [1 :data-direction])))
    (is (some #{"0.0 pts"} (collect-strings modeled-below)))
    (is (= "rises" (get-in modeled-above [1 :data-direction])))
    (is (some #{"+0.1 pts"} (collect-strings modeled-above)))))

(deftest correlation-effect-copy-respects-sign-and-zero-crossings-test
  (let [summary {:modeled-volatility 0.4
                 :all-move-together-volatility 0.6
                 :zero-correlation-volatility 0.3
                 :reduction-vs-all-move-together 0.2
                 :reduction-ratio-vs-all-move-together (/ 1 3)
                 :modeled-minus-zero-correlation 0.1}]
    (doseq [[label current-effect recommended-effect expected]
            [["positive becomes less positive" 0.10 0.05 "less amplifying"]
             ["negative becomes more negative" -0.05 -0.10 "more offsetting"]
             ["positive crosses below zero" 0.05 -0.05 "turns offsetting"]
             ["negative crosses above zero" -0.05 0.05 "turns amplifying"]]]
      (testing label
        (let [view (render-comparison
                    (assoc summary :modeled-minus-zero-correlation current-effect)
                    (assoc summary :modeled-minus-zero-correlation
                           recommended-effect))
              effect (comparison-row
                      view
                      "portfolio-optimizer-risk-diversification-outcome-row"
                      :data-outcome "correlation-effect")]
          (is (some #(str/includes? % expected) (collect-strings effect))))))))

(deftest target-only-matrix-renders-unavailable-comparison-without-fake-marks-test
  (let [view (render base-result)
        matrix (node-by-role
                view "portfolio-optimizer-risk-diversification-matrix")
        current-markers (collect-nodes
                         matrix
                         #(= "portfolio-optimizer-risk-diversification-current-marker"
                             (get-in % [1 :data-role])))
        recommended-markers (collect-nodes
                             matrix
                             #(= "portfolio-optimizer-risk-diversification-recommended-marker"
                                 (get-in % [1 :data-role])))
        connectors (nodes-with-class
                    matrix "optimizer-risk-diversification-connector")
        summary (node-by-role
                 view "portfolio-optimizer-risk-diversification-decision-summary")
        em-dashes (filter #{"—"} (collect-strings matrix))]
    (is (= 0 (count current-markers)))
    (is (= 0 (count connectors)))
    (is (= 3 (count recommended-markers)))
    (is (= 10 (count em-dashes)))
    (is (some #(str/includes? (str/lower-case %) "unavailable")
              (collect-strings summary)))))

(deftest risk-card-separates-balance-diversification-and-attribution-test
  (let [result (assoc-in base-result
                         [:risk-structure :current-diversification]
                         {:modeled-volatility 0.24
                          :all-move-together-volatility 0.32
                          :zero-correlation-volatility 0.20
                          :reduction-vs-all-move-together 0.08
                          :reduction-ratio-vs-all-move-together 0.25
                          :modeled-minus-zero-correlation 0.04})
        view (render result)
        strings (set (collect-strings view))
        comparison (node-by-role
                    view "portfolio-optimizer-risk-diversification-comparison")
        row (row-for view "perp:BTC")]
    (testing "Risk Balance explains its narrow optimizer objective"
      (is (some #(str/includes? % "balances risk ownership") strings))
      (is (some #(str/includes? % "does not minimize total volatility") strings)))
    (testing "Diversification compares both books and all three benchmarks"
      (is (some? comparison))
      (is (every? (set (collect-strings comparison))
                  ["Current" "Recommended" "All move together"
                   "Zero correlation" "Modeled"])))
    (testing "attribution is explicitly additive and non-causal"
      (is (some #(str/includes? % "final-weight attribution") strings))
      (is (some #(str/includes? % "not removal impact") strings))
      (is (= (get-in row [1 :data-own-end])
             (get-in row [1 :data-cross-start])))
      (is (= (get-in row [1 :data-cross-end])
             (get-in row [1 :data-net-end]))))
    (testing "the retired causal-overclaim labels are absent"
      (is (not (contains? strings "Risk if held in isolation")))
      (is (not (contains? strings "Diversification Effect"))))))

(deftest legacy-structure-keeps-existing-risk-views-test
  (let [legacy (update base-result :risk-structure
                       dissoc :current-diversification :target-diversification)
        view (render legacy)
        strings (set (collect-strings view))]
    (is (some? (node-by-role view
                             "portfolio-optimizer-risk-view-tab-contribution")))
    (is (some? (node-by-role view
                             "portfolio-optimizer-risk-selected-breakdown")))
    (is (some? (node-by-role view
                             "portfolio-optimizer-risk-view-tab-correlation")))
    (is (some? (node-by-role view
                             "portfolio-optimizer-risk-view-tab-risk-return")))
    (is (or (nil? (node-by-role
                   view "portfolio-optimizer-risk-diversification-comparison"))
            (some #(str/includes? % "Re-run") strings)))
    (is (not (some #(re-find #"All move together.*0(?:\.0)?%" %) strings)))))

(deftest bridge-directions-use-words-and-economic-tones-test
  (let [view (render base-result)
        positive (row-for view "perp:BTC")
        negative (row-for view "spot:PURR")]
    (is (= "amplifies" (get-in positive [1 :data-cross-effect])))
    (is (= "offsets" (get-in negative [1 :data-cross-effect])))
    (is (= "risk-amplifying" (get-in positive [1 :data-cross-tone])))
    (is (= "risk-offsetting" (get-in negative [1 :data-cross-tone])))
    (is (str/includes? (get-in positive [1 :title]) "amplifies"))
    (is (str/includes? (get-in negative [1 :title]) "offsets"))
    (doseq [row [positive negative]]
      (is (str/includes? (get-in row [1 :title]) "starts"))
      (is (str/includes? (get-in row [1 :title]) "ends")))))

(deftest net-and-negative-contributor-kpi-stay-judgment-neutral-test
  (doseq [net [0.25 -0.25 0.0]]
    (let [result (-> base-result
                     (assoc-in [:risk-contributions
                                :relative-contributions-by-instrument
                                "perp:BTC"] net)
                     (assoc-in [:risk-contributions :relative-contributions 0]
                               net)
                     (assoc-in [:risk-structure
                                :standalone-share-by-instrument
                                "perp:BTC"] 0.25)
                     (assoc-in [:risk-structure
                                :diversification-share-by-instrument
                                "perp:BTC"] (- net 0.25)))
          view (render result)
          row (row-for view "perp:BTC")
          net-node (node-by-role row "portfolio-optimizer-risk-breakdown-net")]
      (is (= "target" (get-in net-node [1 :data-tone])))))
  (let [view (render (assoc-in base-result
                               [:risk-contributions :negative-contribution-count]
                               1))
        kpi (node-by-role view
                          "portfolio-optimizer-risk-contributions-negative")]
    (is (= "neutral" (get-in kpi [1 :data-tone])))
    (is (some #{"1"} (collect-strings kpi)))))

(deftest attribution-copy-forbids-removal-overclaims-test
  (let [view (render base-result)
        strings (set (collect-strings view))]
    (is (every? strings ["Own-variance term" "Cross-covariance effect"
                         "Net risk contribution"]))
    (is (some #(str/includes? % "final-weight attribution") strings))
    (is (not (contains? strings "Risk if held in isolation")))
    (is (not (contains? strings "Diversification Effect")))
    (is (not-any? #(or (str/includes? % "Removing this asset would")
                       (str/includes? % "Without this asset"))
                  strings))))

(deftest legacy-results-keep-stable-view-identities-test
  (let [legacy (update base-result :risk-structure
                       dissoc :target-diversification :current-diversification)
        view (render legacy)
        strings (set (collect-strings view))]
    (doseq [role ["portfolio-optimizer-risk-view-tab-contribution"
                  "portfolio-optimizer-risk-view-tab-breakdown"
                  "portfolio-optimizer-risk-view-tab-correlation"
                  "portfolio-optimizer-risk-view-tab-risk-return"]]
      (is (some? (node-by-role view role))))
    (is (some? (node-by-role view
                             "portfolio-optimizer-risk-selected-breakdown")))
    (is (or (nil? (node-by-role
                   view "portfolio-optimizer-risk-diversification-comparison"))
            (some #(str/includes? % "Re-run") strings)))))

(deftest current-only-summary-never-renders-a-comparison-test
  (let [summary {:modeled-volatility 0.2
                 :all-move-together-volatility 0.3
                 :zero-correlation-volatility 0.18
                 :reduction-vs-all-move-together 0.1
                 :reduction-ratio-vs-all-move-together (/ 1 3)
                 :modeled-minus-zero-correlation 0.02}
        current-only (-> base-result
                         (update :risk-structure dissoc
                                 :target-diversification)
                         (assoc-in [:risk-structure :current-diversification]
                                   summary))
        view (render current-only)]
    (is (nil? (node-by-role
               view "portfolio-optimizer-risk-diversification-comparison")))
    (is (nil? (node-by-role
               view "portfolio-optimizer-risk-diversification-current")))))

(deftest rounded-zero-cross-effects-render-neutral-test
  (doseq [[effect expected tone]
          [[0.00049 "neutral" "neutral"]
           [0.00051 "amplifies" "risk-amplifying"]
           [-0.00051 "offsets" "risk-offsetting"]]]
    (let [net (+ 0.4 effect)
          result (-> base-result
                     (assoc-in [:risk-structure
                                :diversification-share-by-instrument
                                "perp:BTC"] effect)
                     (assoc-in [:risk-contributions
                                :relative-contributions-by-instrument
                                "perp:BTC"] net)
                     (assoc-in [:risk-contributions :relative-contributions 0]
                               net))
          row (row-for (render result) "perp:BTC")]
      (is (= expected (get-in row [1 :data-cross-effect])))
      (is (= tone (get-in row [1 :data-cross-tone]))))))
