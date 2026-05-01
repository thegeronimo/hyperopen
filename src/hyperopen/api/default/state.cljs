(ns hyperopen.api.default.state
  (:require [hyperopen.api.instance :as api-instance]
            [hyperopen.api.service :as api-service]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.platform :as platform]
            [hyperopen.telemetry :as telemetry]))

(def info-url api-instance/info-url)

(def default-info-client-config
  api-instance/default-info-client-config)

(defn make-default-api-service
  []
  (api-instance/make-default-api-service))

(defonce api-facade-state
  (atom {:service (make-default-api-service)}))

(defn now-ms []
  (platform/now-ms))

(defn active-api-service
  []
  (:service @api-facade-state))

(defn install-api-service!
  [service]
  (swap! api-facade-state assoc :service service)
  nil)

(defn configure-api-service!
  [opts]
  (let [opts* (or opts {})
        configured-info-client (if (contains? opts* :info-client-config)
                                 (merge default-info-client-config
                                        (:info-client-config opts*))
                                 default-info-client-config)
        service-opts (merge {:info-client-config configured-info-client
                             :log-fn telemetry/log!}
                            (dissoc opts* :info-client-config))]
    (install-api-service! (api-service/make-service service-opts))))

(defn reset-api-service!
  []
  (install-api-service! (make-default-api-service)))

(defn make-api
  [opts]
  (api-instance/make-api opts))

(defn normalize-funding-history-request-filters
  [filters]
  (funding-history/normalize-funding-history-filters
   filters
   (now-ms)
   funding-history/default-window-ms))

(defn api-log-fn
  []
  (api-service/log-fn (active-api-service)))

(defn get-request-stats
  []
  (api-service/get-request-stats (active-api-service)))

(defn reset-request-runtime!
  []
  (api-service/reset-service! (active-api-service)))

(defn post-info!
  ([body]
   (post-info! body {}))
  ([body opts]
   (api-service/request-info! (active-api-service) body opts))
  ([body opts attempt]
   (api-service/request-info! (active-api-service) body opts attempt)))
