(ns hyperopen.workbench.scenes.vaults.transfer-modal-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.fixtures :as fixtures]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.vaults.detail.transfer-modal :as transfer-modal]))

(portfolio/configure-scenes
  {:title "Vault Transfer Modal"
   :collection :vaults})

(defn- modal-store
  [scene-id overrides]
  (ws/create-store scene-id (fixtures/vault-transfer-modal overrides)))

(defn- modal-reducers
  []
  {:actions/close-vault-transfer-modal
   (fn [state _dispatch-data]
     (assoc state :open? false))

   :actions/handle-vault-transfer-modal-keydown
   (fn [state _dispatch-data key]
     (if (= key "Escape")
       (assoc state :open? false)
       state))

   :actions/set-vault-transfer-amount
   (fn [state _dispatch-data value]
     (assoc state :amount-input value))

   :actions/set-vault-transfer-withdraw-all
   (fn [state _dispatch-data checked?]
     (assoc state :withdraw-all? checked?))

   :actions/submit-vault-transfer
   (fn [state _dispatch-data]
     (assoc state :error "Workbench submit is stubbed"))})

(defonce deposit-store
  (modal-store ::deposit {}))

(defonce withdraw-store
  (modal-store ::withdraw {:mode :withdraw
                           :title "Withdraw from Basis Capture Alpha"
                           :confirm-label "Withdraw"}))

(defonce validation-store
  (modal-store ::validation {:mode :withdraw
                             :title "Withdraw from Basis Capture Alpha"
                             :confirm-label "Withdraw"
                             :submit-disabled? true
                             :preview-ok? false
                             :preview-message "Amount exceeds available settled balance."
                             :error "Review transfer limits before submitting."}))

(defn- transfer-scene
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (modal-reducers)
    (transfer-modal/vault-transfer-modal-view @store))))

(portfolio/defscene deposit
  :params deposit-store
  [store]
  (transfer-scene store))

(portfolio/defscene withdraw
  :params withdraw-store
  [store]
  (transfer-scene store))

(portfolio/defscene disabled-validation
  :params validation-store
  [store]
  (transfer-scene store))
