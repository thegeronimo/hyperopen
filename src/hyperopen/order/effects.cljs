(ns hyperopen.order.effects
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.account.surface-service :as account-surface-service]
            [hyperopen.api.default :as api]
            [hyperopen.api.market-metadata.facade :as market-metadata]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.order.effects.spot-refresh :as spot-refresh]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.order.cancel-guard :as cancel-guard]
            [hyperopen.order.toast-payloads :as toast-payloads]
            [hyperopen.api.trading :as trading-api]))

(defn- cancel-request-oids
  [request]
  (cancel-guard/cancel-request-oids request))

(defn- add-pending-cancel-oids
  [state cancel-oids]
  (if (seq cancel-oids)
    (update-in state [:orders :pending-cancel-oids]
               (fn [pending]
                 (into (if (set? pending)
                         pending
                         (set (or pending [])))
                       cancel-oids)))
    state))

(defn- remove-pending-cancel-oids
  [state cancel-oids]
  (if (seq cancel-oids)
    (update-in state [:orders :pending-cancel-oids]
               (fn [pending]
                 (let [pending-set (if (set? pending)
                                     pending
                                     (set (or pending [])))
                       next-set (reduce disj pending-set cancel-oids)]
                   (when (seq next-set)
                     next-set))))
    state))

(defn prune-canceled-open-orders
  [state request]
  (let [cancel-entries (cancel-guard/cancel-request-guard-entries request)]
    (cancel-guard/prune-open-order-sources state cancel-entries)))

(defn- active-wallet-address?
  [state address]
  (let [wallet-address (get-in state [:wallet :address])
        address* (account-context/normalize-address address)
        wallet-address* (account-context/normalize-address wallet-address)]
    (if (and address* wallet-address*)
      (= address* wallet-address*)
      (= address wallet-address))))

(defn- apply-open-orders-success-for-active-address
  [store address dex]
  (fn [payload]
    (swap! store
           (fn [state]
             (if (active-wallet-address? state address)
               (api-projections/apply-open-orders-success state dex payload)
               state)))
    payload))

(defn- apply-open-orders-error-for-active-address
  [store address]
  (fn [err]
    (swap! store
           (fn [state]
             (if (active-wallet-address? state address)
               (api-projections/apply-open-orders-error state err)
               state)))
    (promise-effects/reject-error err)))

(defn- refresh-open-orders-snapshot!
  [store address dex opts]
  (-> (api/request-frontend-open-orders! address
                                         (cond-> (merge {:force-refresh? true} (or opts {}))
                                           (and dex (not= dex "")) (assoc :dex dex)))
      (.then (apply-open-orders-success-for-active-address store address dex))
      (.catch (apply-open-orders-error-for-active-address store address))))

(defn- refresh-default-clearinghouse-snapshot!
  [store address opts]
  (-> (api/request-clearinghouse-state! address nil opts)
      (.then (fn [data]
               (swap! store
                      (fn [state]
                        ;; Guard stale async completions from older wallet sessions.
                        (if (= address (get-in state [:wallet :address]))
                          (assoc-in state [:webdata2 :clearinghouseState] data)
                          state)))))
      (.catch (fn [err]
                (telemetry/log! "Error refreshing default clearinghouse state after order mutation:" err)))))

(defn- refresh-perp-dex-clearinghouse-snapshot!
  [store address dex opts]
  (-> (api/request-clearinghouse-state! address dex opts)
      (.then (promise-effects/apply-success-and-return
              store
              api-projections/apply-perp-dex-clearinghouse-success
              dex))
      (.catch (promise-effects/apply-error-and-reject
               store
               api-projections/apply-perp-dex-clearinghouse-error))))

(defn- ensure-perp-dex-metadata!
  [store opts]
  (market-metadata/ensure-and-apply-perp-dex-metadata!
   {:store store
    :ensure-perp-dexs-data! api/ensure-perp-dexs-data!
    :apply-perp-dexs-success api-projections/apply-perp-dexs-success
    :apply-perp-dexs-error api-projections/apply-perp-dexs-error}
   opts))

