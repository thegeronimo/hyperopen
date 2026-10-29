(ns hyperopen.api.projections.orders
  (:require [hyperopen.account.chart-fill-history :as chart-fill-history]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.errors :as api-errors]
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
  "With `requested-address`, also seeds the chart's own uncapped fill history
  (see chart-fill-history) — but only when the request was for the account
  that is effective now: that history is a union of pages, so a late response
  for a previous account would otherwise stay mixed in for the session."
  ([state rows]
   (assoc-in state [:orders :fills] rows))
  ([state requested-address rows]
   (let [effective (account-context/effective-account-address state)]
     (cond-> (assoc-in state [:orders :fills] rows)
       (and (some? (chart-fill-history/normalize-address requested-address))
            (= (chart-fill-history/normalize-address requested-address)
               (chart-fill-history/normalize-address effective)))
       (chart-fill-history/apply-snapshot effective rows {:rest-page? true})))))

(defn apply-user-fills-error
  [state err]
  (let [{:keys [message category]} (normalized-error err)]
    (-> state
        (assoc-in [:orders :fills-error] message)
        (assoc-in [:orders :fills-error-category] category))))
