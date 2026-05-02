(ns hyperopen.views.vaults.detail-vm
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]
            [hyperopen.vaults.detail.transfer :as transfer-model]
            [hyperopen.vaults.domain.identity :as vault-identity]
            [hyperopen.vaults.infrastructure.routes :as vault-routes]
            [hyperopen.views.vaults.detail-vm.activity-section :as activity-section]
            [hyperopen.views.vaults.detail-vm.cache :as detail-cache]
            [hyperopen.views.vaults.detail-vm.chart-section :as chart-section]
            [hyperopen.views.vaults.detail-vm.context :as detail-context]))

(def ^:private summary-cache
  detail-cache/summary-cache)

(def ^:private chart-series-data-cache
  detail-cache/chart-series-data-cache)

(def ^:private benchmark-points-cache
  detail-cache/benchmark-points-cache)

(def ^:private performance-metrics-cache
  detail-cache/performance-metrics-cache)

(defn reset-vault-detail-vm-cache!
  []
  (detail-cache/reset-cache!))

(defn vault-detail-vm
  ([state]
   (vault-detail-vm state {:now-ms (.now js/Date)}))
  ([state {:keys [now-ms]}]
   (let [now-ms* (or (detail-context/optional-number now-ms)
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
         viewer-details (detail-context/viewer-details-by-address state vault-address viewer-address)
         details (merge (or details-base {})
                        (or viewer-details {}))
         row (detail-context/row-by-address state vault-address)
         webdata (get-in state [:vaults :webdata-by-vault vault-address])
         user-equity (get-in state [:vaults :user-equity-by-address vault-address])
         viewer-follower (detail-context/viewer-follower-row details viewer-address)
         relationship (or (:relationship details)
                          (:relationship row)
                          {:type :normal})
         activity-sources (activity-section/detail-activity-sources state vault-address relationship webdata)
         metrics-context (detail-context/detail-metrics-context state details row user-equity viewer-follower)
         vault-name (detail-context/resolve-vault-name details row vault-address)
         vault-comparison-label (or (detail-context/non-blank-text (:name details))
                                    (detail-context/non-blank-text (:name row))
                                    (when-not detail-loading? vault-address)
                                    "Selected Vault")
         vault-transfer (transfer-model/read-model state {:vault-address vault-address
                                                          :vault-name vault-name
                                                          :details details
                                                          :webdata webdata})
         wallet-address (vault-identity/normalize-vault-address (get-in state [:wallet :address]))
         agent-ready? (= :ready (get-in state [:wallet :agent :status]))
         chart-section (chart-section/build-vault-detail-chart-section state
                                                                       snapshot-range
                                                                       activity-tab
                                                                       chart-series
                                                                       details-base
                                                                       viewer-details
                                                                       metrics-context
                                                                       vault-comparison-label)
         activity-section (activity-section/build-vault-detail-activity-section state
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
       :followers (activity-section/followers-count details)
       :leader-commission (detail-context/normalize-percent-value (:leader-commission details))
       :leader-fraction (detail-context/normalize-percent-value (:leader-fraction details))
       :metrics {:tvl tvl
                 :past-month-return month-return
                 :your-deposit your-deposit
                 :all-time-earned all-time-earned
                 :apr (detail-context/normalize-percent-value apr)}
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
