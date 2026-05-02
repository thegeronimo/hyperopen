(ns hyperopen.views.vaults.detail-vm.activity-section
  (:require [hyperopen.vaults.adapters.webdata :as webdata-adapter]
            [hyperopen.vaults.detail.activity :as activity-model]
            [hyperopen.vaults.domain.identity :as vault-identity]
            [hyperopen.views.vaults.detail-vm.context :as context]))

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
            (when (seq (context/non-blank-text err))
              err)))
        addresses))

(defn- normalize-depositor-row
  [row]
  (when (map? row)
    {:address (vault-identity/normalize-vault-address (:user row))
     :vault-amount (context/optional-number (:vault-equity row))
     :unrealized-pnl (context/optional-number (:pnl row))
     :all-time-pnl (context/optional-number (:all-time-pnl row))
     :days-following (context/optional-int (:days-following row))}))

(defn- activity-depositors
  [details]
  (let [followers (or (:followers details) [])]
    (->> (if (sequential? followers) followers [])
         (keep normalize-depositor-row)
         (sort-by (fn [{:keys [vault-amount]}]
                    (js/Math.abs (or vault-amount 0)))
                  >)
         vec)))

(defn followers-count
  [details]
  (or (context/optional-int (:followers-count details))
      (when (sequential? (:followers details))
        (count (:followers details)))
      0))

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

(defn detail-activity-sources
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

(defn build-vault-detail-activity-section
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
