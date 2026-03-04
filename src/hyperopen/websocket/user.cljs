(ns hyperopen.websocket.user
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.default :as api]
            [hyperopen.api.market-metadata.facade :as market-metadata]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.order.feedback-runtime :as order-feedback-runtime]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.wallet.address-watcher :as address-watcher]))

(defonce user-state (atom {:subscriptions #{}}))
(defonce ^:private account-surface-refresh-timeout-id (atom nil))

(def ^:private fill-account-surface-refresh-debounce-ms 250)

(defn- normalized-address
  [value]
  (account-context/normalize-address value))

(defn- active-effective-address
  [store]
  (some-> (account-context/effective-account-address @store)
          normalized-address))

(defn- message-address
  [msg]
  (or (normalized-address (:user msg))
      (normalized-address (:address msg))
      (normalized-address (:walletAddress msg))
      (normalized-address (get-in msg [:data :user]))
      (normalized-address (get-in msg [:data :address]))
      (normalized-address (get-in msg [:data :walletAddress]))
      (normalized-address (get-in msg [:data :wallet]))))

(defn- message-for-active-address?
  [store msg]
  (let [msg-address (message-address msg)
        active-address (active-effective-address store)]
    (if msg-address
      (and active-address
           (= msg-address active-address))
      true)))

(defn- requested-address-active?
  [store requested-address]
  (let [requested-address* (normalized-address requested-address)
        active-address (active-effective-address store)]
    (and requested-address*
         active-address
         (= requested-address* active-address))))

(defn- apply-success-and-return-when-address-active
  [store requested-address apply-fn & leading-args]
  (fn [payload]
    (when (requested-address-active? store requested-address)
      (apply swap! store apply-fn (concat leading-args [payload])))
    payload))

(defn- apply-error-and-reject-when-address-active
  [store requested-address apply-error-fn & leading-args]
  (fn [err]
    (when (requested-address-active? store requested-address)
      (apply swap! store apply-error-fn (concat leading-args [err])))
    (promise-effects/reject-error err)))

(defn- subscribe! [sub]
  (ws-client/send-message! {:method "subscribe"
                            :subscription sub}))

(defn- unsubscribe! [sub]
  (ws-client/send-message! {:method "unsubscribe"
                            :subscription sub}))

(defn subscribe-user! [address]
  (when address
    (let [subs [{:type "openOrders" :user address}
                {:type "userFills" :user address}
                {:type "userFundings" :user address}
                {:type "userNonFundingLedgerUpdates" :user address}]]
      (doseq [s subs] (subscribe! s))
      (swap! user-state update :subscriptions conj address)
      (telemetry/log! "Subscribed to user streams for:" address))))

(defn unsubscribe-user! [address]
  (when address
    (let [subs [{:type "openOrders" :user address}
                {:type "userFills" :user address}
                {:type "userFundings" :user address}
                {:type "userNonFundingLedgerUpdates" :user address}]]
      (doseq [s subs] (unsubscribe! s))
      (swap! user-state update :subscriptions disj address)
      (telemetry/log! "Unsubscribed from user streams for:" address))))

(defn- upsert-seq [current incoming]
  (let [combined (concat incoming current)]
    (vec (take 200 combined))))

(defn- refresh-open-orders-snapshot!
  [store address dex opts]
  (-> (api/request-frontend-open-orders! address
                                         (cond-> (or opts {})
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
               (swap! store
                      (fn [state]
                        (if (= address (account-context/effective-account-address state))
                          (assoc-in state [:webdata2 :clearinghouseState] data)
                          state)))))
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

(defn- refresh-account-surfaces-after-user-fill!
  [store address]
  (when address
    (refresh-open-orders-snapshot! store address nil {:priority :high})
    (refresh-spot-clearinghouse-snapshot! store address {:priority :high})
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
                  (telemetry/log! "Error refreshing per-dex account surfaces after user fill:" err))))))

(defn- clear-account-surface-refresh-timeout!
  []
  (when-let [timeout-id @account-surface-refresh-timeout-id]
    (platform/clear-timeout! timeout-id)
    (reset! account-surface-refresh-timeout-id nil)))

(defn- schedule-account-surface-refresh-after-fill!
  [store]
  (let [address (account-context/effective-account-address @store)]
    (when address
      (clear-account-surface-refresh-timeout!)
      (reset! account-surface-refresh-timeout-id
              (platform/set-timeout!
               (fn []
                 (reset! account-surface-refresh-timeout-id nil)
                 (when (= address (account-context/effective-account-address @store))
                   (refresh-account-surfaces-after-user-fill! store address)))
               fill-account-surface-refresh-debounce-ms)))))

(defn- extract-channel-rows
  [msg collection-key]
  (let [payload (:data msg)]
    (cond
      (and (map? payload)
           (sequential? (get payload collection-key)))
      {:rows (vec (get payload collection-key))
       :snapshot? (boolean (:isSnapshot payload))}

      (sequential? payload)
      {:rows (vec payload)
       :snapshot? (boolean (:isSnapshot msg))}

      :else
      {:rows []
       :snapshot? false})))

(defn- clear-order-feedback-toast-timeout!
  []
  (order-feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
   runtime-state/runtime
   platform/clear-timeout!))

(defn- schedule-order-feedback-toast-clear!
  [store]
  (order-feedback-runtime/schedule-order-feedback-toast-clear!
   {:store store
    :runtime runtime-state/runtime
    :clear-order-feedback-toast! order-feedback-runtime/clear-order-feedback-toast!
    :clear-order-feedback-toast-timeout! clear-order-feedback-toast-timeout!
    :order-feedback-toast-duration-ms runtime-state/order-feedback-toast-duration-ms
    :set-timeout-fn platform/set-timeout!}))

(defn- fill-identity
  [fill]
  (when (map? fill)
    (or (some-> (or (:tid fill)
                    (:fill-id fill)
                    (:fillId fill)
                    (:id fill))
                (vector :id))
        (let [time-token (or (:time fill)
                             (:timestamp fill)
                             (:ts fill)
                             (:t fill))
              coin-token (or (:coin fill)
                             (:symbol fill)
                             (:asset fill))
              side-token (or (:side fill)
                             (:dir fill))
              size-token (or (:sz fill)
                             (:size fill))
              price-token (or (:px fill)
                              (:price fill)
                              (:fillPx fill)
                              (:avgPx fill))]
          (when (or (some? time-token)
                    (some? coin-token)
                    (some? side-token)
                    (some? size-token)
                    (some? price-token))
            [:fallback time-token coin-token side-token size-token price-token])))))

(defn- novel-fills
  [existing incoming]
  (let [known (->> (or existing [])
                   (keep fill-identity)
                   set)]
    (second
     (reduce (fn [[seen acc] fill]
               (if-let [identity (fill-identity fill)]
                 (if (contains? seen identity)
                   [seen acc]
                   [(conj seen identity) (conj acc fill)])
                 [seen (conj acc fill)]))
             [known []]
             (or incoming [])))))

(defn- fill-toast-message
  [rows]
  (let [rows* (vec (or rows []))
        count* (count rows*)]
    (when (pos? count*)
      (if (= 1 count*)
        (let [coin* (some-> (or (:coin (first rows*))
                                (:symbol (first rows*))
                                (:asset (first rows*)))
                            str
                            str/trim)]
          (if (seq coin*)
            (str "Order filled: " coin* ".")
            "Order filled."))
        (str count* " orders filled.")))))

(defn- show-user-fill-toast!
  [store rows]
  (when-let [message (fill-toast-message rows)]
    (order-feedback-runtime/show-order-feedback-toast!
     store
     :success
     message
     schedule-order-feedback-toast-clear!)))

(defn create-user-handler [subscribe-fn unsubscribe-fn]
  (reify address-watcher/IAddressChangeHandler
    (on-address-changed [_ old-address new-address]
      (when old-address (unsubscribe-fn old-address))
      (when new-address (subscribe-fn new-address)))
    (get-handler-name [_] "user-ws-subscription-handler")))

(defn- open-orders-handler [store]
  (fn [msg]
    (when (and (= "openOrders" (:channel msg))
               (message-for-active-address? store msg))
      (swap! store assoc-in [:orders :open-orders] (:data msg)))))

(defn- user-fills-handler [store]
  (fn [msg]
    (when (and (= "userFills" (:channel msg))
               (message-for-active-address? store msg))
      (let [{:keys [rows snapshot?]} (extract-channel-rows msg :fills)]
        (when (seq rows)
          (if snapshot?
            (swap! store assoc-in [:orders :fills] rows)
            (let [existing (get-in @store [:orders :fills] [])
                  new-rows (vec (novel-fills existing rows))]
              (when (seq new-rows)
                (swap! store update-in [:orders :fills] #(upsert-seq (or % []) new-rows))
                (show-user-fill-toast! store new-rows)
                (schedule-account-surface-refresh-after-fill! store)))))))))

(defn- user-fundings-handler [store]
  (fn [msg]
    (when (and (= "userFundings" (:channel msg))
               (message-for-active-address? store msg))
      (let [{:keys [rows]} (extract-channel-rows msg :fundings)
            normalized (funding-history/normalize-ws-funding-rows rows)]
        (when (seq normalized)
          (swap! store
                 (fn [state]
                   (let [existing (get-in state [:orders :fundings-raw] [])
                         filters (get-in state [:account-info :funding-history :filters])
                         filters* (funding-history/normalize-funding-history-filters
                                   filters
                                   (platform/now-ms))
                         merged (funding-history/merge-funding-history-rows existing normalized)
                         filtered (funding-history/filter-funding-history-rows merged filters*)]
                     (-> state
                         (assoc-in [:orders :fundings-raw] merged)
                         (assoc-in [:orders :fundings] filtered))))))))))

(defn- user-ledger-handler [store]
  (fn [msg]
    (when (and (= "userNonFundingLedgerUpdates" (:channel msg))
               (message-for-active-address? store msg))
      (let [{:keys [rows snapshot?]} (extract-channel-rows msg :nonFundingLedgerUpdates)]
        (when (seq rows)
          (if snapshot?
            (swap! store assoc-in [:orders :ledger] rows)
            (do
              (swap! store update-in [:orders :ledger] #(upsert-seq (or % []) rows))
              (schedule-account-surface-refresh-after-fill! store))))))))

(defn init! [store]
  (ws-client/register-handler! "openOrders" (open-orders-handler store))
  (ws-client/register-handler! "userFills" (user-fills-handler store))
  (ws-client/register-handler! "userFundings" (user-fundings-handler store))
  (ws-client/register-handler! "userNonFundingLedgerUpdates" (user-ledger-handler store))
  (telemetry/log! "User websocket handlers initialized"))
