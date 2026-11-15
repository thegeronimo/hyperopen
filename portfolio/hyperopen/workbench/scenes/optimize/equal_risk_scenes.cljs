(ns hyperopen.workbench.scenes.optimize.equal-risk-scenes
  "Workbench scenes for the RISK CONTRIBUTION BALANCE card (designer spec
  2026-07-11) and its WHY THIS RISK ALLOCATION companion. `designer-parity`
  reproduces the mock's book (3 long / 2 short, signed per-side targets,
  mild deviations, 2 binding caps) for pixel comparison against the spec;
  the other scenes stress the states the spec doesn't draw: a hedged book
  under the real engine's uniform signed target (large negative deviations),
  an exact two-asset book, a capped 24-asset universe with the remainder
  line, and a persisted pre-redesign payload that must degrade gracefully."
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.views.portfolio.optimize.results-summary :as results-summary]
            [hyperopen.views.portfolio.optimize.risk-contributions-card
             :as risk-contributions-card]))

(portfolio/configure-scenes
  {:title "Equal Risk balance"
   :collection :optimize})

(defn- instrument-id
  [symbol]
  (str "perp:" symbol))

(defn- equal-risk-result
  "Solved-result fixture for the card: `rows` are
  [symbol weight share current-share target-share]. `overrides` shallow-merges
  over the finished result — pass COMPLETE replacement values (a partial
  nested map replaces the whole key; deep-merge traps live here)."
  [{:keys [rows quality rms max-abs freedom solver-extra diagnostics overrides]}]
  (let [symbols (mapv first rows)
        ids (mapv (comp instrument-id first) rows)
        weights (mapv second rows)
        shares (mapv #(nth % 2) rows)
        currents (mapv #(nth % 3) rows)
        targets (mapv #(nth % 4) rows)]
    (merge
     {:status :solved
     :as-of-ms 1752200000000
     :solver {:strategy :sequential-equal-risk
              :objective-kind :equal-risk}
     :instrument-ids ids
     :labels-by-instrument (zipmap ids symbols)
     :target-weights-by-instrument (zipmap ids weights)
     :expected-return 0.11
     :volatility 0.21
     :current-expected-return 0.08
     :current-volatility 0.26
     :performance {:in-sample-sharpe 0.52}
     :current-performance {:in-sample-sharpe 0.31}
     :frontier-overlays {:standalone (map (fn [symbol share]
                                            {:label symbol
                                             :volatility (+ 0.3 (* 0.6 (js/Math.abs share)))
                                             :expected-return (+ 0.02 (* 0.4 share))})
                                          symbols
                                          shares)}
     :risk-contributions
     {:method :signed-euler-volatility
      :instrument-ids ids
      :relative-contributions shares
      :target-relative-contributions targets
      :relative-contributions-by-instrument (zipmap ids shares)
      :target-relative-contributions-by-instrument (zipmap ids targets)
      :sum-relative-contributions (reduce + 0 shares)
      :rms-error rms
      :max-absolute-error max-abs
      :negative-contribution-count (count (filter neg? shares))
      :quality quality}
     :current-risk-contributions
     {:relative-contributions-by-instrument (zipmap ids currents)
      :rms-error (* 2.2 rms)
      :max-absolute-error (* 2.2 max-abs)}
     :equal-risk-solver
     (merge {:strategy :sequential-equal-risk
             :converged? true
             :termination-reason :step-tolerance
             :iterations 12
             :initialization-count 4
             :selected-initialization :inverse-volatility
             :allocation-freedom freedom
             :initializations [{:seed-kind :equal-notional :status :completed
                                :objective 2.1e-4 :converged? true}
                               {:seed-kind :inverse-volatility :status :completed
                                :objective 2.1e-4 :converged? true}]}
            solver-extra)
      :diagnostics (merge {:gross-exposure 1.0
                           :net-exposure 0.5
                           :long-exposure 0.75
                           :short-exposure 0.25
                           :binding-constraints
                           [{:instrument-id (first ids)
                             :constraint :upper-bound
                             :weight (second (first rows))
                             :bound (second (first rows))}]}
                          diagnostics)}
     overrides)))

(def ^:private designer-parity-result
  (equal-risk-result
   {:rows [["BTC" 0.30 0.231 0.302 0.20]
           ["ETH" 0.25 0.194 0.259 0.20]
           ["SP500" 0.20 0.183 0.221 0.20]
           ["MSTR" -0.15 -0.182 -0.213 -0.20]
           ["SOL" -0.10 -0.190 -0.221 -0.20]]
    :quality :approximate
    :rms 0.018
    :max-abs 0.031
    :freedom {:status :limited
              :free-degrees 2
              :binding-count 2
              :books {:long 3 :short 2}}
    :overrides
    {:volatility 3.0147
     :expected-return 8.4191
     :current-volatility 2.9888
     :current-expected-return 8.5323
     :performance {:in-sample-sharpe 2.79}
     :current-performance {:in-sample-sharpe 2.86}
     :frontier-overlays
     {:standalone [{:instrument-id "perp:SOL"
                    :label "SOL"
                    :volatility 2.35
                    :expected-return 10.1}
                   {:instrument-id "perp:BTC"
                    :label "BTC"
                    :volatility 4.42
                    :expected-return 8.05}
                   {:instrument-id "perp:ETH"
                    :label "ETH"
                    :volatility 3.55
                    :expected-return 5.1}
                   {:instrument-id "perp:xyz:SP500"
                    :label "SP500"
                    :volatility 1.02
                    :expected-return 4.55}
                   {:instrument-id "perp:xyz:MSTR"
                    :label "MSTR"
                    :volatility 3.32
                    :expected-return -3.4}]}}}))

(def ^:private hedged-book-result
  (equal-risk-result
   {:rows [["BTC" 0.35 0.62 0.71 0.25]
           ["ETH" 0.30 0.45 0.38 0.25]
           ["GOLD" 0.20 0.18 0.09 0.25]
           ["MSTR" -0.15 -0.25 -0.18 0.25]]
    :quality :not-converged
    :rms 0.24
    :max-abs 0.50
    :freedom {:status :open
              :free-degrees 2
              :binding-count 0
              :books {:long 3 :short 1}}
    :solver-extra {:converged? false
                   :termination-reason :max-iterations
                   :iterations 60}}))

(def ^:private exact-two-asset-result
  (equal-risk-result
   {:rows [["BTC" 0.55 0.5 0.62 0.5]
           ["ETH" 0.45 0.5 0.38 0.5]]
    :quality :exact
    :rms 0.0
    :max-abs 0.0
    :freedom {:status :fully-determined
              :free-degrees 0
              :binding-count 0
              :books {:long 2 :short 0}}
    :diagnostics {:binding-constraints []}}))

(def ^:private capped-universe-result
  (let [symbols ["BTC" "ETH" "SOL" "AVAX" "LINK" "ARB" "OP" "DOGE"
                 "AAPL" "TSM" "NOW" "DKNG" "EWZ" "GOLD" "SILVER" "SP500"
                 "NDX" "TLT" "UNI" "AAVE" "MKR" "LDO" "INJ" "TIA"]
        n (count symbols)
        target (/ 1.0 n)
        share (fn [index]
                (let [wobble (* 0.02 (js/Math.sin (* 2.1 index)))]
                  (+ target wobble (* -0.001 index))))
        rows (map-indexed (fn [index symbol]
                            [symbol
                             (+ 0.02 (* 0.002 index))
                             (share index)
                             (+ (share index) (* 0.015 (js/Math.cos index)))
                             target])
                          symbols)]
    (equal-risk-result
     {:rows (vec rows)
      :quality :approximate
      :rms 0.011
      :max-abs 0.021
      :freedom {:status :limited
                :free-degrees 18
                :binding-count 3
                :books {:long 24 :short 0}}})))

(def ^:private concentrated-current-result
  (equal-risk-result
   {:rows [["BTC" 0.35 0.278 0.005 0.20]
           ["ETH" 0.30 0.252 0.012 0.20]
           ["FARTCOIN" 0.25 0.243 0.008 0.20]
           ["xyz:MSTR" 0.20 0.239 0.004 0.20]
           ["xyz:SP500" -0.10 -0.012 0.971 0.20]]
    :quality :approximate
    :rms 0.107
    :max-abs 0.212
    :freedom {:status :open
              :free-degrees 3
              :binding-count 0
              :books {:long 4 :short 1}}
    :diagnostics {:binding-constraints []}}))

(def ^:private persisted-pre-redesign-result
  (-> designer-parity-result
      (dissoc :current-risk-contributions)
      (assoc :equal-risk-solver {:strategy :sequential-equal-risk
                                 :converged? true
                                 :termination-reason :step-tolerance
                                 :iterations 9})))

(defn- card-shell
  [result]
  (layout/page-shell
   (layout/desktop-shell
    [:div {:class ["portfolio-optimizer" "mx-auto" "w-full"]
           :style {:max-width "1040px"}}
     [:div {:class ["space-y-4" "p-4"]}
      (risk-contributions-card/risk-contributions-card result)
      (results-summary/equal-risk-context-card result)]])))

(portfolio/defscene designer-parity
  []
  (card-shell designer-parity-result))

(portfolio/defscene hedged-book-uniform-target
  []
  (card-shell hedged-book-result))

(portfolio/defscene exact-two-asset
  []
  (card-shell exact-two-asset-result))

(portfolio/defscene capped-24-asset-universe
  []
  (card-shell capped-universe-result))

(portfolio/defscene concentrated-current-off-scale
  []
  (card-shell concentrated-current-result))

(portfolio/defscene persisted-pre-redesign
  []
  (card-shell persisted-pre-redesign-result))
