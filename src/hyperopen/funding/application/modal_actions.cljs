(ns hyperopen.funding.application.modal-actions
  (:require [hyperopen.funding.application.modal-commands :as modal-commands]
            [hyperopen.funding.application.modal-vm :as modal-vm]
            [hyperopen.funding.domain.assets :as assets-domain]
            [hyperopen.funding.domain.lifecycle :as lifecycle-domain]
            [hyperopen.funding.domain.modal-state :as modal-state-domain]
            [hyperopen.funding.domain.policy :as policy-domain]))

(def ^:private funding-modal-path
  [:funding-ui :modal])

(def withdraw-min-usdc assets-domain/withdraw-min-usdc)
(def deposit-min-usdc assets-domain/deposit-min-usdc)
(def deposit-chain-id-mainnet assets-domain/deposit-chain-id-mainnet)
(def deposit-chain-id-testnet assets-domain/deposit-chain-id-testnet)
(def deposit-quick-amounts assets-domain/deposit-quick-amounts)
(def withdraw-default-asset-key assets-domain/withdraw-default-asset-key)

(def ^:private anchor-keys
  [:left :right :top :bottom :width :height :viewport-width :viewport-height])

(def parse-num policy-domain/parse-num)
(def non-blank-text policy-domain/non-blank-text)
(def normalize-amount-input policy-domain/normalize-amount-input)
(def normalize-evm-address policy-domain/normalize-evm-address)
(def normalize-mode policy-domain/normalize-mode)
(def normalize-deposit-step policy-domain/normalize-deposit-step)
(def normalize-withdraw-step policy-domain/normalize-withdraw-step)

(defn- normalize-anchor
  [anchor]
  (let [anchor* (cond
                  (map? anchor) anchor
                  (some? anchor) (js->clj anchor :keywordize-keys true)
                  :else nil)]
    (when (map? anchor*)
      (let [normalized (reduce (fn [acc k]
                                 (if-let [num (parse-num (get anchor* k))]
                                   (assoc acc k num)
                                   acc))
                               {}
                               anchor-keys)]
        (when (seq normalized)
          normalized)))))

(defn- wallet-address
  [state]
  (normalize-evm-address (get-in state [:wallet :address])))

(def hyperunit-lifecycle-terminal? lifecycle-domain/hyperunit-lifecycle-terminal?)
(def default-hyperunit-lifecycle-state lifecycle-domain/default-hyperunit-lifecycle-state)
(def normalize-hyperunit-lifecycle lifecycle-domain/normalize-hyperunit-lifecycle)
(def default-hyperunit-fee-estimate-state lifecycle-domain/default-hyperunit-fee-estimate-state)
(def normalize-hyperunit-fee-estimate lifecycle-domain/normalize-hyperunit-fee-estimate)
(def default-hyperunit-withdrawal-queue-state lifecycle-domain/default-hyperunit-withdrawal-queue-state)
(def normalize-hyperunit-withdrawal-queue lifecycle-domain/normalize-hyperunit-withdrawal-queue)

(def default-funding-modal-state modal-state-domain/default-funding-modal-state)

(def normalize-deposit-asset-key assets-domain/normalize-deposit-asset-key)
(def normalize-withdraw-asset-key assets-domain/normalize-withdraw-asset-key)
(def deposit-assets assets-domain/deposit-assets)
(def deposit-asset assets-domain/deposit-asset)
(def deposit-asset-implemented? assets-domain/deposit-asset-implemented?)
(def deposit-assets-filtered assets-domain/deposit-assets-filtered)
(def withdraw-assets policy-domain/withdraw-assets)
(def withdraw-assets-filtered policy-domain/withdraw-assets-filtered)
(def withdraw-asset policy-domain/withdraw-asset)
(def withdraw-minimum-amount assets-domain/withdraw-minimum-amount)
(def hyperunit-source-chain assets-domain/hyperunit-source-chain)

(def transfer-max-amount policy-domain/transfer-max-amount)
(def withdraw-max-amount policy-domain/withdraw-max-amount)
(def format-usdc-display policy-domain/format-usdc-display)
(def format-usdc-input policy-domain/format-usdc-input)
(def hyperunit-lifecycle-failure? policy-domain/hyperunit-lifecycle-failure?)
(def hyperunit-lifecycle-recovery-hint policy-domain/hyperunit-lifecycle-recovery-hint)
(def hyperunit-explorer-tx-url policy-domain/hyperunit-explorer-tx-url)
(def hyperunit-fee-entry policy-domain/hyperunit-fee-entry)
(def hyperunit-withdrawal-queue-entry policy-domain/hyperunit-withdrawal-queue-entry)
(def estimate-fee-display policy-domain/estimate-fee-display)
(def transfer-preview policy-domain/transfer-preview)
(def withdraw-preview policy-domain/withdraw-preview)
(def deposit-preview policy-domain/deposit-preview)
(def preview policy-domain/preview)

