(ns hyperopen.portfolio.optimizer.application.engine.payload
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.display-frontier :as display-frontier]
            [hyperopen.portfolio.optimizer.application.engine.equal-risk-payload
             :as equal-risk-payload]
            [hyperopen.portfolio.optimizer.application.engine.inverse-volatility-payload
             :as inverse-volatility-payload]
            [hyperopen.portfolio.optimizer.application.engine.solver-health :as solver-health]
            [hyperopen.portfolio.optimizer.contracts.constants :as contracts-constants]
            [hyperopen.portfolio.optimizer.application.engine.target-selection :as target-selection]
            [hyperopen.portfolio.optimizer.application.instrument-labels :as instrument-labels]
            [hyperopen.portfolio.optimizer.domain.diagnostics :as diagnostics]
            [hyperopen.portfolio.optimizer.domain.frontier-overlays :as frontier-overlays]
            [hyperopen.portfolio.optimizer.domain.math :as math]
            [hyperopen.portfolio.optimizer.domain.rebalance :as rebalance]
            [hyperopen.portfolio.optimizer.domain.weight-cleaning :as weight-cleaning]
            [hyperopen.portfolio.optimizer.coercion :as coercion]))

(defn- sqrt
  [value]
  (js/Math.sqrt (max 0 value)))

(def ^:private finite-number? coercion/finite-number?)

(def ^:private parse-number coercion/parse-float-number)

(defn- dust-threshold
  [request]
  (let [direct-threshold (get-in request [:constraints :dust-threshold])
        dust-usdc (get-in request [:constraints :dust-usdc])
        nav-usdc (or (get-in request [:current-portfolio :capital :nav-usdc])
                     (get-in request [:current-portfolio :capital :account-value-usd]))]
    (if (some? direct-threshold)
      direct-threshold
      (when (and (finite-number? dust-usdc)
                 (finite-number? nav-usdc)
                 (pos? nav-usdc))
        (/ dust-usdc nav-usdc)))))

(defn- equal-risk-request?
  [request]
  (= :equal-risk (get-in request [:objective :kind])))

(defn- covariance-only-request?
  [request]
  (contains? contracts-constants/covariance-only-objective-kinds
             (get-in request [:objective :kind])))

(defn- inverse-volatility-request?
  [request]
  (= :inverse-volatility (get-in request [:objective :kind])))

(defn- aligned-clean-weights
  [instrument-ids weights encoded-constraints request]
  (let [cleaned (weight-cleaning/clean-weights
                 {:instrument-ids instrument-ids
                  :weights weights
                  ;; Covariance-only objectives publish the validated solver
                  ;; weights verbatim: dust removal would break the exact book
                  ;; equalities and every position in the fixed universe is
                  ;; intentional.
                  :dust-threshold (if (covariance-only-request? request)
                                    0
                                    (dust-threshold request))
                  :long-only? (:long-only? encoded-constraints)
                  :target-net (:net-target encoded-constraints)})
        by-id (zipmap (:instrument-ids cleaned) (:weights cleaned))]
    {:target-weights (mapv #(or (get by-id %) 0) instrument-ids)
     :dropped (:dropped cleaned)}))

(defn- equal-risk-sections
  "Equal-risk payload sections (see engine.equal-risk-payload); nil for every
  other objective."
  [request sections-inputs]
  (when (equal-risk-request? request)
    (equal-risk-payload/equal-risk-sections sections-inputs)))

(defn- inverse-volatility-sections
  "Risk-weighted sizing payload section (see
  engine.inverse-volatility-payload); nil for every other objective."
  [request sections-inputs]
  (when (inverse-volatility-request? request)
    (inverse-volatility-payload/inverse-volatility-sections sections-inputs)))

(defn- normalized-instruments-by-id
  [universe]
  (into {}
        (map (fn [instrument]
               [(:instrument-id instrument)
                (assoc instrument
                       :instrument-type (or (:instrument-type instrument)
                                            (:market-type instrument)))]))
        universe))

(defn- latest-history-prices-by-id
  [request instrument-ids]
  (into {}
        (keep (fn [instrument-id]
                (let [latest-row (last (get-in request
                                               [:history
                                                :price-series-by-instrument
                                                instrument-id]))
                      price (or (parse-number (:close latest-row))
                                (parse-number (:close-price latest-row)))]
                  (when (finite-number? price)
                    [instrument-id price]))))
        instrument-ids))

