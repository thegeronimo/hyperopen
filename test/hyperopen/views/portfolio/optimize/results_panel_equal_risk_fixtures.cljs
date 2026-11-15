(ns hyperopen.views.portfolio.optimize.results-panel-equal-risk-fixtures
  "Shared fixtures for the Equal Risk results-panel test namespaces
  (results-panel-equal-risk-test and
  results-panel-equal-risk-structure-test — split for the namespace-size
  cap): the approximate solved result, its :risk-structure-carrying variant,
  and the render helpers. Not a test namespace — no deftests here."
  (:require [hyperopen.views.portfolio.optimize.results-panel :as results-panel]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [solved-result]]))

(def equal-risk-solver-section
  {:strategy :sequential-equal-risk
   :converged? true
   :termination-reason :step-tolerance
   :iterations 7
   :initialization-count 4
   :selected-initialization :inverse-volatility
   :objective-value 1.2e-4
   :exactness-tolerance 0.005
   :allocation-freedom {:status :limited
                        :free-degrees 1
                        :binding-count 2
                        :books {:long 1 :short 1}}
   :initializations [{:seed-kind :equal-notional :status :completed
                      :objective 1.2e-4 :converged? true}
                     {:seed-kind :inverse-volatility :status :completed
                      :objective 1.2e-4 :converged? true}]})

(def approximate-result
  (-> solved-result
      (assoc :solver {:strategy :sequential-equal-risk
                      :objective-kind :equal-risk})
      (assoc :risk-contributions
             {:method :signed-euler-volatility
              :instrument-ids ["perp:BTC" "spot:PURR"]
              :variance-contributions [0.002 0.002]
              :volatility-contributions [0.031 0.031]
              :relative-contributions [0.62 0.38]
              :target-relative-contributions [0.5 0.5]
              :relative-contributions-by-instrument {"perp:BTC" 0.62
                                                     "spot:PURR" 0.38}
              :target-relative-contributions-by-instrument {"perp:BTC" 0.5
                                                            "spot:PURR" 0.5}
              :sum-relative-contributions 1.0
              :rms-error 0.12
              :max-absolute-error 0.12
              :negative-contribution-count 0
              :quality :approximate})
      (assoc :current-risk-contributions
             {:relative-contributions-by-instrument {"perp:BTC" 0.9
                                                     "spot:PURR" 0.1}
              :rms-error 0.4
              :max-absolute-error 0.4})
      (assoc :equal-risk-solver equal-risk-solver-section)))

(def structured-result
  (assoc approximate-result
         :risk-structure
         {:method :signed-euler-decomposition
          :portfolio-volatility 0.4
          :current-diversification {:modeled-volatility 0.24
                                    :all-move-together-volatility 0.32
                                    :zero-correlation-volatility 0.20
                                    :reduction-vs-all-move-together 0.08
                                    :reduction-ratio-vs-all-move-together 0.25
                                    :modeled-minus-zero-correlation 0.04}
          :target-diversification {:modeled-volatility 0.40
                                   :all-move-together-volatility 0.60
                                   :zero-correlation-volatility 0.35
                                   :reduction-vs-all-move-together 0.20
                                   :reduction-ratio-vs-all-move-together (/ 1 3)
                                   :modeled-minus-zero-correlation 0.05}
          :standalone-share-by-instrument {"perp:BTC" 0.75
                                           "spot:PURR" 0.5}
          :diversification-share-by-instrument {"perp:BTC" -0.13
                                                "spot:PURR" -0.12}
          :pnl-portfolio-correlation-by-instrument {"perp:BTC" 0.9
                                                    "spot:PURR" 0.55}
          :correlation {:instrument-ids ["perp:BTC" "spot:PURR"]
                        :matrix [[1.0 0.6]
                                 [0.6 1.0]]
                        :hidden-count 0}}))

(defn render
  [result]
  (results-panel/results-panel
   {:result result
    :computed-at-ms 2600}
   {:objective {:kind :equal-risk}}
   {:frontier-overlay-mode :standalone}))

(defn render-with-state
  [result state]
  (results-panel/results-panel
   {:result result
    :computed-at-ms 2600}
   {:objective {:kind :equal-risk}}
   {:frontier-overlay-mode :standalone
    :state state}))
