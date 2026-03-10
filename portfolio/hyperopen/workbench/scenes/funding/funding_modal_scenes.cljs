(ns hyperopen.workbench.scenes.funding.funding-modal-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.workbench.support.fixtures :as fixtures]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.funding-modal :as funding-modal]))

(portfolio/configure-scenes
  {:title "Funding Modal"
   :collection :funding})

(defn- funding-store
  [scene-id modal-overrides]
  (ws/create-store scene-id (fixtures/funding-modal-state modal-overrides)))

(defn- delegate
  [transition]
  (fn [state _dispatch-data & args]
    (apply transition state args)))

(defn- funding-reducers
  []
  {:actions/close-funding-modal (delegate funding-actions/close-funding-modal)
   :actions/handle-funding-modal-keydown (delegate funding-actions/handle-funding-modal-keydown)
   :actions/set-funding-modal-field (delegate funding-actions/set-funding-modal-field)
   :actions/search-funding-deposit-assets (delegate funding-actions/search-funding-deposit-assets)
   :actions/search-funding-withdraw-assets (delegate funding-actions/search-funding-withdraw-assets)
   :actions/select-funding-deposit-asset (delegate funding-actions/select-funding-deposit-asset)
   :actions/return-to-funding-deposit-asset-select (delegate funding-actions/return-to-funding-deposit-asset-select)
   :actions/return-to-funding-withdraw-asset-select (delegate funding-actions/return-to-funding-withdraw-asset-select)
   :actions/enter-funding-deposit-amount (delegate funding-actions/enter-funding-deposit-amount)
   :actions/set-funding-deposit-amount-to-minimum (delegate funding-actions/set-funding-deposit-amount-to-minimum)
   :actions/enter-funding-transfer-amount (delegate funding-actions/enter-funding-transfer-amount)
   :actions/select-funding-withdraw-asset (delegate funding-actions/select-funding-withdraw-asset)
   :actions/enter-funding-withdraw-destination (delegate funding-actions/enter-funding-withdraw-destination)
   :actions/enter-funding-withdraw-amount (delegate funding-actions/enter-funding-withdraw-amount)
   :actions/set-funding-transfer-direction (delegate funding-actions/set-funding-transfer-direction)
   :actions/set-funding-amount-to-max (delegate funding-actions/set-funding-amount-to-max)
   :actions/submit-funding-send (delegate funding-actions/submit-funding-send)
   :actions/submit-funding-transfer (delegate funding-actions/submit-funding-transfer)
   :actions/submit-funding-withdraw (delegate funding-actions/submit-funding-withdraw)
   :actions/submit-funding-deposit (delegate funding-actions/submit-funding-deposit)})

(defonce deposit-select-store
  (funding-store ::deposit-select
                 {:mode :deposit
                  :deposit-step :asset-select
                  :deposit-search-input ""
                  :deposit-selected-asset-key nil
                  :anchor {:left 980
                           :right 1130
                           :top 620
                           :bottom 660
                           :viewport-width 1440
                           :viewport-height 900}}))

(defonce deposit-amount-store
  (funding-store ::deposit-amount
                 {:mode :deposit
                  :deposit-step :amount-entry
                  :deposit-selected-asset-key :btc
                  :amount-input "0.15"
                  :anchor {:left 980
                           :right 1130
                           :top 620
                           :bottom 660
                           :viewport-width 1440
                           :viewport-height 900}}))

(defonce send-store
  (funding-store ::send
                 {:mode :send
                  :send-step :amount-entry
                  :send-selected-asset-key :usdc
                  :amount-input "250"
                  :destination-input "0x4892f4d7ee4e3a08993ef891cc1d2b6fc0f5d75d"
                  :anchor {:left 980
                           :right 1130
                           :top 620
                           :bottom 660
                           :viewport-width 1440
                           :viewport-height 900}}))

