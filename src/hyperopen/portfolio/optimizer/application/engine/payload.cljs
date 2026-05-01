(ns hyperopen.portfolio.optimizer.application.engine.payload
  (:require [hyperopen.portfolio.optimizer.application.display-frontier :as display-frontier]
            [hyperopen.portfolio.optimizer.application.engine.target-selection :as target-selection]
            [hyperopen.portfolio.optimizer.application.instrument-labels :as instrument-labels]
            [hyperopen.portfolio.optimizer.domain.diagnostics :as diagnostics]
            [hyperopen.portfolio.optimizer.domain.frontier-overlays :as frontier-overlays]
            [hyperopen.portfolio.optimizer.domain.math :as math]
            [hyperopen.portfolio.optimizer.domain.rebalance :as rebalance]
            [hyperopen.portfolio.optimizer.domain.weight-cleaning :as weight-cleaning]))

(defn- sqrt
  [value]
  (js/Math.sqrt (max 0 value)))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- parse-number
  [value]
  (cond
    (finite-number? value) value

    (string? value)
    (let [parsed (js/parseFloat value)]
      (when (finite-number? parsed)
        parsed))

    :else
    nil))

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

(defn- aligned-clean-weights
  [instrument-ids weights encoded-constraints request]
  (let [cleaned (weight-cleaning/clean-weights
                 {:instrument-ids instrument-ids
                  :weights weights
                  :dust-threshold (dust-threshold request)
                  :long-only? (:long-only? encoded-constraints)
                  :target-net (:net-target encoded-constraints)})
        by-id (zipmap (:instrument-ids cleaned) (:weights cleaned))]
    {:target-weights (mapv #(or (get by-id %) 0) instrument-ids)
     :dropped (:dropped cleaned)}))

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

(defn- labels-by-instrument
  [request instrument-ids]
  (instrument-labels/labels-by-instrument (:universe request) instrument-ids))

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
    {:return-observations (count (:return-calendar history))
     :oldest-common-ms (:oldest-common-ms freshness)
     :latest-common-ms (:latest-common-ms freshness)
     :age-ms (:age-ms freshness)
     :stale? (:stale? freshness)}))

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
      :fee-bps-by-id (:fee-bps-by-id execution-assumptions)})))

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
   current-weights*]
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
        current-expected-return (math/portfolio-return current-weights* expected-returns)
        current-volatility (sqrt (math/portfolio-variance current-weights*
                                                          (:covariance risk-result)))
        expected-return (math/portfolio-return target-weights expected-returns)
        volatility (sqrt (math/portfolio-variance target-weights (:covariance risk-result)))
        labels-by-instrument* (labels-by-instrument request instrument-ids)
        overlay-payload (frontier-overlays/overlay-series
                         {:instrument-ids instrument-ids
                          :target-weights target-weights
                          :expected-returns expected-returns
                          :covariance (:covariance risk-result)
                          :labels-by-instrument labels-by-instrument*})]
    {:status :solved
     :scenario-id (:scenario-id request)
     :as-of-ms (:as-of-ms request)
     :instrument-ids instrument-ids
     :target-weights target-weights
     :current-weights current-weights*
     :labels-by-instrument labels-by-instrument*
     :target-weights-by-instrument (zipmap instrument-ids target-weights)
     :current-weights-by-instrument (zipmap instrument-ids current-weights*)
     :dropped-weights dropped
     :current-expected-return current-expected-return
     :current-volatility current-volatility
     :current-performance (sharpe-summary current-expected-return current-volatility)
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
     :return-decomposition-by-instrument (:decomposition-by-instrument return-result)
     :black-litterman-diagnostics (:diagnostics return-result)
     :warnings (vec (concat (:warnings request)
                            (:warnings risk-result)
                            (:warnings return-result)
                            (:warnings default-frontier)
                            (when cash-warning [cash-warning])))
     :rebalance-preview (rebalance-preview request
                                           instrument-ids
                                           current-weights*
                                           target-weights)}))

(defn infeasible-payload
  [request risk-result return-result solver-plan]
  (assoc solver-plan
         :scenario-id (:scenario-id request)
         :warnings (vec (concat (:warnings request)
                                (:warnings risk-result)
                                (:warnings return-result)))))

(defn result-from-solver-results
  [request
   {:keys [risk-result
           return-result
           expected-returns
           encoded
           current-weights
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
                        current-weights))
      selection)))
