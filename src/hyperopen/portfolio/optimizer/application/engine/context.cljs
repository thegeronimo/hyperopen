(ns hyperopen.portfolio.optimizer.application.engine.context
  (:require [hyperopen.portfolio.optimizer.application.display-frontier :as display-frontier]
            [hyperopen.portfolio.optimizer.domain.black-litterman :as black-litterman]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]
            [hyperopen.portfolio.optimizer.domain.returns :as returns]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]))

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

(defn current-weights
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

(defn expected-return-vector
  [return-result instrument-ids]
  (mapv #(or (get-in return-result [:expected-returns-by-instrument %]) 0)
        instrument-ids))

(defn expected-return-inputs-by-instrument
  "Returns the same ordered expected-return inputs used by objective scoring."
  [request]
  (let [risk-result (risk/estimate-risk-model
                     {:risk-model (:risk-model request)
                      :periods-per-year (:periods-per-year request)
                      :history (:history request)})
        instrument-ids (:instrument-ids risk-result)
        return-result (expected-return-result request risk-result)
        expected-returns (expected-return-vector return-result instrument-ids)]
    (zipmap instrument-ids expected-returns)))

(defn- encoded-constraints
  [request instrument-ids]
  (constraints/encode-constraints
   {:universe (ordered-universe (:universe request) instrument-ids)
    :current-weights (into {}
                           (map (fn [instrument-id]
                                  [instrument-id (current-weight request instrument-id)]))
                           instrument-ids)
    :constraints (:constraints request)}))

(defn report-progress!
  [on-progress payload]
  (when (fn? on-progress)
    (on-progress payload)))

(defn optimization-context
  ([request]
   (optimization-context request nil))
  ([request on-progress]
   (let [risk-result (risk/estimate-risk-model
                      {:risk-model (:risk-model request)
                       :periods-per-year (:periods-per-year request)
                       :history (:history request)})
         _ (report-progress!
            on-progress
            {:step :risk-model
             :status :succeeded
             :percent 100
             :detail (or (some-> (:model risk-result) name)
                         "estimated")})
         instrument-ids (:instrument-ids risk-result)
         return-result (expected-return-result request risk-result)
         _ (report-progress!
            on-progress
            {:step :return-model
             :status :succeeded
             :percent 100
             :detail (or (some-> (:model return-result) name)
                         "estimated")})
         expected-returns (expected-return-vector return-result instrument-ids)
         encoded (encoded-constraints request instrument-ids)
         current-weights* (current-weights request instrument-ids)
         solver-plan (objectives/build-solver-plan
                      {:objective (:objective request)
                       :instrument-ids instrument-ids
                       :expected-returns expected-returns
                       :covariance (:covariance risk-result)
                       :encoded-constraints encoded
                       :return-tilts (:return-tilts request)})
         {:keys [plans aliases]} (display-frontier/build-plans
                                  {:request request
                                   :instrument-ids instrument-ids
                                   :expected-returns expected-returns
                                   :covariance (:covariance risk-result)
                                   :solver-plan solver-plan
                                   :return-tilts (:return-tilts request)})]
     {:risk-result risk-result
      :return-result return-result
      :expected-returns expected-returns
      :encoded encoded
      :current-weights current-weights*
      :solver-plan solver-plan
      :display-frontier-plans plans
      :display-frontier-aliases aliases})))
