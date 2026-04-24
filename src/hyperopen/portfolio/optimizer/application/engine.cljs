(ns hyperopen.portfolio.optimizer.application.engine
  (:require [hyperopen.portfolio.optimizer.domain.black-litterman :as black-litterman]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.diagnostics :as diagnostics]
            [hyperopen.portfolio.optimizer.domain.frontier :as frontier]
            [hyperopen.portfolio.optimizer.domain.math :as math]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]
            [hyperopen.portfolio.optimizer.domain.rebalance :as rebalance]
            [hyperopen.portfolio.optimizer.domain.returns :as returns]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]
            [hyperopen.portfolio.optimizer.domain.weight-cleaning :as weight-cleaning]))

(defn- sqrt
  [value]
  (js/Math.sqrt (max 0 value)))

(defn- return-model-kind
  [request]
  (get-in request [:return-model :kind]))

(defn- universe-by-id
  [universe]
  (into {}
        (map (fn [instrument]
               [(:instrument-id instrument) instrument]))
        universe))

(defn- ordered-universe
  [universe instrument-ids]
  (let [by-id (universe-by-id universe)]
    (mapv by-id instrument-ids)))

(defn- current-weight
  [request instrument-id]
  (or (get-in request [:current-portfolio :by-instrument instrument-id :weight])
      0))

