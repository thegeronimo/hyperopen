(ns hyperopen.runtime.effect-adapters.portfolio-optimizer
  (:require [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.portfolio.optimizer.application.execution :as execution]
            [hyperopen.portfolio.optimizer.application.run-bridge :as run-bridge]
            [hyperopen.portfolio.optimizer.infrastructure.history-client :as history-client]
            [hyperopen.portfolio.optimizer.infrastructure.persistence :as persistence]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer-scenarios :as scenario-effects]))

(def ^:dynamic *request-run!* run-bridge/request-run!)
(def ^:dynamic *request-history-bundle!* history-client/request-history-bundle!)
(def ^:dynamic *request-candle-snapshot!* api/request-candle-snapshot!)
(def ^:dynamic *request-market-funding-history!* api/request-market-funding-history!)
(def ^:dynamic *load-scenario-index!* persistence/load-scenario-index!)
(def ^:dynamic *load-scenario!* persistence/load-scenario!)
(def ^:dynamic *save-scenario!* persistence/save-scenario!)
(def ^:dynamic *save-scenario-index!* persistence/save-scenario-index!)
(def ^:dynamic *next-scenario-id* (fn [now-ms] (str "scn_" now-ms)))
(def ^:dynamic *now-ms* #(.now js/Date))
(def ^:dynamic *submit-order!* trading-api/submit-order!)
(def ^:dynamic *dispatch!* nxr/dispatch)

(def ^:private default-funding-window-ms
  (* 365 24 60 60 1000))

(defn run-portfolio-optimizer-effect
  ([_ store request request-signature]
   (run-portfolio-optimizer-effect nil store request request-signature nil))
  ([_ store request request-signature opts]
   (let [opts* (or opts {})]
     (*request-run!*
      (cond-> {:request request
               :request-signature request-signature
               :store store}
        (contains? opts* :computed-at-ms)
        (assoc :computed-at-ms (:computed-at-ms opts*)))))))

(defn- request-candle-snapshot!
  [coin opts]
  (*request-candle-snapshot!* coin
                             :interval (:interval opts)
                             :bars (:bars opts)
                             :priority (:priority opts)))

(defn- request-signature
  [request]
  (select-keys request
               [:universe
                :interval
                :bars
                :priority
                :now-ms
                :funding-window-ms
                :funding-start-ms
                :funding-end-ms]))

(defn- optimizer-runtime
  [state]
  (get-in state [:portfolio :optimizer :runtime]))

(defn- history-request
  [state opts]
  (let [opts* (or opts {})
        runtime (optimizer-runtime state)
        now-ms (or (:now-ms opts*)
                   (:as-of-ms runtime)
                   (*now-ms*))]
    (merge {:universe (get-in state [:portfolio :optimizer :draft :universe])
            :interval :1d
            :bars 365
            :priority :high
            :now-ms now-ms
            :funding-window-ms default-funding-window-ms}
           (select-keys runtime
                        [:stale-after-ms
                         :funding-periods-per-year
                         :funding-window-ms])
           opts*)))

(defn- begin-history-load-state
  [signature started-at-ms]
  {:status :loading
   :request-signature signature
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :error nil
   :warnings []})

(defn- success-history-load-state
  [current-state completed-at-ms bundle]
  {:status :succeeded
   :request-signature (:request-signature current-state)
   :started-at-ms (:started-at-ms current-state)
   :completed-at-ms completed-at-ms
   :error nil
   :warnings (vec (:warnings bundle))})

(defn- error-message
  [err]
  (or (when (map? err)
        (:message err))
      (some-> err .-message)
      (str err)))

(defn- failed-history-load-state
  [current-state completed-at-ms err]
  {:status :failed
   :request-signature (:request-signature current-state)
   :started-at-ms (:started-at-ms current-state)
   :completed-at-ms completed-at-ms
   :error {:message (error-message err)}
   :warnings []})

(defn- apply-history-success
  [state signature completed-at-ms bundle]
  (if (= signature
         (get-in state [:portfolio :optimizer :history-load-state :request-signature]))
    (let [current-state (get-in state [:portfolio :optimizer :history-load-state])]
      (-> state
          (assoc-in [:portfolio :optimizer :history-data]
                    (assoc bundle :loaded-at-ms completed-at-ms))
          (assoc-in [:portfolio :optimizer :history-load-state]
                    (success-history-load-state current-state completed-at-ms bundle))))
    state))

(defn- apply-history-error
  [state signature completed-at-ms err]
  (if (= signature
         (get-in state [:portfolio :optimizer :history-load-state :request-signature]))
    (let [current-state (get-in state [:portfolio :optimizer :history-load-state])]
      (assoc-in state
                [:portfolio :optimizer :history-load-state]
                (failed-history-load-state current-state completed-at-ms err)))
    state))

(defn load-portfolio-optimizer-history-effect
  ([_ store]
   (load-portfolio-optimizer-history-effect nil store nil))
  ([_ store opts]
   (let [request (history-request @store opts)
         signature (request-signature request)
         now-ms-fn *now-ms*
         started-at-ms (now-ms-fn)]
     (if (seq (:universe request))
       (do
         (swap! store assoc-in
                [:portfolio :optimizer :history-load-state]
                (begin-history-load-state signature started-at-ms))
         (-> (*request-history-bundle!*
              {:request-candle-snapshot! request-candle-snapshot!
               :request-market-funding-history! *request-market-funding-history!*}
             request)
             (.then (fn [bundle]
                      (let [completed-at-ms (now-ms-fn)]
                        (swap! store apply-history-success signature completed-at-ms bundle)
                        bundle)))
             (.catch (fn [err]
                       (let [completed-at-ms (now-ms-fn)]
                         (swap! store apply-history-error signature completed-at-ms err))
                       nil))))
       (js/Promise.resolve nil)))))

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

(defn execute-portfolio-optimizer-plan-effect
  ([_ store plan]
   (let [now-ms-fn *now-ms*
         submit-order! *submit-order!*
         dispatch! *dispatch!*
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
                        ledger)))))))))

