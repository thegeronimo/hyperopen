(ns hyperopen.portfolio.optimizer.infrastructure.worker-client
  (:require [hyperopen.portfolio.optimizer.infrastructure.wire :as wire]))

(defonce ^:private message-handler
  (atom nil))

(defn normalize-worker-message
  [data]
  (wire/normalize-wire-values
   (cond
     (map? data) data
     (some? data) (js->clj data :keywordize-keys true)
     :else {})))

(defn set-message-handler!
  [handler]
  (reset! message-handler handler))

(defonce ^:dynamic optimizer-worker
  (delay
    (when (exists? js/Worker)
      (let [worker (js/Worker. "/js/portfolio_optimizer_worker.js")]
        (.addEventListener worker "message"
                           (fn [^js event]
                             (when-let [handler @message-handler]
                               (handler (normalize-worker-message (.-data event))))))
        worker))))

(defn current-worker
  [worker-ref]
  (cond
    (nil? worker-ref) nil
    (satisfies? IDeref worker-ref) @worker-ref
    :else worker-ref))

(defn post-run!
  ([id request]
   (post-run! optimizer-worker id request))
  ([worker-ref id request]
   (when-let [worker (current-worker worker-ref)]
     (.postMessage worker #js {:id id
                               :type "run-optimizer"
                               :payload (clj->js request)})
     true)))
