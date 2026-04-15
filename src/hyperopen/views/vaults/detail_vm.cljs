(ns hyperopen.views.vaults.detail-vm
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.vaults.adapters.webdata :as webdata-adapter]
            [hyperopen.vaults.detail.activity :as activity-model]
            [hyperopen.vaults.detail.benchmarks :as benchmarks-model]
            [hyperopen.vaults.detail.metrics-bridge :as metrics-bridge]
            [hyperopen.vaults.detail.performance :as performance-model]
            [hyperopen.vaults.detail.types :as detail-types]
            [hyperopen.vaults.detail.transfer :as transfer-model]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]
            [hyperopen.vaults.domain.identity :as vault-identity]
            [hyperopen.vaults.infrastructure.routes :as vault-routes]
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

(defn- activity-addresses
  [vault-address relationship]
  (->> (concat [vault-address]
               (vault-identity/relationship-child-addresses relationship))
       (keep vault-identity/normalize-vault-address)
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
          (when (= vault-address (vault-identity/normalize-vault-address (:vault-address row)))
            row))
        (or (get-in state [:vaults :merged-index-rows]) [])))

(defn- viewer-details-by-address
  [state vault-address viewer-address]
  (when-let [viewer-address* (vault-identity/normalize-vault-address viewer-address)]
    (get-in state [:vaults :viewer-details-by-address vault-address viewer-address*])))