(defn- refresh-account-surfaces-after-order-mutation!
  [store address & [{:keys [refresh-spot?]}]]
  (account-surface-service/refresh-after-order-mutation!
   {:store store
    :address address
    :ensure-perp-dexs! ensure-perp-dex-metadata!
    :refresh-open-orders! refresh-open-orders-snapshot!
    :refresh-default-clearinghouse! refresh-default-clearinghouse-snapshot!
    :refresh-spot-clearinghouse! spot-refresh/refresh-spot-clearinghouse-snapshot!
    :refresh-perp-dex-clearinghouse! refresh-perp-dex-clearinghouse-snapshot!
    :refresh-spot? refresh-spot?
    :resolve-current-address (fn [state] (get-in state [:wallet :address]))
    :log-fn telemetry/log!}))

(defn- submit-order-error-message
  [exchange-response-error resp]
  (str "Order placement failed: " (exchange-response-error resp)))

(defn- cancel-order-error-message
  [exchange-response-error resp]
  (str "Order cancellation failed: " (exchange-response-error resp)))

(defn- submit-status-entries
  [resp]
  (let [statuses (get-in resp [:response :data :statuses])
        status (get-in resp [:response :data :status])]
    (cond
      (sequential? statuses) (vec statuses)
      (some? status) [status]
      :else [])))

(defn- submit-status-error
  [idx status-entry]
  (let [error-value (when (map? status-entry)
                      (:error status-entry))]
    (when (some? error-value)
      (let [message (cond
                      (string? error-value) error-value
                      (map? error-value) (or (:message error-value)
                                             (pr-str error-value))
                      :else (str error-value))
            trimmed (str/trim (or message ""))]
        (when (seq trimmed)
          (str "Order " (inc idx) ": " trimmed))))))

(defn- submit-outcome
  [exchange-response-error resp]
  (let [top-level-ok? (= "ok" (:status resp))
        statuses (submit-status-entries resp)
        status-errors (->> statuses
                           (map-indexed submit-status-error)
                           (keep identity)
                           vec)
        status-count (count statuses)
        error-count (count status-errors)
        success-count (max 0 (- status-count error-count))
        partial? (and (pos? success-count) (pos? error-count))]
    (cond
      (not top-level-ok?)
      {:ok? false
       :success-count 0
       :error-text (str (exchange-response-error resp))
       :toast-message (submit-order-error-message exchange-response-error resp)}

      (pos? error-count)
      (let [prefix (if partial?
                     "Order placement partially failed: "
                     "Order placement failed: ")
            error-detail (str/join "; " status-errors)]
        {:ok? false
         :success-count success-count
         :error-text error-detail
         :toast-message (str prefix error-detail)})

      :else
      {:ok? true
       :success-count success-count})))

(defn- cancel-status-entries
  [request resp]
  (let [statuses (get-in resp [:response :data :statuses])
        status (get-in resp [:response :data :status])
        cancel-count (count (get-in request [:action :cancels]))]
    (cond
      (sequential? statuses)
      (vec
       (take cancel-count
             (concat statuses
                     (repeat {:error "Missing exchange cancel status."}))))

      (some? status)
      (if (= cancel-count 1)
        [status]
        (vec
         (take cancel-count
               (concat [status]
                       (repeat {:error "Missing exchange cancel status."})))))

      (pos? cancel-count)
      (vec (repeat cancel-count "success"))

      :else
      [])))

(defn- non-success-status-text
  [status-entry]
  (when (string? status-entry)
    (let [status-text (str/trim status-entry)]
      (when (and (seq status-text)
                 (not= "success" (str/lower-case status-text)))
        status-text))))

(defn- status-entry-error-value
  [status-entry]
  (cond
    (and (map? status-entry)
         (contains? status-entry :error))
    (:error status-entry)

    :else
    (non-success-status-text status-entry)))

(defn- error-value-message
  [error-value]
  (cond
    (string? error-value) error-value
    (map? error-value) (or (:message error-value)
                           (pr-str error-value))
    :else (some-> error-value str)))

(defn- nonblank-message
  [message]
  (let [trimmed (str/trim (or message ""))]
    (when (seq trimmed)
      trimmed)))

(defn- cancel-status-error-value
  [status-entry]
  (some-> status-entry
          status-entry-error-value
          error-value-message
          nonblank-message))

(defn- cancel-status-error
  [idx status-entry]
  (when-let [message (cancel-status-error-value status-entry)]
    (str "Order " (inc idx) ": " message)))

(defn- twap-cancel-request?
  [request]
  (= "twapCancel" (get-in request [:action :type])))

(defn- twap-cancel-error-message
  [exchange-response-error resp]
  (str "TWAP termination failed: " (exchange-response-error resp)))

