(ns hyperopen.api.projections.borrow-lend
  "State projections for HyperCore borrow/lend info requests. Payloads arrive
  already normalized by `hyperopen.borrow-lend.normalize`."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.errors :as api-errors]))

(defn- total-usd
  "Sum of `leg` (:supplied or :borrowed) across the stored account's positions
  at oracle prices."
  [state leg]
  (let [px-by-token (into {}
                          (map (juxt :token :oracle-px))
                          (get-in state [:borrow-lend :reserves]))]
    (reduce (fn [acc position]
              (let [amount (get position leg)
                    px (get px-by-token (:token position))]
                (if (and (number? amount) (number? px)) (+ acc (* amount px)) acc)))
            0
            (get-in state [:borrow-lend :user-state :positions]))))

(defn- with-total-supplied
  "Supplied value (stablecoins and collateral) and borrowed value, so account
  totals can count lending net of debt: supplied funds leave the spot balance,
  and a borrow adds to it."
  [state]
  (-> state
      (assoc-in [:borrow-lend :total-supplied-usd] (total-usd state :supplied))
      (assoc-in [:borrow-lend :total-borrowed-usd] (total-usd state :borrowed))))

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
  (-> (success state :reserves [:borrow-lend :reserves]
               (if (sequential? reserves) (vec reserves) []))
      with-total-supplied))

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
                (account-context/normalize-address address))
      with-total-supplied))

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
