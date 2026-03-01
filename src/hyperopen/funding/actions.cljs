(ns hyperopen.funding.actions
  (:require [clojure.string :as str]
            [hyperopen.domain.trading :as trading-domain]))

(def ^:private funding-modal-path
  [:funding-ui :modal])

(def ^:private withdraw-min-usdc
  5)

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- parse-num
  [value]
  (trading-domain/parse-num value))

(defn- finite-number?
  [value]
  (and (number? value)
       (js/isFinite value)
       (not (js/isNaN value))))

(defn- clamp
  [value min-value max-value]
  (cond
    (< value min-value) min-value
    (> value max-value) max-value
    :else value))

(defn- amount->text
  [value]
  (if (finite-number? value)
    (trading-domain/number->clean-string (max 0 value) 6)
    "0"))

(defn- parse-input-amount
  [value]
  (let [parsed (parse-num (or value ""))]
    (when (finite-number? parsed)
      (max 0 parsed))))

(defn- normalize-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (string? text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn- wallet-address
  [state]
  (normalize-address (get-in state [:wallet :address])))

(defn- normalize-mode
  [value]
  (let [mode (cond
               (keyword? value) value
               (string? value) (some-> value str/trim str/lower-case keyword)
               :else nil)]
    (cond
      (= :deposit mode) :deposit
      (= :transfer mode) :transfer
      (= :withdraw mode) :withdraw
      (= :legacy mode) :legacy
      :else nil)))

(defn default-funding-modal-state
  []
  {:open? false
   :mode nil
   :legacy-kind nil
   :amount-input ""
   :to-perp? true
   :destination-input ""
   :submitting? false
   :error nil})

(defn- modal-state
  [state]
  (merge (default-funding-modal-state)
         (if (map? (get-in state funding-modal-path))
           (get-in state funding-modal-path)
           {})))

(defn modal-open?
  [state]
  (true? (:open? (modal-state state))))

(defn- usdc-coin?
  [coin]
  (and (string? coin)
       (str/starts-with? (str/upper-case (str/trim coin)) "USDC")))

(defn- balance-row-available
  [row]
  (when (map? row)
    (let [available-direct (or (parse-num (:available row))
                               (parse-num (:availableBalance row))
                               (parse-num (:free row)))
          total (or (parse-num (:total row))
                    (parse-num (:totalBalance row)))
          hold (parse-num (:hold row))
          derived (cond
                    (finite-number? total)
                    (if (finite-number? hold)
                      (- total hold)
                      total)

                    :else nil)
          available (or available-direct derived)]
      (when (finite-number? available)
        (max 0 available)))))

(defn- spot-usdc-available
  [state]
  (some (fn [row]
          (when (usdc-coin? (:coin row))
            (balance-row-available row)))
        (get-in state [:spot :clearinghouse-state :balances])))

(defn- summary-derived-withdrawable
  [summary]
  (let [account-value (parse-num (:accountValue summary))
        margin-used (parse-num (:totalMarginUsed summary))]
    (when (and (finite-number? account-value)
               (finite-number? margin-used))
      (max 0 (- account-value margin-used)))))

(defn- perps-withdrawable
  [state]
  (let [clearinghouse-state (or (get-in state [:webdata2 :clearinghouseState]) {})
        direct (some parse-num
                     [(:withdrawable clearinghouse-state)
                      (:withdrawableUsd clearinghouse-state)
                      (:withdrawableUSDC clearinghouse-state)
                      (:availableToWithdraw clearinghouse-state)
                      (:availableToWithdrawUsd clearinghouse-state)
                      (:availableToWithdrawUSDC clearinghouse-state)])
        summary (or (:marginSummary clearinghouse-state)
                    (:crossMarginSummary clearinghouse-state)
                    {})
        derived (summary-derived-withdrawable summary)
        value (or direct derived)]
    (if (finite-number? value)
      (max 0 value)
      0)))

(defn- transfer-max-amount
  [state {:keys [to-perp?]}]
  (if (true? to-perp?)
    (or (spot-usdc-available state) 0)
    (perps-withdrawable state)))

(defn- withdraw-max-amount
  [state]
  (perps-withdrawable state))

(defn- format-usdc-display
  [value]
  (.toLocaleString (js/Number. (max 0 (or (parse-num value) 0)))
                   "en-US"
                   #js {:minimumFractionDigits 2
                        :maximumFractionDigits 2}))

(defn- format-usdc-input
  [value]
  (amount->text (max 0 (or (parse-num value) 0))))

(defn- transfer-preview
  [state modal]
  (let [amount (parse-input-amount (:amount-input modal))
        to-perp? (true? (:to-perp? modal))
        max-amount (transfer-max-amount state modal)]
    (cond
      (not (finite-number? max-amount))
      {:ok? false
       :display-message "Unable to determine transfer balance."}

      (<= max-amount 0)
      {:ok? false
       :display-message (if to-perp?
                          "No spot USDC available to transfer."
                          "No perps balance available to transfer.")}

      (not (finite-number? amount))
      {:ok? false
       :display-message "Enter a valid amount."}

      (<= amount 0)
      {:ok? false
       :display-message "Enter an amount greater than 0."}

      (> amount max-amount)
      {:ok? false
       :display-message "Amount exceeds available balance."}

      :else
      {:ok? true
       :request {:action {:type "usdClassTransfer"
                          :amount (amount->text amount)
                          :toPerp to-perp?}}})))

