(ns hyperopen.portfolio.optimizer.application.rebalance-preview
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts.constants :as contracts-constants]
            [hyperopen.portfolio.optimizer.domain.rebalance :as rebalance]
            [hyperopen.portfolio.optimizer.ids :as ids]))

(def ^:private maker-fee-bps
  contracts-constants/maker-fee-bps)

(def ^:private finite-number? coercion/finite-number?)
(def ^:private non-blank-text coercion/non-blank-text)
(def ^:private parse-number coercion/parse-float-number)

(defn- instrument-id-keyed
  "Defense-in-depth: rekey an instrument-keyed map through ids/instrument-id-key so
   any keyword id that slipped past the worker-boundary codec (e.g. :perp:BTC, which
   stringifies to the literal \":perp:BTC\") can never spawn a phantom instrument-id
   here. Idempotent on already-stringified keys."
  [m]
  (when (map? m)
    (into {}
          (map (fn [[k v]] [(ids/instrument-id-key k) v]))
          m)))

(defn- ordered-distinct
  [values]
  (vec (distinct (keep non-blank-text values))))

(defn- normalized-weight-map
  [weights-by-id]
  (into {}
        (keep (fn [[instrument-id weight]]
                (when (and (non-blank-text instrument-id)
                           (finite-number? weight))
                  [(non-blank-text instrument-id) weight])))
        weights-by-id))

(defn- vector-weight-map
  [instrument-ids weights]
  (normalized-weight-map
   (map vector
        (or instrument-ids [])
        (or weights []))))

(defn- target-weights-by-id
  [result]
  (merge (vector-weight-map (:instrument-ids result)
                            (:target-weights result))
         (normalized-weight-map (:target-weights-by-instrument result))))

(defn- request-current-weights-by-id
  [request]
  (let [current-portfolio (:current-portfolio request)]
    (normalized-weight-map
     (map (fn [row]
            [(:instrument-id row) (:weight row)])
          (concat (vals (:by-instrument current-portfolio))
                  (:exposures current-portfolio))))))

(defn- result-current-weights-by-id
  [result]
  (merge (vector-weight-map (:instrument-ids result)
                            (:current-weights result))
         (normalized-weight-map (:current-weights-by-instrument result))
         (vector-weight-map (:current-portfolio-instrument-ids result)
                            (:current-portfolio-weights result))
         (normalized-weight-map
          (instrument-id-keyed
           (:current-portfolio-weights-by-instrument result)))))

(defn- current-weights-by-id
  [request result]
  (merge (result-current-weights-by-id result)
         (request-current-weights-by-id request)))

(defn- normalize-instrument
  [instrument]
  (when-let [instrument-id (non-blank-text (:instrument-id instrument))]
    [instrument-id
     (assoc instrument
            :instrument-id instrument-id
            :instrument-type (or (:instrument-type instrument)
                                 (:market-type instrument)))]))

(defn- instruments-by-id
  [request]
  (into {}
        (keep normalize-instrument)
        (let [current-portfolio (:current-portfolio request)]
          (concat (:requested-universe request)
                  (:universe request)
                  (:current-portfolio-universe request)
                  (vals (:by-instrument current-portfolio))
                  (:exposures current-portfolio)))))

(defn- row-price
  [row]
  (some (fn [key]
          (let [price (parse-number (get row key))]
            (when (finite-number? price)
              price)))
        [:close :close-price :mark-price :markPx :mark :oraclePx :midPx]))

(defn- latest-history-prices-by-id
  [request instrument-ids]
  (into {}
        (keep (fn [instrument-id]
                (when-let [price (some-> (get-in request
                                                  [:history
                                                   :price-series-by-instrument
                                                   instrument-id])
                                          last
                                          row-price)]
                  [instrument-id price])))
        instrument-ids))

(defn- current-prices-by-id
  [request]
  (let [current-portfolio (:current-portfolio request)]
    (into {}
          (keep (fn [row]
                  (when-let [instrument-id (non-blank-text (:instrument-id row))]
                    (when-let [price (row-price row)]
                      [instrument-id price]))))
          (concat (vals (:by-instrument current-portfolio))
                  (:exposures current-portfolio)))))

(defn- prices-by-id
  [request instrument-ids]
  (merge (latest-history-prices-by-id request instrument-ids)
         (current-prices-by-id request)
         (or (get-in request [:execution-assumptions :prices-by-id]) {})))

(defn- preview-instrument-ids
  [request result target-by-id current-by-id]
  (ordered-distinct
   (concat (:instrument-ids result)
           (keys target-by-id)
           (:current-portfolio-instrument-ids result)
           (keys (instrument-id-keyed
                  (:current-portfolio-weights-by-instrument result)))
           (keys current-by-id)
           (keys (get-in request [:current-portfolio :by-instrument]))
           (map :instrument-id (get-in request [:current-portfolio :exposures])))))

(defn- capital-usd
  [request]
  (or (get-in request [:current-portfolio :capital :nav-usdc])
      (get-in request [:current-portfolio :capital :account-value-usd])
      0))

(defn- current-perp-gross-usdc
  [request]
  (let [exposures (get-in request [:current-portfolio :exposures])
        perp-notionals (keep #(when (= :perp (:market-type %))
                                (:abs-notional-usdc %))
                             exposures)]
    (when (seq exposures)
      (reduce + 0 perp-notionals))))

