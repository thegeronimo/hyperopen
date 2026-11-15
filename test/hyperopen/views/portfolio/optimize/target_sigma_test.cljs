(ns hyperopen.views.portfolio.optimize.target-sigma-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.optimize.scenario-objective-menu :as objective-menu]
            [hyperopen.views.portfolio.optimize.target-sigma :as target-sigma]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [change-actions click-actions collect-nodes collect-strings
                     input-actions node-by-role]]))

(def ^:private target-volatility-draft
  {:objective {:kind :target-volatility
               :target-volatility 0.12}})

(defn- strip
  [{:keys [draft pending running? loading?]
    :or {draft target-volatility-draft}}]
  (target-sigma/target-sigma-strip
   {:state {:portfolio-ui {:optimizer {:target-sigma-draft pending}}}
    :draft draft
    :running? running?
    :loading? loading?}))

(deftest sigma-percent-text-rounds-and-trims-test
  (is (= "22" (target-sigma/sigma-percent-text 0.22)))
  (is (= "22.5" (target-sigma/sigma-percent-text 0.225)))
  (is (= "12" (target-sigma/sigma-percent-text 0.12)))
  (is (nil? (target-sigma/sigma-percent-text nil)))
  (is (nil? (target-sigma/sigma-percent-text "12"))))

(deftest frontier-sigma-bounds-uses-frontier-extremes-test
  (is (= {:min-vol 0.06
          :max-vol 0.35
          :max-return-vol 0.35}
         (target-sigma/frontier-sigma-bounds
          {:frontier [{:volatility 0.06 :expected-return 0.04}
                      {:volatility 0.2 :expected-return 0.11}
                      {:volatility 0.35 :expected-return 0.19}]})))
  (is (= {:min-vol 0.08
          :max-vol 0.3
          :max-return-vol 0.3}
         (target-sigma/frontier-sigma-bounds
          {:frontiers {:unconstrained [{:volatility 0.08 :expected-return 0.05}
                                       {:volatility 0.3 :expected-return 0.2}]
                       :constrained [{:volatility 0.1 :expected-return 0.06}]}
           :frontier [{:volatility 0.5 :expected-return 0.4}]}))
      "prefers the unconstrained display frontier over the target frontier")
  (is (nil? (target-sigma/frontier-sigma-bounds nil)))
  (is (nil? (target-sigma/frontier-sigma-bounds {:frontier []}))))

(deftest dial-max-percent-widens-for-levered-frontiers-and-typed-targets-test
  (is (= 40 (target-sigma/dial-max-percent nil 0.22))
      "default dial tops out at 40%")
  (is (= 40 (target-sigma/dial-max-percent {:max-vol 0.3} 0.2))
      "an unlevered frontier keeps the default dial")
  (is (= 590 (target-sigma/dial-max-percent {:max-vol 4.89} 0.12))
      "a levered frontier widens the dial with ~20% headroom")
  (is (= 590 (target-sigma/dial-max-percent {:max-vol 1.48 :current-vol 4.86} 0.12))
      "a current portfolio running ~486% sigma widens the dial past the frontier top")
  (is (= 1000 (target-sigma/dial-max-percent {:max-vol 4.89} 0.12 10))
      "a typed 1000% target becomes the new dial max")
  (is (= 40 (target-sigma/dial-max-percent nil nil))
      "no inputs falls back to the 40% default"))

(deftest frontier-sigma-bounds-carries-current-portfolio-vol-test
  (is (= 4.86
         (:current-vol
          (target-sigma/frontier-sigma-bounds
           {:current-volatility 4.86
            :frontier [{:volatility 0.17 :expected-return 0.1}
                       {:volatility 1.48 :expected-return 6.4}]})))))

(deftest target-sigma-strip-rescales-dial-for-levered-results-test
  (let [levered-result {:frontier [{:volatility 0.06 :expected-return 0.04}
                                   {:volatility 4.89 :expected-return 6.4}]}
        node (target-sigma/target-sigma-strip
              {:state {}
               :draft target-volatility-draft
               :result levered-result})
        slider (node-by-role node "portfolio-optimizer-target-sigma-slider")
        strings (set (collect-strings node))]
    (is (= 590 (get-in slider [1 :max])))
    (is (contains? strings "590%"))))

(deftest sigma-helper-copy-picks-spec-context-sentence-test
  (is (= "Solver maximizes expected return at exactly σ = 6%. Very low σ may bind against the cash floor."
         (target-sigma/sigma-helper-copy 0.06 nil)))
  (is (= "Solver maximizes expected return at exactly σ = 35%. High σ may require leverage beyond the gross cap."
         (target-sigma/sigma-helper-copy 0.35 nil)))
  (is (= "Solver maximizes expected return at exactly σ = 22%. Sits between min-vol (≈6%) and max-return (≈35%) on the frontier."
         (target-sigma/sigma-helper-copy 0.22 {:min-vol 0.06 :max-return-vol 0.35})))
  (is (= "Solver maximizes expected return at exactly σ = 22%. Sits between min-vol and max-return on the frontier."
         (target-sigma/sigma-helper-copy 0.22 nil))))

