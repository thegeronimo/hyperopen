(ns hyperopen.order.effects
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.default :as api]
            [hyperopen.api.market-metadata.facade :as market-metadata]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.api.trading :as trading-api]))

(defn- cancel-request-oids
  [request]
  (->> (get-in request [:action :cancels] [])
       (keep (fn [cancel]
               (trading-api/resolve-cancel-order-oid cancel)))
       set))

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

(defn- remove-canceled-open-orders-seq
  [orders cancel-oids]
  (->> (or orders [])
       (remove (fn [order]
                 (when-let [oid (trading-api/resolve-cancel-order-oid order)]
                   (contains? cancel-oids oid))))
       vec))

(defn- remove-canceled-open-orders
  [orders cancel-oids]
  (cond
    (not (seq cancel-oids))
    orders

    (sequential? orders)
    (remove-canceled-open-orders-seq orders cancel-oids)

    (map? orders)
    (cond
      (sequential? (:orders orders))
      (update orders :orders remove-canceled-open-orders-seq cancel-oids)

      (sequential? (:openOrders orders))
      (update orders :openOrders remove-canceled-open-orders-seq cancel-oids)

      (sequential? (:data orders))
      (update orders :data remove-canceled-open-orders-seq cancel-oids)

      :else
      orders)

    :else
    orders))

(defn prune-canceled-open-orders
  [state request]
  (let [cancel-oids (cancel-request-oids request)]
    (if (seq cancel-oids)
      (-> state
          (update-in [:orders :open-orders] remove-canceled-open-orders cancel-oids)
          (update-in [:orders :open-orders-snapshot] remove-canceled-open-orders cancel-oids)
          (update-in [:orders :open-orders-snapshot-by-dex]
                     (fn [orders-by-dex]
                       (reduce-kv (fn [acc dex dex-orders]
                                    (assoc acc dex (remove-canceled-open-orders dex-orders cancel-oids)))
                                  (if (map? orders-by-dex)
                                    (empty orders-by-dex)
                                    {})
                                  (or orders-by-dex {})))))
      state)))

(defn- refresh-open-orders-snapshot!
  [store address dex opts]
  (-> (api/request-frontend-open-orders! address
                                         (cond-> (or opts {})
                                           (and dex (not= dex "")) (assoc :dex dex)))
      (.then (promise-effects/apply-success-and-return
              store
              api-projections/apply-open-orders-success
              dex))
      (.catch (promise-effects/apply-error-and-reject
               store
               api-projections/apply-open-orders-error))))

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

(defn- refresh-account-surfaces-after-order-mutation!
  [store address]
  (when address
    (refresh-open-orders-snapshot! store address nil {:priority :high})
    (refresh-default-clearinghouse-snapshot! store address {:priority :high})
    (-> (market-metadata/ensure-and-apply-perp-dex-metadata!
         {:store store
          :ensure-perp-dexs-data! api/ensure-perp-dexs-data!
          :apply-perp-dexs-success api-projections/apply-perp-dexs-success
          :apply-perp-dexs-error api-projections/apply-perp-dexs-error}
         {:priority :low})
        (.then (fn [dex-names]
                 (doseq [dex dex-names]
                   (refresh-open-orders-snapshot! store address dex {:priority :low})
                   (refresh-perp-dex-clearinghouse-snapshot! store address dex {:priority :low}))))
        (.catch (fn [err]
                  (telemetry/log! "Error refreshing per-dex account surfaces after order mutation:" err))))))

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

