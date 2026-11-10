(ns hyperopen.outcomes.application.merge-actions
  "Merge pairs: one Yes share and one No share of the same outcome go back to
   one quote token. The exchange action is the signed `userOutcome` L1 action
   (Hyperliquid exchange endpoint, \"Merge outcome\"):

     {\"type\": \"userOutcome\",
      \"mergeOutcome\": {\"outcome\": Number, \"amount\": String | null}}

   `outcome` is the outcome identifier, `amount` the number of pairs (X Yes +
   X No shares become X quote tokens; null means the maximum). Pure: this
   namespace builds the payload and decides the effects; signing and sending
   live in the effect interpreter."
  (:require [hyperopen.account.context :as account-context]))

(defn- whole-number
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/parseFloat value)
            :else js/NaN)]
    (when (and (number? n) (js/isFinite n) (== n (js/Math.floor n)))
      (js/Math.floor n))))

(defn merge-outcome-action
  "The exchange action for merging `pairs` whole pairs of `outcome-id`, or nil
   when either is not a positive whole number."
  [{:keys [outcome-id pairs]}]
  (let [outcome (whole-number outcome-id)
        amount (whole-number pairs)]
    (when (and (some? outcome) (>= outcome 0)
               (some? amount) (pos? amount))
      {:type "userOutcome"
       :mergeOutcome {:outcome outcome
                      :amount (str amount)}})))

(defn- guard-error
  [message]
  [[:effects/save [:outcome-ticket :merge :error] message]])

(defn submit-outcome-merge
  "One click on the Merge ticket's button: mark the ticket submitting, then
   the one heavy effect that signs and sends the action."
  [state {:keys [pairs] :as args}]
  (let [action (merge-outcome-action args)
        blocked (account-context/mutations-blocked-message state)]
    (cond
      (seq blocked) (guard-error blocked)
      (nil? (account-context/owner-address state)) (guard-error "Connect your wallet first.")
      (nil? action) (guard-error "Enter a number of pairs to merge.")
      :else
      [[:effects/save-many [[[:outcome-ticket :merge :submitting?] true]
                            [[:outcome-ticket :merge :error] nil]]]
       [:effects/api-submit-outcome-merge {:kind :merge
                                           :pairs (whole-number pairs)
                                           :action action}]])))
