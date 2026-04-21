(ns hyperopen.api.projections.orders
  (:require [hyperopen.api.errors :as api-errors]
            [hyperopen.order.cancel-guard :as cancel-guard]))

(defn- normalized-error
  [err]
  (api-errors/normalize-error err))

(defn apply-open-orders-success
  [state dex rows]
  (let [rows* (cancel-guard/prune-open-order-payload
               rows
               (cancel-guard/state-guard-entries state))
        state* (assoc-in state [:orders :open-orders-hydrated?] true)]
    (if (and dex (not= dex ""))
      (assoc-in state* [:orders :open-orders-snapshot-by-dex dex] rows*)
      (assoc-in state* [:orders :open-orders-snapshot] rows*))))

(defn apply-open-orders-error
  [state err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:orders :open-error] message)
        (assoc-in [:orders :open-error-category] category))))

(defn apply-user-fills-success
  [state rows]
  (assoc-in state [:orders :fills] rows))

(defn apply-user-fills-error
  [state err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:orders :fills-error] message)
        (assoc-in [:orders :fills-error-category] category))))