(defn- normalize-depositor-row
  [row]
  (when (map? row)
    {:address (vault-identity/normalize-vault-address (:user row))
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

(defn- viewer-follower-row
  [details viewer-address]
  (let [viewer-address* (vault-identity/normalize-vault-address viewer-address)
        follower-state (:follower-state details)]
    (or (when (and viewer-address*
                   (= viewer-address*
                      (vault-identity/normalize-vault-address (:user follower-state))))
          follower-state)
        (when viewer-address*
          (some (fn [row]
                  (when (= viewer-address*
                           (vault-identity/normalize-vault-address (:user row)))
                    row))
                (or (:followers details) []))))))

(defn- resolve-chart-series
  [series-by-key selected-series]
  (let [selected* (vault-ui-state/normalize-vault-detail-chart-series selected-series)
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

(defn- benchmark-history-pending?
  [selected-series activity-tab strategy-return-points selected-benchmark-coins benchmark-points-by-coin]
  (and (or (= selected-series :returns)
           (= activity-tab :performance-metrics))
       (seq strategy-return-points)
       (seq selected-benchmark-coins)
       (boolean
        (some (fn [coin]
                (and (seq coin)
                     (nil? (detail-types/vault-benchmark-address coin))
                     (empty? (get benchmark-points-by-coin coin))))
              selected-benchmark-coins))))

(defn- background-status-model
  [benchmark-history-pending?]
  (let [items (cond-> []
                benchmark-history-pending?
                (conj {:id :benchmark-history
                       :label "Benchmark history"}))]
    {:visible? (boolean (seq items))
     :title "Vault analytics are still syncing"
     :detail "The chart is ready. The remaining analytics will fill in automatically."
     :items items}))

(defonce summary-cache
  (atom nil))

(defonce chart-series-data-cache
  (atom nil))

(defonce benchmark-points-cache
  (atom nil))

(defonce performance-metrics-cache
  (atom nil))

(defn reset-vault-detail-vm-cache!
  []
  (benchmarks-model/reset-vault-detail-benchmarks-cache!)
  (reset! metrics-bridge/last-metrics-request nil)
  (reset! summary-cache nil)
  (reset! chart-series-data-cache nil)
  (reset! benchmark-points-cache nil)
  (reset! performance-metrics-cache nil))

(defn- source-row-time-ms
  [row]
  (cond
    (map? row)
    (or (optional-number (:time-ms row))
        (optional-number (:timestamp row))
        (optional-number (:time row))
        (optional-number (:t row)))

    (and (sequential? row)
         (>= (count row) 2))
    (optional-number (first row))

    :else nil))

(defn- source-row-value
  [row]
  (cond
    (map? row)
    (or (optional-number (:value row))
        (optional-number (:account-value row))
        (optional-number (:accountValue row))
        (optional-number (:pnl row)))

    (and (sequential? row)
         (>= (count row) 2))
    (optional-number (second row))

    :else nil))

(defn- sampled-series-source-version
  [rows]
  (let [rows* (vec (or rows []))
        row-count (count rows*)]
    (if (pos? row-count)
      (let [mid-idx (quot row-count 2)
            first-row (nth rows* 0 nil)
            mid-row (nth rows* mid-idx nil)
            last-row (nth rows* (dec row-count) nil)]
        (hash [row-count
               (source-row-time-ms first-row)
               (source-row-value first-row)
               (source-row-time-ms mid-row)
               (source-row-value mid-row)
               (source-row-time-ms last-row)
               (source-row-value last-row)]))
      0)))

(defn- summary-source-version
  [summary]
  (hash [(sampled-series-source-version (:accountValueHistory summary))
         (sampled-series-source-version (:pnlHistory summary))]))

(defn- selected-benchmark-labels
  [returns-benchmark-selector]
  (let [label-by-coin (or (:label-by-coin returns-benchmark-selector)
                          {})]
    (mapv (fn [coin]
            [coin
             (or (get label-by-coin coin)
                 coin)])
          (vec (or (:selected-coins returns-benchmark-selector)
                   [])))))

(defn- benchmark-source-version-map
  [benchmark-points-by-coin selected-benchmark-coins]
  (into {}
        (map (fn [coin]
               [coin
                (sampled-series-source-version
                 (get benchmark-points-by-coin coin))]))
        selected-benchmark-coins))

(defn- cached-portfolio-summary
  [details-base viewer-details snapshot-range]
  (let [cache @summary-cache]
    (if (and (map? cache)
             (= snapshot-range (:snapshot-range cache))
             (identical? details-base (:details-base cache))
             (identical? viewer-details (:viewer-details cache)))
      (:summary cache)
      (let [summary (performance-model/portfolio-summary (merge (or details-base {})
                                                                (or viewer-details {}))
                                                         snapshot-range)]
        (reset! summary-cache {:snapshot-range snapshot-range
                               :details-base details-base
                               :viewer-details viewer-details
                               :summary summary})
        summary))))

(defn- cached-chart-series-data
  [state summary]
  (let [summary-version (summary-source-version summary)
        cache @chart-series-data-cache]
    (if (and (map? cache)
             (= summary-version (:summary-version cache)))
      (:series-by-key cache)
      (let [series-by-key (performance-model/chart-series-data state summary)]
        (reset! chart-series-data-cache {:summary-version summary-version
                                         :series-by-key series-by-key})
        series-by-key))))

(defn- cached-benchmark-points-by-coin
  [state snapshot-range selected-benchmark-coins strategy-return-points strategy-window]
  (let [strategy-source-version (sampled-series-source-version strategy-return-points)
        strategy-window-version (hash (select-keys (or strategy-window {})
                                                   [:cutoff-ms :window-start-ms :window-end-ms
                                                    :complete-window? :returns-source :point-count]))
        candles (get state :candles)
        merged-index-rows (get-in state [:vaults :merged-index-rows])
        benchmark-details-by-address (get-in state [:vaults :benchmark-details-by-address])
        details-by-address (get-in state [:vaults :details-by-address])
        cache @benchmark-points-cache]
    (if (and (map? cache)
             (= snapshot-range (:snapshot-range cache))
             (= selected-benchmark-coins (:selected-benchmark-coins cache))
             (= strategy-source-version (:strategy-source-version cache))
             (= strategy-window-version (:strategy-window-version cache))
             (identical? candles (:candles cache))
             (identical? merged-index-rows (:merged-index-rows cache))
             (identical? benchmark-details-by-address (:benchmark-details-by-address cache))
             (identical? details-by-address (:details-by-address cache)))
      (:benchmark-points-by-coin cache)
      (let [benchmark-points-by-coin (benchmarks-model/benchmark-cumulative-return-points-by-coin
                                      state
                                      snapshot-range
                                      selected-benchmark-coins
                                      strategy-return-points
                                      strategy-window)]
        (reset! benchmark-points-cache {:snapshot-range snapshot-range
                                        :selected-benchmark-coins selected-benchmark-coins
                                        :strategy-source-version strategy-source-version
                                        :strategy-window-version strategy-window-version
                                        :candles candles
                                        :merged-index-rows merged-index-rows
                                        :benchmark-details-by-address benchmark-details-by-address
                                        :details-by-address details-by-address
                                        :benchmark-points-by-coin benchmark-points-by-coin})
        benchmark-points-by-coin))))

(defn- cached-performance-metrics-model
  [state snapshot-range returns-benchmark-selector benchmark-context]
  (let [selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector)
                                          []))
        benchmark-labels (selected-benchmark-labels returns-benchmark-selector)
        request-signature (metrics-bridge/metrics-request-signature snapshot-range
                                                                    selected-benchmark-coins
                                                                    (:strategy-source-version benchmark-context)
                                                                    (:benchmark-source-version-map benchmark-context))
        metrics-result (get-in state [:vaults-ui :detail-performance-metrics-result])
        loading? (boolean (get-in state [:vaults-ui :detail-performance-metrics-loading?]))
        cache @performance-metrics-cache]
    (if (and (map? cache)
             (= request-signature (:request-signature cache))
             (= benchmark-labels (:benchmark-labels cache))
             (identical? metrics-result (:metrics-result cache))
             (= loading? (:loading? cache)))
      (:model cache)
      (let [model (performance-model/performance-metrics-model state
                                                               snapshot-range
                                                               returns-benchmark-selector
                                                               benchmark-context)]
        (reset! performance-metrics-cache {:request-signature request-signature
                                           :benchmark-labels benchmark-labels
                                           :metrics-result metrics-result
                                           :loading? loading?
                                           :model model})
        model))))

(defn- resolve-webdata-source
  [webdata fallback-paths]
  (some (fn [path]
          (get-in webdata path))
        fallback-paths))

(defn- preferred-history-source
  [state state-path addresses webdata fallback-paths]
  (let [rows (concat-address-rows state state-path addresses)]
    (if (seq rows)
      rows
      (resolve-webdata-source webdata fallback-paths))))

(defn- detail-activity-sources
  [state vault-address relationship webdata]
  (let [history-addresses (activity-addresses vault-address relationship)]
    {:history-addresses history-addresses
     :fills-source (preferred-history-source state
                                             [:vaults :fills-by-vault]
                                             history-addresses
                                             webdata
                                             [[:fills]
                                              [:userFills]
                                              [:data :fills]
                                              [:data :userFills]])
     :funding-source (preferred-history-source state
                                               [:vaults :funding-history-by-vault]
                                               history-addresses
                                               webdata
                                               [[:fundings]
                                                [:userFundings]
                                                [:funding-history]
                                                [:data :fundings]
                                                [:data :userFundings]])
     :order-history-source (preferred-history-source state
                                                     [:vaults :order-history-by-vault]
                                                     history-addresses
                                                     webdata
                                                     [[:order-history]
                                                      [:orderHistory]
                                                      [:historicalOrders]
                                                      [:data :order-history]
                                                      [:data :orderHistory]
                                                      [:data :historicalOrders]])
     :ledger-source (or (get-in state [:vaults :ledger-updates-by-vault vault-address])
                        (resolve-webdata-source webdata
                                                [[:depositsWithdrawals]
                                                 [:nonFundingLedgerUpdates]
                                                 [:data :depositsWithdrawals]
                                                 [:data :nonFundingLedgerUpdates]]))}))

(defn- detail-metrics-context
  [state details row user-equity viewer-follower]
  (let [tvl (or (optional-number (:tvl details))
                (optional-number (:tvl row)))
        apr (or (optional-number (:apr details))
                (optional-number (:apr row)))
        return-for-range (fn [snapshot-range]
                           (or (performance-model/summary-cumulative-return-percent
                                state
                                (performance-model/portfolio-summary-by-range details snapshot-range))
                               (performance-model/snapshot-value-by-range row snapshot-range tvl)))
        month-return (return-for-range :month)
        your-deposit (or (optional-number (:equity user-equity))
                         (optional-number (:vault-equity viewer-follower))
                         (optional-number (get-in details [:follower-state :vault-equity])))
        all-time-earned (or (optional-number (:all-time-pnl viewer-follower))
                            (optional-number (get-in details [:follower-state :all-time-pnl])))]
    {:tvl tvl
     :apr apr
     :return-for-range return-for-range
     :month-return month-return
     :your-deposit your-deposit
     :all-time-earned all-time-earned}))

(defn- resolve-vault-name
  [details row vault-address]
  (or (non-blank-text (:name details))
      (non-blank-text (:name row))
      vault-address
      "Vault"))

(defn- build-benchmark-series
  [selected-series selected-benchmark-coins benchmark-label-by-coin benchmark-points-by-coin]
  (if (= selected-series :returns)
    (mapv (fn [idx coin]
            {:id (keyword (str "benchmark-" idx))
             :coin coin
             :label (or (get benchmark-label-by-coin coin)
                        coin)
             :stroke (chart-model/benchmark-series-stroke idx)
             :raw-points (vec (or (get benchmark-points-by-coin coin) []))})
          (range)
          selected-benchmark-coins)
    []))

(defn- build-benchmark-context
  [strategy-return-points benchmark-points-by-coin selected-benchmark-coins]
  (let [strategy-cumulative-rows (performance-model/cumulative-rows strategy-return-points)
        benchmark-cumulative-rows-by-coin
        (into {}
              (map (fn [coin]
                     [coin (performance-model/cumulative-rows
                            (get benchmark-points-by-coin coin))]))
              selected-benchmark-coins)]
    {:strategy-cumulative-rows strategy-cumulative-rows
     :benchmark-cumulative-rows-by-coin benchmark-cumulative-rows-by-coin
     :strategy-source-version (sampled-series-source-version strategy-cumulative-rows)
     :benchmark-source-version-map (benchmark-source-version-map benchmark-cumulative-rows-by-coin
                                                                selected-benchmark-coins)}))

(defn- build-vault-detail-chart-section
  [state snapshot-range activity-tab chart-series details-base viewer-details metrics-context]
  (let [details (merge (or details-base {})
                       (or viewer-details {}))
        summary (cached-portfolio-summary details-base viewer-details snapshot-range)
        returns-history-context (performance-model/returns-history-context state details snapshot-range)
        returns-benchmark-selector (benchmarks-model/returns-benchmark-selector-model state)
        series-by-key (cached-chart-series-data state summary)
        selected-series (resolve-chart-series series-by-key chart-series)
        strategy-return-points (vec (or (get (performance-model/chart-series-data
                                              state summary (:summary returns-history-context))
                                             :returns)
                                        []))
        strategy-raw-points (if (= selected-series :returns)
                              strategy-return-points
                              (vec (or (get series-by-key selected-series) [])))
        selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector) []))
        benchmark-label-by-coin (or (:label-by-coin returns-benchmark-selector) {})
        benchmark-points-by-coin (cached-benchmark-points-by-coin state snapshot-range
                                                                  selected-benchmark-coins
                                                                  strategy-return-points
                                                                  returns-history-context)
        benchmark-history-loading? (benchmark-history-pending? selected-series activity-tab
                                                               strategy-return-points
                                                               selected-benchmark-coins
                                                               benchmark-points-by-coin)
        benchmark-series (build-benchmark-series selected-series
                                                 selected-benchmark-coins
                                                 benchmark-label-by-coin
                                                 benchmark-points-by-coin)
        raw-series (cond-> [{:id :strategy
                             :label "Vault"
                             :stroke (chart-model/strategy-series-stroke selected-series)
                             :raw-points strategy-raw-points}]
                     (seq benchmark-series)
                     (into benchmark-series))
        chart-model* (chart-model/build-chart-model {:selected-series selected-series
                                                     :raw-series raw-series})
        series (:series chart-model*)
        benchmark-context (build-benchmark-context strategy-return-points
                                                   benchmark-points-by-coin
                                                   selected-benchmark-coins)
        performance-metrics-base (cached-performance-metrics-model state
                                                                   snapshot-range
                                                                   returns-benchmark-selector
                                                                   benchmark-context)
        performance-metrics (assoc performance-metrics-base
                                   :loading? (or benchmark-history-loading?
                                                 (:loading? performance-metrics-base)))
        return-for-range (:return-for-range metrics-context)
        month-return (:month-return metrics-context)]
    {:background-status (background-status-model benchmark-history-loading?)
     :snapshot-range snapshot-range
     :snapshot {:day (return-for-range :day)
                :week (return-for-range :week)
                :month month-return
                :all-time (return-for-range :all-time)}
     :performance-metrics (assoc performance-metrics
                                 :timeframe-options chart-timeframe-options
                                 :selected-timeframe snapshot-range
                                 :timeframe-menu-open? (true? (get-in state [:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?])))
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
             :timeframe-menu-open? (true? (get-in state [:vaults-ui :detail-chart-timeframe-dropdown-open?]))
             :selected-timeframe snapshot-range
             :selected-series selected-series
             :returns-benchmark returns-benchmark-selector
             :strategy-window returns-history-context
             :y-ticks (:y-ticks chart-model*)
             :points (:points chart-model*)
             :series series}}))

