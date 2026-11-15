(ns hyperopen.portfolio.optimizer.application.pipeline-workflow
  (:require [hyperopen.portfolio.optimizer.application.progress :as progress]
            [hyperopen.portfolio.optimizer.application.run-identity :as run-identity]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as defaults]))

(defn- clear-stale-refinement
  [state]
  (if (get-in state contracts/refinement-active-path)
    state
    (assoc-in state contracts/refinement-path (defaults/default-refinement-state))))

(defn progress-error
  [code message]
  {:code code
   :message message})

(defn current-progress
  [state]
  (get-in state contracts/optimization-progress-path))

(defn update-progress
  [state run-id f & args]
  (let [progress-state (current-progress state)]
    (if (= run-id (:run-id progress-state))
      (assoc-in state
                contracts/optimization-progress-path
                (apply f progress-state args))
      state)))

(defn selection-prefetch-loading?
  [state]
  (and (= :loading
          (get-in state contracts/history-load-state-status-path))
       (some? (get-in state
                      contracts/history-prefetch-active-instrument-id-path))))

(defn- begin-pipeline-progress
  [state run-id request started-at-ms]
  (assoc-in state
            contracts/optimization-progress-path
            (progress/begin-progress {:run-id run-id
                                      :scenario-id (:scenario-id request)
                                      :request request
                                      :started-at-ms started-at-ms})))

(defn- mark-progress-step
  [state run-id step-id attrs]
  (update-progress state run-id progress/mark-step step-id attrs))

(defn- worker-run-state
  [state run-id]
  (-> state
      (mark-progress-step run-id
                          :fetch-returns
                          {:status :succeeded
                           :percent 100})
      (mark-progress-step run-id
                          :risk-model
                          {:status :running
                           :percent 15})))

(defn request-worker-run-command
  [run-id request]
  {:command/type :optimizer.workflow/request-worker-run
   :run-id run-id
   :request request
   :request-signature (run-identity/build-request-signature request)})

(defn begin-run
  [{:keys [state run-id started-at-ms history-idle-poll-ms history-idle-timeout-ms]}]
  (let [state (clear-stale-refinement state)
        draft (get-in state contracts/draft-path)
        universe (vec (:universe draft))
        initial-readiness (setup-readiness/build-readiness state)
        initial-request (or (:request initial-readiness)
                            {:scenario-id (:id draft)
                             :requested-universe universe
                             :universe universe
                             :return-model (:return-model draft)
                             :risk-model (:risk-model draft)
                             :objective (:objective draft)})
        state* (begin-pipeline-progress state run-id initial-request started-at-ms)]
    (cond
      (empty? universe)
      {:state (update-progress state*
                               run-id
                               progress/fail-progress
                               started-at-ms
                               (progress-error :missing-universe
                                               "Select a universe before running."))
       :commands []}

      (selection-prefetch-loading? state)
      {:state state*
       :commands [(cond-> {:command/type :optimizer.workflow/wait-for-history-idle
                           :run-id run-id}
                    (some? history-idle-poll-ms)
                    (assoc :poll-ms history-idle-poll-ms)
                    (some? history-idle-timeout-ms)
                    (assoc :timeout-ms history-idle-timeout-ms))]}

      (setup-readiness/current-portfolio-history-load-needed? initial-readiness)
      {:state state*
       :commands [{:command/type :optimizer.workflow/load-history
                   :run-id run-id}]}

      (:runnable? initial-readiness)
      {:state (worker-run-state state* run-id)
       :commands [(request-worker-run-command run-id (:request initial-readiness))]}

      :else
      {:state state*
       :commands [{:command/type :optimizer.workflow/load-history
                   :run-id run-id}]})))

(defn after-history-idle
  [{:keys [state run-id]}]
  (let [{:keys [request runnable?] :as readiness}
        (setup-readiness/build-readiness state)]
    (cond
      (setup-readiness/current-portfolio-history-load-needed? readiness)
      {:state state
       :commands [{:command/type :optimizer.workflow/load-history
                   :run-id run-id}]}

      runnable?
      {:state (worker-run-state state run-id)
       :commands [(request-worker-run-command run-id request)]}

      :else
      {:state state
       :commands [{:command/type :optimizer.workflow/load-history
                   :run-id run-id}]})))

(defn after-history-loaded
  [{:keys [state run-id]}]
  (let [{:keys [request runnable?] :as readiness} (setup-readiness/build-readiness state)]
    (if runnable?
      {:state (worker-run-state state run-id)
       :commands [(request-worker-run-command run-id request)]}
      (throw (js/Error. (setup-readiness/readiness-error-message readiness))))))

(defn fail-run
  [{:keys [state run-id completed-at-ms error]}]
  {:state (update-progress state
                           run-id
                           progress/fail-progress
                           completed-at-ms
                           error)
   :commands []})
