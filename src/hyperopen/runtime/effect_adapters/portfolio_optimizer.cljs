(ns hyperopen.runtime.effect-adapters.portfolio-optimizer
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.default :as api]
            [hyperopen.portfolio.optimizer.application.scenario-records :as scenario-records]
            [hyperopen.portfolio.optimizer.application.run-bridge :as run-bridge]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.optimizer.infrastructure.history-client :as history-client]
            [hyperopen.portfolio.optimizer.infrastructure.persistence :as persistence]))

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

(defn- solved-run?
  [last-successful-run]
  (= :solved (get-in last-successful-run [:result :status])))

(defn- current-scenario-id
  [state opts now-ms]
  (or (:scenario-id opts)
      (get-in state [:portfolio :optimizer :active-scenario :loaded-id])
      (get-in state [:portfolio :optimizer :draft :id])
      (*next-scenario-id* now-ms)))

(defn- default-scenario-index
  []
  {:ordered-ids []
   :by-id {}})

(defn- begin-scenario-save-state
  [scenario-id started-at-ms]
  {:status :saving
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :error nil})

(defn- saved-scenario-save-state
  [scenario-id started-at-ms completed-at-ms]
  {:status :saved
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error nil})

(defn- failed-scenario-save-state
  [scenario-id started-at-ms completed-at-ms err]
  {:status :failed
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error {:message (error-message err)}})

(defn- begin-scenario-index-load-state
  [started-at-ms]
  {:status :loading
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :error nil})

(defn- loaded-scenario-index-load-state
  [started-at-ms completed-at-ms]
  {:status :loaded
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error nil})

(defn- failed-scenario-index-load-state
  [started-at-ms completed-at-ms err]
  {:status :failed
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error {:message (error-message err)}})

(defn- begin-scenario-load-state
  [scenario-id started-at-ms]
  {:status :loading
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :error nil})

(defn- loaded-scenario-load-state
  [scenario-id started-at-ms completed-at-ms]
  {:status :loaded
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error nil})

(defn- not-found-scenario-load-state
  [scenario-id started-at-ms completed-at-ms]
  {:status :not-found
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error {:message (str "Scenario " scenario-id " was not found.")}})

(defn- failed-scenario-load-state
  [scenario-id started-at-ms completed-at-ms err]
  {:status :failed
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error {:message (error-message err)}})

(defn- apply-scenario-save-success
  [state scenario-index scenario-record started-at-ms completed-at-ms]
  (-> state
      (assoc-in [:portfolio :optimizer :draft] (:config scenario-record))
      (assoc-in [:portfolio :optimizer :active-scenario]
                {:loaded-id (:id scenario-record)
                 :status :saved
                 :read-only? false})
      (assoc-in [:portfolio :optimizer :scenario-index] scenario-index)
      (assoc-in [:portfolio :optimizer :scenario-save-state]
                (saved-scenario-save-state (:id scenario-record)
                                           started-at-ms
                                           completed-at-ms))))

(defn- apply-scenario-save-error
  [state scenario-id started-at-ms completed-at-ms err]
  (assoc-in state
            [:portfolio :optimizer :scenario-save-state]
            (failed-scenario-save-state scenario-id
                                        started-at-ms
                                        completed-at-ms
                                        err)))

(defn- apply-scenario-index-load-success
  [state scenario-index started-at-ms completed-at-ms]
  (-> state
      (assoc-in [:portfolio :optimizer :scenario-index] scenario-index)
      (assoc-in [:portfolio :optimizer :scenario-index-load-state]
                (loaded-scenario-index-load-state started-at-ms completed-at-ms))))

(defn- apply-scenario-index-load-error
  [state started-at-ms completed-at-ms err]
  (assoc-in state
            [:portfolio :optimizer :scenario-index-load-state]
            (failed-scenario-index-load-state started-at-ms
                                             completed-at-ms
                                             err)))

(defn- apply-scenario-load-success
  [state scenario-id scenario-record started-at-ms completed-at-ms]
  (let [scenario-index (scenario-records/refresh-scenario-index-summary
                        (or (get-in state [:portfolio :optimizer :scenario-index])
                            (default-scenario-index))
                        (scenario-records/scenario-summary scenario-record))]
    (-> state
        (assoc-in [:portfolio :optimizer :draft] (:config scenario-record))
        (assoc-in [:portfolio :optimizer :last-successful-run] (:saved-run scenario-record))
        (assoc-in [:portfolio :optimizer :active-scenario]
                  {:loaded-id scenario-id
                   :status (:status scenario-record)
                   :read-only? false})
        (assoc-in [:portfolio :optimizer :scenario-index] scenario-index)
        (assoc-in [:portfolio :optimizer :scenario-load-state]
                  (loaded-scenario-load-state scenario-id
                                              started-at-ms
                                              completed-at-ms)))))

(defn- apply-scenario-load-not-found
  [state scenario-id started-at-ms completed-at-ms]
  (assoc-in state
            [:portfolio :optimizer :scenario-load-state]
            (not-found-scenario-load-state scenario-id
                                           started-at-ms
                                           completed-at-ms)))

(defn- apply-scenario-load-error
  [state scenario-id started-at-ms completed-at-ms err]
  (assoc-in state
            [:portfolio :optimizer :scenario-load-state]
            (failed-scenario-load-state scenario-id
                                        started-at-ms
                                        completed-at-ms
                                        err)))

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

