(ns hyperopen.runtime.effect-adapters.portfolio-optimizer.history)

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
     (if (seq (:universe request))
       (do
         (swap! store assoc-in
                [:portfolio :optimizer :history-load-state]
                (begin-history-load-state signature started-at-ms))
         (-> ((:request-history-bundle! env)
              {:request-candle-snapshot! (partial request-candle-snapshot! env)
               :request-market-funding-history! (:request-market-funding-history! env)
               :request-vault-details! (:request-vault-details! env)
               :on-progress on-progress}
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
