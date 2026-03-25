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
           withdraw-step-amount-entry?]}]
  (or error
      (when (and (not preview-ok?)
                 (seq preview-message)
                 (or (not= mode :deposit)
                     deposit-step-amount-entry?)
                 (or (not= mode :withdraw)
                     withdraw-step-amount-entry?))
        preview-message)))

(defn- show-status-message?
  [{:keys [legacy? deposit? withdraw? withdraw-step-amount-entry?]} status-message]
  (boolean
   (and (seq status-message)
        (not legacy?)
        (not deposit?)
        (or (not withdraw?)
            withdraw-step-amount-entry?))))

(defn- submit-disabled?
  [{:keys [submitting?
           deposit?
           withdraw?
           deposit-step-amount-entry?
           withdraw-step-amount-entry?
           preview-ok?]}]
  (or submitting?
      (and deposit?
           (not deposit-step-amount-entry?))
      (and withdraw?
           (not withdraw-step-amount-entry?))
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
      :transfer "Perps <-> Spot"
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
  [{:keys [submitting? mode]}]
  (if submitting?
    "Submitting..."
    (case mode
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
           selected-withdraw-asset]}]
  (case mode
    :deposit (deposit-content-kind deposit-step
                                   selected-deposit-asset
                                   selected-deposit-flow-kind
                                   selected-deposit-implemented?)
    :send :send/form
    :transfer :transfer/form
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
