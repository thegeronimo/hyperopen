(ns hyperopen.websocket.user-runtime.refresh
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.account.surface-service :as account-surface-service]
            [hyperopen.api.default :as api]
            [hyperopen.api.market-metadata.facade :as market-metadata]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.user-runtime.common :as common]
            [hyperopen.websocket.user-runtime.subscriptions :as user-subscriptions-runtime]))

(def ^:private fill-account-surface-refresh-debounce-ms
  250)

(def ^:private account-surface-refresh-timeout-path
  [:timeouts :user-account-surface-refresh])

(defn apply-success-and-return-when-address-active
  [store requested-address apply-fn & leading-args]
  (fn [payload]
    (when (common/requested-address-active? store requested-address)
      (apply swap! store apply-fn (concat leading-args [payload])))
    payload))

(defn apply-error-and-reject-when-address-active
  [store requested-address apply-error-fn & leading-args]
  (fn [err]
    (when (common/requested-address-active? store requested-address)
      (apply swap! store apply-error-fn (concat leading-args [err])))
    (promise-effects/reject-error err)))

(defn- refresh-open-orders-snapshot!
  [store address dex opts]
  (-> (api/request-frontend-open-orders! address
                                         (cond-> (merge {:force-refresh? true}
                                                        (or opts {}))
                                           (and dex (not= dex "")) (assoc :dex dex)))
      (.then (apply-success-and-return-when-address-active
              store
              address
              api-projections/apply-open-orders-success
              dex))
      (.catch (apply-error-and-reject-when-address-active
               store
               address
               api-projections/apply-open-orders-error))))

(defn- refresh-default-clearinghouse-snapshot!
  [store address opts]
  (-> (api/request-clearinghouse-state! address nil opts)
      (.then (fn [data]
               (when (common/requested-address-active? store address)
                 (swap! store assoc-in [:webdata2 :clearinghouseState] data))
               data))
      (.catch (fn [err]
                (telemetry/log! "Error refreshing default clearinghouse state after user fill:" err)))))

(defn- refresh-spot-clearinghouse-snapshot!
  [store address opts]
  (-> (api/request-spot-clearinghouse-state! address opts)
      (.then (apply-success-and-return-when-address-active
              store
              address
              api-projections/apply-spot-balances-success))
      (.catch (apply-error-and-reject-when-address-active
               store
               address
               api-projections/apply-spot-balances-error))))

(defn- refresh-perp-dex-clearinghouse-snapshot!
  [store address dex opts]
  (-> (api/request-clearinghouse-state! address dex opts)
      (.then (apply-success-and-return-when-address-active
              store
              address
              api-projections/apply-perp-dex-clearinghouse-success
              dex))
      (.catch (apply-error-and-reject-when-address-active
               store
               address
               api-projections/apply-perp-dex-clearinghouse-error))))

(defn- ensure-perp-dex-metadata!
  [store opts]
  (market-metadata/ensure-and-apply-perp-dex-metadata!
   {:store store
    :ensure-perp-dexs-data! api/ensure-perp-dexs-data!
    :apply-perp-dexs-success api-projections/apply-perp-dexs-success
    :apply-perp-dexs-error api-projections/apply-perp-dexs-error}
   opts))

(defn refresh-account-surfaces-after-user-fill!
  [store address]
  (account-surface-service/refresh-after-user-fill!
   {:store store
    :address address
    :ensure-perp-dexs! ensure-perp-dex-metadata!
    :sync-perp-dex-clearinghouse-subscriptions!
    user-subscriptions-runtime/sync-perp-dex-clearinghouse-subscriptions!
    :refresh-open-orders! refresh-open-orders-snapshot!
    :refresh-default-clearinghouse! refresh-default-clearinghouse-snapshot!
    :refresh-spot-clearinghouse! refresh-spot-clearinghouse-snapshot!
    :refresh-perp-dex-clearinghouse! refresh-perp-dex-clearinghouse-snapshot!
    :resolve-current-address account-context/effective-account-address
    :log-fn telemetry/log!}))

(defn clear-account-surface-refresh-timeout!
  []
  (when-let [timeout-id (get-in @runtime-state/runtime account-surface-refresh-timeout-path)]
    (platform/clear-timeout! timeout-id)
    (swap! runtime-state/runtime assoc-in account-surface-refresh-timeout-path nil)))

(defn schedule-account-surface-refresh-after-fill!
  [store]
  (when-let [address (account-context/effective-account-address @store)]
    (let [address* (common/normalized-address address)]
      (when address*
        (clear-account-surface-refresh-timeout!)
        (swap! runtime-state/runtime
               assoc-in
               account-surface-refresh-timeout-path
               (platform/set-timeout!
                (fn []
                  (swap! runtime-state/runtime
                         assoc-in
                         account-surface-refresh-timeout-path
                         nil)
                  (when (common/requested-address-active? store address*)
                    (refresh-account-surfaces-after-user-fill! store address*)))
                fill-account-surface-refresh-debounce-ms))))))