(defn- stable-dollar-coin?
  [coin]
  (boolean (some-> coin str str/trim str/upper-case
                   (str/starts-with? "USD"))))

(defn- venue-collateral-usd
  [request]
  (let [portfolio (:current-portfolio request)]
    (if (= :unified (get-in portfolio [:account :mode]))
      (+ (or (get-in portfolio [:capital :cash-usdc]) 0)
         (reduce + 0 (keep #(when (and (= :spot (:market-type %))
                                       (stable-dollar-coin? (:coin %)))
                              (:abs-notional-usdc %))
                           (:exposures portfolio))))
      (get-in portfolio [:capital :nav-usdc]))))

(defn- blocklisted-id?
  "True when the row's instrument is on the draft blocklist (matched across every id
   candidate, since the blocklist may store a different alias than the row id)."
  [blocklist instruments-by-id instrument-id]
  (boolean (and (seq blocklist)
                (or (contains? blocklist instrument-id)
                    (some blocklist
                          (ids/instrument-id-candidates
                           (get instruments-by-id instrument-id)))))))

(defn- target-weight-for
  "Target weight the preview trades toward, or nil when the optimizer expressed no
   opinion. Only an explicit result target — or an explicit user blocklisting, which
   means \"exit this position\" — may stage a trade; a held instrument the allocator
   excluded (out-of-universe spot, dropped for missing history, ...) must NOT default
   to a 0% target, which would stage it as a sell-to-zero."
  [target-by-id blocklist instruments-by-id instrument-id]
  (if (contains? target-by-id instrument-id)
    (get target-by-id instrument-id)
    (when (blocklisted-id? blocklist instruments-by-id instrument-id)
      0)))

(defn- leverage-by-id
  [request]
  (or (get-in request [:constraints :perp-leverage])
      (get-in request [:constraints :per-perp-leverage-caps])))

(defn- build-derived-preview
  [request result {:keys [exit-instrument-ids]}]
  (let [target-by-id (target-weights-by-id result)
        current-by-id (current-weights-by-id request result)
        instrument-ids (preview-instrument-ids request
                                               result
                                               target-by-id
                                               current-by-id)
        instruments (instruments-by-id request)
        blocklist (into (set (keep non-blank-text
                                   (get-in request [:constraints :blocklist])))
                        (keep non-blank-text exit-instrument-ids))]
    (when (seq instrument-ids)
      (rebalance/build-rebalance-preview
       {:capital-usd (capital-usd request)
        :current-margin-used-usdc (get-in request
                                          [:current-portfolio
                                           :capital
                                           :total-margin-used-usdc])
        :current-gross-exposure-usdc (get-in request
                                             [:current-portfolio
                                              :capital
                                              :gross-exposure-usdc])
        :current-perp-gross-usdc (current-perp-gross-usdc request)
        :venue-collateral-usd (venue-collateral-usd request)
        :rebalance-tolerance (get-in request [:constraints :rebalance-tolerance])
        :fallback-slippage-bps (get-in request
                                       [:execution-assumptions
                                        :fallback-slippage-bps])
        :instrument-ids instrument-ids
        :current-weights (mapv #(get current-by-id % 0) instrument-ids)
        :target-weights (mapv #(target-weight-for target-by-id
                                                  blocklist
                                                  instruments
                                                  %)
                              instrument-ids)
        :instruments-by-id instruments
        :prices-by-id (prices-by-id request instrument-ids)
        :cost-contexts-by-id (get-in request
                                     [:execution-assumptions
                                      :cost-contexts-by-id])
        :leverage-by-id (leverage-by-id request)
        :fee-bps-by-id (get-in request
                               [:execution-assumptions
                                :fee-bps-by-id])
        :default-fee-bps (get-in request
                                 [:execution-assumptions :default-fee-bps])
        :maker-fee-bps maker-fee-bps}))))

(defn result-with-rebalance-preview
  [request result]
  (cond
    (not= :solved (:status result))
    result

    (map? (:rebalance-preview result))
    result

    (not (map? request))
    result

    :else
    (if-let [preview (build-derived-preview request result nil)]
      (assoc result :rebalance-preview preview)
      result)))

(defn result-with-refreshed-rebalance-preview
  ([request result]
   (result-with-refreshed-rebalance-preview request result nil))
  ([request result opts]
   (if-let [preview (and (map? request)
                         (= :solved (:status result))
                         (build-derived-preview request result opts))]
     (assoc result :rebalance-preview preview)
     result)))

(defn last-successful-run-with-rebalance-preview
  [request last-successful-run]
  (if (map? last-successful-run)
    (update last-successful-run
            :result
            #(result-with-rebalance-preview request %))
    last-successful-run))