(defn- withdraw-preview
  [state modal]
  (let [amount (parse-input-amount (:amount-input modal))
        destination (normalize-address (:destination-input modal))
        max-amount (withdraw-max-amount state)]
    (cond
      (nil? destination)
      {:ok? false
       :display-message "Enter a valid destination address."}

      (<= max-amount 0)
      {:ok? false
       :display-message "No withdrawable balance available."}

      (not (finite-number? amount))
      {:ok? false
       :display-message "Enter a valid amount."}

      (< amount withdraw-min-usdc)
      {:ok? false
       :display-message (str "Minimum withdrawal is " withdraw-min-usdc " USDC.")}

      (> amount max-amount)
      {:ok? false
       :display-message "Amount exceeds withdrawable balance."}

      :else
      {:ok? true
       :request {:action {:type "withdraw3"
                          :amount (amount->text amount)
                          :destination destination}}})))

(defn- preview
  [state modal]
  (case (normalize-mode (:mode modal))
    :transfer (transfer-preview state modal)
    :withdraw (withdraw-preview state modal)
    {:ok? false
     :display-message "Funding action unavailable."}))

(defn funding-modal-view-model
  [state]
  (let [modal (modal-state state)
        mode (normalize-mode (:mode modal))
        preview-result (preview state modal)
        preview-ok? (:ok? preview-result)
        transfer-max (transfer-max-amount state modal)
        withdraw-max (withdraw-max-amount state)
        max-amount (case mode
                     :transfer transfer-max
                     :withdraw withdraw-max
                     0)
        error (:error modal)
        status-message (or error
                           (when (and (not preview-ok?)
                                      (seq (:display-message preview-result)))
                             (:display-message preview-result)))
        submitting? (true? (:submitting? modal))
        submit-disabled? (or submitting?
                            (not preview-ok?))
        legacy-kind (or (:legacy-kind modal) :unknown)
        title (case mode
                :deposit "Deposit"
                :transfer "Perps <-> Spot"
                :withdraw "Withdraw"
                :legacy (str/capitalize (name legacy-kind))
                "Funding")]
    {:open? (true? (:open? modal))
     :mode mode
     :legacy-kind legacy-kind
     :title title
     :amount-input (or (:amount-input modal) "")
     :to-perp? (true? (:to-perp? modal))
     :destination-input (or (:destination-input modal) "")
     :max-display (format-usdc-display max-amount)
     :max-input (format-usdc-input max-amount)
     :submitting? submitting?
     :submit-disabled? submit-disabled?
     :preview-ok? preview-ok?
     :status-message status-message
     :submit-label (if submitting?
                     "Submitting..."
                     (case mode
                       :transfer "Transfer"
                       :withdraw "Withdraw"
                       "Confirm"))
     :min-withdraw-usdc withdraw-min-usdc}))

(defn open-funding-deposit-modal
  [state]
  (let [base (modal-state state)]
    [[:effects/save funding-modal-path
      (-> (default-funding-modal-state)
          (assoc :open? true
                 :mode :deposit
                 :legacy-kind nil
                 :destination-input (or (wallet-address state)
                                        (:destination-input base "")
                                        "")))]]))

(defn open-funding-transfer-modal
  [state]
  [[:effects/save funding-modal-path
    (-> (default-funding-modal-state)
        (assoc :open? true
               :mode :transfer
               :to-perp? true
               :destination-input (or (wallet-address state) "")))]])

(defn open-funding-withdraw-modal
  [state]
  [[:effects/save funding-modal-path
    (-> (default-funding-modal-state)
        (assoc :open? true
               :mode :withdraw
               :destination-input (or (wallet-address state) "")))]])

(defn- open-legacy-funding-modal
  [state legacy-kind]
  (let [legacy* (if (keyword? legacy-kind)
                  legacy-kind
                  (keyword (str/lower-case (str (or legacy-kind "unknown")))))]
    [[:effects/save funding-modal-path
      (-> (default-funding-modal-state)
          (assoc :open? true
                 :mode :legacy
                 :legacy-kind legacy*
                 :destination-input (or (wallet-address state) "")))]]))

(defn close-funding-modal
  [_state]
  [[:effects/save funding-modal-path (default-funding-modal-state)]])

(defn handle-funding-modal-keydown
  [state key]
  (if (= key "Escape")
    (close-funding-modal state)
    []))

(defn set-funding-modal-field
  [state path value]
  (let [modal (modal-state state)
        path* (if (vector? path) path [path])
        value* (case path*
                 [:amount-input] (str (or value ""))
                 [:destination-input] (str (or value ""))
                 value)
        next-modal (-> modal
                       (assoc-in path* value*)
                       (assoc :error nil))]
    [[:effects/save funding-modal-path next-modal]]))

(defn set-funding-transfer-direction
  [state to-perp?]
  (let [modal (modal-state state)]
    [[:effects/save funding-modal-path
      (-> modal
          (assoc :to-perp? (true? to-perp?)
                 :error nil))]]))

(defn set-funding-amount-to-max
  [state]
  (let [modal (modal-state state)
        mode (normalize-mode (:mode modal))
        max-amount (case mode
                     :transfer (transfer-max-amount state modal)
                     :withdraw (withdraw-max-amount state)
                     0)]
    [[:effects/save funding-modal-path
      (-> modal
          (assoc :amount-input (format-usdc-input max-amount)
                 :error nil))]]))

(defn submit-funding-transfer
  [state]
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
       [:effects/api-submit-funding-transfer (:request result)]])))

(defn submit-funding-withdraw
  [state]
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
       [:effects/api-submit-funding-withdraw (:request result)]])))

(defn set-funding-modal-compat
  [state modal]
  (let [mode (cond
               (keyword? modal) modal
               (string? modal) (keyword (str/lower-case (str/trim modal)))
               :else nil)]
    (case mode
      nil (close-funding-modal state)
      :deposit (open-funding-deposit-modal state)
      :withdraw (open-funding-withdraw-modal state)
      :send (open-funding-transfer-modal state)
      :transfer (open-funding-transfer-modal state)
      (open-legacy-funding-modal state mode))))
