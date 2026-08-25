(ns hyperopen.portfolio.optimizer.infrastructure.history-client
  (:require [hyperopen.portfolio.optimizer.application.history-loader :as history-loader]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as api-v2]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.legacy-fallback :as legacy-fallback]
            [hyperopen.portfolio.optimizer.application.history-loader.instruments :as instruments]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.infrastructure.history-api-v2-client :as history-api-v2-client]))

(def default-legacy-fallback-request-spacing-ms
  0)

(defn- sleep-ms
  [ms]
  (if (pos? (or ms 0))
    (js/Promise.
     (fn [resolve _reject]
       (js/setTimeout resolve ms)))
    (js/Promise.resolve nil)))

(defn- progress-identifier
  [kind id-key identifier]
  (cond-> {:kind kind}
    (= id-key :coin) (assoc :coin identifier)
    (= id-key :vault-address) (assoc :vault-address identifier)))

(defn- report-source-progress!
  [on-progress payload]
  (when (fn? on-progress)
    (on-progress payload)))

(defn- report-progress!
  [on-progress progress-state kind id-key identifier total]
  (let [completed (swap! progress-state inc)]
    (report-source-progress!
     on-progress
     (assoc (progress-identifier kind id-key identifier)
            :source :info-endpoint
            :completed completed
            :total total
            :percent (if (pos? total)
                       (* 100 (/ completed total))
                       100)))))

(defn- tracked-request
  [request! on-progress progress-state kind id-key total {:keys [opts] :as request}]
  (let [identifier (get request id-key)]
    (-> (request! identifier opts)
        (.then (fn [result]
                 (report-progress! on-progress progress-state kind id-key identifier total)
                 result)))))

(defn- request-candle-entry
  [request-candle-snapshot! on-progress progress-state total {:keys [coin] :as request}]
  {:bucket :candles
   :key coin
   :request! (fn []
               (tracked-request request-candle-snapshot!
                                on-progress
                                progress-state
                                :candles
                                :coin
                                total
                                request))})

(defn- request-funding-entry
  [request-market-funding-history! on-progress progress-state total {:keys [coin] :as request}]
  {:bucket :funding
   :key coin
   :request! (fn []
               (tracked-request request-market-funding-history!
                                on-progress
                                progress-state
                                :funding
                                :coin
                                total
                                request))})

(defn- request-vault-details-entry
  [request-vault-details! on-progress progress-state total {:keys [vault-address] :as request}]
  {:bucket :vault-details
   :key vault-address
   :request! (fn []
               (tracked-request request-vault-details!
                                on-progress
                                progress-state
                                :vault-details
                                :vault-address
                                total
                                request))})

(defn- legacy-request-spacing-ms
  [optimizer-history-api]
  (or (:legacy-fallback-request-spacing-ms optimizer-history-api)
      default-legacy-fallback-request-spacing-ms))

(defn- run-legacy-entry!
  [entry]
  (-> ((:request! entry))
      (.then (fn [value]
               (assoc entry :value value)))))

(defn- run-legacy-entries!
  "Run the legacy /info requests one at a time, spacing consecutive requests by
  `spacing-ms`.

  The serialization is deliberate and is pinned by
  `request-history-bundle-throttles-legacy-info-requests-test`: parallel /info
  requests have previously produced a 429 backoff storm (see the optimizer entry
  in docs/exec-plans/tech-debt-tracker.md). Do not make this concurrent without
  a rate-limit measurement.

  The spacing separates one request from the NEXT one, so the final entry does
  not sleep — that sleep delayed the resolved bundle by a full interval (200ms
  in production) while no further request was waiting on it."
  [entries spacing-ms]
  (let [entries (vec entries)
        last-index (dec (count entries))]
    (reduce-kv (fn [promise index entry]
                 (-> promise
                     (.then (fn [results]
                              (-> (run-legacy-entry! entry)
                                  (.then (fn [result]
                                           (if (= index last-index)
                                             (conj results result)
                                             (-> (sleep-ms spacing-ms)
                                                 (.then (fn []
                                                          (conj results result))))))))))))
               (js/Promise.resolve [])
               entries)))

(defn- assoc-legacy-result
  [bundle {:keys [bucket key value]}]
  (case bucket
    :candles
    (assoc-in bundle [:candle-history-by-coin key] value)

    :funding
    (assoc-in bundle [:funding-history-by-coin key] value)

    :vault-details
    (assoc-in bundle [:vault-details-by-address key] value)

    bundle))

