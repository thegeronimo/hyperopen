(ns hyperopen.views.vaults.detail-vm
  (:require [clojure.string :as str]
            [hyperopen.vaults.adapters.webdata :as webdata-adapter]
            [hyperopen.vaults.actions :as vault-actions]
            [hyperopen.vaults.detail.activity :as activity-model]
            [hyperopen.vaults.detail.benchmarks :as benchmarks-model]
            [hyperopen.vaults.detail.performance :as performance-model]
            [hyperopen.vaults.detail.transfer :as transfer-model]
            [hyperopen.views.vaults.detail.chart :as chart-model]))

(def ^:private chart-timeframe-options
  [{:value :day
    :label "24H"}
   {:value :week
    :label "7D"}
   {:value :month
    :label "30D"}
   {:value :three-month
    :label "3M"}
   {:value :six-month
    :label "6M"}
   {:value :one-year
    :label "1Y"}
   {:value :two-year
    :label "2Y"}
   {:value :all-time
    :label "All-time"}])

(defn- optional-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [trimmed (str/trim value)]
      (when (seq trimmed)
        (let [parsed (js/Number trimmed)]
          (when (js/isFinite parsed)
            parsed))))

    :else nil))

(defn- optional-int
  [value]
  (when-let [n (optional-number value)]
    (js/Math.floor n)))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- normalize-address
  [value]
  (some-> value non-blank-text str/lower-case))

(defn- relationship-child-addresses
  [relationship]
  (->> (or (:child-addresses relationship) [])
       (keep normalize-address)
       distinct
       vec))

(defn- activity-addresses
  [vault-address relationship]
  (->> (concat [vault-address]
               (relationship-child-addresses relationship))
       (keep normalize-address)
       distinct
       vec))

(defn- concat-address-rows
  [state path addresses]
  (->> addresses
       (mapcat (fn [address]
                 (let [rows (get-in state (conj path address))]
                   (if (sequential? rows)
                     rows
                     []))))
       vec))

(defn- address-loading?
  [state loading-key addresses]
  (boolean
   (some true?
         (map #(get-in state [:vaults :loading loading-key %])
              addresses))))

(defn- first-address-error
  [state error-key addresses]
  (some (fn [address]
          (let [err (get-in state [:vaults :errors error-key address])]
            (when (seq (non-blank-text err))
              err)))
        addresses))

(defn- normalize-percent-value
  [value]
  (when-let [n (optional-number value)]
    (if (<= (js/Math.abs n) 1)
      (* 100 n)
      n)))

(defn- row-by-address
  [state vault-address]
  (some (fn [row]
          (when (= vault-address (normalize-address (:vault-address row)))
            row))
        (or (get-in state [:vaults :merged-index-rows]) [])))

(defn- normalize-depositor-row
  [row]
  (when (map? row)
    {:address (normalize-address (:user row))
     :vault-amount (optional-number (:vault-equity row))
     :unrealized-pnl (optional-number (:pnl row))
     :all-time-pnl (optional-number (:all-time-pnl row))
     :days-following (optional-int (:days-following row))}))

(defn- activity-depositors
  [details]
  (let [followers (or (:followers details) [])]
    (->> (if (sequential? followers) followers [])
         (keep normalize-depositor-row)
         (sort-by (fn [{:keys [vault-amount]}]
                    (js/Math.abs (or vault-amount 0)))
                  >)
         vec)))

(defn- resolve-chart-series
  [series-by-key selected-series]
  (let [selected* (vault-actions/normalize-vault-detail-chart-series selected-series)
        has-series? (fn [k]
                      (seq (get series-by-key k)))]
    (cond
      (= :returns selected*) :returns
      (has-series? selected*) selected*
      (has-series? :pnl) :pnl
      (has-series? :account-value) :account-value
      :else selected*)))

(defn- followers-count
  [details]
  (or (optional-int (:followers-count details))
      (when (sequential? (:followers details))
        (count (:followers details)))
      0))

