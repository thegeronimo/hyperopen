(ns hyperopen.views.portfolio.optimize.leverage-impact-panel-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.domain.leverage-risk :as leverage-risk]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.views.portfolio.optimize.format :as opt-format]
            [hyperopen.views.portfolio.optimize.leverage-impact-panel :as panel]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-nodes collect-strings node-attr node-by-role]]))

(defn- strings-of
  [node]
  (set (collect-strings node)))

(defn- tooltip-text
  [node tip-id]
  (some->> (node-by-role node tip-id)
           collect-strings
           (str/join " ")))

(defn- whole-usd
  [value]
  (opt-format/format-usdc value {:maximum-fraction-digits 0}))

(deftest panel-hidden-below-the-leverage-gate-test
  ;; Base fixture: 0.9x gross at 28% σ — no leverage story to tell.
  (is (nil? (panel/leverage-impact-panel (fixtures/sample-solved-result)))))

(deftest levered-target-gets-modeled-dollar-outcomes-test
  (let [result (fixtures/sample-solved-result
                {:diagnostics {:gross-exposure 2.5}})
        node (panel/leverage-impact-panel result)
        strings (strings-of node)
        target-outcome (leverage-risk/outcome-model {:expected-return 0.16
                                                     :volatility 0.28})
        current-outcome (leverage-risk/outcome-model {:expected-return 0.12
                                                      :volatility 0.24})]
    (is (some? node))
    (is (contains? strings "One-year modeled leverage impact"))
    (is (contains? strings "Modeled"))
    ;; Median rows carry the modeled dollars for both books (capital $100k).
    (is (contains? strings
                   (whole-usd (* 100000 (:median-ending-factor target-outcome)))))
    (is (contains? strings
                   (whole-usd (* 100000 (:median-ending-factor current-outcome)))))
    (is (some? (node-by-role node
                             "portfolio-optimizer-leverage-impact-median-current")))
    (is (some? (node-by-role node
                             "portfolio-optimizer-leverage-impact-median-target")))
    ;; The mockup's tile row: mean, 5th percentile, and both loss odds.
    (is (contains? (strings-of (node-by-role node
                                             "portfolio-optimizer-leverage-impact-mean"))
                   (whole-usd (* 100000 (:mean-ending-factor target-outcome)))))
    (is (some? (node-by-role node "portfolio-optimizer-leverage-impact-p5")))
    (is (some? (node-by-role node "portfolio-optimizer-leverage-impact-terminal")))
    ;; The honesty fine print is always on the panel.
    (is (some #(str/includes? % "Lognormal model") (collect-strings node)))
    (is (some #(str/includes? % "Modeled, not a guarantee") (collect-strings node)))
    ;; The drawdown odds are framed as a floor on ruin risk; the only mention
    ;; of "liquidation probability" is the honest disclaimer that we do NOT
    ;; present one (checked precisely in the tips test below).
    (is (some #(str/includes? % "floor on ruin risk") (collect-strings node)))))

(deftest tiles-carry-current-book-comparators-test
  ;; Each stat tile frames the target number against the current book: the
  ;; domain outcome-model already computes every current-book statistic, so
  ;; the tiles show "Now X" — the delta the decision actually hinges on.
  (let [node (panel/leverage-impact-panel
              (fixtures/sample-solved-result
               {:diagnostics {:gross-exposure 2.5}}))
        current-outcome (leverage-risk/outcome-model {:expected-return 0.12
                                                      :volatility 0.24})
        tile-compare (fn [role]
                       (some->> (node-by-role node (str role "-current"))
                                collect-strings
                                (str/join " ")))]
    (is (= (str "Now " (panel/compact-usd
                        (* 100000 (:mean-ending-factor current-outcome))))
           (tile-compare "portfolio-optimizer-leverage-impact-mean")))
    (is (= (str "Now " (panel/compact-usd
                        (* 100000 (:p5-ending-factor current-outcome))))
           (tile-compare "portfolio-optimizer-leverage-impact-p5")))
    (is (str/starts-with?
         (tile-compare "portfolio-optimizer-leverage-impact-terminal") "Now "))
    (is (str/starts-with?
         (tile-compare "portfolio-optimizer-leverage-impact-touch") "Now "))))

(deftest tiles-skip-comparators-without-a-current-book-test
  (let [node (panel/leverage-impact-panel
              (fixtures/sample-solved-result
               {:diagnostics {:gross-exposure 2.5}
                :current-volatility nil
                :current-expected-return nil}))]
    (is (some? node))
    (doseq [role ["portfolio-optimizer-leverage-impact-mean"
                  "portfolio-optimizer-leverage-impact-p5"
                  "portfolio-optimizer-leverage-impact-terminal"
                  "portfolio-optimizer-leverage-impact-touch"]]
      (is (nil? (node-by-role node (str role "-current")))
          (str role " must not fabricate a current comparator")))))

(deftest median-shortfall-headline-is-signed-test
  (let [shortfall-node (panel/leverage-impact-panel
                        (fixtures/sample-solved-result
                         {:diagnostics {:gross-exposure 2.5}
                          ;; High σ drags the target median below current.
                          :volatility 1.4}))
        gain-node (panel/leverage-impact-panel
                   (fixtures/sample-solved-result
                    {:diagnostics {:gross-exposure 2.5}}))
        headline (fn [node]
                   (some->> (node-by-role node
                                          "portfolio-optimizer-leverage-impact-median-shortfall")
                            collect-strings
                            (str/join " ")))]
    (is (str/includes? (headline shortfall-node)
                       "Median wealth shortfall vs current"))
    (is (str/includes? (headline shortfall-node) "−$"))
    (is (str/includes? (headline gain-node) "Median wealth gain vs current"))
    (is (str/includes? (headline gain-node) "+$"))))

(deftest volatility-gate-surfaces-panel-without-gross-leverage-test
  (is (some? (panel/leverage-impact-panel
              (fixtures/sample-solved-result {:volatility 1.2})))))

(deftest distribution-draws-the-lognormal-with-three-markers-test
  (let [result (fixtures/sample-solved-result
                {:diagnostics {:gross-exposure 2.5}})
        node (panel/leverage-impact-panel result)
        dist (node-by-role node "portfolio-optimizer-leverage-impact-distribution")
        dist-strings (strings-of dist)
        target-outcome (leverage-risk/outcome-model {:expected-return 0.16
                                                     :volatility 0.28})]
    (is (some? dist))
    (is (some? (node-by-role dist "portfolio-optimizer-leverage-impact-dist-curve")))
    (is (some? (node-by-role dist "portfolio-optimizer-leverage-impact-dist-p5")))
    (is (some? (node-by-role dist "portfolio-optimizer-leverage-impact-dist-median")))
    (is (some? (node-by-role dist "portfolio-optimizer-leverage-impact-dist-mean")))
    ;; Marker labels use the compact dollar form, computed from the model.
    (doseq [factor [(:p5-ending-factor target-outcome)
                    (:median-ending-factor target-outcome)
                    (:mean-ending-factor target-outcome)]]
      (is (contains? dist-strings (panel/compact-usd (* 100000 factor)))))
    (is (contains? dist-strings "5th pct."))
    (is (contains? dist-strings "Median"))
    (is (contains? dist-strings "Mean"))
    (is (contains? dist-strings "Lower"))
    (is (contains? dist-strings "Higher"))
    ;; The log-scaled axis is disclosed, not silent.
    (is (some #(str/includes? % "log-scaled") (collect-strings node)))))

(deftest distribution-skipped-for-a-degenerate-zero-sigma-model-test
  ;; A zero-volatility target can still pass the gross gate; the deterministic
  ;; outcome has no distribution to draw.
  (let [node (panel/leverage-impact-panel
              (fixtures/sample-solved-result
               {:diagnostics {:gross-exposure 2.5}
                :volatility 0}))]
    (is (some? node))
    (is (nil? (node-by-role node
                            "portfolio-optimizer-leverage-impact-distribution")))))

(deftest panel-speaks-in-multiples-without-account-equity-test
  (let [node (panel/leverage-impact-panel
              (fixtures/sample-solved-result
               {:diagnostics {:gross-exposure 2.5}
                :rebalance-preview {:capital-usd nil}}))
        strings (strings-of node)
        target-outcome (leverage-risk/outcome-model {:expected-return 0.16
                                                     :volatility 0.28})]
    (is (some? node))
    (is (contains? strings
                   (str (opt-format/format-multiple
                         (:median-ending-factor target-outcome))
                        " start")))
    (is (nil? (node-by-role node
                            "portfolio-optimizer-leverage-impact-median-shortfall")))
    (is (some #(str/includes? % "multiples of starting equity")
              (collect-strings node)))
    ;; Distribution markers fall back to multiples too.
    (is (contains? (strings-of (node-by-role node
                                             "portfolio-optimizer-leverage-impact-distribution"))
                   (opt-format/format-multiple
                    (:median-ending-factor target-outcome))))))

(deftest probabilities-render-as-percentages-test
  (let [node (panel/leverage-impact-panel
              (fixtures/sample-solved-result
               {:diagnostics {:gross-exposure 2.5}
                :volatility 4.1182
                :expected-return 18.6606}))
        touch (node-by-role node "portfolio-optimizer-leverage-impact-touch")
        terminal (node-by-role node "portfolio-optimizer-leverage-impact-terminal")]
    ;; From the domain tests: ~87.8% terminal, ~98.2% touch at this μ/σ.
    (is (contains? (strings-of terminal) "87.8%"))
    (is (contains? (strings-of touch) "98.2%"))))

(deftest compact-usd-matches-the-mockup-forms-test
  (is (= "$408" (panel/compact-usd 408.2)))
  (is (= "$4.1k" (panel/compact-usd 4120)))
  (is (= "$43k" (panel/compact-usd 43210)))
  (is (= "$186k" (panel/compact-usd 186000)))
  (is (= "$2M" (panel/compact-usd 1966060)))
  (is (= "−$500" (panel/compact-usd -500))))

(deftest every-field-carries-an-accessible-info-tip-test
  (let [node (panel/leverage-impact-panel
              (fixtures/sample-solved-result
               {:diagnostics {:gross-exposure 2.5}}))]
    ;; Each tip is a role=tooltip card wired to a focusable trigger by
    ;; aria-describedby — hover AND keyboard reach it.
    (doseq [tip-id ["portfolio-optimizer-leverage-impact-title-tip"
                    "portfolio-optimizer-leverage-impact-modeled-tip"
                    "portfolio-optimizer-leverage-impact-median-tip"
                    "portfolio-optimizer-leverage-impact-shortfall-tip"
                    "portfolio-optimizer-leverage-impact-mean-tip"
                    "portfolio-optimizer-leverage-impact-p5-tip"
                    "portfolio-optimizer-leverage-impact-terminal-tip"
                    "portfolio-optimizer-leverage-impact-touch-tip"
                    "portfolio-optimizer-leverage-impact-distribution-tip"]]
      (let [tip (node-by-role node tip-id)
            trigger (first (collect-nodes node
                                          #(= tip-id (get-in % [1 :aria-describedby]))))]
        (is (some? tip) (str "missing tip " tip-id))
        (is (= "tooltip" (node-attr tip :role)))
        (is (= tip-id (node-attr tip :id)))
        ;; A focusable trigger describes itself by this tip's id (hover AND
        ;; keyboard reach it).
        (is (some? trigger) (str "no aria-describedby trigger for " tip-id))
        (is (some? (node-attr trigger :tabindex))
            (str "trigger for " tip-id " is not keyboard-focusable"))))))

(deftest median-tip-frames-median-vs-mean-and-anchors-starting-equity-test
  (let [tip (tooltip-text
             (panel/leverage-impact-panel
              (fixtures/sample-solved-result {:diagnostics {:gross-exposure 2.5}}))
             "portfolio-optimizer-leverage-impact-median-tip")]
    ;; The user's #1 concern: median must not be read as the average.
    (is (str/includes? tip "half the modeled outcomes finish above"))
    (is (str/includes? tip "not the average"))
    ;; Anchors the $ so $408 is never contextless.
    (is (str/includes? tip (whole-usd 100000)))))

(deftest ending-vs-touching-tips-are-distinct-and-honest-test
  (let [node (panel/leverage-impact-panel
              (fixtures/sample-solved-result {:diagnostics {:gross-exposure 2.5}}))
        terminal (tooltip-text node "portfolio-optimizer-leverage-impact-terminal-tip")
        touch (tooltip-text node "portfolio-optimizer-leverage-impact-touch-tip")]
    ;; Ending-down is an end-of-year metric; touching is path-dependent — the
    ;; guide's key distinction.
    (is (str/includes? terminal "finishes the year"))
    (is (str/includes? touch "at any point in the year"))
    ;; Touching stays honest: a floor on ruin risk, never a liquidation prob.
    (is (str/includes? touch "floor on ruin risk"))
    (is (str/includes? touch "not a liquidation probability"))))

(deftest tips-never-claim-a-simulation-or-unmodeled-costs-test
  ;; Our model is closed-form lognormal with no funding/execution/liquidation.
  ;; The tips must not borrow the mockup's simulation/cost language.
  (let [copy (str/lower-case
              (str/join " "
                        (collect-strings
                         (panel/leverage-impact-panel
                          (fixtures/sample-solved-result
                           {:diagnostics {:gross-exposure 2.5}})))))]
    (is (str/includes? copy "not a simulation"))
    (is (not (str/includes? copy "10,000")))
    (is (not (str/includes? copy "simulated paths")))
    ;; The panel names funding/execution/liquidation only to say they are NOT
    ;; modeled — never as computed cash costs.
    (is (str/includes? copy "not modeled"))))

(deftest distribution-tip-discloses-the-log-axis-test
  (let [tip (tooltip-text
             (panel/leverage-impact-panel
              (fixtures/sample-solved-result {:diagnostics {:gross-exposure 2.5}}))
             "portfolio-optimizer-leverage-impact-distribution-tip")]
    (is (str/includes? tip "log-scaled"))
    (is (str/includes? tip "equal multiples, not equal dollars"))))

(deftest distribution-markers-stay-finite-when-outcome-factors-underflow-test
  ;; At the volatility a poisoned covariance produces, the modeled ending
  ;; factors underflow to 0. (js/Math.log 0) is -Infinity, which used to land
  ;; verbatim in the SVG :x1/:cx attributes as the string "-Infinity" — the
  ;; downstream cause of the "$0 -> $0" cells seen on 2026-08-23.
  (let [result (fixtures/sample-solved-result
                {:diagnostics {:gross-exposure 12}
                 :volatility 40.0
                 :expected-return -0.9})
        node (panel/leverage-impact-panel result)
        coordinates (->> (collect-nodes node vector?)
                         (mapcat (fn [n]
                                   [(node-attr n :x1) (node-attr n :x2)
                                    (node-attr n :cx) (node-attr n :cy)
                                    (node-attr n :d)]))
                         (remove nil?)
                         (map str))]
    (is (some? node) "The panel must still render at extreme volatility.")
    (is (seq coordinates))
    (is (not-any? #(str/includes? % "Infinity") coordinates)
        "Every marker coordinate must be a finite number.")
    (is (not-any? #(str/includes? % "NaN") coordinates))))
