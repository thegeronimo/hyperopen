(ns hyperopen.funding.application.modal-vm.presentation
  (:require [clojure.string :as str]))

(defn- deposit-content-kind
  [deposit-step selected-asset flow-kind supported?]
  (cond
    (not= deposit-step :amount-entry) :deposit/select
    (nil? selected-asset) :deposit/missing-asset
    (not supported?) :deposit/unavailable
    (= flow-kind :hyperunit-address) :deposit/address
    :else :deposit/amount))

(defn- summary-row
  [label value]
  {:label label
   :value value})

(defn- status-message
  [{:keys [error
           preview-ok?
           preview-message
           mode
           deposit-step-amount-entry?
           withdraw-step-amount-entry?
           transfer-blocked]}]
  (or error
      (when (and (not preview-ok?)
                 (seq preview-message)
                 ;; A blocked Transfer explains itself in its own card.
                 (not (and (= mode :transfer) transfer-blocked))
                 (or (not= mode :deposit)
                     deposit-step-amount-entry?)
                 (or (not= mode :withdraw)
                     withdraw-step-amount-entry?))
        preview-message)))

(def ^:private blank-amount-message
  "The legacy Perps <-> Spot preview's answer to an empty amount."
  "Enter a valid amount.")

(defn- transfer-message
  "The Transfer form's own message: why the draft can't be submitted (or
   the last submit error), rendered in the form next to its submit. nil
   outside Transfer mode and while a HyperEVM run shows (its views explain
   themselves), so a finished move never shows the draft checked against
   balances the move itself just changed.

   An amount not typed yet is not an error on any route: the legacy
   preview's \"Enter a valid amount.\" for a blank field is left out here
   (the preview itself is unchanged), as the HyperEVM routes already say
   nothing, so a freshly opened form shows no red error and no
   `aria-invalid`."
  [{:keys [mode transfer-evm error amount-input] :as ctx}]
  (when (and (= mode :transfer) (nil? transfer-evm))
    (let [message (status-message ctx)]
      (when-not (and (nil? error)
                     (str/blank? (str amount-input))
                     (= blank-amount-message message))
        message))))

(defn- show-status-message?
  "The shell's red status line. Transfer renders its message in the form
   (`transfer-message`), above its sticky submit, so the shell never shows
   one there."
  [{:keys [legacy? deposit? withdraw? withdraw-step-amount-entry? mode]} status-message]
  (boolean
   (and (seq status-message)
        (not legacy?)
        (not deposit?)
        (not= mode :transfer)
        (or (not withdraw?)
            withdraw-step-amount-entry?))))

(defn- submit-disabled?
  [{:keys [submitting?
           deposit?
           withdraw?
           deposit-step-amount-entry?
           withdraw-step-amount-entry?
           preview-ok?
           mode
           transfer-evm]}]
  (or submitting?
      (and deposit?
           (not deposit-step-amount-entry?))
      (and withdraw?
           (not withdraw-step-amount-entry?))
      ;; A HyperEVM run owns the modal until it ends; never resubmit from it.
      (and (= mode :transfer) (some? transfer-evm))
      (not preview-ok?)))

(defn- title
  [{:keys [mode
           deposit?
           withdraw?
           deposit-step-amount-entry?
           withdraw-step-amount-entry?
           selected-deposit-symbol
           selected-withdraw-symbol
           legacy-kind]}]
  (cond
    (and deposit?
         deposit-step-amount-entry?
         (seq selected-deposit-symbol))
    (str "Deposit " selected-deposit-symbol)

    (and withdraw?
         withdraw-step-amount-entry?
         (seq selected-withdraw-symbol))
    (str "Withdraw " selected-withdraw-symbol)

    :else
    (case mode
      :deposit "Deposit"
      :send "Send Tokens"
      :transfer "Transfer"
      :withdraw "Withdraw"
      :legacy (str/capitalize (name legacy-kind))
      "Funding")))

