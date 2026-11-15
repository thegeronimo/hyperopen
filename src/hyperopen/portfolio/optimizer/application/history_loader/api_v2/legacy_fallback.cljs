(ns hyperopen.portfolio.optimizer.application.history-loader.api-v2.legacy-fallback
  (:require [hyperopen.portfolio.optimizer.application.history-loader.api-v2.codec :as codec]
            [hyperopen.portfolio.optimizer.application.history-loader.instruments :as instruments]
            [hyperopen.portfolio.optimizer.application.history-loader.normalization :as normalization]))

(def default-funding-periods-per-year
  8760)

(def ^:private fallback-lineage-kinds
  #{:missing})

(def ^:private hard-lineage-kinds
  #{:rejected})

(def ^:private hard-quality-statuses
  #{:failed :rejected})

(def hard-warning-codes
  #{:instrument-kind-mismatch
    :proxy-mapping-unapproved
    :proxy-validation-failed
    :validation-failed})

(def recoverable-warning-codes
  #{:identity-ambiguous
    :missing-candle-history})

(defn hard-warning?
  [warning]
  (contains? hard-warning-codes (:code warning)))

(defn- hard-series?
  [series]
  (and (map? series)
       (or (contains? hard-lineage-kinds (:lineage-kind series))
           (contains? hard-quality-statuses
                      (get-in series [:quality :status]))
           (some hard-warning? (:warnings series)))))

(defn series-fallback-needed?
  [series]
  (and (not (hard-series? series))
       (or (nil? series)
           (contains? fallback-lineage-kinds (:lineage-kind series))
           (not (seq (:points series)))
           (some #(contains? recoverable-warning-codes (:code %))
                 (:warnings series)))))

(defn- legacy-funding-summary
  [instrument funding-history-by-coin funding-periods-per-year]
  (if-not (instruments/perp-instrument? instrument)
    {:source :not-applicable
     :annualized-carry 0
     :status :not-applicable}
    (let [coin (instruments/normalize-coin instrument)
          rows (normalization/normalize-funding-history
                (get funding-history-by-coin coin))
          average-rate (when (seq rows)
                         (/ (reduce + (map :funding-rate-raw rows))
                            (count rows)))]
      (if (number? average-rate)
        {:source :legacy-fallback
         :rows rows
         :average-rate average-rate
         :annualized-carry (* average-rate funding-periods-per-year)
         :status :available}
        {:source :missing-market-funding-history
         :rows []
         :average-rate nil
         :annualized-carry 0
         :status :missing}))))

(defn- point-return
  [previous current]
  (let [previous-close (:close previous)
        current-close (:close current)]
    (when (and (codec/finite-number? previous-close)
               (codec/finite-number? current-close)
               (pos? previous-close)
               (pos? current-close))
      (- (/ current-close previous-close) 1))))

(defn- points-with-returns
  [rows]
  (mapv (fn [previous current]
          (assoc current :return (point-return previous current)))
        (cons nil rows)
        rows))

(defn- legacy-price-history
  [instrument candle-history-by-coin vault-details-by-address]
  (if (instruments/vault-instrument? instrument)
    (normalization/normalize-vault-history
     (get vault-details-by-address (instruments/vault-address instrument)))
    (normalization/normalize-candle-history
     (get candle-history-by-coin (instruments/normalize-coin instrument)))))

(defn series
  [instrument
   candle-history-by-coin
   funding-history-by-coin
   vault-details-by-address
   funding-periods-per-year]
  (let [history (legacy-price-history instrument
                                      candle-history-by-coin
                                      vault-details-by-address)
        local-id (instruments/normalize-instrument-id instrument)]
    (when (seq history)
      {:instrument-id (:optimizer-history/instrument-id instrument)
       :local-instrument-id local-id
       :lineage-kind :legacy-fallback
       :series-kind (if (instruments/vault-instrument? instrument)
                      :vault-return-index
                      :market-price)
       :points (points-with-returns history)
       :funding (legacy-funding-summary instrument
                                        funding-history-by-coin
                                        funding-periods-per-year)
       :warnings []})))

(defn suppress-warning?
  [fallback-local-ids warning-local-id warning]
  (and (contains? fallback-local-ids warning-local-id)
       (contains? recoverable-warning-codes (:code warning))))

(defn warning
  [instrument-id]
  {:code :optimizer-history-api-legacy-fallback
   :instrument-id instrument-id
   :message "Legacy history filled an optimizer history API gap."})