(defn load-portfolio-optimizer-scenario-index-effect
  ([_ store]
   (load-portfolio-optimizer-scenario-index-effect nil store nil))
  ([_ store _opts]
   (let [state @store
         address (account-context/effective-account-address state)
         now-ms-fn *now-ms*
         load-scenario-index! *load-scenario-index!*
         started-at-ms (now-ms-fn)]
     (if address
       (do
         (swap! store assoc-in
                [:portfolio :optimizer :scenario-index-load-state]
                (begin-scenario-index-load-state started-at-ms))
         (-> (load-scenario-index! address)
             (.then (fn [loaded-index]
                      (let [completed-at-ms (now-ms-fn)
                            scenario-index (or loaded-index
                                               (default-scenario-index))]
                        (swap! store
                               apply-scenario-index-load-success
                               scenario-index
                               started-at-ms
                               completed-at-ms)
                        scenario-index)))
             (.catch (fn [err]
                       (let [completed-at-ms (now-ms-fn)]
                         (swap! store
                                apply-scenario-index-load-error
                                started-at-ms
                                completed-at-ms
                                err))
                       nil))))
       (do
         (swap! store
                apply-scenario-index-load-error
                started-at-ms
                started-at-ms
                {:message "Cannot load scenario index without an address."})
         (js/Promise.resolve nil))))))

(defn load-portfolio-optimizer-scenario-effect
  ([_ store scenario-id]
   (load-portfolio-optimizer-scenario-effect nil store scenario-id nil))
  ([_ store scenario-id _opts]
   (let [now-ms-fn *now-ms*
         load-scenario! *load-scenario!*
         started-at-ms (now-ms-fn)]
     (if scenario-id
       (do
         (swap! store assoc-in
                [:portfolio :optimizer :scenario-load-state]
                (begin-scenario-load-state scenario-id started-at-ms))
         (-> (load-scenario! scenario-id)
             (.then (fn [scenario-record]
                      (let [completed-at-ms (now-ms-fn)]
                        (if (map? scenario-record)
                          (do
                            (swap! store
                                   apply-scenario-load-success
                                   scenario-id
                                   scenario-record
                                   started-at-ms
                                   completed-at-ms)
                            scenario-record)
                          (do
                            (swap! store
                                   apply-scenario-load-not-found
                                   scenario-id
                                   started-at-ms
                                   completed-at-ms)
                            nil)))))
             (.catch (fn [err]
                       (let [completed-at-ms (now-ms-fn)]
                         (swap! store
                                apply-scenario-load-error
                                scenario-id
                                started-at-ms
                                completed-at-ms
                                err))
                       nil))))
       (do
         (swap! store
                apply-scenario-load-error
                scenario-id
                started-at-ms
                started-at-ms
                {:message "Cannot load scenario without a scenario id."})
         (js/Promise.resolve nil))))))

(defn save-portfolio-optimizer-scenario-effect
  ([_ store]
   (save-portfolio-optimizer-scenario-effect nil store nil))
  ([_ store opts]
   (let [opts* (or opts {})
         state @store
         address (account-context/effective-account-address state)
         draft (or (get-in state [:portfolio :optimizer :draft])
                   (optimizer-defaults/default-draft))
         last-successful-run (get-in state [:portfolio :optimizer :last-successful-run])
         now-ms-fn *now-ms*
         load-scenario-index! *load-scenario-index!*
         save-scenario! *save-scenario!*
         save-scenario-index! *save-scenario-index!*
         started-at-ms (now-ms-fn)
         scenario-id (current-scenario-id state opts* started-at-ms)
         existing-index (or (get-in state [:portfolio :optimizer :scenario-index])
                            (default-scenario-index))]
     (if (and address
              scenario-id
              (solved-run? last-successful-run))
       (do
         (swap! store assoc-in
                [:portfolio :optimizer :scenario-save-state]
                (begin-scenario-save-state scenario-id started-at-ms))
         (-> (load-scenario-index! address)
             (.then (fn [loaded-index]
                      (let [scenario-record (scenario-records/build-saved-scenario-record
                                             {:address address
                                              :scenario-id scenario-id
                                              :draft draft
                                              :last-successful-run last-successful-run
                                              :saved-at-ms started-at-ms})
                            scenario-index (scenario-records/upsert-scenario-index
                                            (or loaded-index existing-index (default-scenario-index))
                                            (scenario-records/scenario-summary scenario-record))]
                        (-> (save-scenario! scenario-id scenario-record)
                            (.then (fn [_]
                                     (save-scenario-index! address scenario-index)))
                            (.then (fn [_]
                                     (let [completed-at-ms (now-ms-fn)]
                                       (swap! store
                                              apply-scenario-save-success
                                              scenario-index
                                              scenario-record
                                              started-at-ms
                                              completed-at-ms)
                                       scenario-record)))))))
             (.catch (fn [err]
                       (let [completed-at-ms (now-ms-fn)]
                         (swap! store
                                apply-scenario-save-error
                                scenario-id
                                started-at-ms
                                completed-at-ms
                                err))
                       nil))))
       (do
         (swap! store assoc-in
                [:portfolio :optimizer :scenario-save-state]
                (failed-scenario-save-state scenario-id
                                            started-at-ms
                                            started-at-ms
                                            {:message "Cannot save scenario without an address and solved run."}))
         (js/Promise.resolve nil))))))