(defn vault-detail-vm
  ([state]
   (vault-detail-vm state {:now-ms (.now js/Date)}))
  ([state {:keys [now-ms]}]
   (let [now-ms* (or (optional-number now-ms)
                     (.now js/Date))
         route (get-in state [:router :path])
        {:keys [kind vault-address]} (vault-actions/parse-vault-route route)
        detail-tab (vault-actions/normalize-vault-detail-tab
                    (get-in state [:vaults-ui :detail-tab]))
        activity-tab (vault-actions/normalize-vault-detail-activity-tab
                      (get-in state [:vaults-ui :detail-activity-tab]))
        chart-series (vault-actions/normalize-vault-detail-chart-series
                      (get-in state [:vaults-ui :detail-chart-series]))
        snapshot-range (vault-actions/normalize-vault-snapshot-range
                        (get-in state [:vaults-ui :snapshot-range]))
        detail-loading? (true? (get-in state [:vaults-ui :detail-loading?]))
        details (get-in state [:vaults :details-by-address vault-address])
        row (row-by-address state vault-address)
        webdata (get-in state [:vaults :webdata-by-vault vault-address])
        user-equity (get-in state [:vaults :user-equity-by-address vault-address])
        relationship (or (:relationship details)
                         (:relationship row)
                         {:type :normal})
        history-addresses (activity-addresses vault-address relationship)
        fills-source (let [rows (concat-address-rows state [:vaults :fills-by-vault] history-addresses)]
                       (if (seq rows)
                         rows
                         (or (:fills webdata)
                             (:userFills webdata)
                             (get-in webdata [:data :fills])
                             (get-in webdata [:data :userFills]))))
        funding-source (let [rows (concat-address-rows state [:vaults :funding-history-by-vault] history-addresses)]
                         (if (seq rows)
                           rows
                           (or (:fundings webdata)
                               (:userFundings webdata)
                               (:funding-history webdata)
                               (get-in webdata [:data :fundings])
                               (get-in webdata [:data :userFundings]))))
        order-history-source (let [rows (concat-address-rows state [:vaults :order-history-by-vault] history-addresses)]
                               (if (seq rows)
                                 rows
                                 (or (:order-history webdata)
                                     (:orderHistory webdata)
                                     (:historicalOrders webdata)
                                     (get-in webdata [:data :order-history])
                                     (get-in webdata [:data :orderHistory])
                                     (get-in webdata [:data :historicalOrders]))))
        ledger-source (or (get-in state [:vaults :ledger-updates-by-vault vault-address])
                          (:depositsWithdrawals webdata)
                          (:nonFundingLedgerUpdates webdata)
                          (get-in webdata [:data :depositsWithdrawals])
                          (get-in webdata [:data :nonFundingLedgerUpdates]))
        tvl (or (optional-number (:tvl details))
                (optional-number (:tvl row))
                0)
        apr (or (optional-number (:apr details))
                (optional-number (:apr row)))
        month-return (performance-model/snapshot-value-by-range row :month tvl)
        your-deposit (or (optional-number (:equity user-equity))
                         (optional-number (get-in details [:follower-state :vault-equity])))
        all-time-earned (optional-number (get-in details [:follower-state :all-time-pnl]))
        vault-name (or (non-blank-text (:name details))
                       (non-blank-text (:name row))
                       vault-address
                       "Vault")
        vault-transfer (transfer-model/read-model state {:vault-address vault-address
                                                         :vault-name vault-name
                                                         :details details
                                                         :webdata webdata})
        wallet-address (vault-actions/normalize-vault-address (get-in state [:wallet :address]))
        agent-ready? (= :ready (get-in state [:wallet :agent :status]))
        summary (performance-model/portfolio-summary details snapshot-range)
        returns-benchmark-selector (benchmarks-model/returns-benchmark-selector-model state)
        series-by-key (performance-model/chart-series-data state summary)
        selected-series (resolve-chart-series series-by-key chart-series)
        strategy-raw-points (vec (or (get series-by-key selected-series) []))
        strategy-return-points (vec (or (get series-by-key :returns) []))
        selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector) []))
        benchmark-label-by-coin (or (:label-by-coin returns-benchmark-selector) {})
        benchmark-points-by-coin (benchmarks-model/benchmark-cumulative-return-points-by-coin state
                                                                                               snapshot-range
                                                                                               selected-benchmark-coins
                                                                                               strategy-return-points)
        benchmark-series (if (= selected-series :returns)
                           (mapv (fn [idx coin]
                                   {:id (keyword (str "benchmark-" idx))
                                    :coin coin
                                    :label (or (get benchmark-label-by-coin coin)
                                               coin)
                                    :stroke (chart-model/benchmark-series-stroke idx)
                                    :raw-points (vec (or (get benchmark-points-by-coin coin) []))})
                                 (range)
                                 selected-benchmark-coins)
                           [])
        raw-series (cond-> [{:id :strategy
                             :label "Vault"
                             :stroke (chart-model/strategy-series-stroke selected-series)
                             :raw-points strategy-raw-points}]
                     (seq benchmark-series)
                     (into benchmark-series))
        chart-model* (chart-model/build-chart-model {:selected-series selected-series
                                                     :raw-series raw-series
                                                     :hover-index (get-in state [:vaults-ui :detail-chart-hover-index])})
        series (:series chart-model*)
        strategy-series (:strategy-series chart-model*)
        chart-points (:points chart-model*)
        strategy-cumulative-rows (performance-model/cumulative-rows strategy-return-points)
        benchmark-cumulative-rows-by-coin (into {}
                                                (map (fn [coin]
                                                       [coin (performance-model/cumulative-rows (get benchmark-points-by-coin coin))]))
                                                selected-benchmark-coins)
        performance-metrics (performance-model/performance-metrics-model returns-benchmark-selector
                                                                          strategy-cumulative-rows
                                                                          benchmark-cumulative-rows-by-coin)
        detail-error (get-in state [:vaults :errors :details-by-address vault-address])
        webdata-error (get-in state [:vaults :errors :webdata-by-vault vault-address])
        fills-error (first-address-error state :fills-by-vault history-addresses)
        funding-error (first-address-error state :funding-history-by-vault history-addresses)
        order-history-error (first-address-error state :order-history-by-vault history-addresses)
        ledger-error (get-in state [:vaults :errors :ledger-updates-by-vault vault-address])
        activity-direction-filter (activity-model/normalize-direction-filter
                                   (get-in state [:vaults-ui :detail-activity-direction-filter]))
        activity-filter-open? (true? (get-in state [:vaults-ui :detail-activity-filter-open?]))
        activity-tabs* activity-model/tabs
        activity-columns-by-tab (activity-model/columns-by-tab)
        activity-sort-state-by-tab (into {}
                                         (map (fn [{:keys [value]}]
                                                [value (activity-model/sort-state state value)]))
                                         activity-tabs*)
        positions-raw (webdata-adapter/positions webdata)
        open-orders-raw (webdata-adapter/open-orders webdata)
        balances-raw (webdata-adapter/balances webdata)
        twaps-raw (webdata-adapter/twaps webdata now-ms*)
        fills-raw (webdata-adapter/fills fills-source)
        funding-history-raw (webdata-adapter/funding-history funding-source)
        order-history-raw (webdata-adapter/order-history order-history-source)
        deposits-withdrawals-raw (webdata-adapter/ledger-updates ledger-source vault-address)
        depositors-raw (activity-depositors details)
        project-rows (fn [tab rows]
                       (activity-model/project-rows rows
                                                    tab
                                                    activity-direction-filter
                                                    (get activity-sort-state-by-tab tab)))
        balances (project-rows :balances balances-raw)
        positions (project-rows :positions positions-raw)
        open-orders (project-rows :open-orders open-orders-raw)
        twaps (project-rows :twap twaps-raw)
        fills (project-rows :trade-history fills-raw)
        funding-history (project-rows :funding-history funding-history-raw)
        order-history (project-rows :order-history order-history-raw)
        deposits-withdrawals (project-rows :deposits-withdrawals deposits-withdrawals-raw)
        depositors (project-rows :depositors depositors-raw)
        activity-loading {:trade-history (address-loading? state :fills-by-vault history-addresses)
                          :funding-history (address-loading? state :funding-history-by-vault history-addresses)
                          :order-history (address-loading? state :order-history-by-vault history-addresses)
                          :deposits-withdrawals (true? (get-in state [:vaults :loading :ledger-updates-by-vault vault-address]))}
        activity-errors {:trade-history fills-error
                         :funding-history funding-error
                         :order-history order-history-error
                         :deposits-withdrawals ledger-error}
        activity-count-by-tab {:balances (count balances-raw)
                               :positions (count positions-raw)
                               :open-orders (count open-orders-raw)
                               :twap (count twaps-raw)
                               :trade-history (count fills-raw)
                               :funding-history (count funding-history-raw)
                               :order-history (count order-history-raw)
                               :deposits-withdrawals (count deposits-withdrawals-raw)
                               :depositors (max (count depositors-raw)
                                                (followers-count details))}]
    {:kind kind
     :vault-address vault-address
     :invalid-address? (and (= :detail kind)
                            (nil? vault-address))
     :loading? detail-loading?
     :error (or detail-error webdata-error)
     :name vault-name
     :leader (or (:leader details)
                 (:leader row))
     :description (or (:description details) "")
     :relationship relationship
     :allow-deposits? (true? (:allow-deposits? details))
     :always-close-on-withdraw? (true? (:always-close-on-withdraw? details))
     :wallet-connected? (string? wallet-address)
     :agent-ready? agent-ready?
     :followers (followers-count details)
     :leader-commission (normalize-percent-value (:leader-commission details))
     :leader-fraction (normalize-percent-value (:leader-fraction details))
     :metrics {:tvl tvl
               :past-month-return month-return
               :your-deposit your-deposit
               :all-time-earned all-time-earned
               :apr (normalize-percent-value apr)}
     :vault-transfer vault-transfer
     :tabs [{:value :about
             :label "About"}
            {:value :vault-performance
             :label "Vault Performance"}
            {:value :your-performance
             :label "Your Performance"}]
     :selected-tab detail-tab
     :snapshot-range snapshot-range
     :snapshot {:day (performance-model/snapshot-value-by-range row :day tvl)
                :week (performance-model/snapshot-value-by-range row :week tvl)
                :month (performance-model/snapshot-value-by-range row :month tvl)
                :all-time (performance-model/snapshot-value-by-range row :all-time tvl)}
     :performance-metrics (assoc performance-metrics
                                 :timeframe-options chart-timeframe-options
                                 :selected-timeframe snapshot-range)
     :chart {:axis-kind (case selected-series
                          :pnl :pnl
                          :returns :returns
                          :account-value :account-value
                          :account-value)
             :series-tabs [{:value :returns
                            :label "Returns"}
                           {:value :account-value
                            :label "Account Value"}
                           {:value :pnl
                            :label "PNL"}]
             :timeframe-options chart-timeframe-options
             :selected-timeframe snapshot-range
             :selected-series selected-series
             :returns-benchmark returns-benchmark-selector
             :hover (:hover chart-model*)
             :y-ticks (:y-ticks chart-model*)
             :points chart-points
             :path (:path strategy-series)
             :series series}
     :activity-tabs (mapv (fn [{:keys [value label]}]
                            {:value value
                             :label label
                             :count (get activity-count-by-tab value 0)})
                          activity-tabs*)
     :selected-activity-tab activity-tab
     :activity-direction-filter activity-direction-filter
     :activity-filter-open? activity-filter-open?
     :activity-filter-options activity-model/activity-filter-options
     :activity-columns-by-tab activity-columns-by-tab
     :activity-sort-state-by-tab activity-sort-state-by-tab
     :selected-activity-sort-state (get activity-sort-state-by-tab activity-tab)
     :activity-balances balances
     :activity-positions positions
     :activity-open-orders open-orders
     :activity-twaps twaps
     :activity-fills fills
     :activity-funding-history funding-history
     :activity-order-history order-history
     :activity-deposits-withdrawals deposits-withdrawals
     :activity-depositors depositors
     :activity-loading activity-loading
     :activity-errors activity-errors
     :activity-summary {:fill-count (count fills)
                        :open-order-count (count open-orders)
                        :position-count (count positions)}})))
