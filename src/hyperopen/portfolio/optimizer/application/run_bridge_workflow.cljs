(ns hyperopen.portfolio.optimizer.application.run-bridge-workflow
  (:require [hyperopen.portfolio.optimizer.application.progress :as progress]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.routes :as portfolio-routes]))

(defn- run-state
  [state]
  (get-in state contracts/run-state-path))

(defn- active-scenario-id
  [state]
  (get-in state contracts/active-scenario-loaded-id-path))

(defn- stale-message?
  [state id]
  (let [current-run (run-state state)
        active-id (active-scenario-id state)
        scenario-id (:scenario-id current-run)]
    (or (not= id (:run-id current-run))
        (and active-id
             scenario-id
             (not= active-id scenario-id)))))

(defn- start-run-state
  [{:keys [run-id scenario-id request-signature started-at-ms]}]
  {:status :running
   :run-id run-id
   :scenario-id scenario-id
   :request-signature request-signature
   :started-at-ms started-at-ms
   :error nil})

(defn- duplicate-in-flight?
  [state last-run-request request-signature explicit-run-id?]
  (and (not explicit-run-id?)
       (= :running (get-in state contracts/run-state-status-path))
       (= request-signature (:request-signature last-run-request))))

(defn start-run
  [{:keys [state last-run-request request request-signature run-id computed-at-ms explicit-run-id?]}]
  (if (duplicate-in-flight? state last-run-request request-signature explicit-run-id?)
    {:state state
     :last-run-request last-run-request
     :run-id nil
     :commands []}
    (let [run-id* run-id
          scenario-id (:scenario-id request)
          started-at-ms computed-at-ms
          last-run-request* {:request-signature request-signature
                             :run-id run-id*}]
      {:state (assoc-in state
                        contracts/run-state-path
                        (start-run-state {:run-id run-id*
                                          :scenario-id scenario-id
                                          :request-signature request-signature
                                          :started-at-ms started-at-ms}))
       :last-run-request last-run-request*
       :run-id run-id*
       :commands [{:command/type :optimizer.workflow/install-worker-handler}
                  {:command/type :optimizer.workflow/post-worker-run
                   :run-id run-id*
                   :request request}]})))

(defn- current-progress
  [state]
  (get-in state contracts/optimization-progress-path))

(defn- update-progress
  [state id f & args]
  (let [progress-state (current-progress state)]
    (if (= id (:run-id progress-state))
      (assoc-in state
                contracts/optimization-progress-path
                (apply f progress-state args))
      state)))

(defn- apply-worker-progress
  [state id payload]
  (if (stale-message? state id)
    state
    (update-progress state id progress/worker-progress payload)))

(defn- success-run-state
  [current-run completed-at-ms]
  {:status :succeeded
   :run-id (:run-id current-run)
   :scenario-id (:scenario-id current-run)
   :request-signature (:request-signature current-run)
   :completed-at-ms completed-at-ms
   :error nil})

(defn- failed-run-state
  [current-run completed-at-ms error]
  {:status :failed
   :run-id (:run-id current-run)
   :scenario-id (:scenario-id current-run)
   :request-signature (:request-signature current-run)
   :completed-at-ms completed-at-ms
   :error error})

(defn- non-solved-run-state
  [current-run completed-at-ms payload]
  {:status (:status payload)
   :run-id (:run-id current-run)
   :scenario-id (:scenario-id current-run)
   :request-signature (:request-signature current-run)
   :completed-at-ms completed-at-ms
   :error nil
   :result payload})

(defn- apply-worker-result
  [state id payload computed-at-ms]
  (if (stale-message? state id)
    state
    (let [current-run (run-state state)]
      (if (= :solved (:status payload))
        (let [scenario-id (:scenario-id current-run)]
          (-> state
              (assoc-in contracts/run-state-path
                        (success-run-state current-run computed-at-ms))
              (assoc-in contracts/last-successful-run-path
                        {:request-signature (:request-signature current-run)
                         :result payload
                         :computed-at-ms computed-at-ms})
              (assoc-in contracts/draft-dirty-path false)
              (assoc-in contracts/refinement-active-path false)
              (update-in contracts/active-scenario-path
                         (fn [active-scenario]
                           (cond-> (assoc (or active-scenario {})
                                          :status :computed
                                          :read-only? false)
                             scenario-id (assoc :loaded-id scenario-id))))
              (update-progress id progress/succeed-progress computed-at-ms)))
        (-> state
            (assoc-in contracts/run-state-path
                      (non-solved-run-state current-run computed-at-ms payload))
            (update-progress id progress/fail-progress computed-at-ms payload))))))

(defn- apply-worker-error
  [state id payload computed-at-ms]
  (if (stale-message? state id)
    state
    (-> state
        (assoc-in contracts/run-state-path
                  (failed-run-state (run-state state) computed-at-ms payload))
        (update-progress id progress/fail-progress computed-at-ms payload))))

(defn- success-commands
  [_state state*]
  (let [route (portfolio-routes/parse-portfolio-route (get-in state* [:router :path]))
        run-scenario-id (:scenario-id (run-state state*))
        refresh {:command/type
                 :optimizer.workflow/refresh-portfolio-optimizer-rebalance-slippage-snapshots}
        reveal {:command/type :optimizer.workflow/reveal-results
                :path (portfolio-routes/portfolio-optimize-scenario-path "draft")}]
    (case (:kind route)
      :optimize-new
      [refresh reveal]

      :optimize-scenario
      (if (contains? #{(or run-scenario-id "draft") "draft"}
                     (:scenario-id route))
        [refresh]
        [refresh {:command/type :optimizer.workflow/announce-run-complete}])

      [refresh {:command/type :optimizer.workflow/announce-run-complete}])))

(defn handle-worker-message
  [{:keys [state message computed-at-ms]}]
  (let [{:keys [id type payload]} message
        state* (case type
                 "optimizer-result"
                 (apply-worker-result state id payload computed-at-ms)

                 :optimizer-result
                 (apply-worker-result state id payload computed-at-ms)

                 "optimizer-progress"
                 (apply-worker-progress state id payload)

                 :optimizer-progress
                 (apply-worker-progress state id payload)

                 "optimizer-error"
                 (apply-worker-error state id payload computed-at-ms)

                 :optimizer-error
                 (apply-worker-error state id payload computed-at-ms)

                 state)
        commands (if (and (contains? #{"optimizer-result" :optimizer-result} type)
                          (= :solved (:status payload))
                          (not (stale-message? state id)))
                   (success-commands state state*)
                   [])]
    {:state state*
     :commands commands}))
