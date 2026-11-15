(ns hyperopen.vaults.detail.metrics-bridge
  (:require [hyperopen.system :as system]
            [hyperopen.portfolio.application.metrics-bridge :as portfolio-metrics-bridge]))

(def normalize-worker-metrics-result
  portfolio-metrics-bridge/normalize-worker-metrics-result)

(def compute-metrics-sync
  portfolio-metrics-bridge/compute-metrics-sync)

(def metrics-request-signature
  portfolio-metrics-bridge/metrics-request-signature)

(defn apply-worker-metrics-result!
  [payload]
  (let [payload* (normalize-worker-metrics-result payload)]
    (swap! system/store
           (fn [state]
             (-> state
                 (assoc-in [:vaults-ui :detail-performance-metrics-result] payload*)
                 (assoc-in [:vaults-ui :detail-performance-metrics-loading?] false))))))

(defonce ^:dynamic metrics-worker
  (delay
    (when (exists? js/Worker)
      (let [worker (js/Worker. "/js/vault_detail_worker.js")]
        (.addEventListener worker "message"
                           (fn [^js e]
                             (let [data (.-data e)
                                   type (.-type data)
                                   payload-js (.-payload data)]
                               (when (= type "metrics-result")
                                 (apply-worker-metrics-result! payload-js)))))
        worker))))

(defonce last-metrics-request (atom nil))

(defn- current-worker
  [worker-ref]
  (cond
    (nil? worker-ref) nil
    (satisfies? IDeref worker-ref) @worker-ref
    :else worker-ref))

(defn request-metrics-computation!
  [request-data request-signature]
  (when (not= request-signature (:signature @last-metrics-request))
    (reset! last-metrics-request {:signature request-signature})
    (when (nil? (get-in @system/store [:vaults-ui :detail-performance-metrics-result]))
      (swap! system/store assoc-in [:vaults-ui :detail-performance-metrics-loading?] true))
    (when-let [worker (current-worker metrics-worker)]
      (.postMessage worker #js {:type "compute-metrics"
                                :payload (clj->js request-data)}))))
