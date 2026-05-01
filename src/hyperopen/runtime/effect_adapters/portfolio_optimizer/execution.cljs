(ns hyperopen.runtime.effect-adapters.portfolio-optimizer.execution
  (:require [hyperopen.portfolio.optimizer.application.execution :as execution]
            [hyperopen.portfolio.optimizer.application.scenario-records :as scenario-records]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer-scenarios :as scenario-effects]))

(defn- begin-execution-state
  [attempt started-at-ms]
  {:status :submitting
   :attempt attempt
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :history []
   :error nil})

(defn- runtime-error-message
  [err]
  (or (when (map? err) (:message err))
      (some-> err .-message)
      (str err)))

(defn- mark-row-failed
  [row err]
  (assoc row
         :status :failed
         :error {:message (runtime-error-message err)}))

(defn- submit-action!
  [submit-order! store address action]
  (submit-order! store address action))

(defn- submit-actions!
  [submit-order! store address actions]
  (reduce
   (fn [promise action]
     (.then promise
            (fn [responses]
              (-> (submit-action! submit-order! store address action)
                  (.then (fn [resp]
                           (conj responses resp)))))))
   (js/Promise.resolve [])
   actions))

(defn- failed-pre-action-response
  [responses]
  (some #(when-not (execution/response-ok? %) %) responses))

(defn- submit-execution-row!
  [submit-order! store address row]
  (if-not (= :ready (:status row))
    (js/Promise.resolve row)
    (let [request (:request row)
          pre-actions (->> (:pre-actions request)
                           (filter map?)
                           vec)
          action (:action request)]
      (if-not (map? action)
        (js/Promise.resolve
         (assoc row
                :status :failed
                :error {:message "Execution row is missing an order action."}))
        (let [submit-promise
              (.then (submit-actions! submit-order! store address pre-actions)
                     (fn [pre-responses]
                       (if-let [failed-pre-action (failed-pre-action-response pre-responses)]
                         (assoc row
                                :status :failed
                                :pre-action-responses pre-responses
                                :error {:message (str "Pre-submit action failed: "
                                                      (pr-str failed-pre-action))})
                         (.then (submit-action! submit-order! store address action)
                                (fn [resp]
                                  (if (execution/response-ok? resp)
                                    (assoc row
                                           :status :submitted
                                           :response resp)
                                    (assoc row
                                           :status :failed
                                           :response resp
                                           :error {:message (str "Order submit failed: "
                                                                 (pr-str resp))})))))))]
          (.catch submit-promise
                  (fn [err]
                    (mark-row-failed row err))))))))

(defn- submit-execution-rows!
  [submit-order! store address rows]
  (reduce
   (fn [promise row]
     (.then promise
            (fn [submitted-rows]
              (-> (submit-execution-row! submit-order! store address row)
                  (.then (fn [submitted-row]
                           (conj submitted-rows submitted-row)))))))
   (js/Promise.resolve [])
   rows))

(defn- execution-ledger
  [attempt started-at-ms completed-at-ms rows]
  {:attempt-id (str "exec_" started-at-ms)
   :scenario-id (:scenario-id attempt)
   :status (execution/final-ledger-status rows)
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :rows rows})

(defn- apply-execution-ledger
  [state ledger]
  (let [history (conj (vec (get-in state [:portfolio :optimizer :execution :history]))
                      ledger)
        scenario-status (:status ledger)]
    (cond-> (-> state
                (assoc-in [:portfolio :optimizer :execution]
                          {:status scenario-status
                           :attempt nil
                           :history history
                           :error (when (= :failed scenario-status)
                                    {:message "Execution failed before any rows submitted."})})
                (assoc-in [:portfolio :optimizer :execution-modal :submitting?] false)
                (assoc-in [:portfolio :optimizer :execution-modal :error]
                          (when (= :failed scenario-status)
                            "Execution failed before any rows submitted.")))
      (contains? #{:executed :partially-executed} scenario-status)
      (assoc-in [:portfolio :optimizer :active-scenario :status] scenario-status))))

(defn- refresh-after-execution!
  [dispatch! store address ledger]
  (when (and address
             (some #(= :submitted (:status %)) (:rows ledger)))
    (dispatch! store nil [[:actions/load-user-data address]
                          [:actions/refresh-order-history]])))

(defn- apply-execution-ledger-persistence
  [state scenario-index scenario-record]
  (-> state
      (assoc-in [:portfolio :optimizer :scenario-index] scenario-index)
      (assoc-in [:portfolio :optimizer :draft] (:config scenario-record))
      (assoc-in [:portfolio :optimizer :active-scenario :status]
                (:status scenario-record))))

(defn- persist-execution-ledger!
  [{:keys [load-scenario!
           load-scenario-index!
           save-scenario!
           save-scenario-index!]}
   store
   address
   ledger]
  (let [scenario-id (:scenario-id ledger)]
    (if-not (and address scenario-id)
      (js/Promise.resolve ledger)
      (let [persist-promise
            (.then (load-scenario! scenario-id)
                   (fn [scenario-record]
                     (if-not (map? scenario-record)
                       ledger
                       (.then (load-scenario-index! address)
                              (fn [loaded-index]
                                (let [updated-record
                                      (scenario-records/append-execution-ledger
                                       scenario-record
                                       ledger)
                                      scenario-index
                                      (scenario-records/refresh-scenario-index-summary
                                       (or loaded-index
                                           (get-in @store
                                                   [:portfolio :optimizer :scenario-index])
                                           (scenario-effects/default-scenario-index))
                                       (scenario-records/scenario-summary updated-record))]
                                  (-> (save-scenario! scenario-id updated-record)
                                      (.then (fn [_]
                                               (save-scenario-index! address scenario-index)))
                                      (.then (fn [_]
                                               (swap! store
                                                      apply-execution-ledger-persistence
                                                      scenario-index
                                                      updated-record)
                                               ledger)))))))))]
        (.catch persist-promise
                (fn [err]
                  (swap! store assoc-in
                         [:portfolio :optimizer :execution :persistence-error]
                         {:message (runtime-error-message err)})
                  ledger))))))

(defn execute-portfolio-optimizer-plan-effect
  [env _ store plan]
  (let [now-ms-fn (:now-ms env)
        submit-order! (:submit-order! env)
        dispatch! (:dispatch! env)
        persistence-env (select-keys env
                                     [:load-scenario!
                                      :load-scenario-index!
                                      :save-scenario!
                                      :save-scenario-index!])
        state @store
        address (get-in state [:wallet :address])
        started-at-ms (now-ms-fn)
        attempt (execution/build-execution-attempt
                 {:plan plan
                  :market-by-key (get-in state [:asset-selector :market-by-key])
                  :orderbooks (:orderbooks state)})]
    (if-not address
      (let [completed-at-ms (now-ms-fn)
            rows (mapv #(if (= :ready (:status %))
                          (assoc % :status :failed
                                 :error {:message "Connect your wallet before executing."})
                          %)
                       (:rows attempt))
            ledger (execution-ledger attempt started-at-ms completed-at-ms rows)]
        (swap! store apply-execution-ledger ledger)
        (js/Promise.resolve ledger))
      (do
        (swap! store assoc-in
               [:portfolio :optimizer :execution]
               (begin-execution-state attempt started-at-ms))
        (-> (submit-execution-rows! submit-order! store address (:rows attempt))
            (.then (fn [rows]
                     (let [completed-at-ms (now-ms-fn)
                           ledger (execution-ledger attempt
                                                    started-at-ms
                                                    completed-at-ms
                                                    rows)]
                       (swap! store apply-execution-ledger ledger)
                       (refresh-after-execution! dispatch! store address ledger)
                       (persist-execution-ledger! persistence-env
                                                  store
                                                  address
                                                  ledger)))))))))
