(ns hyperopen.api.default.orders
  (:require [hyperopen.api.compat :as api-compat]
            [hyperopen.api.gateway.orders :as order-gateway]))

(defn request-frontend-open-orders!
  [{:keys [post-info!]} address opts]
  (order-gateway/request-frontend-open-orders! {:post-info! post-info!} address opts))

(defn fetch-frontend-open-orders!
  [{:keys [log-fn request-frontend-open-orders!]} store address opts]
  (api-compat/fetch-frontend-open-orders!
   {:log-fn log-fn
    :request-frontend-open-orders! request-frontend-open-orders!}
   store
   address
   opts))

(defn request-user-fills!
  [{:keys [post-info!]} address opts]
  (order-gateway/request-user-fills! {:post-info! post-info!} address opts))

(defn fetch-user-fills!
  [{:keys [log-fn request-user-fills!]} store address opts]
  (api-compat/fetch-user-fills!
   {:log-fn log-fn
    :request-user-fills! request-user-fills!}
   store
   address
   opts))

(defn request-historical-orders-data!
  [{:keys [log-fn post-info!]} address opts]
  (api-compat/fetch-historical-orders!
   {:log-fn log-fn
    :post-info! post-info!}
   address
   opts))

(defn request-historical-orders!
  [{:keys [request-historical-orders-data!]} address opts]
  (order-gateway/request-historical-orders!
   {:request-historical-orders-data! request-historical-orders-data!}
   address
   opts))
