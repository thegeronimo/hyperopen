(ns hyperopen.portfolio.optimizer.worker
  (:require [hyperopen.portfolio.optimizer.application.engine :as engine]
            [hyperopen.portfolio.optimizer.infrastructure.wire :as wire]
            [hyperopen.portfolio.optimizer.infrastructure.solver-adapter :as solver-adapter]))

(def ^:dynamic run-optimization-async
  engine/run-optimization-async)

(defn- post-message!
  [id type payload]
  (.postMessage js/self #js {:id id
                             :type type
                             :payload (clj->js payload)}))

(defn- normalize-worker-request
  [request]
  (wire/normalize-worker-boundary request))

(defn optimizer-result-payload
  ([request]
   (optimizer-result-payload nil request))
  ([id request]
  (run-optimization-async
   (normalize-worker-request request)
   {:solve-problem solver-adapter/solve-with-osqp
    :on-progress (fn [payload]
                   (when id
                     (post-message! id "optimizer-progress" payload)))})))

(defn- post-run-result!
  [id payload]
  (-> (optimizer-result-payload id payload)
      (.then (fn [result]
               (post-message! id "optimizer-result" result)))
      (.catch (fn [err]
                (post-message! id
                               "optimizer-error"
                               {:code :optimizer-worker-error
                                :message (str err)})))))

(defn- handle-message
  [^js event]
  (let [data (.-data event)
        id (.-id data)
        type (keyword (.-type data))
        payload (js->clj (.-payload data) :keywordize-keys true)]
    (case type
      :ping
      (post-message! id "optimizer-pong" {:ready? true})

      :run-optimizer
      (post-run-result! id payload)

      :run-optimization
      (post-run-result! id payload)

      (post-message! id
                     "optimizer-error"
                     {:code :unknown-message-type
                      :message (str "Unknown optimizer worker message type: " type)}))))

(defn ^:export init
  []
  (js/console.log "Portfolio Optimizer Web Worker initialized.")
  (.addEventListener js/self "message" handle-message))
