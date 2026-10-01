(ns hyperopen.funding.application.modal-state
  (:require [clojure.string :as str]
            [hyperopen.funding.domain.assets :as assets-domain]
            [hyperopen.funding.domain.policy :as policy-domain]
            [hyperopen.funding.domain.lifecycle :as lifecycle-domain]
            [hyperopen.funding.domain.transfer-route :as transfer-route]))

(defn default-funding-modal-state
  []
  {:open? false
   :mode nil
   :legacy-kind nil
   :anchor nil
   :opener-data-role nil
   :focus-return-data-role nil
   :focus-return-token 0
   :send-token nil
   :send-symbol nil
   :send-prefix-label nil
   :send-max-amount nil
   :send-max-display nil
   :send-max-input ""
   :deposit-step :asset-select
   :deposit-search-input ""
   :withdraw-step :asset-select
   :withdraw-search-input ""
   :deposit-selected-asset-key nil
   :deposit-generated-address nil
   :deposit-generated-signatures nil
   :deposit-generated-asset-key nil
   :amount-input ""
   :to-perp? true
   :transfer-dex ""
   :transfer-destination-address ""
   :transfer-from-subaccount ""
   ;; Transfer places and asset. Both places nil means the legacy Perps <->
   ;; Spot route derived from :to-perp?. :transfer-asset is a spot token index.
   :transfer-from nil
   :transfer-to nil
   :transfer-asset nil
   ;; HyperEVM run progress and the one-click gas top-up, written by effects.
   :transfer-evm nil
   :transfer-gas-topup nil
   :destination-input ""
   :withdraw-selected-asset-key assets-domain/withdraw-default-asset-key
   :withdraw-generated-address nil
   :hyperunit-lifecycle (lifecycle-domain/default-hyperunit-lifecycle-state)
   :hyperunit-fee-estimate (lifecycle-domain/default-hyperunit-fee-estimate-state)
   :hyperunit-withdrawal-queue (lifecycle-domain/default-hyperunit-withdrawal-queue-state)
   :submitting? false
   :error nil})

(defn normalize-data-role
  [value]
  (some-> value str str/trim not-empty))

(defn focus-return-token
  [modal]
  (let [token (:focus-return-token modal)]
    (if (and (number? token)
             (not (neg? token)))
      token
      0)))

(defn with-open-focus-metadata
  [modal current-modal opener-data-role]
  (assoc modal
         :opener-data-role (normalize-data-role opener-data-role)
         :focus-return-data-role nil
         :focus-return-token (focus-return-token current-modal)))

(defn closed-funding-modal-state
  [default-funding-modal-state current-modal]
  (let [opener-data-role (normalize-data-role (:opener-data-role current-modal))
        current-token (focus-return-token current-modal)]
    (assoc (default-funding-modal-state)
           :focus-return-data-role opener-data-role
           :focus-return-token (if opener-data-role
                                 (inc current-token)
                                 current-token))))

(defn normalize-modal-state
  [{:keys [stored-modal normalize-anchor-fn]}]
  (let [modal (merge (default-funding-modal-state)
                     (if (map? stored-modal) stored-modal {}))]
    (assoc modal
           :anchor (when (fn? normalize-anchor-fn)
                     (normalize-anchor-fn (:anchor modal)))
           :withdraw-step (policy-domain/normalize-withdraw-step
                           (:withdraw-step modal))
           :withdraw-selected-asset-key (or (assets-domain/normalize-withdraw-asset-key
                                             (:withdraw-selected-asset-key modal))
                                            assets-domain/withdraw-default-asset-key)
           :hyperunit-fee-estimate (lifecycle-domain/normalize-hyperunit-fee-estimate
                                    (:hyperunit-fee-estimate modal))
           :hyperunit-withdrawal-queue (lifecycle-domain/normalize-hyperunit-withdrawal-queue
                                        (:hyperunit-withdrawal-queue modal))
           :hyperunit-lifecycle (lifecycle-domain/normalize-hyperunit-lifecycle
                                 (:hyperunit-lifecycle modal))
           :transfer-from (transfer-route/normalize-location (:transfer-from modal))
           :transfer-to (transfer-route/normalize-location (:transfer-to modal))
           :transfer-asset (transfer-route/normalize-asset-index (:transfer-asset modal))
           :transfer-evm (when (map? (:transfer-evm modal)) (:transfer-evm modal))
           :transfer-gas-topup (when (map? (:transfer-gas-topup modal))
                                 (:transfer-gas-topup modal)))))
