(ns hyperopen.portfolio.optimizer.application.request-builder
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.history-loader :as history-loader]
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

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- normalize-id-list
  [values]
  (->> (cond
         (nil? values) []
         (set? values) values
         (sequential? values) values
         :else [values])
       (keep non-blank-text)
       distinct
       vec))

(defn- normalize-net-exposure
  [constraints]
  (let [net-min (:net-min constraints)
        net-max (:net-max constraints)]
    (if (or (some? net-min)
            (some? net-max))
      (cond-> {}
        (some? net-min) (assoc :min net-min)
        (some? net-max) (assoc :max net-max))
      (:net-exposure constraints))))

(def ^:private draft-only-constraint-keys
  #{:gross-max
    :net-min
    :net-max
    :asset-overrides
    :held-locks
    :perp-leverage})

(defn- normalize-constraints
  [constraints]
  (let [constraints* (or constraints {})
        allowlist (normalize-id-list (:allowlist constraints*))
        blocklist (normalize-id-list (:blocklist constraints*))
        held-locks (normalize-id-list (:held-locks constraints*))
        net-exposure (normalize-net-exposure constraints*)]
    (cond-> (apply dissoc constraints* draft-only-constraint-keys)
      true
      (assoc :blocklist blocklist)

      (empty? allowlist)
      (dissoc :allowlist)

      (seq allowlist)
      (assoc :allowlist allowlist)

      (contains? constraints* :gross-max)
      (assoc :gross-leverage (:gross-max constraints*))

      (some? net-exposure)
      (assoc :net-exposure net-exposure)

      (contains? constraints* :asset-overrides)
      (assoc :per-asset-overrides (:asset-overrides constraints*))

      (contains? constraints* :held-locks)
      (assoc :held-position-locks held-locks)

      (contains? constraints* :perp-leverage)
      (assoc :per-perp-leverage-caps (:perp-leverage constraints*)))))

(defn- normalize-execution-assumptions
  [execution-assumptions]
  (let [assumptions* (or execution-assumptions {})
        fallback-slippage-bps (or (:fallback-slippage-bps assumptions*)
                                  (:slippage-fallback-bps assumptions*))]
    (cond-> (dissoc assumptions* :slippage-fallback-bps)
      (some? fallback-slippage-bps)
      (assoc :fallback-slippage-bps fallback-slippage-bps))))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- confidence-variance
  [confidence]
  (let [confidence* (-> confidence
                        (max 0.0)
                        (min 1.0))]
    (max 0.000001 (- 1.0 confidence*))))

(defn- normalize-black-litterman-view
  [view]
  (let [view* (or view {})
        confidence (:confidence view*)
        instrument-id (non-blank-text (:instrument-id view*))
        long-id (non-blank-text (:long-instrument-id view*))
        short-id (non-blank-text (:short-instrument-id view*))
        weights (cond
                  (map? (:weights view*))
                  (:weights view*)

                  (and (= :absolute (:kind view*))
                       instrument-id)
                  {instrument-id 1}

                  (and (= :relative (:kind view*))
                       long-id
                       short-id
                       (not= long-id short-id))
                  {long-id 1
                   short-id -1}

                  :else nil)]
    (cond-> (assoc view* :weights weights)
      (and (not (finite-number? (:confidence-variance view*)))
           (finite-number? confidence))
      (assoc :confidence-variance (confidence-variance confidence)))))

(defn- normalize-return-model
  [return-model]
  (let [return-model* (or return-model default-return-model)]
    (if (= :black-litterman (:kind return-model*))
      (update return-model* :views #(vec (map normalize-black-litterman-view (or % []))))
      return-model*)))

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
        return-model (normalize-return-model (:return-model draft*))
        risk-model (or (:risk-model draft*) default-risk-model)
        objective (or (:objective draft*) default-objective)
        constraints (normalize-constraints (:constraints draft*))
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
             :execution-assumptions (normalize-execution-assumptions
                                     (:execution-assumptions draft*))
             :history history
             :warnings warnings
             :as-of-ms as-of-ms}
      prior (assoc :black-litterman-prior prior))))
