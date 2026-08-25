(ns hyperopen.portfolio.application.metrics-bridge
  (:require [clojure.string :as str]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.portfolio.metrics.parsing :as parsing]
            [hyperopen.system :as system]))

(def ^:private empty-source-version-counter
  0)

(def metrics-request-signature-schema-version
  3)

(defn- metric-token
  [value]
  (cond
    (keyword? value) value
    (string? value) (let [trimmed (some-> value str/trim)]
                      (when (seq trimmed)
                        (keyword trimmed)))
    :else nil))

(defn- normalize-metric-token-map
  [token-map]
  (into {}
        (map (fn [[metric-key metric-token-value]]
               [metric-key
                (or (metric-token metric-token-value)
                    metric-token-value)]))
        (or token-map {})))

(defn normalize-worker-metric-values
  [metric-values]
  (let [metric-values* (cond
                         (map? metric-values) metric-values
                         (some? metric-values) (js->clj metric-values :keywordize-keys true)
                         :else nil)]
    (if (map? metric-values*)
      (-> metric-values*
          (update :metric-status normalize-metric-token-map)
          (update :metric-reason normalize-metric-token-map))
      metric-values*)))

(defn normalize-worker-metrics-result
  [payload]
  (let [payload* (cond
                   (map? payload) payload
                   (some? payload) (js->clj payload :keywordize-keys true)
                   :else {})
        benchmark-values-by-coin (or (:benchmark-values-by-coin payload*)
                                     {})]
    (assoc payload*
           :portfolio-values (normalize-worker-metric-values (:portfolio-values payload*))
           :benchmark-values-by-coin (into {}
                                           (map (fn [[coin metric-values]]
                                                  [(if (keyword? coin)
                                                     (name coin)
                                                     coin)
                                                   (normalize-worker-metric-values metric-values)]))
                                           benchmark-values-by-coin))))

(declare last-metrics-request)

(defn request-id
  "Correlation token for one worker round trip.

  The worker already reads `(.-id data)` off the incoming message and copies it
  onto its reply (see `hyperopen.portfolio.worker`); nothing ever set it, so
  every reply was applied to the store unconditionally no matter which request
  it answered. A signature map cannot be sent directly — `postMessage`
  structured-clones its argument and a ClojureScript collection is not
  cloneable — so the token is the signature's hash rendered as a string. Equal
  signatures always produce equal hashes, which is the only property the
  correlation check needs."
  [request-signature]
  (when (some? request-signature)
    (str (hash request-signature))))

(defn apply-worker-metrics-result
  "Fold one worker reply into the store, ignoring replies that answer a request
  we have already superseded. Records the signature the result was computed for
  so the view model can tell the trader when the numbers on screen belong to a
  window that is no longer selected."
  [state id payload]
  (let [{:keys [signature]} @last-metrics-request
        expected-id (request-id signature)]
    (if (and (some? expected-id)
             (some? id)
             (not= id expected-id))
      state
      (-> state
          (assoc-in [:portfolio-ui :metrics-result] payload)
          (assoc-in [:portfolio-ui :metrics-result-signature] signature)
          (assoc-in [:portfolio-ui :metrics-loading?] false)))))