(defn- modal-state
  [state]
  (modal-state-domain/normalize-modal-state
   {:stored-modal (get-in state funding-modal-path)
    :normalize-anchor-fn normalize-anchor}))

(defn modal-open?
  [state]
  (true? (:open? (modal-state state))))

(defn funding-modal-view-model
  [state]
  (modal-vm/funding-modal-view-model
   {:modal-state modal-state
   :normalize-mode normalize-mode
   :normalize-hyperunit-lifecycle normalize-hyperunit-lifecycle
   :normalize-deposit-step normalize-deposit-step
    :normalize-withdraw-step normalize-withdraw-step
    :deposit-assets-filtered deposit-assets-filtered
    :deposit-asset deposit-asset
    :withdraw-assets-filtered withdraw-assets-filtered
    :withdraw-assets withdraw-assets
    :withdraw-asset withdraw-asset
    :deposit-asset-implemented? deposit-asset-implemented?
    :normalize-deposit-asset-key normalize-deposit-asset-key
    :non-blank-text non-blank-text
    :preview preview
    :normalize-hyperunit-fee-estimate normalize-hyperunit-fee-estimate
    :normalize-hyperunit-withdrawal-queue normalize-hyperunit-withdrawal-queue
    :hyperunit-source-chain hyperunit-source-chain
    :hyperunit-fee-entry hyperunit-fee-entry
    :hyperunit-withdrawal-queue-entry hyperunit-withdrawal-queue-entry
    :hyperunit-explorer-tx-url hyperunit-explorer-tx-url
    :hyperunit-lifecycle-terminal? hyperunit-lifecycle-terminal?
    :hyperunit-lifecycle-failure? hyperunit-lifecycle-failure?
    :hyperunit-lifecycle-recovery-hint hyperunit-lifecycle-recovery-hint
    :estimate-fee-display estimate-fee-display
    :transfer-max-amount transfer-max-amount
    :withdraw-max-amount withdraw-max-amount
    :withdraw-minimum-amount withdraw-minimum-amount
    :format-usdc-display format-usdc-display
    :format-usdc-input format-usdc-input
    :deposit-quick-amounts deposit-quick-amounts
    :deposit-min-usdc deposit-min-usdc
    :withdraw-min-usdc withdraw-min-usdc}
   state))

(declare close-funding-modal
         open-funding-send-modal
         open-funding-deposit-modal
         open-funding-withdraw-modal
         open-funding-transfer-modal
         open-legacy-funding-modal
         submit-funding-send)

(defn- command-deps
  []
  {:modal-state modal-state
   :normalize-anchor normalize-anchor
   :default-funding-modal-state default-funding-modal-state
   :wallet-address wallet-address
   :funding-modal-path funding-modal-path
   :parse-num parse-num
   :normalize-withdraw-asset-key normalize-withdraw-asset-key
   :withdraw-default-asset-key withdraw-default-asset-key
   :close-funding-modal-fn close-funding-modal
   :open-funding-send-modal-fn open-funding-send-modal
   :open-funding-deposit-modal-fn open-funding-deposit-modal
   :open-funding-withdraw-modal-fn open-funding-withdraw-modal
   :open-funding-transfer-modal-fn open-funding-transfer-modal
   :open-legacy-funding-modal-fn open-legacy-funding-modal
   :normalize-amount-input normalize-amount-input
   :normalize-deposit-step normalize-deposit-step
   :normalize-withdraw-step normalize-withdraw-step
   :normalize-deposit-asset-key normalize-deposit-asset-key
   :deposit-asset deposit-asset
   :deposit-min-usdc deposit-min-usdc
   :default-hyperunit-lifecycle-state default-hyperunit-lifecycle-state
   :default-hyperunit-withdrawal-queue-state default-hyperunit-withdrawal-queue-state
   :normalize-mode normalize-mode
   :normalize-hyperunit-lifecycle normalize-hyperunit-lifecycle
   :non-blank-text non-blank-text
   :transfer-max-amount transfer-max-amount
   :withdraw-max-amount withdraw-max-amount
   :withdraw-asset withdraw-asset
   :format-usdc-input format-usdc-input
   :send-preview policy-domain/send-preview
   :transfer-preview transfer-preview
   :withdraw-preview withdraw-preview
   :deposit-preview deposit-preview})

