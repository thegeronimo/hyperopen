(ns hyperopen.api.service
  (:require [hyperopen.api.info-client :as info-client]
            [hyperopen.api.runtime :as api-runtime]
            [hyperopen.platform :as platform]))

(def default-config
  {:info-client-config info-client/default-config
   :now-ms-fn platform/now-ms
   :log-fn println})

(defn make-service
  [{:keys [info-client-instance info-client-config now-ms-fn log-fn]
    :or {info-client-config (:info-client-config default-config)
         now-ms-fn (:now-ms-fn default-config)
         log-fn (:log-fn default-config)}}]
  (let [client (or info-client-instance
                   (info-client/make-info-client
                    {:config info-client-config
                     :log-fn log-fn}))]
    {:runtime (api-runtime/make-runtime {:info-client client})
     :now-ms-fn now-ms-fn
     :log-fn log-fn}))

(defn runtime
  [service]
  (:runtime service))

(defn now-ms
  [service]
  ((:now-ms-fn service)))

(defn log-fn
  [service]
  (:log-fn service))

(defn- request-info-fn
  [service]
  (:request-info! (api-runtime/info-client (runtime service))))

(defn request-info!
  ([service body]
   (request-info! service body {}))
  ([service body opts]
   ((request-info-fn service) body opts))
  ([service body opts attempt]
   ((request-info-fn service) body opts attempt)))

(defn get-request-stats
  [service]
  ((:get-request-stats (api-runtime/info-client (runtime service)))))

(defn reset-service!
  [service]
  ((:reset! (api-runtime/info-client (runtime service))))
  (api-runtime/reset-runtime! (runtime service))
  nil)

(defn ensure-perp-dexs-data!
  [service store request-perp-dexs! opts]
  (let [runtime* (runtime service)
        existing (get-in @store [:perp-dexs])]
    (if (seq existing)
      (js/Promise.resolve existing)
      (if-let [inflight (api-runtime/ensure-perp-dexs-flight runtime*)]
        inflight
        (let [tracked-ref (atom nil)
              tracked (-> (request-perp-dexs!
                           (merge {:dedupe-key :perp-dexs}
                                  opts))
                          (.finally
                           (fn []
                             (api-runtime/clear-ensure-perp-dexs-flight-if-tracked!
                              runtime*
                              @tracked-ref))))]
          (reset! tracked-ref tracked)
          (api-runtime/set-ensure-perp-dexs-flight! runtime* tracked)
          tracked)))))

(defn ensure-spot-meta-data!
  [_service store request-spot-meta! opts]
  (if-let [meta (get-in @store [:spot :meta])]
    (js/Promise.resolve meta)
    (request-spot-meta! (merge {:dedupe-key :spot-meta}
                               opts))))

(defn ensure-public-webdata2!
  [service fetch-public-webdata2! opts]
  (let [runtime* (runtime service)
        force? (boolean (:force? opts))
        opts* (dissoc opts :force?)]
    (if (and (not force?) (some? (api-runtime/public-webdata2-cache runtime*)))
      (js/Promise.resolve (api-runtime/public-webdata2-cache runtime*))
      (-> (fetch-public-webdata2! (merge {:dedupe-key :public-webdata2}
                                         opts*))
          (.then (fn [snapshot]
                   (api-runtime/set-public-webdata2-cache! runtime* snapshot)
                   snapshot))))))