(defn- twap-cancel-outcome
  [exchange-response-error resp]
  (let [top-level-ok? (= "ok" (:status resp))
        statuses (let [values (get-in resp [:response :data :statuses])
                       value (get-in resp [:response :data :status])]
                   (cond
                     (sequential? values) (vec values)
                     (some? value) [value]
                     :else []))
        first-error (some cancel-status-error-value statuses)]
    (cond
      (not top-level-ok?)
      {:ok? false
       :error-text (str (exchange-response-error resp))
       :toast-message (twap-cancel-error-message exchange-response-error resp)}

      (seq first-error)
      {:ok? false
       :error-text first-error
       :toast-message (str "TWAP termination failed: " first-error)}

      :else
      {:ok? true
       :toast-message (toast-payloads/twap-cancel-success-toast-payload)})))

(defn- successful-cancel-entries
  [request status-entries]
  (->> (map vector
            (get-in request [:action :cancels] [])
            status-entries)
       (keep (fn [[cancel status-entry]]
               (when-not (cancel-status-error-value status-entry)
                 cancel)))
       vec))

(defn- successful-cancel-request
  [request successful-cancels]
  (when (seq successful-cancels)
    {:action (assoc (:action request) :cancels (vec successful-cancels))}))

(defn- cancel-outcome
  [exchange-response-error request resp]
  (let [top-level-ok? (= "ok" (:status resp))
        status-entries (cancel-status-entries request resp)
        successful-cancels (successful-cancel-entries request status-entries)
        status-errors (->> status-entries
                           (map-indexed cancel-status-error)
                           (keep identity)
                           vec)
        success-count (count successful-cancels)
        error-count (count status-errors)
        partial? (and (pos? success-count) (pos? error-count))]
    (cond
      (not top-level-ok?)
      {:ok? false
       :success-cancels []
       :error-text (str (exchange-response-error resp))
       :toast-message (cancel-order-error-message exchange-response-error resp)}

      (pos? error-count)
      (let [prefix (if partial?
                     "Order cancellation partially failed: "
                     "Order cancellation failed: ")
            error-detail (str/join "; " status-errors)]
        {:ok? false
         :success-cancels successful-cancels
         :error-text error-detail
         :toast-message (str prefix error-detail)})

      :else
      {:ok? true
       :success-cancels successful-cancels
       :toast-message (toast-payloads/cancel-success-toast-payload success-count)})))

(defn- margin-mode-sync-error-message
  [error-text]
  (str "Margin mode update failed: " error-text))

(defn- pre-submit-outcome
  [exchange-response-error resp]
  (if (= "ok" (:status resp))
    {:ok? true}
    (let [error-text (str (exchange-response-error resp))]
      {:ok? false
       :error-text error-text
       :toast-message (margin-mode-sync-error-message error-text)})))

(defn- spectate-mode-precondition-error
  [state]
  (account-context/mutations-blocked-message state))

(defn- update-order-submit-runtime
  [state error-text]
  (-> state
      (assoc-in [:order-form-runtime :submitting?] false)
      (assoc-in [:order-form-runtime :error] error-text)))

(defn- open-enable-trading-recovery
  ([state]
   (-> state
       (update-order-submit-runtime nil)
       (assoc-in [:wallet :agent :recovery-modal-open?] true)))
  ([state error-text]
   (cond-> (open-enable-trading-recovery state)
     (seq error-text) (assoc-in [:wallet :agent :error] error-text))))

(defn- dispatch-unlock-agent-trading!
  [dispatch! store replay-action request]
  (when (fn? dispatch!)
    (case replay-action
      :actions/submit-unlocked-order-request
      (swap! store update-order-submit-runtime nil)

      :actions/submit-unlocked-cancel-request
      (swap! store assoc-in [:orders :cancel-error] nil)

      nil)
    (dispatch! store nil [[:actions/unlock-agent-trading
                           {:after-success-actions [[replay-action request]]}]])
    true))

(defn- trading-readiness-message
  [agent-status base-message unlock-message waiting-message]
  (case agent-status
    :locked unlock-message
    :unlocking waiting-message
    base-message))

