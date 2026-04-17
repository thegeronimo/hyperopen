(ns hyperopen.api.projections.user-fees
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.errors :as api-errors]))

(defn begin-load
  ([state]
   (begin-load state nil))
  ([state address]
   (-> state
       (assoc-in [:portfolio :user-fees-loading?] true)
       (assoc-in [:portfolio :user-fees-loading-for-address]
                 (account-context/normalize-address address))
       (assoc-in [:portfolio :user-fees-error] nil))))

(defn apply-success
  ([state payload]
   (apply-success state nil payload))
  ([state address payload]
   (-> state
       (assoc-in [:portfolio :user-fees] payload)
       (assoc-in [:portfolio :user-fees-loading?] false)
       (assoc-in [:portfolio :user-fees-loading-for-address] nil)
       (assoc-in [:portfolio :user-fees-error] nil)
       (assoc-in [:portfolio :user-fees-loaded-at-ms] (.now js/Date))
       (assoc-in [:portfolio :user-fees-loaded-for-address]
                 (account-context/normalize-address address)))))

(defn apply-error
  ([state err]
   (apply-error state nil err))
  ([state address err]
   (let [{:keys [message]} (api-errors/normalize-error err)]
     (-> state
         (assoc-in [:portfolio :user-fees-loading?] false)
         (assoc-in [:portfolio :user-fees-loading-for-address] nil)
         (assoc-in [:portfolio :user-fees-error] message)
         (assoc-in [:portfolio :user-fees-error-for-address]
                   (account-context/normalize-address address))))))
