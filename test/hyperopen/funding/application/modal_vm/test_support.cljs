(ns hyperopen.funding.application.modal-vm.test-support
  (:require [clojure.string :as str]
            [hyperopen.funding.application.modal-vm.amounts :as amounts]
            [hyperopen.funding.application.modal-vm.async :as async]
            [hyperopen.funding.application.modal-vm.context :as context]
            [hyperopen.funding.application.modal-vm.lifecycle :as lifecycle]
            [hyperopen.funding.application.modal-vm.presentation :as presentation]))

(defn non-blank-text
  [value]
  (when (and (string? value)
             (not (str/blank? value)))
    value))

(defn base-deps
  ([] (base-deps {}))
  ([overrides]
   (merge {:modal-state (fn [state] (:modal state))
           :normalize-mode identity
           :normalize-hyperunit-lifecycle (fn [value]
                                            (merge {:direction nil
                                                    :asset-key nil
                                                    :state nil
                                                    :status nil}
                                                   value))
           :normalize-deposit-step #(or % :asset-select)
           :normalize-withdraw-step #(or % :asset-select)
           :deposit-assets-filtered (fn [state _modal] (:deposit-assets state))
           :deposit-asset (fn [state _modal] (:deposit-asset state))
           :withdraw-assets-filtered (fn [state _modal] (:withdraw-assets state))
           :withdraw-assets (fn [state] (:withdraw-assets state))
           :withdraw-asset (fn [state _modal] (:withdraw-asset state))
           :deposit-asset-implemented? (fn [asset] (true? (:implemented? asset)))
           :normalize-deposit-asset-key identity
           :non-blank-text non-blank-text
           :preview (fn [state _modal] (:preview-result state))
           :normalize-hyperunit-fee-estimate (fn [value]
                                               (merge {:status :ready
                                                       :by-chain {}
                                                       :error nil}
                                                      value))
           :normalize-hyperunit-withdrawal-queue (fn [value]
                                                   (merge {:status :ready
                                                           :by-chain {}
                                                           :error nil}
                                                          value))
           :hyperunit-source-chain (fn [asset] (:source-chain asset))
           :hyperunit-fee-entry (fn [estimate chain] (get-in estimate [:by-chain chain]))
           :hyperunit-withdrawal-queue-entry (fn [queue chain] (get-in queue [:by-chain chain]))
           :hyperunit-explorer-tx-url (fn [direction chain tx-id]
                                        (when tx-id
                                          (str "https://explorer/"
                                               (name direction)
                                               "/"
                                               (or chain "hyperliquid")
                                               "/"
                                               tx-id)))
           :hyperunit-lifecycle-terminal? (fn [lifecycle]
                                            (contains? #{:completed :terminal}
                                                       (:status lifecycle)))
           :hyperunit-lifecycle-failure? (fn [lifecycle] (= :failed (:state lifecycle)))
           :hyperunit-lifecycle-recovery-hint (fn [lifecycle] (:recovery-hint lifecycle))
           :estimate-fee-display (fn [amount chain]
                                   (when amount
                                     (str amount "@" chain)))
           :transfer-max-amount (fn [state _modal] (:transfer-max state))
           :withdraw-max-amount (fn [_state asset] (:max asset))
           :withdraw-minimum-amount (fn [asset] (:min asset))
           :format-usdc-display str
           :format-usdc-input str
           :deposit-quick-amounts [5 10 25]
           :deposit-min-usdc 5
           :withdraw-min-usdc 5}
          overrides)))

(defn deposit-asset
  [& {:keys [key symbol flow-kind source-chain minimum implemented?]
      :or {key :btc
           symbol "BTC"
           flow-kind :hyperunit-address
           source-chain "bitcoin"
           minimum 0.0001
           implemented? true}}]
  {:key key
   :symbol symbol
   :flow-kind flow-kind
   :source-chain source-chain
   :minimum minimum
   :implemented? implemented?})

(defn withdraw-asset
  [& {:keys [key symbol flow-kind source-chain min max]
      :or {key :usdc
           symbol "USDC"
           flow-kind :evm-address
           source-chain "ethereum"
           min 5
           max 100}}]
  {:key key
   :symbol symbol
   :flow-kind flow-kind
   :source-chain source-chain
   :min min
   :max max})

(defn base-state
  ([] (base-state {}))
  ([{:keys [modal] :as overrides}]
   (let [default-state {:modal {:open? true
                                :mode :deposit
                                :deposit-step :amount-entry
                                :to-perp? true
                                :amount-input ""
                                :destination-input ""}
                        :deposit-assets [(deposit-asset)]
                        :deposit-asset (deposit-asset)
                        :withdraw-assets [(withdraw-asset)]
                        :withdraw-asset (withdraw-asset)
                        :preview-result {:ok? true
                                         :display-message nil}
                        :transfer-max 55}]
     (-> default-state
         (update :modal merge modal)
         (merge (dissoc overrides :modal))))))

(defn build-context
  ([]
   (build-context (base-deps) (base-state)))
  ([deps state]
   (-> (context/base-context deps state)
       (context/with-asset-context deps)
       (context/with-generated-address-context deps)
       (context/with-preview-context deps)
       (async/with-async-context deps)
       (lifecycle/with-lifecycle-context deps)
       (amounts/with-amount-context deps))))

(defn build-presented-context
  ([]
   (build-presented-context (base-deps) (base-state)))
  ([deps state]
   (presentation/with-presentation-context (build-context deps state))))