(defn- run-pre-submit-actions!
  [store address request exchange-response-error runtime-error-message]
  (let [pre-actions (->> (:pre-actions request)
                         (filter map?)
                         vec)]
    (letfn [(submit-next! [remaining]
              (if (empty? remaining)
                (js/Promise.resolve {:ok? true})
                (-> (trading-api/submit-order! store address (first remaining))
                    (.then (fn [resp]
                             (let [result (pre-submit-outcome exchange-response-error resp)]
                               (if (:ok? result)
                                 (submit-next! (rest remaining))
                                 result))))
                    (.catch (fn [err]
                              (let [error-text (runtime-error-message err)]
                                {:ok? false
                                 :error-text error-text
                                 :toast-message (margin-mode-sync-error-message error-text)}))))))]
      (submit-next! pre-actions))))

(defn api-submit-order
  [{:keys [dispatch! exchange-response-error runtime-error-message show-toast!]} _ store request]
  (let [state @store
        spectate-mode-message (spectate-mode-precondition-error state)
        address (get-in state [:wallet :address])
        agent-status (get-in state [:wallet :agent :status])]
    (if (seq spectate-mode-message)
      (do
        (swap! store assoc-in [:order-form-runtime :error] spectate-mode-message)
        (show-toast! store :error spectate-mode-message))
      (if (nil? address)
      (do
        (swap! store assoc-in [:order-form-runtime :error] "Connect your wallet before submitting.")
        (show-toast! store :error "Connect your wallet before submitting."))
      (if (not= :ready agent-status)
        (let [message (trading-readiness-message
                       agent-status
                       "Enable trading before submitting orders."
                       "Unlock trading before submitting orders."
                       "Awaiting passkey before submitting orders.")]
          (case agent-status
            :locked
            (when-not (dispatch-unlock-agent-trading!
                       dispatch!
                       store
                       :actions/submit-unlocked-order-request
                       request)
              (swap! store assoc-in [:order-form-runtime :error] message)
              (show-toast! store :error message))

            :unlocking
            (do
              (swap! store assoc-in [:order-form-runtime :error] message)
              (show-toast! store :error message))

            (swap! store open-enable-trading-recovery message)))
        (do
          (swap! store assoc-in [:order-form-runtime :submitting?] true)
          (let [refresh-opts {:refresh-spot? (spot-refresh/outcome-order-mutation? request)}]
            (letfn [(handle-submit-runtime-error! [err]
                      (let [error-text (runtime-error-message err)]
                        (swap! store update-order-submit-runtime error-text)
                        (show-toast! store :error (str "Order placement failed: " error-text))))]
            (-> (run-pre-submit-actions! store
                                         address
                                         request
                                         exchange-response-error
                                         runtime-error-message)
                (.then (fn [pre-submit-result]
                         (if-not (:ok? pre-submit-result)
                           (do
                             (swap! store update-order-submit-runtime (:error-text pre-submit-result))
                             (show-toast! store :error (:toast-message pre-submit-result)))
                           (-> (trading-api/submit-order! store address (:action request))
                               (.then (fn [resp]
                                        (let [{:keys [ok? success-count error-text toast-message]}
                                              (submit-outcome exchange-response-error resp)]
                                          (if ok?
                                            (do
                                              (swap! store update-order-submit-runtime nil)
                                              (show-toast! store :success {:toast-surface :order-submitted :headline "Order submitted" :subline "Awaiting fill confirmation" :message "Order submitted."})
                                              (refresh-account-surfaces-after-order-mutation! store address refresh-opts)
                                              (dispatch! store nil [[:actions/refresh-order-history]]))
                                            (if (trading-api/enable-trading-recovery-error? error-text)
                                              (swap! store open-enable-trading-recovery)
                                              (do
                                                (swap! store update-order-submit-runtime error-text)
                                                (show-toast! store :error toast-message)
                                                (when (pos? success-count)
                                                  (refresh-account-surfaces-after-order-mutation! store address refresh-opts)
                                                  (dispatch! store nil [[:actions/refresh-order-history]]))))))))
                               (.catch handle-submit-runtime-error!)))))
                (.catch handle-submit-runtime-error!))))))))))

(defn- update-position-tpsl-modal-error
  [state error-text]
  (-> state
      (assoc-in [:positions-ui :tpsl-modal :submitting?] false)
      (assoc-in [:positions-ui :tpsl-modal :error] error-text)))

(defn- set-position-tpsl-modal-error!
  [store error-text]
  (swap! store update-position-tpsl-modal-error error-text))

(defn- refresh-order-surfaces-after-submit!
  [store dispatch! address]
  (refresh-account-surfaces-after-order-mutation! store address)
  (dispatch! store nil [[:actions/refresh-order-history]]))

