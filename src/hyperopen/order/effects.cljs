(ns hyperopen.order.effects
  (:require [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.api.trading :as trading-api]))

(defn- cancel-request-oids
  [request]
  (->> (get-in request [:action :cancels] [])
       (keep (fn [cancel]
               (trading-api/resolve-cancel-order-oid cancel)))
       set))

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
      (.then (fn [rows]
               (swap! store api-projections/apply-open-orders-success dex rows)
               rows))
      (.catch (fn [err]
                (swap! store api-projections/apply-open-orders-error err)
                (js/Promise.reject err)))))

(defn- refresh-open-orders-after-cancel!
  [store address]
  (when address
    (refresh-open-orders-snapshot! store address nil {:priority :high})
    (-> (api/ensure-perp-dexs-data! store {:priority :low})
        (.then (fn [dexs]
                 (swap! store api-projections/apply-perp-dexs-success dexs)
                 dexs))
        (.then (fn [dexs]
                 (doseq [dex (or dexs [])]
                   (refresh-open-orders-snapshot! store address dex {:priority :low}))))
        (.catch (fn [err]
                  (swap! store api-projections/apply-perp-dexs-error err)
                  (println "Error refreshing per-dex open orders after cancel:" err))))))

(defn- submit-order-error-message
  [exchange-response-error resp]
  (str "Order placement failed: " (exchange-response-error resp)))

(defn- cancel-order-error-message
  [exchange-response-error resp]
  (str "Order cancellation failed: " (exchange-response-error resp)))

(defn api-submit-order
  [{:keys [dispatch! exchange-response-error runtime-error-message show-toast!]} _ store request]
  (let [address (get-in @store [:wallet :address])
        agent-status (get-in @store [:wallet :agent :status])]
    (cond
      (nil? address)
      (do
        (swap! store assoc-in [:order-form :error] "Connect your wallet before submitting.")
        (show-toast! store :error "Connect your wallet before submitting."))

      (not= :ready agent-status)
      (do
        (swap! store assoc-in [:order-form :error] "Enable trading before submitting orders.")
        (show-toast! store :error "Enable trading before submitting orders."))

      :else
      (do
        (swap! store assoc-in [:order-form :submitting?] true)
        (-> (trading-api/submit-order! store address (:action request))
            (.then (fn [resp]
                     (swap! store assoc-in [:order-form :submitting?] false)
                     (if (= "ok" (:status resp))
                       (do
                         (swap! store assoc-in [:order-form :error] nil)
                         (show-toast! store :success "Order submitted.")
                         (dispatch! store nil [[:actions/refresh-order-history]]))
                       (let [error-text (str (exchange-response-error resp))]
                         (swap! store assoc-in [:order-form :error] error-text)
                         (show-toast! store :error (submit-order-error-message exchange-response-error resp))))))
            (.catch (fn [err]
                      (let [error-text (runtime-error-message err)]
                        (swap! store assoc-in [:order-form :submitting?] false)
                        (swap! store assoc-in [:order-form :error] error-text)
                        (show-toast! store :error (str "Order placement failed: " error-text))))))))))

(defn api-cancel-order
  [{:keys [dispatch!
           exchange-response-error
           prune-canceled-open-orders-fn
           runtime-error-message
           show-toast!]} _ store request]
  (let [address (get-in @store [:wallet :address])
        agent-status (get-in @store [:wallet :agent :status])
        prune-fn (or prune-canceled-open-orders-fn
                     prune-canceled-open-orders)]
    (cond
      (nil? address)
      (do
        (swap! store assoc-in [:orders :cancel-error] "Connect your wallet before cancelling.")
        (show-toast! store :error "Connect your wallet before cancelling."))

      (not= :ready agent-status)
      (do
        (swap! store assoc-in [:orders :cancel-error] "Enable trading before cancelling orders.")
        (show-toast! store :error "Enable trading before cancelling orders."))

      :else
      (-> (trading-api/cancel-order! store address (:action request))
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (do
                       (swap! store
                              (fn [state]
                                (-> state
                                    (assoc-in [:orders :cancel-error] nil)
                                    (assoc-in [:orders :cancel-response] resp)
                                    (prune-fn request))))
                       (show-toast! store :success "Order canceled.")
                       (refresh-open-orders-after-cancel! store address)
                       (dispatch! store nil [[:actions/refresh-order-history]]))
                     (let [error-text (str (exchange-response-error resp))]
                       (swap! store assoc-in [:orders :cancel-error] error-text)
                       (show-toast! store :error (cancel-order-error-message exchange-response-error resp))))))
          (.catch (fn [err]
                    (let [error-text (runtime-error-message err)]
                      (swap! store assoc-in [:orders :cancel-error] error-text)
                      (show-toast! store :error (str "Order cancellation failed: " error-text)))))))))
