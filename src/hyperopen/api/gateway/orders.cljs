(ns hyperopen.api.gateway.orders
  (:require [hyperopen.api.endpoints.orders :as order-endpoints]
            [hyperopen.api.fetch-compat :as fetch-compat]))

(defn- with-dex
  [opts dex]
  (cond-> (or opts {})
    (and dex (not= dex "")) (assoc :dex dex)))

(defn request-frontend-open-orders!
  ([deps address]
   (request-frontend-open-orders! deps address {}))
  ([{:keys [post-info!]} address opts]
   (let [opts* (or opts {})
         dex (:dex opts*)
         request-opts (dissoc opts* :dex)]
     (order-endpoints/request-frontend-open-orders! post-info!
                                                    address
                                                    dex
                                                    request-opts)))
  ([deps address dex opts]
   (request-frontend-open-orders! deps address (with-dex opts dex))))

(defn fetch-frontend-open-orders!
  ([deps store address]
   (fetch-frontend-open-orders! deps store address {}))
  ([{:keys [log-fn
            request-frontend-open-orders!
            apply-open-orders-success
            apply-open-orders-error]}
    store
    address
    opts]
   (fetch-compat/fetch-frontend-open-orders!
    {:log-fn log-fn
     :request-frontend-open-orders! request-frontend-open-orders!
     :apply-open-orders-success apply-open-orders-success
     :apply-open-orders-error apply-open-orders-error}
    store
    address
    opts))
  ([deps store address dex opts]
   (fetch-frontend-open-orders! deps store address (with-dex opts dex))))

(defn request-user-fills!
  [{:keys [post-info!]} address opts]
  (order-endpoints/request-user-fills! post-info! address opts))

(defn request-user-fills-by-time!
  [{:keys [post-info!]} address window opts]
  (order-endpoints/request-user-fills-by-time! post-info! address window opts))

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