(defn- position-tpsl-submit-precondition-error
  [state address agent-status]
  (let [spectate-mode-message (spectate-mode-precondition-error state)]
    (cond
      (seq spectate-mode-message)
      spectate-mode-message

      (nil? address)
      "Connect your wallet before submitting."

      (not= :ready agent-status)
      (trading-readiness-message agent-status
                                 "Enable trading before submitting orders."
                                 "Unlock trading before submitting orders."
                                 "Awaiting passkey before submitting orders.")

      :else
      nil)))

(defn- reject-position-tpsl-submit!
  [store show-toast! error-text]
  (set-position-tpsl-modal-error! store error-text)
  (show-toast! store :error error-text))

(defn- handle-position-tpsl-submit-response!
  [store dispatch! exchange-response-error show-toast! address resp]
  (let [{:keys [ok? success-count error-text toast-message]}
        (submit-outcome exchange-response-error resp)]
    (if ok?
      (do
        (swap! store assoc-in [:positions-ui :tpsl-modal]
               (position-tpsl/default-modal-state))
        (show-toast! store :success "TP/SL orders submitted.")
        (refresh-order-surfaces-after-submit! store dispatch! address))
      (do
        (set-position-tpsl-modal-error! store error-text)
        (show-toast! store :error toast-message)
        (when (pos? success-count)
          (refresh-order-surfaces-after-submit! store dispatch! address))))))

(defn- handle-position-tpsl-submit-runtime-error!
  [store runtime-error-message show-toast! err]
  (let [error-text (runtime-error-message err)]
    (set-position-tpsl-modal-error! store error-text)
    (show-toast! store :error (str "Order placement failed: " error-text))))

(defn api-submit-position-tpsl
  [{:keys [dispatch! exchange-response-error runtime-error-message show-toast!]} _ store request]
  (let [state @store
        address (get-in state [:wallet :address])
        agent-status (get-in state [:wallet :agent :status])]
    (if-let [error-text (position-tpsl-submit-precondition-error state address agent-status)]
      (reject-position-tpsl-submit! store show-toast! error-text)
      (-> (trading-api/submit-order! store address (:action request))
          (.then (partial handle-position-tpsl-submit-response!
                          store
                          dispatch!
                          exchange-response-error
                          show-toast!
                          address))
          (.catch (partial handle-position-tpsl-submit-runtime-error!
                           store
                           runtime-error-message
                           show-toast!))))))

(defn- update-position-margin-modal-error
  [state error-text]
  (-> state
      (assoc-in [:positions-ui :margin-modal :submitting?] false)
      (assoc-in [:positions-ui :margin-modal :error] error-text)))

(defn- set-position-margin-modal-error!
  [store error-text]
  (swap! store update-position-margin-modal-error error-text))

(defn- position-margin-submit-precondition-error
  [state address agent-status]
  (let [spectate-mode-message (spectate-mode-precondition-error state)]
    (cond
      (seq spectate-mode-message)
      spectate-mode-message

      (nil? address)
      "Connect your wallet before updating margin."

      (not= :ready agent-status)
      (trading-readiness-message agent-status
                                 "Enable trading before updating margin."
                                 "Unlock trading before updating margin."
                                 "Awaiting passkey before updating margin.")

      :else
      nil)))

(defn- reject-position-margin-submit!
  [store show-toast! error-text]
  (set-position-margin-modal-error! store error-text)
  (show-toast! store :error error-text))

(defn- handle-position-margin-submit-response!
  [store dispatch! exchange-response-error show-toast! address resp]
  (if (= "ok" (:status resp))
    (do
      (swap! store assoc-in [:positions-ui :margin-modal]
             (position-margin/default-modal-state))
      (show-toast! store :success "Margin updated.")
      (refresh-order-surfaces-after-submit! store dispatch! address))
    (let [error-text (str (exchange-response-error resp))]
      (set-position-margin-modal-error! store error-text)
      (show-toast! store :error (str "Margin update failed: " error-text)))))

(defn- handle-position-margin-submit-runtime-error!
  [store runtime-error-message show-toast! err]
  (let [error-text (runtime-error-message err)]
    (set-position-margin-modal-error! store error-text)
    (show-toast! store :error (str "Margin update failed: " error-text))))

