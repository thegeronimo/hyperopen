(ns hyperopen.portfolio.optimizer.application.engine.context
  (:require [hyperopen.portfolio.optimizer.application.display-frontier :as display-frontier]
            [hyperopen.portfolio.optimizer.contracts.constants :as contracts-constants]
            [hyperopen.portfolio.optimizer.domain.black-litterman :as black-litterman]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.history-assumption-proxy :as history-assumption-proxy]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]
            [hyperopen.portfolio.optimizer.domain.returns :as returns]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]
            [hyperopen.portfolio.optimizer.ids :as ids]))

(defn- return-model-kind
  [request]
  (get-in request [:return-model :kind]))

(defn- universe-by-id
  [universe]
  (into {}
        (mapcat (fn [instrument]
                  (map (fn [instrument-id]
                         [instrument-id instrument])
                       (ids/instrument-id-candidates instrument))))
        universe))

(defn- ordered-universe
  [universe instrument-ids]
  (let [by-id (universe-by-id universe)]
    (mapv (fn [instrument-id]
            (assoc (or (get by-id instrument-id) {})
                   :instrument-id instrument-id))
          instrument-ids)))

(defn- id-set
  [values]
  (cond
    (nil? values) nil
    (set? values) values
    (sequential? values) (set values)
    :else #{values}))

(defn- instrument-allowed?
  [allowlist instrument]
  (or (nil? allowlist)
      (some allowlist (ids/instrument-id-candidates instrument))))

(defn- instrument-blocked?
  [blocklist instrument]
  (boolean (some blocklist (ids/instrument-id-candidates instrument))))

(defn- spot-instrument?
  [instrument]
  (let [instrument-id (:instrument-id instrument)
        market-type (ids/normalize-market-type
                     (or (:market-type instrument)
                         (:instrument-type instrument)
                         (:optimizer-history/instrument-kind instrument)))]
    (or (= :spot market-type)
        (and (string? instrument-id)
             (or (.startsWith instrument-id "spot:")
                 (.startsWith instrument-id "hl:spot:"))))))

(defn- active-universe
  [request]
  (let [allowlist (id-set (get-in request [:constraints :allowlist]))
        blocklist (or (id-set (get-in request [:constraints :blocklist])) #{})
        include-spot? (true? (get-in request [:constraints :include-spot?]))]
    (filterv (fn [instrument]
               (and (instrument-allowed? allowlist instrument)
                    (not (instrument-blocked? blocklist instrument))
                    (or include-spot?
                        (not (spot-instrument? instrument)))))
             (:universe request))))

(defn- first-available-id
  [available instrument]
  (first (filter available (ids/instrument-id-candidates instrument))))

(defn- active-instrument-ids
  [request available-instrument-ids]
  (let [available (set available-instrument-ids)
        selected (if (seq (:universe request))
                   (keep (partial first-available-id available)
                         (active-universe request))
                   available-instrument-ids)]
    (vec (distinct selected))))

(defn- covariance-submatrix
  [covariance indexes]
  (mapv (fn [row-idx]
          (let [row (nth covariance row-idx)]
            (mapv #(nth row %) indexes)))
        indexes))

(defn- filter-risk-result
  [risk-result active-ids]
  (let [instrument-ids (:instrument-ids risk-result)]
    (if (= instrument-ids active-ids)
      risk-result
      (let [index-by-id (zipmap instrument-ids (range))
            indexes (mapv index-by-id active-ids)]
        (assoc risk-result
               :instrument-ids active-ids
               :covariance (covariance-submatrix (:covariance risk-result)
                                                 indexes))))))

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

(defn- current-portfolio-return-model
  [request]
  (let [return-model (:return-model request)]
    (if (= :black-litterman (:kind return-model))
      {:kind :historical-mean}
      return-model)))

(defn- current-portfolio-return-estimate
  [request effective-return-result]
  (let [baseline (returns/estimate-expected-returns
                  {:return-model (current-portfolio-return-model request)
                   :periods-per-year (:periods-per-year request)
                   :history (:current-portfolio-history request)})
        effective-returns (:expected-returns-by-instrument effective-return-result)]
    (if (and (= :black-litterman (return-model-kind request))
             (seq effective-returns))
      (-> baseline
          (assoc :model :black-litterman)
          (update :expected-returns-by-instrument merge effective-returns))
      baseline)))

(defn expected-return-vector
  [return-result instrument-ids]
  (mapv #(or (get-in return-result [:expected-returns-by-instrument %]) 0)
        instrument-ids))

(defn- expected-return-result
  [request risk-result]
  (if (= :black-litterman (return-model-kind request))
    (let [base (base-return-estimate request)
          baseline-prior-returns (expected-return-vector base
                                                         (:instrument-ids risk-result))
          posterior (black-litterman/posterior-returns
                     {:instrument-ids (:instrument-ids risk-result)
                      :covariance (:covariance risk-result)
                      :prior-weights (prior-weights request (:instrument-ids risk-result))
                      :prior-returns baseline-prior-returns
                      :risk-aversion (get-in request [:return-model :risk-aversion])
                      :tau (get-in request [:return-model :tau])
                      :views (get-in request [:return-model :views])
                      :prior-source (get-in request [:black-litterman-prior :source])})
          posterior-diagnostics (:diagnostics posterior)
          diagnostics (assoc posterior-diagnostics
                             :prior-return-source
                             (if (= :provided (:prior-return-source posterior-diagnostics))
                               :baseline-expected-returns
                               (:prior-return-source posterior-diagnostics))
                             :weight-prior-source
                             (get-in request [:black-litterman-prior :source]))]
      {:model :black-litterman
       :status (:status posterior)
       :instrument-ids (:instrument-ids posterior)
       :expected-returns-by-instrument (:expected-returns-by-instrument posterior)
       :decomposition-by-instrument (:decomposition-by-instrument base)
       :diagnostics diagnostics
       :warnings (vec (concat (:warnings posterior)
                              (:warnings base)))})
    (base-return-estimate request)))

(defn- history-sans-freshness
  [request]
  (dissoc (:history request) :freshness))

(defn- return-inputs-memo-lookup
  [memo key compute]
  (let [cached @memo]
    (if (and cached (= (:key cached) key))
      (:value cached)
      (let [value (compute)]
        (vreset! memo {:key key :value value})
        value))))

(defonce ^:private ui-risk-result-memo (volatile! nil))

(defn- ui-risk-result
  [request]
  (return-inputs-memo-lookup
   ui-risk-result-memo
   {:risk-model (:risk-model request)
    :periods-per-year (:periods-per-year request)
    :history (history-sans-freshness request)}
   (fn []
     (risk/estimate-risk-model
      {:risk-model (:risk-model request)
       :periods-per-year (:periods-per-year request)
       :history (:history request)}))))

(defonce ^:private expected-return-inputs-memo (volatile! nil))

(defn expected-return-inputs-by-instrument
  "Returns the same ordered expected-return inputs used by objective scoring."
  [request]
  (return-inputs-memo-lookup
   expected-return-inputs-memo
   (-> (select-keys request [:risk-model :periods-per-year :history
                             :return-model :black-litterman-prior])
       (assoc :history (history-sans-freshness request)))
   (fn []
     (let [risk-result (ui-risk-result request)
           instrument-ids (:instrument-ids risk-result)
           return-result (expected-return-result request risk-result)
           expected-returns (expected-return-vector return-result instrument-ids)]
       (zipmap instrument-ids expected-returns)))))

(defonce ^:private baseline-return-inputs-memo (volatile! nil))

(defn baseline-expected-return-inputs-by-instrument
  "Returns the baseline historical/funding expected-return inputs before Black-Litterman posterior blending."
  [request]
  (return-inputs-memo-lookup
   baseline-return-inputs-memo
   (-> (select-keys request [:risk-model :periods-per-year :history :return-model])
       (assoc :history (history-sans-freshness request)))
   (fn []
     (let [risk-result (ui-risk-result request)
           instrument-ids (:instrument-ids risk-result)
           return-result (base-return-estimate request)
           expected-returns (expected-return-vector return-result instrument-ids)]
       (zipmap instrument-ids expected-returns)))))

(defn- encoded-constraints
  [request instrument-ids]
  (constraints/encode-constraints
   {:universe (ordered-universe (:universe request) instrument-ids)
    :history (:history request)
    :current-weights (into {}
                           (map (fn [instrument-id]
                                  [instrument-id (current-weight request instrument-id)]))
                           instrument-ids)
    :constraints (:constraints request)}))

(defn- invalid-return-model-plan
  [return-result]
  {:status :infeasible
   :reason :invalid-return-model
   :warnings (:warnings return-result)
   :problems []})

(defn- current-portfolio-analysis
  [request effective-return-result]
  (when-let [history (:current-portfolio-history request)]
    (let [risk-result (risk/estimate-risk-model
                       {:risk-model (:risk-model request)
                        :periods-per-year (:periods-per-year request)
                        :history history})
          instrument-ids (:instrument-ids risk-result)
          return-result (current-portfolio-return-estimate request
                                                          effective-return-result)
          expected-returns (expected-return-vector return-result instrument-ids)
          weights (current-weights request instrument-ids)]
      {:risk-result risk-result
       :return-result return-result
       :expected-returns expected-returns
       :instrument-ids instrument-ids
       :weights weights})))

(defn report-progress!
  [on-progress payload]
  (when (fn? on-progress)
    (on-progress payload)))

(defn optimization-context
  ([request]
   (optimization-context request nil))
  ([request on-progress]
   (report-progress!
    on-progress
    {:step :risk-model
     :status :running
     :percent 25
     :detail "estimating covariance"})
   (let [{conservative-inputs :conservative
          proxy-inputs :proxy} (history-assumptions/history-assumption-engine-inputs
                                request)
         raw-risk-result (-> (risk/estimate-risk-model
                              {:risk-model (:risk-model request)
                               :periods-per-year (:periods-per-year request)
                               :history (:history request)})
                             (risk/augment-risk-result-with-assumptions conservative-inputs)
                             (history-assumption-proxy/augment-risk-result-with-proxy-assumptions
                              proxy-inputs
                              {:periods-per-year (:periods-per-year request)}))
         instrument-ids (active-instrument-ids request (:instrument-ids raw-risk-result))
         risk-result (filter-risk-result raw-risk-result instrument-ids)
         _ (report-progress!
            on-progress
            {:step :risk-model
             :status :succeeded
             :percent 100
             :detail (or (some-> (:model risk-result) name)
                         "estimated")})
         _ (report-progress!
            on-progress
            {:step :return-model
             :status :running
             :percent 25
             :detail "estimating expected returns"})
         return-result (history-assumptions/augment-expected-returns
                        (expected-return-result request risk-result)
                        (merge conservative-inputs proxy-inputs))
         _ (report-progress!
            on-progress
            {:step :return-model
             :status :succeeded
             :percent 100
             :detail (or (some-> (:model return-result) name)
                         "estimated")})
         expected-returns (expected-return-vector return-result instrument-ids)
         _ (report-progress!
            on-progress
            {:step :solve
             :status :running
             :percent 0
             :detail "encoding constraints"})
         current-portfolio-analysis* (current-portfolio-analysis request
                                                                 return-result)]
     (if (and (= :invalid (:status return-result))
              (not (contains? contracts-constants/covariance-only-objective-kinds
                              (get-in request [:objective :kind]))))
       {:risk-result risk-result
        :return-result return-result
        :expected-returns expected-returns
        :encoded nil
        :current-weights (current-weights request instrument-ids)
        :current-portfolio-analysis current-portfolio-analysis*
        :solver-plan (invalid-return-model-plan return-result)
        :display-frontier-plans {}
        :display-frontier-aliases {}}
       (let [encoded (encoded-constraints request instrument-ids)
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
          :current-portfolio-analysis current-portfolio-analysis*
          :solver-plan solver-plan
          :display-frontier-plans plans
          :display-frontier-aliases aliases})))))
