(ns hyperopen.runtime.effect-adapters.portfolio-optimizer.history
  (:require [hyperopen.portfolio.optimizer.application.history-prefetch :as history-prefetch]))

(def ^:private default-funding-window-ms
  (* 365 24 60 60 1000))

(defn- request-candle-snapshot!
  [{:keys [request-candle-snapshot!]} coin opts]
  (request-candle-snapshot! coin opts))

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
  [env state opts]
  (let [opts* (or opts {})
        runtime (optimizer-runtime state)
        now-ms (or (:now-ms opts*)
                   (:as-of-ms runtime)
                   ((:now-ms env)))]
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

(defn- selection-prefetch?
  [opts]
  (and (= :selection-prefetch (:source opts))
       (:queue? opts)))

(defn- request-opts
  [opts]
  (dissoc (or opts {}) :on-progress :source :queue? :merge?))

(defn- merge-history-bundle
  [history-data bundle completed-at-ms]
  (-> (or history-data {})
      (update :candle-history-by-coin
              merge
              (or (:candle-history-by-coin bundle) {}))
      (update :funding-history-by-coin
              merge
              (or (:funding-history-by-coin bundle) {}))
      (update :vault-details-by-address
              merge
              (or (:vault-details-by-address bundle) {}))
      (update :warnings
              #(vec (concat (or % []) (or (:warnings bundle) []))))
      (assoc :loaded-at-ms completed-at-ms)))

(defn- begin-selection-prefetch
  [state instrument-id* signature started-at-ms]
  (-> state
      (assoc-in [:portfolio :optimizer :history-load-state]
                (begin-history-load-state signature started-at-ms))
      (update-in [:portfolio :optimizer :history-prefetch]
                 (fn [prefetch-state]
                   (-> (merge history-prefetch/default-state prefetch-state)
                       (assoc :active-instrument-id instrument-id*)
                       (assoc-in [:by-instrument-id instrument-id*]
                                 (history-prefetch/loading-status started-at-ms)))))))

(defn- remove-queued-instrument
  [queue instrument-id*]
  (vec (remove #(= instrument-id* (history-prefetch/instrument-id %))
               (or queue []))))

(defn- finish-selection-prefetch-state
  [prefetch-state instrument-id* status]
  (let [prefetch-state* (merge history-prefetch/default-state prefetch-state)]
    (cond-> (-> prefetch-state*
                (update :queue remove-queued-instrument instrument-id*)
                (assoc-in [:by-instrument-id instrument-id*] status))
      (= instrument-id* (:active-instrument-id prefetch-state*))
      (assoc :active-instrument-id nil))))

(defn- current-universe-ids
  [state]
  (keep :instrument-id
        (get-in state [:portfolio :optimizer :draft :universe])))

(defn- apply-selection-prefetch-success
  [state instrument-id* signature completed-at-ms bundle]
  (let [current-prefetch-state (history-prefetch/prefetch-state state)
        current-status (get-in current-prefetch-state
                               [:by-instrument-id instrument-id*])
        selected? (history-prefetch/instrument-selected? state instrument-id*)
        current-signature? (= signature
                              (get-in state
                                      [:portfolio
                                       :optimizer
                                       :history-load-state
                                       :request-signature]))
        status (history-prefetch/succeeded-status
                (:started-at-ms current-status)
                completed-at-ms
                (:warnings bundle))]
    (cond-> state
      (and selected? current-signature?)
      (update-in [:portfolio :optimizer :history-data]
                 merge-history-bundle
                 bundle
                 completed-at-ms)

      current-signature?
      (assoc-in [:portfolio :optimizer :history-load-state]
                (success-history-load-state
                 (get-in state [:portfolio :optimizer :history-load-state])
                 completed-at-ms
                 bundle))

      :always
      (update-in [:portfolio :optimizer :history-prefetch]
                 finish-selection-prefetch-state
                 instrument-id*
                 status)

      :always
      (update-in [:portfolio :optimizer :history-prefetch]
                 history-prefetch/cleanup-to-instrument-ids
                 (current-universe-ids state)))))

(defn- apply-selection-prefetch-error
  [state instrument-id* signature completed-at-ms err]
  (let [current-prefetch-state (history-prefetch/prefetch-state state)
        current-status (get-in current-prefetch-state
                               [:by-instrument-id instrument-id*])
        current-load-state (get-in state [:portfolio :optimizer :history-load-state])
        current-signature? (= signature (:request-signature current-load-state))
        status (history-prefetch/failed-status
                (:started-at-ms current-status)
                completed-at-ms
                {:message (error-message err)})]
    (cond-> state
      current-signature?
      (assoc-in [:portfolio :optimizer :history-load-state]
                (failed-history-load-state current-load-state completed-at-ms err))

      :always
      (update-in [:portfolio :optimizer :history-prefetch]
                 finish-selection-prefetch-state
                 instrument-id*
                 status)

      :always
      (update-in [:portfolio :optimizer :history-prefetch]
                 history-prefetch/cleanup-to-instrument-ids
                 (current-universe-ids state)))))

(declare drain-selection-prefetch!)

(defn- request-history-bundle!
  [env on-progress request]
  ((:request-history-bundle! env)
   {:request-candle-snapshot! (partial request-candle-snapshot! env)
    :request-market-funding-history! (:request-market-funding-history! env)
    :request-vault-details! (:request-vault-details! env)
    :on-progress on-progress}
   request))

(defn- drain-selection-prefetch!
  [env store opts]
  (let [state @store
        prefetch-state (history-prefetch/prefetch-state state)
        active-id (:active-instrument-id prefetch-state)
        instrument (history-prefetch/first-queued-instrument state)
        instrument-id* (history-prefetch/instrument-id instrument)]
    (cond
      active-id
      (js/Promise.resolve nil)

      (nil? instrument-id*)
      (js/Promise.resolve nil)

      :else
      (let [request (history-request env
                                     state
                                     (assoc (request-opts opts)
                                            :universe [instrument]))
            signature (request-signature request)
            now-ms-fn (:now-ms env)
            started-at-ms (now-ms-fn)
            on-progress (:on-progress opts)]
        (swap! store begin-selection-prefetch instrument-id* signature started-at-ms)
        (-> (request-history-bundle! env on-progress request)
            (.then (fn [bundle]
                     (let [completed-at-ms (now-ms-fn)]
                       (swap! store
                              apply-selection-prefetch-success
                              instrument-id*
                              signature
                              completed-at-ms
                              bundle)
                       (drain-selection-prefetch! env store opts))))
            (.catch (fn [err]
                      (let [completed-at-ms (now-ms-fn)]
                        (swap! store
                               apply-selection-prefetch-error
                               instrument-id*
                               signature
                               completed-at-ms
                               err)
                        (drain-selection-prefetch! env store opts)))))))))

(defn load-portfolio-optimizer-history-effect
  ([_env store]
   (load-portfolio-optimizer-history-effect _env nil store nil))
  ([env _ store opts]
   (let [opts* (or opts {})
         request (history-request env @store (dissoc opts* :on-progress))
         signature (request-signature request)
         on-progress (:on-progress opts*)
         now-ms-fn (:now-ms env)
         started-at-ms (now-ms-fn)]
     (cond
       (selection-prefetch? opts*)
       (drain-selection-prefetch! env store opts*)

       (seq (:universe request))
       (do
         (swap! store assoc-in
                [:portfolio :optimizer :history-load-state]
                (begin-history-load-state signature started-at-ms))
         (-> (request-history-bundle! env on-progress request)
             (.then (fn [bundle]
                      (let [completed-at-ms (now-ms-fn)]
                        (swap! store apply-history-success signature completed-at-ms bundle)
                        bundle)))
             (.catch (fn [err]
                       (let [completed-at-ms (now-ms-fn)]
                         (swap! store apply-history-error signature completed-at-ms err))
                       nil))))
       :else
       (js/Promise.resolve nil)))))