(defonce ^:dynamic metrics-worker
  (delay
   (when (exists? js/Worker)
     (let [worker (js/Worker. "/js/portfolio_worker.js")]
       (.addEventListener worker "message"
                          (fn [^js e]
                            (let [data (.-data e)
                                  type (.-type data)
                                  id (.-id data)
                                  payload-js (.-payload data)]
                              (when (= type "metrics-result")
                                (let [payload (normalize-worker-metrics-result payload-js)]
                                  (swap! system/store apply-worker-metrics-result id payload))))))
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
    ;; Keep existing metrics visible during background recomputes to avoid
    ;; flashing the overlay spinner on live data refreshes.
    (when (nil? (get-in @system/store [:portfolio-ui :metrics-result]))
      (swap! system/store assoc-in [:portfolio-ui :metrics-loading?] true))
    (when-let [worker (current-worker metrics-worker)]
      (.postMessage worker #js {:id (request-id request-signature)
                                :type "compute-metrics"
                                :payload (clj->js request-data)}))))

(defn metrics-request-signature
  [summary-time-range selected-benchmark-coins strategy-source-version benchmark-source-version-map]
  (let [selected-coins (vec (or selected-benchmark-coins []))]
    {:metrics-schema-version metrics-request-signature-schema-version
     :summary-time-range (portfolio-actions/normalize-summary-time-range summary-time-range)
     :selected-benchmark-coins selected-coins
     :strategy-source-version strategy-source-version
     :benchmark-source-versions (mapv (fn [coin]
                                        [coin
                                         (get benchmark-source-version-map coin
                                              empty-source-version-counter)])
                                      selected-coins)}))

(defn request-benchmark-daily-rows
  [portfolio-request]
  (if (contains? portfolio-request :benchmark-daily-rows)
    (or (:benchmark-daily-rows portfolio-request) [])
    (portfolio-metrics/daily-compounded-returns (or (:benchmark-cumulative-rows portfolio-request)
                                                    []))))

(defn request-strategy-daily-rows
  [request]
  (if (contains? request :strategy-daily-rows)
    (or (:strategy-daily-rows request) [])
    (portfolio-metrics/daily-compounded-returns (or (:strategy-cumulative-rows request)
                                                    []))))

(defn- benchmark-relative-metrics-request
  [portfolio-request portfolio-strategy-daily-rows benchmark-daily-rows]
  {:strategy-cumulative-rows (:strategy-cumulative-rows portfolio-request)
   :strategy-daily-rows portfolio-strategy-daily-rows
   :benchmark-daily-rows benchmark-daily-rows
   :rf (or (:rf portfolio-request) 0)
   :mar (or (:mar portfolio-request) 0)
   :periods-per-year (or (:periods-per-year portfolio-request) 365)
   :quality-gates (:quality-gates portfolio-request)})

(defn compute-metrics-sync
  [request-data]
  (let [portfolio-request (:portfolio-request request-data)
        benchmark-requests (:benchmark-requests request-data)
        benchmark-daily-rows (request-benchmark-daily-rows portfolio-request)
        portfolio-strategy-daily-rows (request-strategy-daily-rows portfolio-request)
        portfolio-result (portfolio-metrics/compute-performance-metrics
                          {:strategy-cumulative-rows (:strategy-cumulative-rows portfolio-request)
                           :strategy-daily-rows portfolio-strategy-daily-rows
                           :benchmark-daily-rows benchmark-daily-rows
                           :rf (or (:rf portfolio-request) 0)
                           :mar (or (:mar portfolio-request) 0)
                           :periods-per-year (or (:periods-per-year portfolio-request) 365)
                           :quality-gates (:quality-gates portfolio-request)})
        benchmark-results (into {}
                                (map (fn [{:keys [coin request]}]
                                       (let [strategy-daily-rows (request-strategy-daily-rows request)]
                                         [coin
                                          (portfolio-metrics/merge-benchmark-column-relative-metrics
                                           (portfolio-metrics/compute-performance-metrics
                                            {:strategy-cumulative-rows (:strategy-cumulative-rows request)
                                             :strategy-daily-rows strategy-daily-rows
                                             :rf 0
                                             :periods-per-year 365})
                                           (portfolio-metrics/compute-performance-metrics
                                            (benchmark-relative-metrics-request portfolio-request
                                                                               portfolio-strategy-daily-rows
                                                                               strategy-daily-rows)))]))
                                benchmark-requests))]
    {:portfolio-values portfolio-result
     :benchmark-values-by-coin benchmark-results}))

(defn vault-snapshot-range-keys
  []
  ["1d" "7d" "30d"])

(defn vault-snapshot-point-value
  [point]
  (cond
    (number? point)
    point

    (and (sequential? point)
         (>= (count point) 2))
    (parsing/optional-number (second point))

    (map? point)
    (or (parsing/optional-number (:value point))
        (parsing/optional-number (:pnl point))
        (parsing/optional-number (:account-value point))
        (parsing/optional-number (:accountValue point)))

    :else
    nil))

(defn normalize-vault-snapshot-return
  [span-key row]
  (let [returns-map (:returns row)]
    (when returns-map
      (let [val (get returns-map span-key)]
        (when (parsing/finite-number? val)
          val)))))

(defn vault-benchmark-snapshot-values
  [vault-row]
  (let [keys (vault-snapshot-range-keys)]
    (into {}
          (map (fn [k]
                 [k (normalize-vault-snapshot-return k vault-row)]))
          keys)))

(defn- normalized-history-rows
  [rows]
  (->> rows
       (keep (fn [row]
               (let [time-ms (parsing/history-point-time-ms row)
                     value (parsing/history-point-value row)]
                 (when (and (parsing/finite-number? time-ms)
                            (parsing/finite-number? value))
                   {:time-ms time-ms
                    :value value}))))
       (sort-by :time-ms)
       vec))

(defn aligned-vault-return-rows
  [vault-row strategy-points]
  (let [vault-points (normalized-history-rows
                      (or (:history vault-row) []))]
    (if (and (seq vault-points) (seq strategy-points))
      (let [strategy-start-ms (:time-ms (first strategy-points))
            anchor-point (last (filter #(<= (:time-ms %) strategy-start-ms) vault-points))
            anchor-val (:value anchor-point)
            relevant-vaults (filter #(>= (:time-ms %) strategy-start-ms) vault-points)
            vault-by-time (into {} (map (juxt :time-ms :value) relevant-vaults))]
        (if (parsing/finite-number? anchor-val)
          (->> strategy-points
               (keep (fn [{:keys [time-ms]}]
                       (when-let [v-val (get vault-by-time time-ms)]
                         (let [factor (/ v-val anchor-val)
                               percent (* 100 (- factor 1))]
                           (when (parsing/finite-number? percent)
                             {:time-ms time-ms
                              :value percent})))))
               vec)
          []))
      [])))
