(ns hyperopen.funding.application.modal-commands
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.application.modal-state :as modal-state]))

(defn- mutation-guard-effects
  [state funding-modal-path]
  (when-let [message (account-context/mutations-blocked-message state)]
    [[:effects/save-many [[(conj funding-modal-path :submitting?) false]
                          [(conj funding-modal-path :error) message]]]]))

(defn- normalize-send-context
  [{:keys [non-blank-text parse-num]} send-context]
  (let [context (if (map? send-context) send-context {})
        max-amount (parse-num (:max-amount context))
        max-display (or (non-blank-text (:max-display context))
                        (when (number? max-amount)
                          (str max-amount)))
        max-input (or (non-blank-text (:max-input context))
                      (when (number? max-amount)
                        (str max-amount)))]
    {:send-token (non-blank-text (:token context))
     :send-symbol (non-blank-text (:symbol context))
     :send-prefix-label (non-blank-text (:prefix-label context))
     :send-max-amount (when (number? max-amount) max-amount)
     :send-max-display max-display
     :send-max-input (or max-input "")}))

(defn open-funding-deposit-modal
  [{:keys [modal-state
           normalize-anchor
           default-funding-modal-state
           wallet-address
           funding-modal-path]}
   state
   anchor
   opener-data-role]
  (let [base (modal-state state)
        anchor* (normalize-anchor anchor)]
    [[:effects/save funding-modal-path
      (-> (default-funding-modal-state)
          (assoc :open? true
                 :mode :deposit
                 :legacy-kind nil
                 :anchor anchor*
                 :deposit-step :asset-select
                 :deposit-search-input ""
                 :withdraw-step :asset-select
                 :withdraw-search-input ""
                 :deposit-selected-asset-key nil
                 :amount-input ""
                 :destination-input (or (wallet-address state)
                                        (:destination-input base "")
                                        ""))
          (modal-state/with-open-focus-metadata base opener-data-role))]
     [:effects/api-fetch-hyperunit-fee-estimate]]))

(defn open-funding-send-modal
  [{:keys [modal-state
           normalize-anchor
           default-funding-modal-state
           funding-modal-path
           non-blank-text
           parse-num]}
   state
   send-context
   anchor
   opener-data-role]
  (let [base (modal-state state)
        anchor* (normalize-anchor anchor)
        send-modal-state (normalize-send-context {:non-blank-text non-blank-text
                                                  :parse-num parse-num}
                                                 send-context)]
    [[:effects/save funding-modal-path
      (-> (default-funding-modal-state)
          (assoc :open? true
                 :mode :send
                 :anchor anchor*
                 :destination-input "")
          (merge send-modal-state)
          (modal-state/with-open-focus-metadata base opener-data-role))]]))

(defn open-funding-transfer-modal
  [{:keys [modal-state
           normalize-anchor
           default-funding-modal-state
           wallet-address
           funding-modal-path]}
   state
   anchor
   opener-data-role]
  (let [base (modal-state state)
        anchor* (normalize-anchor anchor)]
    [[:effects/save funding-modal-path
      (-> (default-funding-modal-state)
          (assoc :open? true
                 :mode :transfer
                 :anchor anchor*
                 :withdraw-step :asset-select
                 :withdraw-search-input ""
                 :to-perp? true
                 :destination-input (or (wallet-address state) ""))
          (modal-state/with-open-focus-metadata base opener-data-role))]]))

(defn open-funding-withdraw-modal
  [{:keys [modal-state
           normalize-anchor
           normalize-withdraw-asset-key
           normalize-withdraw-step
           withdraw-default-asset-key
           default-funding-modal-state
           wallet-address
           funding-modal-path]}
   state
   anchor
   opener-data-role]
  (let [base (modal-state state)
        anchor* (normalize-anchor anchor)
        selected-asset-key (or (normalize-withdraw-asset-key
                                (:withdraw-selected-asset-key base))
                               withdraw-default-asset-key)]
    [[:effects/save funding-modal-path
      (-> (default-funding-modal-state)
          (assoc :open? true
                 :mode :withdraw
                 :anchor anchor*
                 :withdraw-step (normalize-withdraw-step :asset-select)
                 :withdraw-search-input ""
                 :withdraw-selected-asset-key selected-asset-key
                 :destination-input (or (wallet-address state) ""))
          (modal-state/with-open-focus-metadata base opener-data-role))]
     [:effects/api-fetch-hyperunit-withdrawal-queue]
     [:effects/api-fetch-hyperunit-fee-estimate]]))