(defn- request-legacy-history-bundle!
  [{:keys [request-candle-snapshot!
           request-market-funding-history!
           request-vault-details!
           optimizer-history-api
           on-progress]}
   {:keys [universe] :as request}]
  (let [plan (history-loader/build-history-request-plan universe request)
        total (+ (count (:candle-requests plan))
                 (count (:funding-requests plan))
                 (count (:vault-detail-requests plan)))
        progress-state (atom 0)
        candle-entries (map (partial request-candle-entry
                                     request-candle-snapshot!
                                     on-progress
                                     progress-state
                                     total)
                            (:candle-requests plan))
        funding-entries (map (partial request-funding-entry
                                      request-market-funding-history!
                                      on-progress
                                      progress-state
                                      total)
                             (:funding-requests plan))
        vault-detail-entries (map (partial request-vault-details-entry
                                           request-vault-details!
                                           on-progress
                                           progress-state
                                           total)
                                  (:vault-detail-requests plan))
        entries (concat candle-entries funding-entries vault-detail-entries)]
    (report-source-progress!
     on-progress
     {:source :info-endpoint
      :kind :legacy-history
      :status :started
      :asset-count (count universe)
      :completed 0
      :total total
      :percent (if (pos? total) 0 100)})
    (-> (run-legacy-entries! entries
                             (legacy-request-spacing-ms optimizer-history-api))
        (.then (fn [results]
                 (assoc (reduce assoc-legacy-result
                                {:candle-history-by-coin {}
                                 :funding-history-by-coin {}
                                 :vault-details-by-address {}}
                                results)
                        :warnings (:warnings plan)
                        :request-plan plan))))))

(defn- fallback-warning
  [err]
  (let [message (some-> err .-message)
        status (aget err "status")
        request-id (aget err "requestId")
        contract-version (aget err "contractVersion")]
    (cond-> {:code :optimizer-history-api-fallback
             :message "Optimizer history API failed; legacy history loader was used."}
      message (assoc :error-message message)
      status (assoc :status status)
      request-id (assoc :request-id request-id)
      contract-version (assoc :contract-version contract-version))))

(defn- with-fallback-warning
  [bundle err]
  (update bundle :warnings
          #(vec (cons (fallback-warning err)
                      (or % [])))))

(defn- backend-history-id
  [instrument]
  (coercion/non-blank-text (:optimizer-history/instrument-id instrument)))

(defn- api-v2-instrument?
  [instrument]
  (boolean (backend-history-id instrument)))

(defn- api-v2-universe
  [request]
  (filterv api-v2-instrument? (:universe request)))

(defn- request-with-universe
  [request universe]
  (assoc request :universe (vec universe)))

(defn- warning-targets-instrument?
  [local-id backend-id warning]
  (contains? (cond-> #{local-id}
               backend-id (conj backend-id))
             (:instrument-id warning)))

(defn- hard-warning-for-instrument?
  [normalized local-id backend-id]
  (boolean
   (some (fn [warning]
           (and (legacy-fallback/hard-warning? warning)
                (warning-targets-instrument? local-id backend-id warning)))
         (:warnings normalized))))

(defn- legacy-fallback-universe
  [request normalized]
  (let [series-by-instrument (:series-by-instrument normalized)]
    (filterv (fn [instrument]
               (let [local-id (instruments/normalize-instrument-id instrument)
                     backend-id (backend-history-id instrument)]
                 (or (not (api-v2-instrument? instrument))
                     (and (not (hard-warning-for-instrument?
                                normalized
                                local-id
                                backend-id))
                          (legacy-fallback/series-fallback-needed?
                           (get series-by-instrument local-id))))))
             (:universe request))))

(defn- targeted-fallback-warning
  [fallback-universe]
  {:code :optimizer-history-api-legacy-fallback
   :message "Legacy history loader was used only for optimizer history API gaps."
   :instrument-ids (mapv instruments/normalize-instrument-id fallback-universe)
   :count (count fallback-universe)})

(defn- with-targeted-legacy-fallback
  [api-bundle legacy-bundle fallback-universe]
  (-> api-bundle
      (merge (select-keys legacy-bundle
                          [:candle-history-by-coin
                           :funding-history-by-coin
                           :vault-details-by-address
                           :request-plan]))
      (update :warnings
              #(vec (concat (or % [])
                            (or (:warnings legacy-bundle) [])
                            [(targeted-fallback-warning fallback-universe)])))))

(defn- request-targeted-legacy-fallback!
  [deps request api-bundle fallback-universe]
  (-> (request-legacy-history-bundle!
       deps
       (request-with-universe request fallback-universe))
      (.then #(with-targeted-legacy-fallback api-bundle
                                             %
                                             fallback-universe))))