(defn- scenario-env
  []
  {:now-ms *now-ms*
   :next-scenario-id *next-scenario-id*
   :load-scenario-index! *load-scenario-index!*
   :load-scenario! *load-scenario!*
   :save-scenario! *save-scenario!*
   :save-scenario-index! *save-scenario-index!*})

(defn load-portfolio-optimizer-scenario-index-effect
  ([_ store]
   (load-portfolio-optimizer-scenario-index-effect nil store nil))
  ([_ store opts]
   (scenario-effects/load-portfolio-optimizer-scenario-index-effect
    (scenario-env)
    store
    opts)))

(defn load-portfolio-optimizer-scenario-effect
  ([_ store scenario-id]
   (load-portfolio-optimizer-scenario-effect nil store scenario-id nil))
  ([_ store scenario-id opts]
   (scenario-effects/load-portfolio-optimizer-scenario-effect
    (scenario-env)
    store
    scenario-id
    opts)))

(defn archive-portfolio-optimizer-scenario-effect
  ([_ store scenario-id]
   (archive-portfolio-optimizer-scenario-effect nil store scenario-id nil))
  ([_ store scenario-id opts]
   (scenario-effects/archive-portfolio-optimizer-scenario-effect
    (scenario-env)
    store
    scenario-id
    opts)))

(defn duplicate-portfolio-optimizer-scenario-effect
  ([_ store scenario-id]
   (duplicate-portfolio-optimizer-scenario-effect nil store scenario-id nil))
  ([_ store scenario-id opts]
   (scenario-effects/duplicate-portfolio-optimizer-scenario-effect
    (scenario-env)
    store
    scenario-id
    opts)))

(defn save-portfolio-optimizer-scenario-effect
  ([_ store]
   (save-portfolio-optimizer-scenario-effect nil store nil))
  ([_ store opts]
   (scenario-effects/save-portfolio-optimizer-scenario-effect
    (scenario-env)
    store
    opts)))
