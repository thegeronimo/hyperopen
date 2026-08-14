(ns hyperopen.api.projections.staking
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.errors :as api-errors]))

(defn- normalized-error
  [err]
  (api-errors/normalize-error err))

(defn begin-staking-validator-summaries-load
  [state]
  (-> state
      (assoc-in [:staking :loading :validator-summaries] true)
      (assoc-in [:staking :errors :validator-summaries] nil)))

(defn apply-staking-validator-summaries-success
  [state rows]
  (-> state
      (assoc-in [:staking :validator-summaries]
                (if (sequential? rows) (vec rows) []))
      (assoc-in [:staking :loading :validator-summaries] false)
      (assoc-in [:staking :errors :validator-summaries] nil)
      (assoc-in [:staking :loaded-at-ms :validator-summaries] (.now js/Date))))

(defn apply-staking-validator-summaries-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (-> state
        (assoc-in [:staking :loading :validator-summaries] false)
        (assoc-in [:staking :errors :validator-summaries] message))))

(defn begin-staking-spot-state-load
  [state]
  (-> state
      (assoc-in [:staking :loading :spot-state] true)
      (assoc-in [:staking :errors :spot-state] nil)))

(defn apply-staking-spot-state-success
  [state spot-state]
  (-> state
      (assoc-in [:staking :spot-state] (when (map? spot-state) spot-state))
      (assoc-in [:staking :loading :spot-state] false)
      (assoc-in [:staking :errors :spot-state] nil)
      (assoc-in [:staking :loaded-at-ms :spot-state] (.now js/Date))))

(defn apply-staking-spot-state-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (-> state
        (assoc-in [:staking :loading :spot-state] false)
        (assoc-in [:staking :errors :spot-state] message))))

(defn begin-staking-delegator-summary-load
  [state]
  (-> state
      (assoc-in [:staking :loading :delegator-summary] true)
      (assoc-in [:staking :errors :delegator-summary] nil)))

(defn apply-staking-delegator-summary-success
  "The delegator summary is written by two paths with different identities: the
  /staking route loads it for the native staking (owner) address and records
  `[:staking :loaded-for :delegator-summary]`, while account bootstrap loads it
  for the effective (possibly subaccount) address off-route and records nothing.

  The three-arity is the bootstrap path and stamps the address it fetched for.
  The two-arity is the route path, which clears that stamp so the freshest of
  the two identity records always wins — see
  `hyperopen.staking.account-scope/delegator-summary-address`."
  ([state summary]
   (apply-staking-delegator-summary-success state nil summary))
  ([state address summary]
   (-> state
       (assoc-in [:staking :delegator-summary]
                 (when (map? summary)
                   summary))
       (assoc-in [:staking :delegator-summary-address]
                 (account-context/normalize-address address))
       (assoc-in [:staking :loading :delegator-summary] false)
       (assoc-in [:staking :errors :delegator-summary] nil)
       (assoc-in [:staking :loaded-at-ms :delegator-summary] (.now js/Date)))))

(defn apply-staking-delegator-summary-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (-> state
        (assoc-in [:staking :loading :delegator-summary] false)
        (assoc-in [:staking :errors :delegator-summary] message))))

(defn begin-staking-delegations-load
  [state]
  (-> state
      (assoc-in [:staking :loading :delegations] true)
      (assoc-in [:staking :errors :delegations] nil)))

(defn apply-staking-delegations-success
  [state rows]
  (-> state
      (assoc-in [:staking :delegations]
                (if (sequential? rows) (vec rows) []))
      (assoc-in [:staking :loading :delegations] false)
      (assoc-in [:staking :errors :delegations] nil)
      (assoc-in [:staking :loaded-at-ms :delegations] (.now js/Date))))

(defn apply-staking-delegations-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (-> state
        (assoc-in [:staking :loading :delegations] false)
        (assoc-in [:staking :errors :delegations] message))))

(defn begin-staking-rewards-load
  [state]
  (-> state
      (assoc-in [:staking :loading :rewards] true)
      (assoc-in [:staking :errors :rewards] nil)))

(defn apply-staking-rewards-success
  [state rows]
  (-> state
      (assoc-in [:staking :rewards]
                (if (sequential? rows) (vec rows) []))
      (assoc-in [:staking :loading :rewards] false)
      (assoc-in [:staking :errors :rewards] nil)
      (assoc-in [:staking :loaded-at-ms :rewards] (.now js/Date))))

(defn apply-staking-rewards-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (-> state
        (assoc-in [:staking :loading :rewards] false)
        (assoc-in [:staking :errors :rewards] message))))

(defn begin-staking-history-load
  [state]
  (-> state
      (assoc-in [:staking :loading :history] true)
      (assoc-in [:staking :errors :history] nil)))

(defn apply-staking-history-success
  [state rows]
  (-> state
      (assoc-in [:staking :history]
                (if (sequential? rows) (vec rows) []))
      (assoc-in [:staking :loading :history] false)
      (assoc-in [:staking :errors :history] nil)
      (assoc-in [:staking :loaded-at-ms :history] (.now js/Date))))

(defn apply-staking-history-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (-> state
        (assoc-in [:staking :loading :history] false)
        (assoc-in [:staking :errors :history] message))))
