(ns hyperopen.api.projections.vaults.user-equities
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.errors :as api-errors]))

(defn- normalized-error
  [err]
  (api-errors/normalize-error err))

(defn- normalize-vault-address
  [value]
  (some-> value str str/trim str/lower-case))

(defn- next-request-id
  [state]
  (inc (or (get-in state [:vaults :user-equities-request-sequence]) 0)))

(defn begin-load
  ([state]
   (begin-load state nil))
  ([state requested-address]
   (begin-load state requested-address (next-request-id state)))
  ([state requested-address request-id]
   (let [request-id* (or request-id (next-request-id state))]
     (-> state
         (assoc-in [:vaults :user-equities-request-sequence] request-id*)
         (assoc-in [:vaults :user-equities-request-id] request-id*)
         (assoc-in [:vaults :loading :user-equities?] true)
         (assoc-in [:vaults :loading :user-equities-for-address]
                   (account-context/normalize-address requested-address))
         (assoc-in [:vaults :errors :user-equities] nil)
         (assoc-in [:vaults :user-equities-error-for-address] nil)))))

(defn apply-success
  ([state rows]
   (apply-success state nil (get-in state [:vaults :user-equities-request-id]) rows))
  ([state requested-address rows]
   (apply-success state requested-address
                  (get-in state [:vaults :user-equities-request-id])
                  rows))
  ([state requested-address request-id rows]
   (if (= request-id (get-in state [:vaults :user-equities-request-id]))
     (let [rows* (when (sequential? rows) (vec rows))
           requested-address* (account-context/normalize-address requested-address)
           by-address (reduce (fn [acc row]
                                (if-let [address (normalize-vault-address (:vault-address row))]
                                  (assoc acc address row)
                                  acc))
                              {}
                              rows*)]
       (-> state
           (assoc-in [:vaults :user-equities] rows*)
           (assoc-in [:vaults :user-equity-by-address] by-address)
           (assoc-in [:vaults :user-equities-for-address] requested-address*)
           (assoc-in [:vaults :loading :user-equities?] false)
           (assoc-in [:vaults :loading :user-equities-for-address] nil)
           (assoc-in [:vaults :errors :user-equities] nil)
           (assoc-in [:vaults :user-equities-error-for-address] nil)
           (assoc-in [:vaults :loaded-at-ms :user-equities] (.now js/Date))))
     state)))

(defn apply-error
  ([state err]
   (apply-error state nil (get-in state [:vaults :user-equities-request-id]) err))
  ([state requested-address err]
   (apply-error state requested-address
                (get-in state [:vaults :user-equities-request-id])
                err))
  ([state requested-address request-id err]
   (if (= request-id (get-in state [:vaults :user-equities-request-id]))
     (let [{:keys [message]} (normalized-error err)
           requested-address* (account-context/normalize-address requested-address)]
       (-> state
           (assoc-in [:vaults :loading :user-equities?] false)
           (assoc-in [:vaults :loading :user-equities-for-address] nil)
           (assoc-in [:vaults :errors :user-equities] message)
           (assoc-in [:vaults :user-equities-error-for-address] requested-address*)))
     state)))
