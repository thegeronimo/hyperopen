(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline
  (:require [hyperopen.portfolio.optimizer.application.progress :as progress]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]))

(defn- request-run-signature
  [request]
  {:scenario-id (:scenario-id request)
   :as-of-ms (:as-of-ms request)
   :request request})

(defn- error-message
  [err]
  (or (when (map? err)
        (:message err))
      (some-> err .-message)
      (str err)))

(defn- progress-error
  [code message]
  {:code code
   :message message})

(defn- current-progress
  [state]
  (get-in state [:portfolio :optimizer :optimization-progress]))

(defn- update-progress
  [state run-id f & args]
  (let [progress-state (current-progress state)]
    (if (= run-id (:run-id progress-state))
      (assoc-in state
                [:portfolio :optimizer :optimization-progress]
                (apply f progress-state args))
      state)))

(defn- mark-progress-step!
  [store run-id step-id attrs]
  (swap! store update-progress run-id progress/mark-step step-id attrs))

(defn- fail-progress!
  [now-ms store run-id error]
  (swap! store update-progress run-id progress/fail-progress (now-ms) error))

(defn- begin-pipeline-progress!
  [now-ms store run-id request]
  (swap! store assoc-in
         [:portfolio :optimizer :optimization-progress]
         (progress/begin-progress {:run-id run-id
                                   :scenario-id (:scenario-id request)
                                   :request request
                                   :started-at-ms (now-ms)})))

(defn- fetch-progress-callback
  [store run-id]
  (fn [{:keys [percent completed total]}]
    (mark-progress-step! store
                         run-id
                         :fetch-returns
                         {:status :running
                          :percent percent
                          :detail (str completed "/" total " requests")})))

(defn- run-worker-from-ready-request!
  [request-run! store run-id request]
  (mark-progress-step! store
                       run-id
                       :fetch-returns
                       {:status :succeeded
                        :percent 100})
  (mark-progress-step! store
                       run-id
                       :risk-model
                       {:status :running
                        :percent 15})
  (request-run! {:request request
                 :request-signature (request-run-signature request)
                 :store store
                 :run-id run-id}))

(defn- pipeline-ready-request
  [store]
  (let [{:keys [request runnable?] :as readiness}
        (setup-readiness/build-readiness @store)]
    (when-not runnable?
      (throw (js/Error. (setup-readiness/readiness-error-message readiness))))
    request))

(defn- selection-prefetch-loading?
  [state]
  (and (= :loading
          (get-in state [:portfolio :optimizer :history-load-state :status]))
       (some? (get-in state
                      [:portfolio :optimizer :history-prefetch :active-instrument-id]))))

(defn- wait-for-history-load-idle!
  [store {:keys [poll-ms timeout-ms]
          :or {poll-ms 50
               timeout-ms 30000}}]
  (let [started-at-ms (.now js/Date)]
    (js/Promise.
     (fn [resolve reject]
       (letfn [(tick []
                 (cond
                   (not (selection-prefetch-loading? @store))
                   (resolve true)

                   (> (- (.now js/Date) started-at-ms) timeout-ms)
                   (reject (js/Error. "Timed out waiting for optimizer history prefetch to settle."))

                   :else
                   (js/setTimeout tick poll-ms)))]
         (tick))))))

(defn- load-history-then-run!
  [load-history! request-run! store run-id]
  (-> (load-history! store
                     {:on-progress (fetch-progress-callback store run-id)})
      (.then (fn [_bundle]
               (let [request (pipeline-ready-request store)]
                 (run-worker-from-ready-request! request-run! store run-id request)
                 run-id)))))

(defn- wait-or-load-history-then-run!
  [{:keys [request-run! load-history! history-idle-poll-ms history-idle-timeout-ms]} store run-id]
  (if (selection-prefetch-loading? @store)
    (-> (wait-for-history-load-idle!
         store
         {:poll-ms history-idle-poll-ms
          :timeout-ms history-idle-timeout-ms})
        (.then (fn [_]
                 (let [{:keys [request runnable?]} (setup-readiness/build-readiness @store)]
                   (if runnable?
                     (do
                       (run-worker-from-ready-request! request-run! store run-id request)
                     run-id)
                    (load-history-then-run! load-history!
                                            request-run!
                                            store
                                            run-id))))))
    (load-history-then-run! load-history!
                            request-run!
                            store
                            run-id)))

(defn run-portfolio-optimizer-pipeline-effect
  [{:keys [now-ms next-run-id request-run! load-history!] :as env} _ store]
  (let [state @store
        draft (get-in state [:portfolio :optimizer :draft])
        universe (vec (:universe draft))
        initial-readiness (setup-readiness/build-readiness state)
        initial-request (or (:request initial-readiness)
                            {:scenario-id (:id draft)
                             :requested-universe universe
                             :universe universe
                             :return-model (:return-model draft)
                             :risk-model (:risk-model draft)
                             :objective (:objective draft)})
        run-id (next-run-id)]
    (begin-pipeline-progress! now-ms store run-id initial-request)
    (cond
      (empty? universe)
      (do
        (fail-progress! now-ms
                        store
                        run-id
                        (progress-error :missing-universe
                                        "Select a universe before running."))
        (js/Promise.resolve nil))

      (:runnable? initial-readiness)
      (do
        (run-worker-from-ready-request! request-run!
                                        store
                                        run-id
                                        (:request initial-readiness))
        (js/Promise.resolve run-id))

      :else
      (-> (wait-or-load-history-then-run! env store run-id)
          (.catch (fn [err]
                    (fail-progress!
                     now-ms
                     store
                     run-id
                     (progress-error :pipeline-failed (error-message err)))
                    nil))))))