(defn- empty-api-v2-history
  []
  {:contract-version api-v2/contract-version
   :status :partial
   :series-by-instrument {}
   :aligned-returns-by-instrument {}
   :warnings []})

(defn- targeted-fallback-failed-warning
  [fallback-universe err]
  (cond-> {:code :optimizer-history-targeted-fallback-failed
           :message (str "Legacy history could not be loaded for "
                         (count fallback-universe)
                         (if (= 1 (count fallback-universe))
                           " asset; "
                           " assets; ")
                         "the optimizer history API results for the rest were kept.")
           :instrument-ids (mapv instruments/normalize-instrument-id fallback-universe)
           :count (count fallback-universe)}
    (some-> err .-message) (assoc :error-message (.-message err))))

(def ^:private no-legacy-escalation-flag
  "hyperopenOptimizerNoLegacyEscalation")

(defn- no-escalation-error
  "Re-raise a gap-fill failure as a load failure that must NOT be retried
  through the whole-universe legacy loader. `apply-history-error` leaves
  `history-data` untouched, so the user keeps the history already on screen and
  gets the existing \"History load failed. Existing history, if any, is
  retained.\" copy plus its Refresh history action."
  [err]
  (let [e (js/Error. (or (some-> err .-message)
                         "Optimizer history gap-fill failed."))]
    (aset e no-legacy-escalation-flag true)
    (when-let [status (when (instance? js/Error err) (aget err "status"))]
      (aset e "status" status))
    e))

(defn- escalate-to-legacy?
  [err]
  (and (not (history-api-v2-client/timeout-error? err))
       (not (and (instance? js/Error err)
                 (true? (aget err no-legacy-escalation-flag))))))

(defn- api-serves-instrument?
  [api-bundle instrument]
  (boolean
   (seq (get-in api-bundle
                [:api-v2-history
                 :series-by-instrument
                 (instruments/normalize-instrument-id instrument)
                 :points]))))

