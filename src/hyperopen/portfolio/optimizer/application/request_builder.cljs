(ns hyperopen.portfolio.optimizer.application.request-builder
  (:require [hyperopen.portfolio.optimizer.application.history-loader :as history-loader]
            [hyperopen.portfolio.optimizer.infrastructure.prior-data :as prior-data]))

(def default-return-model
  {:kind :historical-mean})

(def default-risk-model
  {:kind :ledoit-wolf})

(def default-objective
  {:kind :minimum-variance})

(defn- draft-universe
  [draft]
  (vec (or (:universe draft) [])))

(defn- black-litterman-return-model?
  [return-model]
  (= :black-litterman (:kind return-model)))

(defn- bl-prior
  [universe current-portfolio market-cap-by-coin return-model]
  (when (black-litterman-return-model? return-model)
    (prior-data/resolve-black-litterman-prior
     {:universe universe
      :market-cap-by-coin market-cap-by-coin
      :current-portfolio current-portfolio})))

(defn build-engine-request
  [{:keys [draft
           current-portfolio
           history-data
           market-cap-by-coin
           as-of-ms
           stale-after-ms
           funding-periods-per-year]}]
  (let [draft* (or draft {})
        requested-universe (draft-universe draft*)
        return-model (or (:return-model draft*) default-return-model)
        risk-model (or (:risk-model draft*) default-risk-model)
        objective (or (:objective draft*) default-objective)
        constraints (or (:constraints draft*) {})
        history (history-loader/align-history-inputs
                 {:universe requested-universe
                  :candle-history-by-coin (:candle-history-by-coin history-data)
                  :funding-history-by-coin (:funding-history-by-coin history-data)
                  :as-of-ms as-of-ms
                  :stale-after-ms stale-after-ms
                  :funding-periods-per-year funding-periods-per-year})
        eligible-universe (:eligible-instruments history)
        prior (bl-prior requested-universe
                        current-portfolio
                        market-cap-by-coin
                        return-model)
        warnings (vec (concat (:warnings history)
                              (:warnings prior)))]
    (cond-> {:scenario-id (:id draft*)
             :universe eligible-universe
             :requested-universe requested-universe
             :current-portfolio current-portfolio
             :return-model return-model
             :risk-model risk-model
             :objective objective
             :constraints constraints
             :execution-assumptions (or (:execution-assumptions draft*) {})
             :history history
             :warnings warnings
             :as-of-ms as-of-ms}
      prior (assoc :black-litterman-prior prior))))