(defn open-legacy-funding-modal
  [{:keys [default-funding-modal-state
           wallet-address
           funding-modal-path]}
   state
   legacy-kind]
  (let [legacy* (if (keyword? legacy-kind)
                  legacy-kind
                  (keyword (str/lower-case (str (or legacy-kind "unknown")))))]
    [[:effects/save funding-modal-path
      (-> (default-funding-modal-state)
          (assoc :open? true
                 :mode :legacy
                 :legacy-kind legacy*
                 :withdraw-step :asset-select
                 :withdraw-search-input ""
                 :destination-input (or (wallet-address state) "")))]]))

(defn close-funding-modal
  [{:keys [default-funding-modal-state
           modal-state
           funding-modal-path]}
   state]
  [[:effects/save funding-modal-path
    (modal-state/closed-funding-modal-state default-funding-modal-state
                                            (modal-state state))]
   [:effects/restore-dialog-focus]])

(defn handle-funding-modal-keydown
  [{:keys [close-funding-modal-fn]}
   state
   key]
  (if (= key "Escape")
    (close-funding-modal-fn state)
    []))

(defn set-funding-modal-field
  [{:keys [modal-state
           normalize-amount-input
           normalize-deposit-step
           normalize-withdraw-step
           normalize-deposit-asset-key
           normalize-withdraw-asset-key
           withdraw-default-asset-key
           wallet-address
           default-hyperunit-lifecycle-state
           default-hyperunit-withdrawal-queue-state
           normalize-mode
           funding-modal-path]}
   state
   path
   value]
  (let [modal (modal-state state)
        path* (if (vector? path) path [path])
        value* (case path*
                 [:amount-input] (normalize-amount-input value)
                 [:destination-input] (str (or value ""))
                 [:deposit-search-input] (str (or value ""))
                 [:withdraw-search-input] (str (or value ""))
                 [:deposit-step] (normalize-deposit-step value)
                 [:withdraw-step] (normalize-withdraw-step value)
                 [:deposit-selected-asset-key] (normalize-deposit-asset-key value)
                 [:withdraw-selected-asset-key] (or (normalize-withdraw-asset-key value)
                                                    withdraw-default-asset-key)
                 value)
        clear-hyperunit-lifecycle? (or (= path* [:amount-input])
                                       (= path* [:destination-input])
                                       (= path* [:deposit-selected-asset-key])
                                       (= path* [:withdraw-selected-asset-key])
                                       (and (= path* [:deposit-step])
                                            (= value* :asset-select)))
        next-modal (cond-> (-> modal
                               (assoc-in path* value*)
                               (assoc :error nil))
                     clear-hyperunit-lifecycle?
                     (assoc :hyperunit-lifecycle (default-hyperunit-lifecycle-state))

                     clear-hyperunit-lifecycle?
                     (assoc :withdraw-generated-address nil)

                     (= path* [:withdraw-selected-asset-key])
                     (assoc :hyperunit-withdrawal-queue
                            (default-hyperunit-withdrawal-queue-state))

                     (= path* [:withdraw-selected-asset-key])
                     (assoc :withdraw-step :amount-entry
                            :amount-input ""
                            :destination-input (or (wallet-address state) ""))

                     (= path* [:deposit-selected-asset-key])
                     (assoc :deposit-step :amount-entry
                            :amount-input ""
                            :deposit-generated-address nil
                            :deposit-generated-signatures nil
                            :deposit-generated-asset-key nil)

                     (and (= path* [:deposit-step])
                          (= value* :asset-select))
                     (assoc :amount-input ""
                            :deposit-generated-address nil
                            :deposit-generated-signatures nil
                            :deposit-generated-asset-key nil)

                     (and (= path* [:withdraw-step])
                          (= value* :asset-select))
                     (assoc :amount-input ""
                            :error nil))
        next-mode (normalize-mode (:mode next-modal))
        refresh-estimate? (and (contains? #{:deposit :withdraw} next-mode)
                               (or (= path* [:deposit-selected-asset-key])
                                   (= path* [:withdraw-selected-asset-key])))
        refresh-withdraw-queue? (and (= next-mode :withdraw)
                                     (= path* [:withdraw-selected-asset-key]))]
    (cond-> [[:effects/save funding-modal-path next-modal]]
      refresh-estimate?
      (conj [:effects/api-fetch-hyperunit-fee-estimate])

      refresh-withdraw-queue?
      (conj [:effects/api-fetch-hyperunit-withdrawal-queue]))))

(defn search-funding-deposit-assets
  [deps state value]
  (set-funding-modal-field deps state [:deposit-search-input] value))

(defn search-funding-withdraw-assets
  [deps state value]
  (set-funding-modal-field deps state [:withdraw-search-input] value))

(defn select-funding-deposit-asset
  [deps state asset-key]
  (set-funding-modal-field deps state [:deposit-selected-asset-key] asset-key))

(defn return-to-funding-deposit-asset-select
  [deps state]
  (set-funding-modal-field deps state [:deposit-step] :asset-select))

(defn return-to-funding-withdraw-asset-select
  [deps state]
  (set-funding-modal-field deps state [:withdraw-step] :asset-select))

(defn enter-funding-deposit-amount
  [deps state value]
  (set-funding-modal-field deps state [:amount-input] value))

(defn set-funding-deposit-amount-to-minimum
  [{:keys [modal-state
           deposit-asset
           deposit-min-usdc] :as deps}
   state]
  (let [modal (modal-state state)
        minimum (or (:minimum (deposit-asset state modal))
                    deposit-min-usdc)]
    (set-funding-modal-field deps state [:amount-input] minimum)))

(defn enter-funding-transfer-amount
  [deps state value]
  (set-funding-modal-field deps state [:amount-input] value))

(defn select-funding-withdraw-asset
  [deps state asset-key]
  (set-funding-modal-field deps state [:withdraw-selected-asset-key] asset-key))

(defn enter-funding-withdraw-destination
  [deps state value]
  (set-funding-modal-field deps state [:destination-input] value))

(defn enter-funding-withdraw-amount
  [deps state value]
  (set-funding-modal-field deps state [:amount-input] value))

(defn set-hyperunit-lifecycle
  [{:keys [modal-state
           normalize-hyperunit-lifecycle
           funding-modal-path]}
   state
   lifecycle]
  (let [modal (modal-state state)]
    [[:effects/save funding-modal-path
      (-> modal
          (assoc :hyperunit-lifecycle (normalize-hyperunit-lifecycle lifecycle)
                 :error nil))]]))

(defn clear-hyperunit-lifecycle
  [{:keys [modal-state
           default-hyperunit-lifecycle-state
           funding-modal-path]}
   state]
  (let [modal (modal-state state)]
    [[:effects/save funding-modal-path
      (assoc modal :hyperunit-lifecycle (default-hyperunit-lifecycle-state))]]))

(defn set-hyperunit-lifecycle-error
  [{:keys [modal-state
           normalize-hyperunit-lifecycle
           non-blank-text
           funding-modal-path]}
   state
   error]
  (let [modal (modal-state state)
        lifecycle (normalize-hyperunit-lifecycle (:hyperunit-lifecycle modal))]
    [[:effects/save funding-modal-path
      (assoc modal :hyperunit-lifecycle (assoc lifecycle
                                               :error (non-blank-text error)))]]))

(defn set-funding-transfer-direction
  [{:keys [modal-state
           funding-modal-path]}
   state
   to-perp?]
  (let [modal (modal-state state)]
    [[:effects/save funding-modal-path
      (-> modal
          (assoc :to-perp? (true? to-perp?)
                 :error nil))]]))

(defn set-funding-amount-to-max
  [{:keys [modal-state
           normalize-mode
           transfer-max-amount
           withdraw-max-amount
           withdraw-asset
           format-usdc-input
           funding-modal-path]}
   state]
  (let [modal (modal-state state)
        mode (normalize-mode (:mode modal))
        max-amount (case mode
                     :send (:send-max-amount modal)
                     :transfer (transfer-max-amount state modal)
                     :withdraw (withdraw-max-amount state (withdraw-asset state modal))
                     0)]
    [[:effects/save funding-modal-path
      (-> modal
          (assoc :amount-input (if (= mode :send)
                                 (or (:send-max-input modal)
                                     (when (number? max-amount)
                                       (str max-amount))
                                     "")
                                 (format-usdc-input max-amount))
                 :error nil))]]))

(defn submit-funding-send
  [{:keys [modal-state
           normalize-mode
           send-preview
           funding-modal-path]}
   state]
  (if-let [guard-effects (mutation-guard-effects state funding-modal-path)]
    guard-effects
    (let [modal (modal-state state)
          mode (normalize-mode (:mode modal))
          result (if (= :send mode)
                   (send-preview state modal)
                   {:ok? false
                    :display-message "Send modal unavailable."})]
      (if-not (:ok? result)
        [[:effects/save-many [[(conj funding-modal-path :submitting?) false]
                              [(conj funding-modal-path :error) (:display-message result)]]]]
        [[:effects/save-many [[(conj funding-modal-path :submitting?) true]
                              [(conj funding-modal-path :error) nil]]]
         [:effects/api-submit-funding-send (:request result)]]))))

(defn submit-funding-transfer
  [{:keys [modal-state
           normalize-mode
           transfer-preview
           funding-modal-path]}
   state]
  (if-let [guard-effects (mutation-guard-effects state funding-modal-path)]
    guard-effects
    (let [modal (modal-state state)
          mode (normalize-mode (:mode modal))
          result (if (= :transfer mode)
                   (transfer-preview state modal)
                   {:ok? false
                    :display-message "Transfer modal unavailable."})]
      (if-not (:ok? result)
        [[:effects/save-many [[(conj funding-modal-path :submitting?) false]
                              [(conj funding-modal-path :error) (:display-message result)]]]]
        [[:effects/save-many [[(conj funding-modal-path :submitting?) true]
                              [(conj funding-modal-path :error) nil]]]
         [:effects/api-submit-funding-transfer (:request result)]]))))

(defn submit-funding-withdraw
  [{:keys [modal-state
           normalize-mode
           withdraw-preview
           funding-modal-path]}
   state]
  (if-let [guard-effects (mutation-guard-effects state funding-modal-path)]
    guard-effects
    (let [modal (modal-state state)
          mode (normalize-mode (:mode modal))
          result (if (= :withdraw mode)
                   (withdraw-preview state modal)
                   {:ok? false
                    :display-message "Withdraw modal unavailable."})]
      (if-not (:ok? result)
        [[:effects/save-many [[(conj funding-modal-path :submitting?) false]
                              [(conj funding-modal-path :error) (:display-message result)]]]]
        [[:effects/save-many [[(conj funding-modal-path :submitting?) true]
                              [(conj funding-modal-path :error) nil]]]
         [:effects/api-submit-funding-withdraw (:request result)]]))))

(defn submit-funding-deposit
  [{:keys [modal-state
           normalize-mode
           deposit-preview
           funding-modal-path]}
   state]
  (if-let [guard-effects (mutation-guard-effects state funding-modal-path)]
    guard-effects
    (let [modal (modal-state state)
          mode (normalize-mode (:mode modal))
          result (if (= :deposit mode)
                   (deposit-preview state modal)
                   {:ok? false
                    :display-message "Deposit modal unavailable."})]
      (if-not (:ok? result)
        [[:effects/save-many [[(conj funding-modal-path :submitting?) false]
                              [(conj funding-modal-path :error) (:display-message result)]]]]
        [[:effects/save-many [[(conj funding-modal-path :submitting?) true]
                              [(conj funding-modal-path :error) nil]]]
         [:effects/api-submit-funding-deposit (:request result)]]))))

(defn set-funding-modal-compat
  [{:keys [close-funding-modal-fn
           open-funding-send-modal-fn
           open-funding-deposit-modal-fn
           open-funding-withdraw-modal-fn
           open-funding-transfer-modal-fn
           open-legacy-funding-modal-fn]}
   state
   modal]
  (let [mode (cond
               (keyword? modal) modal
               (string? modal) (keyword (str/lower-case (str/trim modal)))
               :else nil)]
    (case mode
      nil (close-funding-modal-fn state)
      :deposit (open-funding-deposit-modal-fn state nil)
      :withdraw (open-funding-withdraw-modal-fn state nil)
      :send (open-funding-send-modal-fn state nil nil)
      :transfer (open-funding-transfer-modal-fn state nil)
      (open-legacy-funding-modal-fn state mode))))