(deftest target-sigma-strip-hidden-for-other-objectives-test
  (is (nil? (strip {:draft {:objective {:kind :max-sharpe}}})))
  (is (nil? (target-sigma/target-sigma-strip
             {:state {}
              :draft target-volatility-draft
              :loading? true}))))

(deftest target-sigma-strip-renders-through-scenario-detail-route-test
  (let [view-for (fn [objective]
                   (portfolio-view/portfolio-view
                    {:router {:path "/portfolio/optimize/draft"}
                     :portfolio {:optimizer {:draft {:objective objective}}}}))]
    (is (some? (node-by-role (view-for {:kind :target-volatility
                                        :target-volatility 0.22})
                             "portfolio-optimizer-target-sigma-strip")))
    (is (nil? (node-by-role (view-for {:kind :max-sharpe})
                            "portfolio-optimizer-target-sigma-strip")))))

(deftest target-sigma-strip-clean-state-has-no-buttons-test
  (let [node (strip {})
        slider (node-by-role node "portfolio-optimizer-target-sigma-slider")
        input (node-by-role node "portfolio-optimizer-target-sigma-input")
        strings (set (collect-strings node))]
    (is (= "12" (get-in slider [1 :value])))
    (is (= 0.5 (get-in slider [1 :step])))
    (is (= "12" (get-in input [1 :value])))
    (is (= [[:actions/set-portfolio-optimizer-target-sigma-draft
             [:event.target/value]]]
           (input-actions slider)))
    (is (= [[:actions/set-portfolio-optimizer-target-sigma-draft
             [:event.target/value]]]
           (change-actions input))
        "text input commits on change so the clamp cannot rewrite mid-typing")
    (is (nil? (input-actions input)))
    (is (nil? (node-by-role node "portfolio-optimizer-target-sigma-reset")))
    (is (nil? (node-by-role node "portfolio-optimizer-target-sigma-rerun")))
    (is (contains? strings "Solving for max return at exactly this σ"))))

(deftest target-sigma-strip-with-pending-offers-rerun-test
  (let [node (strip {:pending 0.31})
        slider (node-by-role node "portfolio-optimizer-target-sigma-slider")
        input (node-by-role node "portfolio-optimizer-target-sigma-input")
        reset (node-by-role node "portfolio-optimizer-target-sigma-reset")
        rerun (node-by-role node "portfolio-optimizer-target-sigma-rerun")
        strings (set (collect-strings node))]
    (is (= "31" (get-in slider [1 :value])))
    (is (= "31" (get-in input [1 :value])))
    (is (= [[:actions/reset-portfolio-optimizer-target-sigma-draft]]
           (click-actions reset)))
    (is (false? (get-in rerun [1 :disabled])))
    (is (= [[:actions/rerun-portfolio-optimizer-at-target-sigma]]
           (click-actions rerun)))
    (is (contains? strings "Re-run at σ = 31%"))
    (is (contains? strings " · estimates refresh on re-run"))))

(deftest target-sigma-strip-disables-rerun-while-running-test
  (let [node (strip {:pending 0.31 :running? true})
        rerun (node-by-role node "portfolio-optimizer-target-sigma-rerun")]
    (is (= true (get-in rerun [1 :disabled])))
    (is (nil? (click-actions rerun)))))

(deftest target-sigma-strip-pending-equal-to-current-counts-as-unchanged-test
  (let [node (strip {:pending 0.12})]
    (is (nil? (node-by-role node "portfolio-optimizer-target-sigma-reset")))
    (is (nil? (node-by-role node "portfolio-optimizer-target-sigma-rerun")))))

(deftest menu-pending-sigma-prefers-staged-then-draft-then-preset-test
  (let [staged {:portfolio-ui {:optimizer {:objective-menu-target-sigma 0.25}}}]
    (is (= 0.25 (target-sigma/menu-pending-sigma staged target-volatility-draft)))
    (is (= 0.12 (target-sigma/menu-pending-sigma {} target-volatility-draft)))
    (is (= 0.12 (target-sigma/menu-pending-sigma {} {:objective {:kind :max-sharpe}})))
    (is (true? (target-sigma/menu-sigma-changed? staged target-volatility-draft)))
    (is (false? (boolean (target-sigma/menu-sigma-changed? {} target-volatility-draft))))
    (is (false? (boolean (target-sigma/menu-sigma-changed?
                          {:portfolio-ui {:optimizer {:objective-menu-target-sigma 0.12}}}
                          target-volatility-draft)))
        "staging the same sigma as the draft is not a change")))