(defn api-submit-position-margin
  [{:keys [dispatch! exchange-response-error runtime-error-message show-toast!]} _ store request]
  (let [state @store
        address (get-in state [:wallet :address])
        agent-status (get-in state [:wallet :agent :status])]
    (if-let [error-text (position-margin-submit-precondition-error state address agent-status)]
      (reject-position-margin-submit! store show-toast! error-text)
      (-> (trading-api/submit-order! store address (:action request))
          (.then (partial handle-position-margin-submit-response!
                          store
                          dispatch!
                          exchange-response-error
                          show-toast!
                          address))
          (.catch (partial handle-position-margin-submit-runtime-error!
                           store
                           runtime-error-message
                           show-toast!))))))

(defn api-cancel-order
  [{:keys [dispatch!
           exchange-response-error
           prune-canceled-open-orders-fn
           runtime-error-message
           show-toast!]} _ store request]
  (let [state @store
        address (get-in state [:wallet :address])
        spectate-mode-message (spectate-mode-precondition-error state)
        agent-status (get-in state [:wallet :agent :status])
        twap-cancel? (twap-cancel-request? request)
        cancel-oids (cancel-request-oids request)
        prune-fn (or prune-canceled-open-orders-fn
                     prune-canceled-open-orders)]
    (cond
      (seq spectate-mode-message)
      (do
        (swap! store assoc-in [:orders :cancel-error] spectate-mode-message)
        (show-toast! store :error spectate-mode-message))

      (nil? address)
      (do
        (swap! store assoc-in [:orders :cancel-error] "Connect your wallet before cancelling.")
        (show-toast! store :error "Connect your wallet before cancelling."))

      (not= :ready agent-status)
      (let [message (trading-readiness-message
                     agent-status
                     "Enable trading before cancelling orders."
                     "Unlock trading before cancelling orders."
                     "Awaiting passkey before cancelling orders.")]
        (case agent-status
          :locked
          (when-not (dispatch-unlock-agent-trading!
                     dispatch!
                     store
                     :actions/submit-unlocked-cancel-request
                     request)
            (swap! store assoc-in [:orders :cancel-error] message)
            (show-toast! store :error message))

          (do
            (swap! store assoc-in [:orders :cancel-error] message)
            (show-toast! store :error message))))

      :else
      (do
        ;; Hide targeted orders immediately; rollback by clearing pending ids on failure.
        (when (seq cancel-oids)
          (swap! store add-pending-cancel-oids cancel-oids))
        (-> (trading-api/cancel-order! store address (:action request))
            (.then (fn [resp]
                     (if twap-cancel?
                       (let [{:keys [ok? error-text toast-message]}
                             (twap-cancel-outcome exchange-response-error resp)]
                         (swap! store
                                (fn [state]
                                  (cond-> (assoc-in state
                                                    [:orders :cancel-error]
                                                    (when-not ok?
                                                      error-text))
                                    (= "ok" (:status resp))
                                    (assoc-in [:orders :cancel-response] resp))))
                         (show-toast! store (if ok? :success :error) toast-message)
                         (when ok?
                           (refresh-account-surfaces-after-order-mutation! store address)
                           (dispatch! store nil [[:actions/refresh-order-history]])))
                       (let [{:keys [ok? success-cancels error-text toast-message]}
                             (cancel-outcome exchange-response-error request resp)
                             success-request (successful-cancel-request request success-cancels)
                             success-count (count success-cancels)]
                         (swap! store
                                (fn [state]
                                  (cond-> (-> state
                                              (assoc-in [:orders :cancel-error]
                                                        (when-not ok?
                                                          error-text))
                                              (remove-pending-cancel-oids cancel-oids))
                                    (= "ok" (:status resp))
                                    (assoc-in [:orders :cancel-response] resp)

                                    (seq success-cancels)
                                    (cancel-guard/record-canceled-oids success-cancels)

                                    (map? success-request)
                                    (prune-fn success-request))))
                         (show-toast! store (if ok? :success :error) toast-message)
                         (when (pos? success-count)
                           (refresh-account-surfaces-after-order-mutation! store address)
                           (dispatch! store nil [[:actions/refresh-order-history]]))))))
            (.catch (fn [err]
                      (let [error-text (runtime-error-message err)]
                        (swap! store
                               (fn [state]
                                 (-> state
                                     (assoc-in [:orders :cancel-error] error-text)
                                     (remove-pending-cancel-oids cancel-oids))))
                        (show-toast! store
                                     :error
                                     (str (if twap-cancel?
                                            "TWAP termination failed: "
                                            "Order cancellation failed: ")
                                          error-text))))))))))
