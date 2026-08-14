(ns hyperopen.staking.account-scope
  (:require [hyperopen.account.context :as account-context]))

(def cleared-user-projections
  [[[:staking :delegator-summary] nil]
   [[:staking :delegator-summary-address] nil]
   [[:staking :delegations] []]
   [[:staking :rewards] []]
   [[:staking :history] []]
   [[:staking :spot-state] nil]
   [[:staking :loading :delegator-summary] false]
   [[:staking :loading :delegations] false]
   [[:staking :loading :rewards] false]
   [[:staking :loading :history] false]
   [[:staking :loading :spot-state] false]
   [[:staking :errors :delegator-summary] nil]
   [[:staking :errors :delegations] nil]
   [[:staking :errors :rewards] nil]
   [[:staking :errors :history] nil]
   [[:staking :errors :spot-state] nil]
   [[:staking :loaded-for :delegator-summary] nil]
   [[:staking :loaded-for :delegations] nil]
   [[:staking :loaded-for :rewards] nil]
   [[:staking :loaded-for :history] nil]
   [[:staking :loaded-for :spot-state] nil]])

(defn current-address?
  [state address]
  (and (= address (get-in state [:staking :account-address]))
       (= address (account-context/native-staking-account-address state))))

(defn delegator-summary-address
  "Which account the delegator summary currently in state describes.

  Two paths write that summary. Account bootstrap fetches it off-route for the
  effective (possibly subaccount) address and stamps
  `[:staking :delegator-summary-address]`. The /staking route fetches it for the
  native staking address, records `[:staking :loaded-for :delegator-summary]`,
  and clears the stamp. Reading the stamp first therefore always yields whichever
  path wrote most recently. Returns nil when no summary has been loaded."
  [state]
  (or (get-in state [:staking :delegator-summary-address])
      (get-in state [:staking :loaded-for :delegator-summary])))

(defn delegator-summary-describes?
  "True when the loaded delegator summary is about `address`. Surfaces outside
  /staking must gate staking figures on this, because the two fetch paths can
  disagree about which account they described."
  [state address]
  (let [summary-address (account-context/normalize-address
                         (delegator-summary-address state))
        address* (account-context/normalize-address address)]
    (boolean (and (seq summary-address)
                  (seq address*)
                  (= summary-address address*)))))

(defn resource-ready?
  [state resource]
  (let [address (account-context/native-staking-account-address state)]
    (and (current-address? state address)
         (= address (get-in state [:staking :loaded-for resource])))))

(defn delegation-row-by-validator
  [state validator]
  (let [validator* (account-context/normalize-address validator)]
    (some (fn [row]
            (when (= validator* (account-context/normalize-address (:validator row)))
              row))
          (get-in state [:staking :delegations]))))

(defn delegation-locked-after?
  [delegation now-ms]
  (let [locked-until-timestamp (:locked-until-timestamp delegation)]
    (and (number? locked-until-timestamp)
         (js/isFinite locked-until-timestamp)
         (number? now-ms)
         (js/isFinite now-ms)
         (> locked-until-timestamp now-ms))))

(defn mutations-blocked-message
  [state]
  (cond
    (account-context/spectate-mode-active? state)
    account-context/spectate-mode-read-only-message

    (account-context/trader-portfolio-route-active? state)
    account-context/trader-portfolio-read-only-message

    :else
    nil))