(defn open-funding-send-modal
  ([state]
   (open-funding-send-modal state nil nil))
  ([state send-context]
   (open-funding-send-modal state send-context nil))
  ([state send-context anchor]
   (modal-commands/open-funding-send-modal (command-deps) state send-context anchor)))

(defn open-funding-deposit-modal
  ([state]
   (open-funding-deposit-modal state nil))
  ([state anchor]
   (modal-commands/open-funding-deposit-modal (command-deps) state anchor)))

(defn open-funding-transfer-modal
  ([state]
   (open-funding-transfer-modal state nil))
  ([state anchor]
   (modal-commands/open-funding-transfer-modal (command-deps) state anchor)))

(defn open-funding-withdraw-modal
  ([state]
   (open-funding-withdraw-modal state nil))
  ([state anchor]
   (modal-commands/open-funding-withdraw-modal (command-deps) state anchor)))

(defn- open-legacy-funding-modal
  [state legacy-kind]
  (modal-commands/open-legacy-funding-modal (command-deps) state legacy-kind))

(defn close-funding-modal
  [state]
  (modal-commands/close-funding-modal (command-deps) state))

(defn handle-funding-modal-keydown
  [state key]
  (modal-commands/handle-funding-modal-keydown (command-deps) state key))

(defn set-funding-modal-field
  [state path value]
  (modal-commands/set-funding-modal-field (command-deps) state path value))

(defn search-funding-deposit-assets
  [state value]
  (modal-commands/search-funding-deposit-assets (command-deps) state value))

(defn search-funding-withdraw-assets
  [state value]
  (modal-commands/search-funding-withdraw-assets (command-deps) state value))

(defn select-funding-deposit-asset
  [state asset-key]
  (modal-commands/select-funding-deposit-asset (command-deps) state asset-key))

(defn return-to-funding-deposit-asset-select
  [state]
  (modal-commands/return-to-funding-deposit-asset-select (command-deps) state))

(defn return-to-funding-withdraw-asset-select
  [state]
  (modal-commands/return-to-funding-withdraw-asset-select (command-deps) state))

(defn enter-funding-deposit-amount
  [state value]
  (modal-commands/enter-funding-deposit-amount (command-deps) state value))

(defn set-funding-deposit-amount-to-minimum
  [state]
  (modal-commands/set-funding-deposit-amount-to-minimum (command-deps) state))

(defn enter-funding-transfer-amount
  [state value]
  (modal-commands/enter-funding-transfer-amount (command-deps) state value))

(defn select-funding-withdraw-asset
  [state asset-key]
  (modal-commands/select-funding-withdraw-asset (command-deps) state asset-key))

(defn enter-funding-withdraw-destination
  [state value]
  (modal-commands/enter-funding-withdraw-destination (command-deps) state value))

(defn enter-funding-withdraw-amount
  [state value]
  (modal-commands/enter-funding-withdraw-amount (command-deps) state value))

(defn set-hyperunit-lifecycle
  [state lifecycle]
  (modal-commands/set-hyperunit-lifecycle (command-deps) state lifecycle))

(defn clear-hyperunit-lifecycle
  [state]
  (modal-commands/clear-hyperunit-lifecycle (command-deps) state))

(defn set-hyperunit-lifecycle-error
  [state error]
  (modal-commands/set-hyperunit-lifecycle-error (command-deps) state error))

(defn set-funding-transfer-direction
  [state to-perp?]
  (modal-commands/set-funding-transfer-direction (command-deps) state to-perp?))

(defn set-funding-amount-to-max
  [state]
  (modal-commands/set-funding-amount-to-max (command-deps) state))

(defn submit-funding-send
  [state]
  (modal-commands/submit-funding-send (command-deps) state))

(defn submit-funding-transfer
  [state]
  (modal-commands/submit-funding-transfer (command-deps) state))

(defn submit-funding-withdraw
  [state]
  (modal-commands/submit-funding-withdraw (command-deps) state))

(defn submit-funding-deposit
  [state]
  (modal-commands/submit-funding-deposit (command-deps) state))

(defn set-funding-modal-compat
  [state modal]
  (modal-commands/set-funding-modal-compat (command-deps) state modal))
