(ns hyperopen.funding.application.modal-vm.test-support
  (:require [clojure.string :as str]
            [hyperopen.funding.application.modal-vm.amounts :as amounts]
            [hyperopen.funding.application.modal-vm.async :as async]
            [hyperopen.funding.application.modal-vm.context :as context]
            [hyperopen.funding.application.modal-vm.lifecycle :as lifecycle]
            [hyperopen.funding.application.modal-vm.presentation :as presentation]
            [hyperopen.funding.application.modal-vm.transfer :as transfer]))

(defn non-blank-text
  [value]
  (when (and (string? value)
             (not (str/blank? value)))
    value))

(defn- normalize-hyperunit-lifecycle
  [value]
  (let [lifecycle* (or value {})]
    {:direction (:direction lifecycle*)
     :asset-key (:asset-key lifecycle*)
     :operation-id (:operation-id lifecycle*)
     :state (:state lifecycle*)
     :status (:status lifecycle*)
     :source-tx-confirmations (:source-tx-confirmations lifecycle*)
     :destination-tx-confirmations (:destination-tx-confirmations lifecycle*)
     :position-in-withdraw-queue (:position-in-withdraw-queue lifecycle*)
     :destination-tx-hash (:destination-tx-hash lifecycle*)
     :state-next-at (:state-next-at lifecycle*)
     :last-updated-ms (:last-updated-ms lifecycle*)
     :error (:error lifecycle*)}))

(defn- normalize-hyperunit-withdrawal-queue
  [value]
  (let [queue* (merge {:status :ready
                       :by-chain {}
                       :requested-at-ms nil
                       :updated-at-ms nil
                       :error nil}
                      value)]
    (update queue* :by-chain
            (fn [by-chain]
              (reduce-kv (fn [acc chain entry]
                           (let [chain* (cond
                                          (keyword? chain) (name chain)
                                          (string? chain) chain
                                          :else (str chain))]
                             (assoc acc chain*
                                    {:chain chain*
                                   :last-withdraw-queue-operation-tx-id
                                   (:last-withdraw-queue-operation-tx-id entry)
                                   :withdrawal-queue-length
                                   (:withdrawal-queue-length entry)})))
                         {}
                         (or by-chain {}))))))

(defn base-deps
  ([] (base-deps {}))
  ([overrides]
   (merge {:modal-state (fn [state] (:modal state))
           :normalize-mode identity
           :normalize-hyperunit-lifecycle normalize-hyperunit-lifecycle
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
           :normalize-hyperunit-withdrawal-queue normalize-hyperunit-withdrawal-queue
           :hyperunit-source-chain (fn [asset] (:hyperunit-source-chain asset))
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
           :hyperunit-lifecycle-recovery-hint (fn [lifecycle]
                                                (when (= :failed (:state lifecycle))
                                                  "Retry from the activity panel."))
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
           :withdraw-min-usdc 5
           :hyperevm-linked-tokens (fn [_state] [])
           :hyperevm-entry (fn [_state _address] nil)
           :hyperevm-moves-blocked-message (fn [_state] nil)
           :token-price-usd (fn [_state _token] nil)
           :wallet-chain-id (fn [_state] nil)}
          overrides)))

(defn deposit-asset
  [& {:keys [key
             symbol
             name
             network
             flow-kind
             source-chain
             hyperunit-source-chain
             route-key
             minimum
             maximum
             chain-id
             implemented?]
      :or {key :btc
           symbol "BTC"
           name "Bitcoin"
           network "Bitcoin"
           flow-kind :hyperunit-address
           hyperunit-source-chain nil
           route-key "lifi"
           minimum 0.0001
           implemented? true}}]
  (let [hyperunit-source-chain* (or hyperunit-source-chain source-chain "bitcoin")
        name* (or name symbol)
        network* (or network
                     (case hyperunit-source-chain*
                       "bitcoin" "Bitcoin"
                       "ethereum" "Ethereum"
                       "solana" "Solana"
                       "monad" "Monad"
                       "plasma" "Plasma"
                       "Arbitrum"))]
    (cond-> {:key key
             :symbol symbol
             :name name*
             :network network*
           :flow-kind flow-kind
           :implemented? implemented?}
      (some? minimum) (assoc :minimum minimum)
      (some? maximum) (assoc :maximum maximum)
      (seq chain-id) (assoc :chain-id chain-id)
      (= flow-kind :hyperunit-address) (assoc :hyperunit-source-chain hyperunit-source-chain*)
      (= flow-kind :route) (assoc :route-key route-key))))

(defn withdraw-asset
  [& {:keys [key
             symbol
             name
             network
             flow-kind
             source-chain
             hyperunit-source-chain
             min
             max
             available-amount
             available-display
             available-detail-display]
      :or {key :usdc
           symbol "USDC"
           name "USDC"
           network "Arbitrum"
           flow-kind :bridge2
           hyperunit-source-chain nil
           min 5
           max 100
           available-amount 100
           available-display "100"
           available-detail-display "100"}}]
  (let [hyperunit-source-chain* (or hyperunit-source-chain source-chain "ethereum")
        name* (or name symbol)
        network* (or network
                     (case hyperunit-source-chain*
                       "bitcoin" "Bitcoin"
                       "ethereum" "Ethereum"
                       "solana" "Solana"
                       "monad" "Monad"
                       "plasma" "Plasma"
                       "Arbitrum"))
        available-amount* (or available-amount max 0)
        available-display* (or available-display (str available-amount*))
        available-detail-display* (or available-detail-display available-display*)]
    (cond-> {:key key
             :symbol symbol
             :name name*
             :network network*
           :flow-kind flow-kind
           :min min
           :max max
             :available-amount available-amount*
             :available-display available-display*
             :available-detail-display available-detail-display*}
      (= flow-kind :hyperunit-address) (assoc :hyperunit-source-chain hyperunit-source-chain*))))

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
       (amounts/with-amount-context deps)
       (transfer/with-transfer-context deps))))

(defn build-presented-context
  ([]
   (build-presented-context (base-deps) (base-state)))
  ([deps state]
   (presentation/with-presentation-context (build-context deps state))))
