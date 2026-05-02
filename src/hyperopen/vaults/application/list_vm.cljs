(ns hyperopen.vaults.application.list-vm
  (:require [hyperopen.vaults.application.list-vm.cache :as cache]
            [hyperopen.vaults.application.list-vm.preview :as preview]
            [hyperopen.vaults.application.list-vm.rows :as rows]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]))

(defn- snapshot-range-keys
  [snapshot-range]
  (rows/snapshot-range-keys snapshot-range))

(declare vault-list-vm)

(defn reset-vault-list-vm-cache! []
  (cache/reset-cache!))

(defn build-startup-preview-record
  ([state]
   (build-startup-preview-record state {}))
  ([state {:keys [now-ms
                  protocol-row-limit
                  user-row-limit]
           :or {protocol-row-limit preview/default-startup-preview-protocol-row-limit
                user-row-limit preview/default-startup-preview-user-row-limit}}]
   (let [snapshot-range (vault-ui-state/normalize-vault-snapshot-range
                         (get-in state [:vaults-ui :snapshot-range]))
         startup-state (-> state
                           (assoc-in [:vaults-ui :search-query] "")
                           (assoc-in [:vaults-ui :filter-leading?] true)
                           (assoc-in [:vaults-ui :filter-deposited?] true)
                           (assoc-in [:vaults-ui :filter-others?] true)
                           (assoc-in [:vaults-ui :filter-closed?] false)
                           (assoc-in [:vaults-ui :sort] {:column vault-ui-state/default-vault-sort-column
                                                         :direction vault-ui-state/default-vault-sort-direction})
                           (assoc-in [:vaults-ui :user-vaults-page-size] vault-ui-state/default-vault-user-page-size)
                           (assoc-in [:vaults-ui :user-vaults-page] vault-ui-state/default-vault-user-page)
                           (assoc-in [:vaults-ui :user-vaults-page-size-dropdown-open?] false)
                           (assoc-in [:vaults :startup-preview] nil))
         live-rows (preview/live-rows-source startup-state)]
     (when (seq live-rows)
       (let [now-ms* (if (number? now-ms) now-ms (.now js/Date))
             model (vault-list-vm startup-state {:now-ms now-ms*})
             protocol-rows (->> (:protocol-rows model)
                                (take protocol-row-limit)
                                vec)
             user-rows (->> (:visible-user-rows model)
                            (take user-row-limit)
                            vec)]
         (when (or (seq protocol-rows)
                   (seq user-rows))
           {:saved-at-ms now-ms*
            :snapshot-range snapshot-range
            :wallet-address (rows/normalize-address (get-in startup-state [:wallet :address]))
            :total-visible-tvl (:total-visible-tvl model)
            :protocol-rows protocol-rows
            :user-rows user-rows
            :stale? false}))))))

(defn vault-list-vm
  ([state]
   (vault-list-vm state {:now-ms (.now js/Date)}))
  ([state {:keys [now-ms]}]
   (let [wallet-address (rows/normalize-address (get-in state [:wallet :address]))
         query (get-in state [:vaults-ui :search-query] "")
         snapshot-range (vault-ui-state/normalize-vault-snapshot-range
                         (get-in state [:vaults-ui :snapshot-range]))
         user-page-size (vault-ui-state/normalize-vault-user-page-size
                         (get-in state [:vaults-ui :user-vaults-page-size]))
         requested-user-page (vault-ui-state/normalize-vault-user-page
                              (get-in state [:vaults-ui :user-vaults-page]))
         filters {:leading? (true? (get-in state [:vaults-ui :filter-leading?] true))
                  :deposited? (true? (get-in state [:vaults-ui :filter-deposited?] true))
                  :others? (true? (get-in state [:vaults-ui :filter-others?] true))
                  :show-closed? (true? (get-in state [:vaults-ui :filter-closed?] false))}
         sort-state (or (get-in state [:vaults-ui :sort])
                        {:column vault-ui-state/default-vault-sort-column
                         :direction vault-ui-state/default-vault-sort-direction})
         equity-by-address (or (get-in state [:vaults :user-equity-by-address]) {})
         live-rows (preview/live-rows-source state)
         now-ms* (if (number? now-ms) now-ms (.now js/Date))
         preview-record (preview/startup-preview-record state snapshot-range)
         parsed-rows (cache/cached-parsed-rows live-rows
                                               wallet-address
                                               equity-by-address
                                               snapshot-range
                                               now-ms*)
         preview-state* (preview/preview-state live-rows preview-record)
         list-loading? (or (true? (get-in state [:vaults :loading :index?]))
                           (true? (get-in state [:vaults :loading :summaries?])))
         baseline-visible? (:baseline-visible? preview-state*)
         loading? (and list-loading?
                       (not baseline-visible?))
         refreshing? (and list-loading?
                          baseline-visible?)
         list-error (or (get-in state [:vaults :errors :index])
                        (get-in state [:vaults :errors :summaries]))
         page-size-dropdown-open? (true? (get-in state [:vaults-ui :user-vaults-page-size-dropdown-open?]))
         model-context {:query query
                        :snapshot-range snapshot-range
                        :filters filters
                        :sort-state sort-state
                        :user-page-size user-page-size
                        :requested-user-page requested-user-page
                        :page-size-dropdown-open? page-size-dropdown-open?
                        :preview-state preview-state*
                        :loading? loading?
                        :refreshing? refreshing?
                        :error list-error}]
     (if (:previewing? preview-state*)
       (preview/preview-vault-list-model preview-record model-context)
       (cache/cached-vault-list-model parsed-rows model-context)))))
