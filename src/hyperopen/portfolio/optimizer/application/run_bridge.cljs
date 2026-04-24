(ns hyperopen.portfolio.optimizer.application.run-bridge
  (:require [hyperopen.portfolio.optimizer.infrastructure.worker-client :as worker-client]
            [hyperopen.system :as system]))

(declare handle-worker-message!)

(defonce last-run-request
  (atom nil))

(defn next-run-id
  []
  (str "optimizer-run-" (.now js/Date) "-" (rand-int 1000000)))

(defn- now-ms
  []
  (.now js/Date))

(defn- run-state
  [state]
  (get-in state [:portfolio :optimizer :run-state]))

(defn- active-scenario-id
  [state]
  (get-in state [:portfolio :optimizer :active-scenario :loaded-id]))

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

(defn request-run!
  [{:keys [request request-signature computed-at-ms]}]
  (when (not= request-signature (:request-signature @last-run-request))
    (worker-client/set-message-handler! handle-worker-message!)
    (let [run-id (next-run-id)
          scenario-id (:scenario-id request)
          started-at-ms (or computed-at-ms (now-ms))]
      (reset! last-run-request {:request-signature request-signature
                                :run-id run-id})
      (swap! system/store assoc-in
             [:portfolio :optimizer :run-state]
             (start-run-state {:run-id run-id
                               :scenario-id scenario-id
                               :request-signature request-signature
                               :started-at-ms started-at-ms}))
      (worker-client/post-run! run-id request)
      run-id)))

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
        (-> state
            (assoc-in [:portfolio :optimizer :run-state]
                      (success-run-state current-run computed-at-ms))
            (assoc-in [:portfolio :optimizer :last-successful-run]
                      {:request-signature (:request-signature current-run)
                       :result payload
                       :computed-at-ms computed-at-ms}))
        (assoc-in state
                  [:portfolio :optimizer :run-state]
                  (non-solved-run-state current-run computed-at-ms payload))))))

(defn- apply-worker-error
  [state id payload computed-at-ms]
  (if (stale-message? state id)
    state
    (assoc-in state
              [:portfolio :optimizer :run-state]
              (failed-run-state (run-state state) computed-at-ms payload))))

(defn handle-worker-message!
  ([message]
   (handle-worker-message! message {:computed-at-ms (now-ms)}))
  ([{:keys [id type payload]} {:keys [computed-at-ms]}]
   (case type
     "optimizer-result"
     (swap! system/store apply-worker-result id payload computed-at-ms)

     :optimizer-result
     (swap! system/store apply-worker-result id payload computed-at-ms)

     "optimizer-error"
     (swap! system/store apply-worker-error id payload computed-at-ms)

     :optimizer-error
     (swap! system/store apply-worker-error id payload computed-at-ms)

     nil)))
