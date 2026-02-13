(ns hyperopen.api.gateway.orders
  (:require [hyperopen.api.endpoints.orders :as order-endpoints]
            [hyperopen.api.fetch-compat :as fetch-compat]))

(defn request-frontend-open-orders!
  [{:keys [post-info!]} address & [dex-or-opts maybe-opts]]
  (let [[dex opts] (if (map? dex-or-opts)
                     [nil dex-or-opts]
                     [dex-or-opts (or maybe-opts {})])]
    (order-endpoints/request-frontend-open-orders! post-info! address dex opts)))

(defn fetch-frontend-open-orders!
  [{:keys [log-fn
           request-frontend-open-orders!
           apply-open-orders-success
           apply-open-orders-error]}
   store
   address
   & [dex-or-opts maybe-opts]]
  (let [[dex opts] (if (map? dex-or-opts)
                     [nil dex-or-opts]
                     [dex-or-opts (or maybe-opts {})])]
    (fetch-compat/fetch-frontend-open-orders!
     {:log-fn log-fn
      :request-frontend-open-orders! request-frontend-open-orders!
      :apply-open-orders-success apply-open-orders-success
      :apply-open-orders-error apply-open-orders-error}
     store
     address
     dex
     opts)))

(defn request-user-fills!
  [{:keys [post-info!]} address opts]
  (order-endpoints/request-user-fills! post-info! address opts))

(defn fetch-user-fills!
  [{:keys [log-fn
           request-user-fills!
           apply-user-fills-success
           apply-user-fills-error]}
   store
   address
   opts]
  (fetch-compat/fetch-user-fills!
   {:log-fn log-fn
    :request-user-fills! request-user-fills!
    :apply-user-fills-success apply-user-fills-success
    :apply-user-fills-error apply-user-fills-error}
   store
   address
   opts))

(defn request-historical-orders-data!
  [{:keys [log-fn post-info!]}
   address
   opts]
  (fetch-compat/fetch-historical-orders!
   {:log-fn log-fn
    :request-historical-orders! (fn [requested-address request-opts]
                                  (order-endpoints/request-historical-orders! post-info!
                                                                             requested-address
                                                                             request-opts))}
   address
   opts))

(defn fetch-historical-orders!
  "Deprecated compatibility wrapper; prefer `request-historical-orders-data!`."
  [deps
   address
   opts]
  (request-historical-orders-data! deps address opts))

(defn request-historical-orders!
  [{:keys [request-historical-orders-data!
           fetch-historical-orders!]}
   address
   opts]
  (if request-historical-orders-data!
    (request-historical-orders-data! address opts)
    (fetch-historical-orders! nil address opts)))
