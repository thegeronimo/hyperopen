(ns hyperopen.vaults.domain.ui-state
  (:require [clojure.string :as str]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.utils.parse :as parse-utils]))

(def default-vault-snapshot-range
  :month)

(def default-vault-sort-column
  :tvl)

(def default-vault-sort-direction
  :desc)

(def default-vault-user-page-size
  10)

(def default-vault-user-page
  1)

(def default-vault-detail-tab
  :about)

(def default-vault-detail-activity-tab
  :performance-metrics)

(def default-vault-detail-activity-direction-filter
  :all)

(def default-vault-detail-activity-sort-direction
  :desc)

(def default-vault-detail-chart-series
  :returns)

(def vault-user-page-size-options
  [5 10 25 50])

(def ^:private vault-snapshot-ranges
  #{:day :week :month :three-month :six-month :one-year :two-year :all-time})

(def ^:private vault-sort-columns
  #{:vault :leader :apr :tvl :your-deposit :age :snapshot})

(def ^:private vault-detail-tabs
  #{:about :vault-performance :your-performance})

(def ^:private vault-detail-activity-tabs
  #{:performance-metrics
    :balances
    :positions
    :open-orders
    :twap
    :trade-history
    :funding-history
    :order-history
    :deposits-withdrawals
    :depositors})

(def ^:private vault-detail-activity-direction-filters
  #{:all :long :short})

(def ^:private sort-directions
  #{:asc :desc})

(def ^:private vault-detail-chart-series-options
  #{:account-value :pnl :returns})

(def ^:private vault-user-page-size-option-set
  (set vault-user-page-size-options))

(defn normalize-vault-snapshot-range
  [value]
  (let [normalized (portfolio-actions/normalize-summary-time-range value)]
    (if (contains? vault-snapshot-ranges normalized)
      normalized
      default-vault-snapshot-range)))

(defn normalize-vault-sort-column
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)]
    (if (contains? vault-sort-columns token)
      token
      default-vault-sort-column)))

(defn normalize-vault-detail-tab
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :vaultperformance :vault-performance
                     :yourperformance :your-performance
                     token)]
    (if (contains? vault-detail-tabs normalized)
      normalized
      default-vault-detail-tab)))

(defn normalize-vault-detail-activity-tab
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :performancemetrics :performance-metrics
                     :performancemetric :performance-metrics
                     :openorders :open-orders
                     :tradehistory :trade-history
                     :fundinghistory :funding-history
                     :orderhistory :order-history
                     :depositswithdrawals :deposits-withdrawals
                     token)]
    (if (contains? vault-detail-activity-tabs normalized)
      normalized
      default-vault-detail-activity-tab)))

(defn normalize-vault-detail-activity-direction-filter
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    keyword)
                :else nil)]
    (if (contains? vault-detail-activity-direction-filters token)
      token
      default-vault-detail-activity-direction-filter)))

(defn normalize-sort-direction
  [value]
  (let [direction (cond
                    (keyword? value) value
                    (string? value) (keyword (str/lower-case (str/trim value)))
                    :else nil)]
    (if (contains? sort-directions direction)
      direction
      default-vault-detail-activity-sort-direction)))

(defn normalize-vault-detail-chart-series
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :accountvalue :account-value
                     :return :returns
                     token)]
    (if (contains? vault-detail-chart-series-options normalized)
      normalized
      default-vault-detail-chart-series)))

(defn normalize-vault-user-page-size
  [value]
  (let [candidate (parse-utils/parse-int-value value)]
    (if (contains? vault-user-page-size-option-set candidate)
      candidate
      default-vault-user-page-size)))

(defn normalize-vault-user-page
  ([value]
   (normalize-vault-user-page value nil))
  ([value max-page]
   (let [candidate (max default-vault-user-page
                        (or (parse-utils/parse-int-value value)
                            default-vault-user-page))
         max-page* (when (some? max-page)
                     (max default-vault-user-page
                          (or (parse-utils/parse-int-value max-page)
                              default-vault-user-page)))]
     (if max-page*
       (min candidate max-page*)
       candidate))))
