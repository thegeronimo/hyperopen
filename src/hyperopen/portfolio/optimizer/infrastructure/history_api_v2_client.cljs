(ns hyperopen.portfolio.optimizer.infrastructure.history-api-v2-client
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as api-v2]
            [hyperopen.portfolio.optimizer.application.history-loader.request-plan :as request-plan]
            [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def default-base-url
  "https://price-history.hyperopen.xyz")

(defn- normalize-base-url
  [base-url]
  (str/replace (or (coercion/non-blank-text base-url)
                   default-base-url)
               #"/+$"
               ""))

(defn- request-id-value
  [request-id]
  (cond
    (fn? request-id) (request-id)
    (some? request-id) request-id
    :else nil))

(defn- keyword-name
  [value fallback]
  (cond
    (keyword? value) (name value)
    (string? value) value
    :else fallback))

(defn- proxy-policy-wire
  [proxy-policy]
  (str/replace (keyword-name proxy-policy "approved-proxy-allowed") #"-" "_"))

(defn- optimizer-history-request-id
  "The request identity is ALWAYS the canonical backend instrument id from
  discovery (e.g. hl:hip3:xyz:NOW). The backend selects the lineage — native,
  approved proxy, or stitched — server-side from the top-level `proxy_policy`, so
  the frontend must NOT substitute a proxy id (external:tiingo:*) here.

  Sending the proxy id makes the backend treat it as a bare external identity
  whose standalone cache is a tiny recent slice, which collapses the shared
  calendar to ~0 usable observations across the basket (the \"not enough usable
  candle observations / 0 usable shared return observations\" failure). Per
  API_CONTRACT.md, `instrument_id` is the canonical backend id from discovery and
  \"the only accepted request identity\"; `client_instrument_id` is the row key."
  [instrument]
  (coercion/non-blank-text (:optimizer-history/instrument-id instrument)))

(defn- interval-wire
  [interval]
  (keyword-name interval "1d"))

(defn- response-json!
  [response]
  (if (and response (fn? (.-json response)))
    (.json response)
    (js/Promise.resolve #js {})))

(defn- parsed-response!
  [normalizer response]
  (-> (response-json! response)
      (.then (fn [payload]
               (let [body (normalizer (js->clj payload))]
                 {:response response
                  :body body})))))

(defn- error-from-response
  [response body]
  (let [status (or (some-> response .-status) 0)
        message (or (:message body)
                    (:error body)
                    (str "Optimizer history API failed with HTTP " status))
        err (js/Error. message)]
    (aset err "status" status)
    (aset err "payload" (clj->js body))
    (aset err "requestId" (:request-id body))
    (aset err "contractVersion" (:contract-version body))
    err))

(defn- validate-contract!
  [body]
  (if (= api-v2/contract-version (:contract-version body))
    body
    (let [err (js/Error. "Unexpected optimizer history API contract version.")]
      (aset err "status" :invalid-contract)
      (aset err "requestId" (:request-id body))
      (aset err "contractVersion" (:contract-version body))
      (throw err))))

(def default-request-timeout-ms
  "Upper bound on a single optimizer-history HTTP request.

  Without one, `history-load-state` has no path out of `:loading`: it only
  leaves that status when the fetch promise settles (history-workflow's
  apply-history-success / apply-history-error), so a request that never settles
  leaves the Run summary spinning until the page is reloaded, with no error
  shown and no way to retry.

  60s is deliberately an order of magnitude above the largest real bundle on
  record — docs/exec-plans/tech-debt-tracker.md measured 6.2s for a 51-asset
  spectate universe — so this can never fire on a slow-but-progressing request.
  It exists only to bound a hang. Override via the :request-timeout-ms key of
  the :optimizer-history-api config."
  60000)

(defn- timeout-error
  [timeout-ms]
  (let [err (js/Error. (str "Optimizer history API request timed out after "
                            timeout-ms
                            "ms."))]
    (aset err "status" :timeout)
    err))

(defn timeout-error?
  "True for the deadline rejection produced by with-request-timeout!.

  The total-failure legacy fallback in infrastructure.history-client must NOT
  treat this like an ordinary backend failure. Its normal reading of a rejection
  is \"the optimizer history API is unavailable, serve the whole universe from
  the old /info endpoints instead\" — but that loader is the slowest path in the
  system (one request per coin plus a ~18-page funding pagination per perp, all
  serial) and it carries no deadline of its own. Falling back on a timeout would
  turn a bounded wait into an unbounded one, which is precisely what this
  deadline exists to prevent."
  [err]
  (boolean (and (instance? js/Error err)
                (= :timeout (aget err "status")))))

(defn- make-abort-controller
  []
  (when (exists? js/AbortController)
    (js/AbortController.)))

(defn- with-request-timeout!
  "Race `run!` against a deadline. `run!` receives an AbortSignal (or nil when
  the runtime has no AbortController) so the in-flight request is actually
  cancelled rather than merely abandoned. A non-positive or non-numeric
  timeout disables the deadline entirely."
  [timeout-ms run!]
  (if-not (and (number? timeout-ms) (pos? timeout-ms))
    (run! nil)
    (let [controller (make-abort-controller)
          timer (atom nil)]
      (-> (js/Promise.race
           #js [(run! (some-> controller .-signal))
                (js/Promise.
                 (fn [_resolve reject]
                   (reset! timer
                           (js/setTimeout
                            (fn []
                              (some-> controller .abort)
                              (reject (timeout-error timeout-ms)))
                            timeout-ms))))])
          (.finally (fn []
                      (when-let [id @timer]
                        (js/clearTimeout id))))))))

(defn- request-json!
  ([fetch-fn url init normalizer]
   (request-json! fetch-fn url init normalizer nil))
  ([fetch-fn url init normalizer timeout-ms]
   (let [fetch-fn* (or fetch-fn js/fetch)]
     (with-request-timeout!
       timeout-ms
       (fn [signal]
         ;; The signal is attached to the already-converted JS init map rather
         ;; than threaded through clj->js, which would try to walk the
         ;; AbortSignal object.
         (let [init-js (clj->js init)]
           (when signal
             (aset init-js "signal" signal))
           (-> (fetch-fn* url init-js)
               (.then (partial parsed-response! normalizer))
               (.then (fn [{:keys [response body]}]
                        (if (and response
                                 (some? (.-ok response))
                                 (false? (.-ok response)))
                          (js/Promise.reject (error-from-response response body))
                          (validate-contract! body)))))))))))

(defn request-instruments!
  [{:keys [fetch-fn base-url request-id request-timeout-ms]}]
  (let [rid (request-id-value request-id)
        headers (cond-> {}
                  rid (assoc "x-request-id" rid))]
    (request-json! fetch-fn
                   (str (normalize-base-url base-url)
                        "/v1/optimizer/instruments")
                   {:method "GET"
                    :headers headers}
                   api-v2/normalize-api-map
                   ;; Discovery carries the same deadline as the bundle request.
                   ;; It resolves the canonical backend instrument ids, and
                   ;; without them nothing qualifies for the api-v2 path at all —
                   ;; so a hung discovery quietly drops every later load onto the
                   ;; slow legacy loader. Bounding it keeps that failure visible.
                   (or request-timeout-ms default-request-timeout-ms))))

(defn- api-instrument-row
  [instrument]
  (let [local-id (coercion/non-blank-text (:instrument-id instrument))
        request-id (optimizer-history-request-id instrument)]
    (when (and local-id request-id)
      {:client_instrument_id local-id
       :instrument_id request-id})))

(def max-instruments-per-history-request
  "The optimizer history API rejects history-bundle requests with more
  than 100 instruments, so larger universes are fetched in chunks and
  the chunk bodies merged."
  100)

(defn- history-body-base
  [{:keys [proxy-policy include-aligned-returns?]} request]
  {:lookback_days (or (:bars request) request-plan/default-bars)
   :interval (interval-wire (:interval request))
   :proxy_policy (proxy-policy-wire proxy-policy)
   :include_aligned_returns (true? include-aligned-returns?)})

(defn- instrument-rows
  [request]
  (vec (keep api-instrument-row (:universe request))))

(defn- instrument-row-chunks
  [rows]
  (if (seq rows)
    (mapv vec (partition-all max-instruments-per-history-request rows))
    [[]]))

(defn- intersect-preserving-order
  [xs ys]
  (let [keep? (set ys)]
    (filterv keep? xs)))

(defn- merged-calendar
  [calendars]
  (reduce intersect-preserving-order
          (vec (first calendars))
          (rest calendars)))

(defn- merged-status
  [statuses]
  ;; A single :error chunk (common-window-empty) must win over a :partial one,
  ;; so the merged bundle honestly reports the degenerate shared calendar rather
  ;; than masking it behind the first non-:ok chunk.
  (cond
    (some #(= :error %) statuses) :error
    (some #(not= :ok %) statuses) (some #(when (not= :ok %) %) statuses)
    :else :ok))

(defn- merge-history-bodies
  [bodies]
  (if (<= (count bodies) 1)
    (first bodies)
    (let [head (first bodies)]
      {:contract-version (:contract-version head)
       :request-id (:request-id head)
       :dataset-version (:dataset-version head)
       :error (some :error bodies)
       :message (some :message bodies)
       :status (merged-status (map :status bodies))
       :common-calendar (merged-calendar (map :common-calendar bodies))
       :return-calendar (merged-calendar (map :return-calendar bodies))
       :aligned-returns-by-instrument (into {}
                                            (map :aligned-returns-by-instrument)
                                            bodies)
       :series-by-instrument (into {}
                                   (map :series-by-instrument)
                                   bodies)
       :warnings (vec (mapcat :warnings bodies))})))

(defn- request-history-bundle-chunk!
  [{:keys [fetch-fn base-url request-id request-timeout-ms]} body-base instruments]
  (let [rid (request-id-value request-id)]
    (request-json! fetch-fn
                   (str (normalize-base-url base-url)
                        "/v1/optimizer/history-bundle")
                   {:method "POST"
                    :headers (cond-> {"content-type" "application/json"}
                               rid (assoc "x-request-id" rid))
                    :body (js/JSON.stringify
                           (clj->js (assoc body-base :instruments instruments)))}
                   api-v2/normalize-history-body
                   ;; Absent means "use the default"; a non-positive value
                   ;; disables the deadline (see with-request-timeout!).
                   (or request-timeout-ms default-request-timeout-ms))))

(defn- report-chunk-progress!
  [on-chunk-progress progress total-chunks total-instruments]
  (when (fn? on-chunk-progress)
    (on-chunk-progress {:completed (:completed progress)
                        :total total-chunks
                        :loaded-count (:loaded-count progress)
                        :requested-count total-instruments})))

(defn request-history-bundle!
  [{:keys [on-chunk-progress] :as deps} request]
  (let [body-base (history-body-base deps request)
        rows (instrument-rows request)
        chunks (instrument-row-chunks rows)
        total-chunks (count chunks)
        total-instruments (count rows)
        progress-state (atom {:completed 0 :loaded-count 0})]
    (-> (js/Promise.all
         (to-array (map (fn [chunk]
                          (-> (request-history-bundle-chunk! deps body-base chunk)
                              (.then (fn [body]
                                       (report-chunk-progress!
                                        on-chunk-progress
                                        (swap! progress-state
                                               #(-> %
                                                    (update :completed inc)
                                                    (update :loaded-count + (count chunk))))
                                        total-chunks
                                        total-instruments)
                                       body))))
                        chunks)))
        (.then (fn [bodies]
                 (merge-history-bodies (vec bodies)))))))
