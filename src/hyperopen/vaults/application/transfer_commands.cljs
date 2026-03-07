(ns hyperopen.vaults.application.transfer-commands
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.vaults.domain.identity :as identity]
            [hyperopen.vaults.domain.transfer-policy :as transfer-policy]))

(def ^:private vault-transfer-modal-path
  [:vaults-ui :vault-transfer-modal])

(defn- vault-transfer-modal
  [state]
  (merge (transfer-policy/default-vault-transfer-modal-state)
         (if (map? (get-in state vault-transfer-modal-path))
           (get-in state vault-transfer-modal-path)
           {})))

(defn open-vault-transfer-modal
  [_state vault-address mode]
  (if-let [vault-address* (identity/normalize-vault-address vault-address)]
    [[:effects/save vault-transfer-modal-path
      (assoc (transfer-policy/default-vault-transfer-modal-state)
             :open? true
             :mode (transfer-policy/normalize-vault-transfer-mode mode)
             :vault-address vault-address*)]]
    []))

(defn close-vault-transfer-modal
  [_state]
  [[:effects/save vault-transfer-modal-path
    (transfer-policy/default-vault-transfer-modal-state)]])

(defn handle-vault-transfer-modal-keydown
  [state key]
  (if (= key "Escape")
    (close-vault-transfer-modal state)
    []))

(defn set-vault-transfer-amount
  [state amount]
  (let [modal (vault-transfer-modal state)
        amount* (if (string? amount)
                  amount
                  (str (or amount "")))
        mode* (transfer-policy/normalize-vault-transfer-mode (:mode modal))
        next-modal (-> modal
                       (assoc :amount-input amount*
                              :error nil)
                       (cond->
                         (and (= mode* :withdraw)
                              (seq (str/trim amount*)))
                         (assoc :withdraw-all? false)))]
    [[:effects/save vault-transfer-modal-path next-modal]]))

(defn set-vault-transfer-withdraw-all
  [state withdraw-all?]
  (let [modal (vault-transfer-modal state)
        mode* (transfer-policy/normalize-vault-transfer-mode (:mode modal))
        enabled? (= mode* :withdraw)
        withdraw-all?* (and enabled?
                            (true? withdraw-all?))
        next-modal (cond-> (assoc modal
                                  :withdraw-all? withdraw-all?*
                                  :error nil)
                     withdraw-all?* (assoc :amount-input ""))]
    [[:effects/save vault-transfer-modal-path next-modal]]))

(defn submit-vault-transfer
  [{:keys [route-vault-address-fn]} state]
  (if-let [spectate-mode-message (account-context/mutations-blocked-message state)]
    [[:effects/save-many [[(conj vault-transfer-modal-path :submitting?) false]
                          [(conj vault-transfer-modal-path :error) spectate-mode-message]]]]
    (let [modal (vault-transfer-modal state)
          result (transfer-policy/vault-transfer-preview
                  {:route-vault-address-fn route-vault-address-fn}
                  state
                  modal)]
      (if-not (:ok? result)
        [[:effects/save-many [[(conj vault-transfer-modal-path :submitting?) false]
                              [(conj vault-transfer-modal-path :error) (:display-message result)]]]]
        [[:effects/save-many [[(conj vault-transfer-modal-path :submitting?) true]
                              [(conj vault-transfer-modal-path :error) nil]]]
         [:effects/api-submit-vault-transfer (:request result)]]))))