(deftest objective-label-shows-applied-sigma-test
  (is (= "Target volatility · 22%"
         (objective-menu/objective-label
          :target-volatility
          {:objective {:kind :target-volatility :target-volatility 0.22}})))
  (is (= "Target volatility · 12%"
         (objective-menu/objective-label :target-volatility {:objective {:kind :max-sharpe}}))
      "falls back to the 12% preset when another objective is active")
  (is (= "Maximum Sharpe" (objective-menu/objective-label :max-sharpe nil))))

(deftest menu-sigma-editor-renders-spec-controls-test
  (let [node (target-sigma/menu-sigma-editor
              {:portfolio-ui {:optimizer {:objective-menu-target-sigma 0.22}}}
              target-volatility-draft
              nil)
        slider (node-by-role node "portfolio-optimizer-objective-menu-target-sigma-slider")
        input (node-by-role node "portfolio-optimizer-objective-menu-target-sigma-input")
        strings (set (collect-strings node))]
    (is (= "22" (get-in input [1 :value])))
    (is (= "22" (get-in slider [1 :value])))
    (is (= 4 (get-in slider [1 :min])))
    (is (= 40 (get-in slider [1 :max])))
    (is (= 0.5 (get-in slider [1 :step])))
    (is (= [[:actions/set-portfolio-optimizer-objective-menu-target-sigma
             [:event.target/value]]]
           (input-actions slider)))
    (is (= [[:actions/set-portfolio-optimizer-objective-menu-target-sigma
             [:event.target/value]]]
           (change-actions input)))
    (is (contains? strings "Target σ (annualized)"))
    (is (contains? strings "4% · defensive"))
    (is (contains? strings "40% · aggressive"))
    (is (contains? strings "Solver maximizes expected return at exactly σ = 22%. Sits between min-vol and max-return on the frontier."))))

(defn- open-menu-state
  [selection staged-sigma]
  {:portfolio-ui {:optimizer (cond-> {:objective-menu-open? true
                                      :objective-menu-selection selection}
                               (some? staged-sigma)
                               (assoc :objective-menu-target-sigma staged-sigma))}})

(defn- option-card-wrappers
  [menu-node]
  (collect-nodes menu-node
                 #(contains? (set (get-in % [1 :class]))
                             "optimizer-objective-menu-option")))

(deftest objective-menu-target-volatility-selected-shows-inline-editor-test
  (let [menu-node (objective-menu/objective-menu
                   (open-menu-state :target-volatility 0.25)
                   target-volatility-draft
                   nil)
        strings (set (collect-strings menu-node))
        apply-button (node-by-role menu-node "portfolio-optimizer-objective-menu-apply")]
    (is (some? (node-by-role menu-node
                             "portfolio-optimizer-objective-menu-target-sigma-editor")))
    (is (= "25" (get-in (node-by-role menu-node
                                      "portfolio-optimizer-objective-menu-target-sigma-input")
                        [1 :value])))
    (is (not (contains? strings "· 25%"))
        "selected option drops the title suffix — the editor shows the value")
    (is (not (contains? strings "· 12%")))
    (is (false? (get-in apply-button [1 :disabled]))
        "apply enables when only the sigma changed")
    (is (some? (click-actions apply-button)))))

(deftest objective-menu-target-volatility-unselected-shows-suffix-and-disables-apply-test
  (let [unchanged-node (objective-menu/objective-menu
                        (open-menu-state :target-volatility nil)
                        target-volatility-draft
                        nil)
        other-pending-node (objective-menu/objective-menu
                            (open-menu-state :max-sharpe nil)
                            target-volatility-draft
                            nil)
        other-strings (set (collect-strings other-pending-node))]
    (is (= true (get-in (node-by-role unchanged-node
                                      "portfolio-optimizer-objective-menu-apply")
                        [1 :disabled]))
        "same selection with no staged sigma keeps apply disabled")
    (is (nil? (node-by-role other-pending-node
                            "portfolio-optimizer-objective-menu-target-sigma-editor")))
    (is (contains? other-strings "· 12%")
        "unselected target-volatility option shows the sigma suffix")))

(deftest objective-menu-option-card-is-clickable-everywhere-test
  (let [menu-node (objective-menu/objective-menu
                   (open-menu-state :max-sharpe nil)
                   target-volatility-draft
                   nil)
        wrappers (option-card-wrappers menu-node)]
    (is (= 5 (count wrappers)))
    (is (every? #(some? (click-actions %)) wrappers)
        "the whole padded option card selects, not just the inner row")))