(defn- build-vault-detail-activity-section
  [state details webdata vault-address now-ms activity-tab activity-sources]
  (let [{:keys [history-addresses fills-source funding-source order-history-source ledger-source]}
        activity-sources
        activity-direction-filter (activity-model/normalize-direction-filter
                                   (get-in state [:vaults-ui :detail-activity-direction-filter]))
        activity-filter-open? (true? (get-in state [:vaults-ui :detail-activity-filter-open?]))
        activity-tabs* activity-model/tabs
        activity-columns-by-tab (activity-model/columns-by-tab)
        activity-sort-state-by-tab (into {}
                                         (map (fn [{:keys [value]}]
                                                [value (activity-model/sort-state state value)]))
                                         activity-tabs*)
        activity-table-config (into {}
                                    (map (fn [{:keys [value]}]
                                           [value {:columns (get activity-columns-by-tab value [])
                                                   :supports-direction-filter? (activity-model/supports-direction-filter? value)}]))
                                    activity-tabs*)
        positions-raw (webdata-adapter/positions webdata)
        open-orders-raw (webdata-adapter/open-orders webdata)
        balances-raw (webdata-adapter/balances webdata)
        twaps-raw (webdata-adapter/twaps webdata now-ms)
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
        activity-errors {:trade-history (first-address-error state :fills-by-vault history-addresses)
                         :funding-history (first-address-error state :funding-history-by-vault history-addresses)
                         :order-history (first-address-error state :order-history-by-vault history-addresses)
                         :deposits-withdrawals (get-in state [:vaults :errors :ledger-updates-by-vault vault-address])}
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
    {:activity-tabs (mapv (fn [{:keys [value label]}]
                            {:value value
                             :label label
                             :count (get activity-count-by-tab value 0)})
                          activity-tabs*)
     :selected-activity-tab activity-tab
     :activity-direction-filter activity-direction-filter
     :activity-filter-open? activity-filter-open?
     :activity-filter-options activity-model/activity-filter-options
     :activity-table-config activity-table-config
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
                        :position-count (count positions)}}))

