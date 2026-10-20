(ns hyperopen.api.projections.borrow-lend
  "State projections for HyperCore borrow/lend info requests. Payloads arrive
  already normalized by `hyperopen.borrow-lend.normalize`."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.errors :as api-errors]))

(defn- begin
  [state k]
  (-> state
      (assoc-in [:borrow-lend :loading k] true)
      (assoc-in [:borrow-lend :errors k] nil)))

(defn- success
  [state k path value]
  (-> state
      (assoc-in path value)
      (assoc-in [:borrow-lend :loading k] false)
      (assoc-in [:borrow-lend :errors k] nil)
      (assoc-in [:borrow-lend :loaded-at-ms k] (.now js/Date))))

(defn- failure
  [state k err]
  (let [{:keys [message]} (api-errors/normalize-error err)]
    (-> state
        (assoc-in [:borrow-lend :loading k] false)
        (assoc-in [:borrow-lend :errors k] message))))

(defn begin-borrow-lend-reserves-load [state] (begin state :reserves))

(defn apply-borrow-lend-reserves-success
  [state reserves]
  (success state :reserves [:borrow-lend :reserves]
           (if (sequential? reserves) (vec reserves) [])))

(defn apply-borrow-lend-reserves-error [state err] (failure state :reserves err))

(defn begin-borrow-lend-user-state-load [state] (begin state :user-state))

(defn apply-borrow-lend-user-state-success
  "Stores the user state together with the address it was fetched for, so a
  view never shows one account's positions under another account."
  [state address user-state]
  (-> state
      (success :user-state [:borrow-lend :user-state]
               (when (map? user-state) user-state))
      (assoc-in [:borrow-lend :user-address]
                (account-context/normalize-address address))))

(defn apply-borrow-lend-user-state-error [state err] (failure state :user-state err))

(defn begin-borrow-lend-interest-load [state] (begin state :interest))

(defn apply-borrow-lend-interest-success
  [state address rows]
  (-> state
      (success :interest [:borrow-lend :interest]
               (if (sequential? rows) (vec rows) []))
      (assoc-in [:borrow-lend :interest-address]
                (account-context/normalize-address address))))

(defn apply-borrow-lend-interest-error [state err] (failure state :interest err))