(defn- deposit-submit-label
  [{:keys [submitting?
           deposit?
           selected-deposit-flow-kind
           generated-address
           preview-ok?
           selected-deposit-implemented?
           preview-message]}]
  (if submitting?
    (if (and deposit?
             (= selected-deposit-flow-kind :hyperunit-address))
      "Generating..."
      "Submitting...")
    (if preview-ok?
      (if (and deposit?
               (= selected-deposit-flow-kind :hyperunit-address))
        (if (seq generated-address)
          "Regenerate address"
          "Generate address")
        "Deposit")
      (if (and deposit?
               (not selected-deposit-implemented?))
        "Deposit unavailable"
        (or preview-message "Enter a valid amount")))))

(defn- submit-label
  [{:keys [submitting? mode transfer-submit-label]}]
  (cond
    (and (= mode :transfer) (seq transfer-submit-label)) transfer-submit-label
    submitting? "Submitting..."
    :else (case mode
            :send "Send"
            :transfer "Transfer"
            :withdraw "Withdraw"
            "Confirm")))

(defn- deposit-unsupported-detail
  [selected-deposit-flow-kind]
  (case selected-deposit-flow-kind
    :route "Route-based bridge/swap flow will be implemented in the next milestone."
    :hyperunit-address "Address-based deposit instructions will be implemented in the next milestone."
    "Deposit flow details are unavailable."))

(defn- deposit-summary-rows
  [deposit-min-amount
   selected-deposit-symbol
   deposit-estimated-time
   deposit-network-fee]
  [(summary-row "Minimum deposit"
                (str deposit-min-amount
                     " "
                     selected-deposit-symbol))
   (summary-row "Estimated time" deposit-estimated-time)
   (summary-row "Network fee" deposit-network-fee)])

(defn- withdraw-summary-rows
  [withdraw-min-amount
   selected-withdraw-symbol
   withdraw-estimated-time
   withdraw-network-fee]
  (cond-> []
    (and (number? withdraw-min-amount)
         (pos? withdraw-min-amount))
    (conj (summary-row "Minimum withdrawal"
                       (str withdraw-min-amount
                            " "
                            selected-withdraw-symbol)))
    true
    (conj (summary-row "Estimated time" withdraw-estimated-time)
          (summary-row "Network fee" withdraw-network-fee))))

(defn- content-kind
  [{:keys [mode
           deposit-step
           withdraw-step
           selected-deposit-asset
           selected-deposit-flow-kind
           selected-deposit-implemented?
           selected-withdraw-asset
           transfer-content-kind]}]
  (case mode
    :deposit (deposit-content-kind deposit-step
                                   selected-deposit-asset
                                   selected-deposit-flow-kind
                                   selected-deposit-implemented?)
    :send :send/form
    :transfer (or transfer-content-kind :transfer/form)
    :withdraw (if (and (= withdraw-step :amount-entry)
                       selected-withdraw-asset)
                :withdraw/detail
                :withdraw/select)
    :legacy :unsupported/workflow
    :unknown))

(defn with-presentation-context
  [ctx]
  (let [status-message (status-message ctx)
        submit-disabled? (submit-disabled? ctx)
        deposit-summary-rows (deposit-summary-rows (:deposit-min-amount ctx)
                                                   (:selected-deposit-symbol ctx)
                                                   (:deposit-estimated-time ctx)
                                                   (:deposit-network-fee ctx))
        withdraw-summary-rows (withdraw-summary-rows (:withdraw-min-amount ctx)
                                                     (:selected-withdraw-symbol ctx)
                                                     (:withdraw-estimated-time ctx)
                                                     (:withdraw-network-fee ctx))]
    (assoc ctx
           :status-message status-message
           :show-status-message? (show-status-message? ctx status-message)
           :transfer-message (transfer-message ctx)
           :submit-disabled? submit-disabled?
           :title (title ctx)
           :deposit-submit-label (deposit-submit-label ctx)
           :submit-label (submit-label ctx)
           :content-kind (content-kind ctx)
           :deposit-unsupported-detail (deposit-unsupported-detail
                                        (:selected-deposit-flow-kind ctx))
           :deposit-summary-rows deposit-summary-rows
           :withdraw-summary-rows withdraw-summary-rows)))

(defn feedback-model
  [{:keys [status-message show-status-message?]}]
  {:message status-message
   :visible? show-status-message?
   :tone :error})