(defn- current-weights
  [request instrument-ids]
  (mapv #(current-weight request %) instrument-ids))

(defn- prior-weights
  [request instrument-ids]
  (mapv #(or (get-in request [:black-litterman-prior :weights-by-instrument %]) 0)
        instrument-ids))

(defn- base-return-estimate
  [request]
  (returns/estimate-expected-returns
   {:return-model (:return-model request)
    :periods-per-year (:periods-per-year request)
    :history (:history request)}))

(defn- expected-return-result
  [request risk-result]
  (if (= :black-litterman (return-model-kind request))
    (let [base (base-return-estimate request)
          posterior (black-litterman/posterior-returns
                     {:instrument-ids (:instrument-ids risk-result)
                      :covariance (:covariance risk-result)
                      :prior-weights (prior-weights request (:instrument-ids risk-result))
                      :risk-aversion (get-in request [:return-model :risk-aversion])
                      :tau (get-in request [:return-model :tau])
                      :views (get-in request [:return-model :views])
                      :prior-source (get-in request [:black-litterman-prior :source])})]
      {:model :black-litterman
       :instrument-ids (:instrument-ids posterior)
       :expected-returns-by-instrument (:expected-returns-by-instrument posterior)
       :decomposition-by-instrument (:decomposition-by-instrument base)
       :diagnostics (:diagnostics posterior)
       :warnings (:warnings base)})
    (base-return-estimate request)))

(defn- expected-return-vector
  [return-result instrument-ids]
  (mapv #(or (get-in return-result [:expected-returns-by-instrument %]) 0)
        instrument-ids))

(defn- encoded-constraints
  [request instrument-ids]
  (constraints/encode-constraints
   {:universe (ordered-universe (:universe request) instrument-ids)
    :current-weights (into {}
                           (map (fn [instrument-id]
                                  [instrument-id (current-weight request instrument-id)]))
                           instrument-ids)
    :constraints (:constraints request)}))

(defn- default-solve-problem
  [_problem]
  {:status :error
   :reason :solver-not-configured})

(defn- solve-plan
  [solver-plan solve-problem]
  (mapv (fn [problem]
          (let [result (solve-problem problem)]
            (assoc result :problem problem)))
        (:problems solver-plan)))

(defn- solve-plan-async
  [solver-plan solve-problem]
  (-> (js/Promise.all
       (clj->js
        (mapv (fn [problem]
                (-> (js/Promise.resolve (solve-problem problem))
                    (.then (fn [result]
                             (assoc result :problem problem)))))
              (:problems solver-plan))))
      (.then (fn [results]
               (vec (array-seq results))))))

(defn- solved?
  [result]
  (= :solved (:status result)))

(defn- portfolio-point
  [expected-returns covariance risk-free-rate idx result]
  (let [weights (:weights result)
        expected-return (math/portfolio-return weights expected-returns)
        variance (math/portfolio-variance weights covariance)
        volatility (sqrt variance)]
    {:id idx
     :return-tilt (get-in result [:problem :return-tilt])
     :weights weights
     :expected-return expected-return
     :volatility volatility
     :sharpe (when (pos? volatility)
               (/ (- expected-return (or risk-free-rate 0))
                  volatility))
     :solver-status (:status result)
     :solver (:solver result)
     :iterations (:iterations result)
     :elapsed-ms (:elapsed-ms result)}))

(defn- solver-failure
  [solver-plan solver-results]
  {:status :infeasible
   :reason :solver-returned-no-solution
   :solver {:strategy (:strategy solver-plan)}
   :solver-results solver-results})

(defn- selected-point
  [request solver-plan solver-results expected-returns covariance]
  (let [points (->> solver-results
                    (keep-indexed (fn [idx result]
                                    (when (solved? result)
                                      (portfolio-point expected-returns
                                                       covariance
                                                       (:risk-free-rate request)
                                                       idx
                                                       result))))
                    vec)]
    (if (empty? points)
      (solver-failure solver-plan solver-results)
      (let [frontier-points (frontier/efficient-frontier points)
            selected (if (= :frontier-sweep (:strategy solver-plan))
                       (frontier/select-frontier-point frontier-points (:objective request))
                       (first points))]
        {:status :solved
         :selected selected
         :frontier frontier-points}))))

(defn- aligned-clean-weights
  [instrument-ids weights encoded-constraints request]
  (let [cleaned (weight-cleaning/clean-weights
                 {:instrument-ids instrument-ids
                  :weights weights
                  :dust-threshold (get-in request [:constraints :dust-threshold])
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

(defn- rebalance-preview
  [request instrument-ids current-weights target-weights]
  (let [execution-assumptions (:execution-assumptions request)]
    (rebalance/build-rebalance-preview
     {:capital-usd (or (get-in request [:current-portfolio :capital :nav-usdc])
                       (get-in request [:current-portfolio :capital :account-value-usd])
                       0)
      :rebalance-tolerance (get-in request [:constraints :rebalance-tolerance])
      :fallback-slippage-bps (:fallback-slippage-bps execution-assumptions)
      :instrument-ids instrument-ids
      :current-weights current-weights
      :target-weights target-weights
      :instruments-by-id (normalized-instruments-by-id (:universe request))
      :prices-by-id (:prices-by-id execution-assumptions)
      :cost-contexts-by-id (:cost-contexts-by-id execution-assumptions)
      :fee-bps-by-id (:fee-bps-by-id execution-assumptions)})))

(defn- solved-payload
  [request
   risk-result
   return-result
   solver-plan
   solver-results
   selection
   encoded
   current-weights*]
  (let [instrument-ids (:instrument-ids risk-result)
        expected-returns (expected-return-vector return-result instrument-ids)
        {:keys [target-weights dropped]} (aligned-clean-weights instrument-ids
                                                               (get-in selection [:selected :weights])
                                                               encoded
                                                               request)
        diagnostics (diagnostics/portfolio-diagnostics
                     {:instrument-ids instrument-ids
                      :current-weights current-weights*
                      :target-weights target-weights
                      :lower-bounds (:lower-bounds encoded)
                      :upper-bounds (:upper-bounds encoded)
                      :covariance (:covariance risk-result)})]
    {:status :solved
     :scenario-id (:scenario-id request)
     :as-of-ms (:as-of-ms request)
     :instrument-ids instrument-ids
     :target-weights target-weights
     :current-weights current-weights*
     :dropped-weights dropped
     :expected-return (math/portfolio-return target-weights expected-returns)
     :volatility (sqrt (math/portfolio-variance target-weights (:covariance risk-result)))
     :solver {:strategy (:strategy solver-plan)
              :objective-kind (get-in solver-plan [:problems 0 :objective-kind])}
     :solver-results solver-results
     :frontier (:frontier selection)
     :diagnostics diagnostics
     :return-model (:model return-result)
     :risk-model (:model risk-result)
     :return-decomposition-by-instrument (:decomposition-by-instrument return-result)
     :black-litterman-diagnostics (:diagnostics return-result)
     :warnings (vec (concat (:warnings request)
                            (:warnings risk-result)
                            (:warnings return-result)))
     :rebalance-preview (rebalance-preview request
                                           instrument-ids
                                           current-weights*
                                           target-weights)}))

(defn- optimization-context
  [request]
  (let [risk-result (risk/estimate-risk-model
                     {:risk-model (:risk-model request)
                      :periods-per-year (:periods-per-year request)
                      :history (:history request)})
        instrument-ids (:instrument-ids risk-result)
        return-result (expected-return-result request risk-result)
        expected-returns (expected-return-vector return-result instrument-ids)
        encoded (encoded-constraints request instrument-ids)
        current-weights* (current-weights request instrument-ids)
        solver-plan (objectives/build-solver-plan
                     {:objective (:objective request)
                      :instrument-ids instrument-ids
                      :expected-returns expected-returns
                      :covariance (:covariance risk-result)
                      :encoded-constraints encoded
                      :return-tilts (:return-tilts request)})]
    {:risk-result risk-result
     :return-result return-result
     :expected-returns expected-returns
     :encoded encoded
     :current-weights current-weights*
     :solver-plan solver-plan}))

(defn- infeasible-payload
  [request risk-result return-result solver-plan]
  (assoc solver-plan
         :scenario-id (:scenario-id request)
         :warnings (vec (concat (:warnings request)
                                (:warnings risk-result)
                                (:warnings return-result)))))

(defn- result-from-solver-results
  [request
   {:keys [risk-result
           return-result
           expected-returns
           encoded
           current-weights
           solver-plan]}
   solver-results]
  (let [selection (selected-point request
                                  solver-plan
                                  solver-results
                                  expected-returns
                                  (:covariance risk-result))]
    (if (= :solved (:status selection))
      (solved-payload request
                      risk-result
                      return-result
                      solver-plan
                      solver-results
                      selection
                      encoded
                      current-weights)
      selection)))

(defn run-optimization
  ([request]
   (run-optimization request {}))
  ([request {:keys [solve-problem]}]
   (let [{:keys [risk-result return-result solver-plan] :as context}
         (optimization-context request)]
     (if (= :infeasible (:status solver-plan))
       (infeasible-payload request risk-result return-result solver-plan)
       (let [solver-results (solve-plan solver-plan (or solve-problem default-solve-problem))]
         (result-from-solver-results request context solver-results))))))

(defn run-optimization-async
  ([request]
   (run-optimization-async request {}))
  ([request {:keys [solve-problem]}]
   (let [{:keys [risk-result return-result solver-plan] :as context}
         (optimization-context request)]
     (if (= :infeasible (:status solver-plan))
       (js/Promise.resolve
        (infeasible-payload request risk-result return-result solver-plan))
       (-> (solve-plan-async solver-plan (or solve-problem default-solve-problem))
           (.then (fn [solver-results]
                    (result-from-solver-results request context solver-results))))))))