(defn vault-detail-vm
  ([state]
   (vault-detail-vm state {:now-ms (.now js/Date)}))
  ([state {:keys [now-ms]}]
   (let [now-ms* (or (optional-number now-ms)
                     (.now js/Date))
         route (get-in state [:router :path])
         {:keys [kind vault-address]} (vault-routes/parse-vault-route route)
         viewer-address (account-context/effective-account-address state)
         detail-tab (vault-ui-state/normalize-vault-detail-tab
                     (get-in state [:vaults-ui :detail-tab]))
         activity-tab (vault-ui-state/normalize-vault-detail-activity-tab
                       (get-in state [:vaults-ui :detail-activity-tab]))
         chart-series (vault-ui-state/normalize-vault-detail-chart-series
                       (get-in state [:vaults-ui :detail-chart-series]))
         snapshot-range (vault-ui-state/normalize-vault-snapshot-range
                         (get-in state [:vaults-ui :snapshot-range]))
         detail-loading? (true? (get-in state [:vaults-ui :detail-loading?]))
         details-base (get-in state [:vaults :details-by-address vault-address])
         viewer-details (viewer-details-by-address state vault-address viewer-address)
         details (merge (or details-base {})
                        (or viewer-details {}))
         row (row-by-address state vault-address)
         webdata (get-in state [:vaults :webdata-by-vault vault-address])
         user-equity (get-in state [:vaults :user-equity-by-address vault-address])
         viewer-follower (viewer-follower-row details viewer-address)
         relationship (or (:relationship details)
                          (:relationship row)
                          {:type :normal})
         activity-sources (detail-activity-sources state vault-address relationship webdata)
         metrics-context (detail-metrics-context state details row user-equity viewer-follower)
         vault-name (resolve-vault-name details row vault-address)
         vault-transfer (transfer-model/read-model state {:vault-address vault-address
                                                          :vault-name vault-name
                                                          :details details
                                                          :webdata webdata})
         wallet-address (vault-identity/normalize-vault-address (get-in state [:wallet :address]))
         agent-ready? (= :ready (get-in state [:wallet :agent :status]))
         chart-section (build-vault-detail-chart-section state
                                                         snapshot-range
                                                         activity-tab
                                                         chart-series
                                                         details-base
                                                         viewer-details
                                                         metrics-context)
         activity-section (build-vault-detail-activity-section state
                                                               details
                                                               webdata
                                                               vault-address
                                                               now-ms*
                                                               activity-tab
                                                               activity-sources)
         {:keys [tvl apr month-return your-deposit all-time-earned]} metrics-context]
     (merge
      {:kind kind
       :vault-address vault-address
       :invalid-address? (and (= :detail kind)
                              (nil? vault-address))
       :loading? detail-loading?
       :error (or (get-in state [:vaults :errors :details-by-address vault-address])
                  (get-in state [:vaults :errors :webdata-by-vault vault-address]))
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
       :selected-tab detail-tab}
      chart-section
      activity-section))))