(defn- min-variance-cash-warning
  [request encoded diagnostics*]
  (let [gross-exposure (:gross-exposure diagnostics*)
        net-min (get-in encoded [:net-exposure :min])]
    (when (and (= :minimum-variance (get-in request [:objective :kind]))
               (false? (:long-only? encoded))
               (not (finite-number? net-min))
               (finite-number? gross-exposure)
               (< gross-exposure 0.05))
      {:code :low-invested-exposure
       :invested-exposure gross-exposure
       :message "Minimum variance selected a near-cash signed portfolio. Use Target Return, Target Volatility, or an explicit Net Min floor if you want invested exposure."})))

(defn- sparse-history-objective-warning
  [request risk-result]
  (let [objective-kind (get-in request [:objective :kind])
        sparse-warning? (some #(= :sparse-history-risk-estimation (:code %))
                              (:warnings risk-result))]
    (when (and sparse-warning?
               (contains? #{:max-sharpe :minimum-variance} objective-kind))
      {:code :sparse-history-objective-sensitivity
       :objective-kind objective-kind
       :message "Sparse-history assets are using mixed-frequency covariance. Maximum Sharpe and Minimum Variance can be more sensitive to those pairwise estimates than target-based objectives."})))

(defn- labels-by-instrument
  [request instrument-ids]
  ;; Held-only assets (e.g. dust spot sold to 0) live in :current-portfolio-universe
  ;; but not the selected/requested universe. Include it so their rebalance rows
  ;; resolve a token symbol instead of falling back to the raw "@113" id. The
  ;; current-portfolio rows go first so a curated selected-universe label still
  ;; wins for assets present in both.
  (instrument-labels/labels-by-instrument (vec (concat (:current-portfolio-universe request)
                                                       (:universe request)
                                                       (:requested-universe request)))
                                          instrument-ids))

(defn- replace-all-text
  [text needle replacement]
  (let [needle* (str needle)
        replacement* (str replacement)]
    (if (or (not (string? text))
            (empty? needle*)
            (= needle* replacement*))
      text
      (loop [remaining text
             out ""]
        (if-let [idx (str/index-of remaining needle*)]
          (recur (subs remaining (+ idx (count needle*)))
                 (str out (subs remaining 0 idx) replacement*))
          (str out remaining))))))

(defn- warning-instrument-ids
  [warning]
  (vec (distinct (concat (keep warning
                               [:instrument-id
                                :left-instrument-id
                                :right-instrument-id
                                :comparator-instrument-id
                                :long-instrument-id
                                :short-instrument-id])
                         (:instrument-ids warning)))))

(defn- warning-label-pairs
  [labels-by-instrument warning]
  (keep (fn [instrument-id]
          (when-let [label (get labels-by-instrument instrument-id)]
            [instrument-id label]))
        (warning-instrument-ids warning)))

(defn- human-warning-label
  [instrument-id label]
  (when (and (string? label)
             (seq label)
             (not= label (str instrument-id)))
    label))

(defn- prepend-warning-label
  [message label]
  (if (and (string? message)
           (seq label)
           (not (str/includes? message label)))
    (str label ": " message)
    message))

(defn- user-facing-warning
  [labels-by-instrument warning]
  (let [label-pairs (vec (warning-label-pairs labels-by-instrument warning))
        primary-label (when-let [instrument-id (:instrument-id warning)]
                        (human-warning-label instrument-id
                                             (get labels-by-instrument instrument-id)))
        warning* (if (:message warning)
                   (reduce (fn [acc [instrument-id label]]
                             (update acc :message replace-all-text instrument-id label))
                           warning
                           label-pairs)
                   warning)]
    (cond-> warning*
      (and primary-label (:message warning*))
      (update :message prepend-warning-label primary-label)

      primary-label
      (assoc :instrument-label primary-label)

      (and (seq (:instrument-ids warning)) (seq label-pairs))
      (assoc :instrument-labels (mapv second label-pairs)))))

(defn- user-facing-warnings
  [labels-by-instrument warnings]
  (mapv (partial user-facing-warning labels-by-instrument) warnings))

(defn- dedupe-warnings
  [warnings]
  (vec (distinct (remove nil? warnings))))

(defn- request-instrument-ids
  [request]
  (vec (distinct (concat (keep :instrument-id (:universe request))
                         (keep :instrument-id (:requested-universe request))
                         (keep :instrument-id
                               (:current-portfolio-universe request))))))

(defn- sharpe-summary
  [expected-return volatility]
  (let [in-sample-sharpe (when (and (finite-number? expected-return)
                                    (finite-number? volatility)
                                    (pos? volatility))
                           (/ expected-return volatility))]
    {:in-sample-sharpe in-sample-sharpe
     :shrunk-sharpe (when (finite-number? in-sample-sharpe)
                      (* 0.5 in-sample-sharpe))}))

(defn- history-summary
  [request]
  (let [history (:history request)
        freshness (:freshness history)]
    (merge (:history-window history)
           {:return-observations (count (:return-calendar history))
            :oldest-common-ms (:oldest-common-ms freshness)
            :latest-common-ms (:latest-common-ms freshness)
            :age-ms (:age-ms freshness)
            :stale? (:stale? freshness)})))

(defn- portfolio-allocation?
  [weights]
  (let [weights* (filter finite-number? weights)]
    (pos? (reduce + 0 (map js/Math.abs weights*)))))

(defn- current-portfolio-metrics
  [analysis]
  (when (and analysis
             (portfolio-allocation? (:weights analysis)))
    (let [weights (:weights analysis)
          expected-returns (:expected-returns analysis)
          covariance (get-in analysis [:risk-result :covariance])
          expected-return (math/portfolio-return weights expected-returns)
          volatility (sqrt (math/portfolio-variance weights covariance))]
      (when (and (finite-number? expected-return)
                 (finite-number? volatility))
        {:instrument-ids (:instrument-ids analysis)
         :weights weights
         :expected-return expected-return
         :volatility volatility
         :performance (sharpe-summary expected-return volatility)}))))

(defn- rebalance-preview
  [request instrument-ids current-weights target-weights]
  (let [execution-assumptions (:execution-assumptions request)]
    (rebalance/build-rebalance-preview
     {:capital-usd (or (get-in request [:current-portfolio :capital :nav-usdc])
                       (get-in request [:current-portfolio :capital :account-value-usd])
                       0)
      :current-margin-used-usdc (get-in request
                                        [:current-portfolio :capital :total-margin-used-usdc])
      :rebalance-tolerance (get-in request [:constraints :rebalance-tolerance])
      :fallback-slippage-bps (:fallback-slippage-bps execution-assumptions)
      :instrument-ids instrument-ids
      :current-weights current-weights
      :target-weights target-weights
      :instruments-by-id (normalized-instruments-by-id (:universe request))
      :prices-by-id (merge (latest-history-prices-by-id request instrument-ids)
                           (:prices-by-id execution-assumptions))
      :cost-contexts-by-id (:cost-contexts-by-id execution-assumptions)
      :leverage-by-id (get-in request [:constraints :perp-leverage])
      :fee-bps-by-id (:fee-bps-by-id execution-assumptions)
      :default-fee-bps (:default-fee-bps execution-assumptions)
      ;; Same maker fee the frontend refresh charges (application.rebalance-preview):
      ;; both build sites must agree, or which one happened to produce the preview on
      ;; screen decides whether a resting row's all-in reads $0.
      :maker-fee-bps contracts-constants/maker-fee-bps})))

(defn- solved-payload
  [request
   risk-result
   return-result
   expected-returns
   solver-plan
   solver-results
   selection
   display-frontiers
   encoded
   current-weights*
   current-portfolio-analysis
   display-frontier-results]
  (let [instrument-ids (:instrument-ids risk-result)
        default-frontier (or (:unconstrained display-frontiers)
                             (:constrained display-frontiers)
                             {:frontier (:target-frontier selection)
                              :frontier-summary {:source :target-solve
                                                 :constraint-mode :constrained
                                                 :point-count (count (:target-frontier
                                                                     selection))}
                              :warnings []})
        {:keys [target-weights dropped]} (aligned-clean-weights instrument-ids
                                                                (get-in selection [:selected :weights])
                                                                encoded
                                                                request)
        diagnostics* (diagnostics/portfolio-diagnostics
                      {:instrument-ids instrument-ids
                       :current-weights current-weights*
                       :target-weights target-weights
                       :lower-bounds (:lower-bounds encoded)
                       :upper-bounds (:upper-bounds encoded)
                       :covariance (:covariance risk-result)
                       :expected-returns expected-returns})
        cash-warning (min-variance-cash-warning request encoded diagnostics*)
        sparse-warning (sparse-history-objective-warning request risk-result)
        selected-current-metrics (current-portfolio-metrics
                                  {:instrument-ids instrument-ids
                                   :weights current-weights*
                                   :expected-returns expected-returns
                                   :risk-result risk-result})
        ;; Plot the Current marker on the SAME basis as the frontier/Target: the selected
        ;; universe's weights + the selected risk-result covariance. The held-book
        ;; current-portfolio-analysis covers a DIFFERENT, larger instrument set (the full
        ;; account — e.g. spot dust positions outside the optimization universe) with its OWN
        ;; covariance, which placed Current at a wildly different volatility from Target (~6x)
        ;; even for a tiny rebalance — an apples-to-oranges comparison. Falling back to the
        ;; held-book metrics only when there is no selected-universe current allocation keeps a
        ;; marker in the edge case where the current book doesn't overlap the chosen universe.
        current-portfolio-metrics* (or selected-current-metrics
                                       (current-portfolio-metrics
                                        current-portfolio-analysis))
        current-portfolio-instrument-ids (or (:instrument-ids
                                              current-portfolio-metrics*)
                                             instrument-ids)
        current-portfolio-weights (or (:weights current-portfolio-metrics*)
                                      current-weights*)
        current-expected-return (:expected-return current-portfolio-metrics*)
        current-volatility (:volatility current-portfolio-metrics*)
        ;; Covariance-only objectives solve through an invalid return model
        ;; (they never use returns); return-based display metrics are then
        ;; omitted, not faked.
        returns-unavailable? (and (covariance-only-request? request)
                                  (= :invalid (:status return-result)))
        expected-return (when-not returns-unavailable?
                          (math/portfolio-return target-weights expected-returns))
        volatility (sqrt (math/portfolio-variance target-weights (:covariance risk-result)))
        equal-risk-sections* (equal-risk-sections request
                                                  {:risk-result risk-result
                                                   :selection selection
                                                   :solver-plan solver-plan
                                                   :diagnostics diagnostics*
                                                   :instrument-ids instrument-ids
                                                   :target-weights target-weights
                                                   :current-weights current-weights*})
        inverse-volatility-sections* (inverse-volatility-sections
                                      request
                                      {:solver-plan solver-plan
                                       :risk-result risk-result
                                       :instrument-ids instrument-ids
                                       :target-weights target-weights
                                       :current-weights current-weights*})
        labels-by-instrument* (labels-by-instrument
                               request
                               (vec (distinct (concat instrument-ids
                                                      (request-instrument-ids request)))))
        overlay-payload (frontier-overlays/overlay-series
                         {:instrument-ids instrument-ids
                          :target-weights target-weights
                          :expected-returns expected-returns
                          :covariance (:covariance risk-result)
                          :labels-by-instrument labels-by-instrument*})
        warnings* (user-facing-warnings
                   labels-by-instrument*
                   (dedupe-warnings
                    (concat (:warnings request)
                            (:warnings encoded)
                            (:warnings solver-plan)
                            (solver-health/warnings solver-results
                                                    display-frontier-results)
                            (:warnings risk-result)
                            (:warnings return-result)
                            (:warnings default-frontier)
                            (:warnings equal-risk-sections*)
                            (when returns-unavailable?
                              [{:code :return-model-unavailable-for-display
                                :message "Expected-return estimates are unavailable; this objective's weights never use them, so only return-based display metrics are hidden."}])
                            (when cash-warning [cash-warning])
                            (when sparse-warning [sparse-warning]))))]
    (merge
     ;; :equal-risk only: contribution + solver sections from published weights.
     (select-keys equal-risk-sections* [:risk-contributions
                                        :current-risk-contributions
                                        :equal-risk-solver
                                        :risk-structure])
     ;; :inverse-volatility only: the sizing-fidelity section plus the same
     ;; objective-agnostic contribution/structure analytics Equal Risk
     ;; publishes (:quality :diagnostic — no convergence quality applies to
     ;; the deterministic projection).
     (select-keys inverse-volatility-sections* [:inverse-volatility
                                                :risk-contributions
                                                :current-risk-contributions
                                                :risk-structure])
    {:status :solved
     :scenario-id (:scenario-id request)
     :as-of-ms (:as-of-ms request)
     :instrument-ids instrument-ids
     :target-weights target-weights
     :current-weights current-weights*
     :current-portfolio-instrument-ids current-portfolio-instrument-ids
     :current-portfolio-weights current-portfolio-weights
     :labels-by-instrument labels-by-instrument*
     :target-weights-by-instrument (zipmap instrument-ids target-weights)
     :current-weights-by-instrument (zipmap instrument-ids current-weights*)
     :current-portfolio-weights-by-instrument (zipmap current-portfolio-instrument-ids
                                                       current-portfolio-weights)
     :expected-returns-by-instrument (zipmap instrument-ids expected-returns)
     :dropped-weights dropped
     :current-expected-return current-expected-return
     :current-volatility current-volatility
     :current-performance (or (:performance current-portfolio-metrics*)
                              (sharpe-summary current-expected-return
                                              current-volatility))
     :expected-return expected-return
     :volatility volatility
     :performance (sharpe-summary expected-return volatility)
     :history-summary (history-summary request)
     :solver {:strategy (:strategy solver-plan)
              :objective-kind (get-in solver-plan [:problems 0 :objective-kind])}
     :solver-results solver-results
     :frontier (:frontier default-frontier)
     :frontier-summary (:frontier-summary default-frontier)
     :frontiers (into {}
                      (map (fn [[mode frontier-data]]
                             [mode (:frontier frontier-data)]))
                      display-frontiers)
     :frontier-summaries (into {}
                               (map (fn [[mode frontier-data]]
                                      [mode (:frontier-summary frontier-data)]))
                               display-frontiers)
     :frontier-overlays overlay-payload
     :diagnostics diagnostics*
     :return-model (:model return-result)
     :risk-model (:model risk-result)
     :requested-risk-model (:requested-model risk-result)
     :risk-estimation (:risk-estimation risk-result)
     :pair-metadata (:pair-metadata risk-result)
     :return-decomposition-by-instrument (:decomposition-by-instrument return-result)
     :black-litterman-diagnostics (:diagnostics return-result)
     :warnings warnings*
     :rebalance-preview (rebalance-preview request
                                           instrument-ids
                                           current-weights*
                                           target-weights)})))

(defn infeasible-payload
  [request risk-result return-result solver-plan]
  (let [labels-by-instrument* (labels-by-instrument
                               request
                               (vec (distinct (concat (:instrument-ids risk-result)
                                                      (request-instrument-ids request)))))
        warnings* (user-facing-warnings
                   labels-by-instrument*
                   (dedupe-warnings
                    (concat (:warnings request)
                            (:warnings solver-plan)
                            (:warnings risk-result)
                            (:warnings return-result))))]
    (assoc solver-plan
           :scenario-id (:scenario-id request)
           :warnings warnings*)))

(defn result-from-solver-results
  [request
   {:keys [risk-result
           return-result
           expected-returns
           encoded
           current-weights
           current-portfolio-analysis
           solver-plan
           display-frontier-plans
           display-frontier-aliases]}
   solver-results
   display-frontier-results]
  (let [selection (target-selection/target-selection request
                                                     solver-plan
                                                     solver-results
                                                     expected-returns
                                                     (:covariance risk-result))]
    (if (= :solved (:status selection))
      (let [display-frontiers (display-frontier/selections
                               {:request request
                                :risk-result risk-result
                                :expected-returns expected-returns
                                :display-frontier-plans display-frontier-plans
                                :display-frontier-aliases display-frontier-aliases
                                :target-frontier (:target-frontier selection)
                                :display-frontier-results display-frontier-results
                                :solved-points-fn target-selection/solved-points})]
        (solved-payload request
                        risk-result
                        return-result
                        expected-returns
                        solver-plan
                        solver-results
                        selection
                        display-frontiers
                        encoded
                        current-weights
                        current-portfolio-analysis
                        display-frontier-results))
      selection)))