(defn- api-serves-every-fallback?
  "True when the API response alone already carries usable points for every
  instrument the gap-fill was going to cover — i.e. the fallback was a top-up
  (funding, a flagged-but-usable series), not the only source of an asset's
  history. Only then is discarding the gap-fill's failure lossless."
  [api-bundle fallback-universe]
  (every? #(api-serves-instrument? api-bundle %) fallback-universe))

(defn- maybe-request-targeted-legacy-fallback!
  [{:keys [optimizer-history-api] :as deps} request api-bundle fallback-universe]
  (if (and (not= false (:allow-legacy-fallback? request))
           (:fallback-to-legacy? optimizer-history-api)
           (seq fallback-universe))
    (-> (request-targeted-legacy-fallback! deps
                                           request
                                           api-bundle
                                           fallback-universe)
        ;; A targeted gap-fill covers the few instruments the API could not serve.
        ;; It must degrade ONLY those instruments. Before 2026-08-24 a rejection here
        ;; propagated to the total-failure handler in request-api-v2-history-bundle!,
        ;; which is meant for "the backend request itself failed" — so one transient
        ;; /info error, for one instrument, discarded a fully successful backend
        ;; bundle and refetched the WHOLE universe through the serial legacy loader
        ;; (one candle request per coin, one funding request per perp, each funding
        ;; request itself paginating ~18 times). Resolving with the API bundle plus a
        ;; warning keeps that escalation impossible.
        (.catch (fn [err]
                  (if (api-serves-every-fallback? api-bundle fallback-universe)
                    ;; Lossless: the API already serves every instrument the
                    ;; gap-fill covered, so degrade with a warning.
                    (js/Promise.resolve
                     (update api-bundle
                             :warnings
                             #(conj (vec %)
                                    (targeted-fallback-failed-warning fallback-universe
                                                                      err))))
                    ;; NOT lossless: the gap-fill was the only source of history
                    ;; for at least one instrument. Resolving here would hand
                    ;; apply-history-success a bundle with no
                    ;; :candle-history-by-coin / :funding-history-by-coin /
                    ;; :vault-details-by-address at all, and that assoc-in
                    ;; REPLACES history-data wholesale — destroying candles a
                    ;; previous load had fetched and persisting the degraded
                    ;; bundle to the per-wallet IndexedDB cache. Fail the load
                    ;; instead, without escalating to the whole-universe legacy
                    ;; loader.
                    (js/Promise.reject (no-escalation-error err))))))
    (js/Promise.resolve api-bundle)))

(defn- fallback-backed-by-api-count
  [fallback-universe]
  (count (filter api-v2-instrument? fallback-universe)))

(defn- report-api-progress!
  [on-progress payload]
  (report-source-progress!
   on-progress
   (merge {:source :backend-api
           :kind :history-bundle}
          payload)))

(defn- report-api-started!
  [on-progress api-request]
  (let [requested-count (count (:universe api-request))]
    (report-api-progress!
     on-progress
     {:status :started
      :requested-count requested-count
      :completed 0
      :total 1
      :percent 0})))

(defn- report-api-loading!
  [on-progress {:keys [completed total loaded-count requested-count]}]
  (report-api-progress!
   on-progress
   {:status :loading
    :requested-count requested-count
    :loaded-count loaded-count
    :completed completed
    :total total
    :percent (if (pos? (or total 0))
               (* 100 (/ completed total))
               100)}))

(defn- report-api-succeeded!
  [on-progress api-request normalized fallback-universe]
  (let [requested-count (count (:universe api-request))
        api-fallback-count (fallback-backed-by-api-count fallback-universe)]
    (report-api-progress!
     on-progress
     {:status :succeeded
      :requested-count requested-count
      :returned-count (count (:series-by-instrument normalized))
      :usable-count (max 0 (- requested-count api-fallback-count))
      :fallback-asset-count (count fallback-universe)
      :completed 1
      :total 1
      :percent 100})))

(defn- report-api-skipped!
  [on-progress fallback-universe]
  (report-api-progress!
   on-progress
   {:status :skipped
    :requested-count 0
    :returned-count 0
    :usable-count 0
    :fallback-asset-count (count fallback-universe)
    :completed 1
    :total 1
    :percent 100}))

(defn- report-api-failed!
  [on-progress api-request fallback-universe]
  (report-api-progress!
   on-progress
   {:status :failed
    :requested-count (count (:universe api-request))
    :returned-count 0
    :usable-count 0
    :fallback-asset-count (count fallback-universe)
    :completed 1
    :total 1
    :percent 100}))

(defn- request-api-v2-history-bundle!
  [{:keys [optimizer-history-api
           fetch-fn
           request-id
           on-progress]
    :as deps}
   request]
  (let [api-request (request-with-universe request (api-v2-universe request))]
    (if-not (seq (:universe api-request))
      (let [fallback-universe (:universe request)]
        (report-api-skipped! on-progress fallback-universe)
        (if (and (not= false (:allow-legacy-fallback? request))
                 (:fallback-to-legacy? optimizer-history-api))
          (request-targeted-legacy-fallback!
           deps
           request
           {:api-v2-history (empty-api-v2-history)
            :warnings []}
           fallback-universe)
          (js/Promise.reject
           (js/Error. "Optimizer history API request has no backend instrument ids."))))
      (do
        (report-api-started! on-progress api-request)
        (-> (history-api-v2-client/request-history-bundle!
             {:fetch-fn fetch-fn
              :base-url (:base-url optimizer-history-api)
              :request-id request-id
              :proxy-policy (:proxy-policy optimizer-history-api)
              :include-aligned-returns? (:include-aligned-returns? optimizer-history-api)
              :request-timeout-ms (:request-timeout-ms optimizer-history-api)
              :on-chunk-progress (partial report-api-loading! on-progress)}
             api-request)
          (.then (fn [body]
                   (let [normalized (api-v2/normalize-history-bundle request body)]
                     (report-api-succeeded!
                      on-progress
                      api-request
                      normalized
                      (legacy-fallback-universe request normalized))
                     {:api-v2-history normalized
                      :warnings (:warnings normalized)})))
          (.then (fn [api-bundle]
                   (maybe-request-targeted-legacy-fallback!
                    deps
                    request
                    api-bundle
                    (legacy-fallback-universe request (:api-v2-history api-bundle)))))
          (.catch (fn [err]
                    (if (and (not= false (:allow-legacy-fallback? request))
                             (:fallback-to-legacy? optimizer-history-api)
                             ;; A deadline breach, and a gap-fill failure that
                             ;; would have cost real history, must terminate the
                             ;; load rather than escalate it into the slowest
                             ;; path in the system.
                             (escalate-to-legacy? err))
                      (do
                        (report-api-failed! on-progress api-request (:universe request))
                        (-> (request-legacy-history-bundle! deps request)
                            (.then #(with-fallback-warning % err))))
                      (js/Promise.reject err)))))))))

(defn request-history-bundle!
  [{:keys [optimizer-history-api] :as deps} request]
  (if (:enabled? optimizer-history-api)
    (request-api-v2-history-bundle! deps request)
    (request-legacy-history-bundle! deps request)))
