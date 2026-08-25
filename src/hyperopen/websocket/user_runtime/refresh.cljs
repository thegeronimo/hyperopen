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
  ;; No `:force-refresh? true` default here on purpose. A forced request is
  ;; routed past BOTH the 2.5s response cache and the single-flight
  ;; de-duplicator (see `api/info_client/flow.cljs`), so defaulting it on meant
  ;; a stream of incoming fills re-downloaded the whole open-orders snapshot up
  ;; to several times a second -- for a market-making account that is a
  ;; half-megabyte, 1,600-order payload the `openOrders` websocket topic had
  ;; just delivered, and it eventually earns an HTTP 429. Callers that genuinely
  ;; need to bypass the cache (an order the user just placed or cancelled) pass
  ;; `:force-refresh? true` themselves.
  (-> (api/request-frontend-open-orders! address
                                         (cond-> (or opts {})
                                           (and dex (not= dex "")) (assoc :dex dex)))
      (.then (fn [payload]
               (when (common/requested-address-active? store address)
                 (swap! store
                        (fn [state]
                          (let [state* (api-projections/apply-open-orders-success
                                        state
                                        dex
                                        payload)]
                            (if (or (nil? dex) (= "" dex))
                              (assoc-in state*
                                        [:orders :open-orders]
                                        (get-in state* [:orders :open-orders-snapshot]))
                              state*)))))
               payload))
      (.catch (apply-error-and-reject-when-address-active
               store
               address
               api-projections/apply-open-orders-error))))

(defn- refresh-default-clearinghouse-snapshot!
  [store address opts]
  (-> (api/request-clearinghouse-state! address nil opts)
      (.then (fn [data]
               (when (common/requested-address-active? store address)
                 (swap! store api-projections/apply-default-clearinghouse-success data))
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

(defn- refresh-account-surfaces-after-user-event!
  [store address opts]
  (account-surface-service/refresh-after-user-fill!
   {:store store
    :address address
    :force-base-open-orders-refresh? (not (false? (:force-base-open-orders-refresh? opts)))
    :ensure-perp-dexs! ensure-perp-dex-metadata!
    :sync-perp-dex-clearinghouse-subscriptions!
    user-subscriptions-runtime/sync-perp-dex-clearinghouse-subscriptions!
    :refresh-open-orders! refresh-open-orders-snapshot!
    :refresh-default-clearinghouse! refresh-default-clearinghouse-snapshot!
    :refresh-spot-clearinghouse! refresh-spot-clearinghouse-snapshot!
    :refresh-perp-dex-clearinghouse! refresh-perp-dex-clearinghouse-snapshot!
    :resolve-current-address account-context/effective-account-address
    :log-fn telemetry/log!}))

(defn refresh-account-surfaces-after-user-fill!
  [store address]
  (refresh-account-surfaces-after-user-event! store address {}))

(defn clear-account-surface-refresh-timeout!
  []
  (when-let [timeout-id (get-in @runtime-state/runtime account-surface-refresh-timeout-path)]
    (platform/clear-timeout! timeout-id)
    (swap! runtime-state/runtime assoc-in account-surface-refresh-timeout-path nil)))

(defn- schedule-account-surface-refresh!
  [store refresh-fn]
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
                    (refresh-fn store address*)))
                fill-account-surface-refresh-debounce-ms))))))

(defn schedule-account-surface-refresh-after-fill!
  [store]
  (schedule-account-surface-refresh!
   store
   (fn [store* address*]
     (refresh-account-surfaces-after-user-fill! store* address*))))

(defn schedule-account-surface-refresh-after-ledger!
  [store]
  (schedule-account-surface-refresh!
   store
   (fn [store* address*]
     (refresh-account-surfaces-after-user-event!
      store*
      address*
      {:force-base-open-orders-refresh? false}))))
