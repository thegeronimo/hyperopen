(ns hyperopen.portfolio.optimizer.worker
  (:require [hyperopen.portfolio.optimizer.application.engine :as engine]
            [hyperopen.portfolio.optimizer.infrastructure.solver-adapter :as solver-adapter]))

(def ^:dynamic run-optimization-async
  engine/run-optimization-async)

(defn- post-message!
  [id type payload]
  (.postMessage js/self #js {:id id
                             :type type
                             :payload (clj->js payload)}))

(defn- instrument-id-key
  [key]
  (cond
    (keyword? key) (name key)
    (string? key) key
    :else (str key)))

(defn- stringify-map-keys
  [value]
  (if (map? value)
    (into {}
          (map (fn [[key item]]
                 [(instrument-id-key key) item]))
          value)
    value))

(def ^:private instrument-key-map-paths
  [[:current-portfolio :by-instrument]
   [:history :return-series-by-instrument]
   [:history :price-series-by-instrument]
   [:history :funding-by-instrument]
   [:black-litterman-prior :weights-by-instrument]
   [:constraints :per-asset-overrides]
   [:constraints :per-perp-leverage-caps]
   [:execution-assumptions :prices-by-id]
   [:execution-assumptions :cost-contexts-by-id]
   [:execution-assumptions :fee-bps-by-id]])

(defn- update-existing-in
  [request path f]
  (if (nil? (get-in request path))
    request
    (update-in request path f)))

(defn- normalize-worker-request
  [request]
  (reduce (fn [request* path]
            (update-existing-in request* path stringify-map-keys))
          request
          instrument-key-map-paths))

(defn optimizer-result-payload
  [request]
  (run-optimization-async
   (normalize-worker-request request)
   {:solve-problem solver-adapter/solve-with-osqp}))

(defn- post-run-result!
  [id payload]
  (-> (optimizer-result-payload payload)
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