(defn- ghost-mode-precondition-error
  [state]
  (account-context/mutations-blocked-message state))

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
        ghost-mode-message (ghost-mode-precondition-error state)
        address (get-in state [:wallet :address])
        agent-status (get-in state [:wallet :agent :status])]
    (if (seq ghost-mode-message)
      (do
        (swap! store assoc-in [:order-form-runtime :error] ghost-mode-message)
        (show-toast! store :error ghost-mode-message))
      (if (nil? address)
      (do
        (swap! store assoc-in [:order-form-runtime :error] "Connect your wallet before submitting.")
        (show-toast! store :error "Connect your wallet before submitting."))
      (if (not= :ready agent-status)
        (do
          (swap! store assoc-in [:order-form-runtime :error] "Enable trading before submitting orders.")
          (show-toast! store :error "Enable trading before submitting orders."))
        (do
          (swap! store assoc-in [:order-form-runtime :submitting?] true)
          (letfn [(handle-submit-runtime-error! [err]
                    (let [error-text (runtime-error-message err)]
                      (swap! store assoc-in [:order-form-runtime :submitting?] false)
                      (swap! store assoc-in [:order-form-runtime :error] error-text)
                      (show-toast! store :error (str "Order placement failed: " error-text))))]
            (-> (run-pre-submit-actions! store
                                         address
                                         request
                                         exchange-response-error
                                         runtime-error-message)
                (.then (fn [pre-submit-result]
                         (if-not (:ok? pre-submit-result)
                           (do
                             (swap! store assoc-in [:order-form-runtime :submitting?] false)
                             (swap! store assoc-in [:order-form-runtime :error] (:error-text pre-submit-result))
                             (show-toast! store :error (:toast-message pre-submit-result)))
                           (-> (trading-api/submit-order! store address (:action request))
                               (.then (fn [resp]
                                        (swap! store assoc-in [:order-form-runtime :submitting?] false)
                                        (let [{:keys [ok? success-count error-text toast-message]}
                                              (submit-outcome exchange-response-error resp)]
                                          (if ok?
                                            (do
                                              (swap! store assoc-in [:order-form-runtime :error] nil)
                                              (show-toast! store :success "Order submitted.")
                                              (refresh-account-surfaces-after-order-mutation! store address)
                                              (dispatch! store nil [[:actions/refresh-order-history]]))
                                            (do
                                              (swap! store assoc-in [:order-form-runtime :error] error-text)
                                              (show-toast! store :error toast-message)
                                              (when (pos? success-count)
                                                (refresh-account-surfaces-after-order-mutation! store address)
                                                (dispatch! store nil [[:actions/refresh-order-history]])))))))
                               (.catch handle-submit-runtime-error!)))))
                (.catch handle-submit-runtime-error!)))))))))

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
  (let [ghost-mode-message (ghost-mode-precondition-error state)]
    (cond
      (seq ghost-mode-message)
      ghost-mode-message

      (nil? address)
      "Connect your wallet before submitting."

      (not= :ready agent-status)
      "Enable trading before submitting orders."

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
  (let [ghost-mode-message (ghost-mode-precondition-error state)]
    (cond
      (seq ghost-mode-message)
      ghost-mode-message

      (nil? address)
      "Connect your wallet before updating margin."

      (not= :ready agent-status)
      "Enable trading before updating margin."

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
        ghost-mode-message (ghost-mode-precondition-error state)
        agent-status (get-in state [:wallet :agent :status])
        cancel-oids (cancel-request-oids request)
        prune-fn (or prune-canceled-open-orders-fn
                     prune-canceled-open-orders)]
    (cond
      (seq ghost-mode-message)
      (do
        (swap! store assoc-in [:orders :cancel-error] ghost-mode-message)
        (show-toast! store :error ghost-mode-message))

      (nil? address)
      (do
        (swap! store assoc-in [:orders :cancel-error] "Connect your wallet before cancelling.")
        (show-toast! store :error "Connect your wallet before cancelling."))

      (not= :ready agent-status)
      (do
        (swap! store assoc-in [:orders :cancel-error] "Enable trading before cancelling orders.")
        (show-toast! store :error "Enable trading before cancelling orders."))

      :else
      (do
        ;; Hide targeted orders immediately; rollback by clearing pending ids on failure.
        (swap! store add-pending-cancel-oids cancel-oids)
        (-> (trading-api/cancel-order! store address (:action request))
            (.then (fn [resp]
                     (if (= "ok" (:status resp))
                       (do
                         (swap! store
                                (fn [state]
                                  (-> state
                                      (assoc-in [:orders :cancel-error] nil)
                                      (assoc-in [:orders :cancel-response] resp)
                                      (prune-fn request)
                                      (remove-pending-cancel-oids cancel-oids))))
                         (show-toast! store :success "Order canceled.")
                         (refresh-account-surfaces-after-order-mutation! store address)
                         (dispatch! store nil [[:actions/refresh-order-history]]))
                       (let [error-text (str (exchange-response-error resp))]
                         (swap! store
                                (fn [state]
                                  (-> state
                                      (assoc-in [:orders :cancel-error] error-text)
                                      (remove-pending-cancel-oids cancel-oids))))
                         (show-toast! store :error (cancel-order-error-message exchange-response-error resp))))))
            (.catch (fn [err]
                      (let [error-text (runtime-error-message err)]
                        (swap! store
                               (fn [state]
                                 (-> state
                                     (assoc-in [:orders :cancel-error] error-text)
                                     (remove-pending-cancel-oids cancel-oids))))
                        (show-toast! store :error (str "Order cancellation failed: " error-text))))))))))