(defonce withdraw-select-store
  (funding-store ::withdraw-select
                 {:mode :withdraw
                  :withdraw-step :asset-select
                  :withdraw-search-input ""
                  :withdraw-selected-asset-key nil
                  :anchor {:left 980
                           :right 1130
                           :top 620
                           :bottom 660
                           :viewport-width 1440
                           :viewport-height 900}}))

(defonce withdraw-success-store
  (funding-store ::withdraw-success
                 {:mode :withdraw
                  :withdraw-step :amount-entry
                  :withdraw-selected-asset-key :btc
                  :amount-input "0.25"
                  :destination-input "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                  :withdraw-generated-address "bridge-protocol-address"
                  :hyperunit-lifecycle {:direction :withdraw
                                        :asset-key :btc
                                        :operation-id "op_btc_10"
                                        :state :broadcasted
                                        :status :terminal
                                        :source-tx-confirmations 2
                                        :destination-tx-confirmations 1
                                        :position-in-withdraw-queue 1
                                        :destination-tx-hash "0000abc123"
                                        :destination-explorer-url "https://mempool.space/tx/0000abc123"
                                        :state-next-at nil
                                        :last-updated-ms 1700000000001
                                        :error nil}
                  :anchor {:left 980
                           :right 1130
                           :top 620
                           :bottom 660
                           :viewport-width 1440
                           :viewport-height 900}}))

(defonce withdraw-queue-error-store
  (funding-store ::withdraw-queue-error
                 {:mode :withdraw
                  :withdraw-step :amount-entry
                  :withdraw-selected-asset-key :btc
                  :amount-input "0.25"
                  :destination-input "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                  :withdraw-generated-address "bridge-protocol-address"
                  :hyperunit-fee-estimate {:status :error
                                           :by-chain {}
                                           :requested-at-ms 1
                                           :updated-at-ms 2
                                           :error "Estimator offline"}
                  :hyperunit-withdrawal-queue {:status :error
                                               :by-chain {"bitcoin" {:chain "bitcoin"
                                                                     :last-withdraw-queue-operation-tx-id "0xqueue123"}}
                                               :requested-at-ms 3
                                               :updated-at-ms 4
                                               :error "Queue service offline"}
                  :hyperunit-lifecycle {:direction :withdraw
                                        :asset-key :btc
                                        :operation-id "op_btc_9"
                                        :state :failed
                                        :status :terminal
                                        :source-tx-confirmations 1
                                        :destination-tx-confirmations nil
                                        :position-in-withdraw-queue 4
                                        :destination-tx-hash "0000abc123"
                                        :destination-explorer-url "https://mempool.space/tx/0000abc123"
                                        :state-next-at nil
                                        :last-updated-ms 1700000000001
                                        :error "Bridge broadcast failed"}
                  :anchor {:left 980
                           :right 1130
                           :top 620
                           :bottom 660
                           :viewport-width 1440
                           :viewport-height 900}}))

(defonce mobile-sheet-store
  (funding-store ::mobile-sheet
                 {:mode :withdraw
                  :withdraw-step :asset-select
                  :withdraw-search-input ""
                  :anchor {:left 0
                           :right 390
                           :top 760
                           :bottom 812
                           :viewport-width 390
                           :viewport-height 844}}))

(defn- funding-scene
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (funding-reducers)
    (funding-modal/render-funding-modal
     (funding-actions/funding-modal-view-model @store)))))

(portfolio/defscene deposit-select
  :params deposit-select-store
  [store]
  (funding-scene store))

(portfolio/defscene deposit-amount
  :params deposit-amount-store
  [store]
  (funding-scene store))

(portfolio/defscene send-form
  :params send-store
  [store]
  (funding-scene store))

(portfolio/defscene withdraw-select
  :params withdraw-select-store
  [store]
  (funding-scene store))

(portfolio/defscene withdraw-detail-success
  :params withdraw-success-store
  [store]
  (funding-scene store))

(portfolio/defscene withdraw-detail-queue-error
  :params withdraw-queue-error-store
  [store]
  (funding-scene store))

(portfolio/defscene mobile-sheet
  :params mobile-sheet-store
  [store]
  (funding-scene store))
