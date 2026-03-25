(ns hyperopen.funding.application.modal-vm.context)

(defn- string-value
  [value]
  (or value ""))

(defn- flow-kind
  [asset]
  (or (:flow-kind asset) :unknown))

(defn- asset-symbol
  [asset fallback]
  (or (:symbol asset) fallback))

(defn asset-minimum
  [asset fallback]
  (or (:minimum asset) fallback))

(defn- modal-legacy-kind
  [modal]
  (or (:legacy-kind modal) :unknown))

(defn base-context
  [{:keys [modal-state
           normalize-mode
           normalize-hyperunit-lifecycle
           normalize-deposit-step
           normalize-withdraw-step
           non-blank-text
           deposit-quick-amounts
           deposit-min-usdc
           withdraw-min-usdc]}
   state]
  (let [modal (modal-state state)
        mode (normalize-mode (:mode modal))
        deposit-step (normalize-deposit-step (:deposit-step modal))
        withdraw-step (normalize-withdraw-step (:withdraw-step modal))]
    {:state state
     :modal modal
     :open? (true? (:open? modal))
     :mode mode
     :deposit? (= mode :deposit)
     :withdraw? (= mode :withdraw)
     :legacy? (= mode :legacy)
     :deposit-step deposit-step
     :deposit-step-amount-entry? (= deposit-step :amount-entry)
     :withdraw-step withdraw-step
     :withdraw-step-amount-entry? (= withdraw-step :amount-entry)
     :deposit-search-input (string-value (:deposit-search-input modal))
     :withdraw-search-input (string-value (:withdraw-search-input modal))
     :amount-input (string-value (:amount-input modal))
     :to-perp? (true? (:to-perp? modal))
     :destination-input (string-value (:destination-input modal))
     :send-token (non-blank-text (:send-token modal))
     :send-symbol (non-blank-text (:send-symbol modal))
     :send-prefix-label (non-blank-text (:send-prefix-label modal))
     :send-max-amount (:send-max-amount modal)
     :send-max-display (string-value (:send-max-display modal))
     :send-max-input (string-value (:send-max-input modal))
     :withdraw-generated-address (non-blank-text (:withdraw-generated-address modal))
     :anchor (:anchor modal)
     :error (:error modal)
     :submitting? (true? (:submitting? modal))
     :legacy-kind (modal-legacy-kind modal)
     :hyperunit-lifecycle (normalize-hyperunit-lifecycle (:hyperunit-lifecycle modal))
     :deposit-quick-amounts deposit-quick-amounts
     :deposit-min-usdc deposit-min-usdc
     :withdraw-min-usdc withdraw-min-usdc}))

(defn with-asset-context
  [{:keys [state modal] :as ctx}
   {:keys [deposit-assets-filtered
           deposit-asset
           withdraw-assets-filtered
           withdraw-assets
           withdraw-asset
           deposit-asset-implemented?]}]
  (let [selected-deposit-asset (deposit-asset state modal)
        selected-withdraw-asset (withdraw-asset state modal)]
    (assoc ctx
           :deposit-assets (deposit-assets-filtered state modal)
           :selected-deposit-asset selected-deposit-asset
           :selected-deposit-asset-key (:key selected-deposit-asset)
           :selected-deposit-symbol (asset-symbol selected-deposit-asset "")
           :selected-deposit-flow-kind (flow-kind selected-deposit-asset)
           :selected-deposit-implemented? (deposit-asset-implemented? selected-deposit-asset)
           :withdraw-assets (withdraw-assets-filtered state modal)
           :withdraw-all-assets (withdraw-assets state)
           :selected-withdraw-asset selected-withdraw-asset
           :selected-withdraw-asset-key (:key selected-withdraw-asset)
           :selected-withdraw-symbol (asset-symbol selected-withdraw-asset "USDC")
           :selected-withdraw-flow-kind (flow-kind selected-withdraw-asset))))

(defn with-generated-address-context
  [{:keys [modal selected-deposit-asset-key] :as ctx}
   {:keys [normalize-deposit-asset-key non-blank-text]}]
  (let [generated-address-asset-key (normalize-deposit-asset-key
                                     (:deposit-generated-asset-key modal))
        generated-address-active? (and selected-deposit-asset-key
                                       (= generated-address-asset-key
                                          selected-deposit-asset-key))
        generated-signatures (when generated-address-active?
                               (:deposit-generated-signatures modal))]
    (assoc ctx
           :generated-address (when generated-address-active?
                                (non-blank-text (:deposit-generated-address modal)))
           :generated-signatures generated-signatures
           :generated-signature-count (if (sequential? generated-signatures)
                                        (count generated-signatures)
                                        0))))

(defn with-preview-context
  [{:keys [state modal] :as ctx}
   {:keys [preview]}]
  (let [preview-result (preview state modal)]
    (assoc ctx
           :preview-result preview-result
           :preview-ok? (:ok? preview-result)
           :preview-message (:display-message preview-result))))
