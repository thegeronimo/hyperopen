(ns hyperopen.websocket.user-runtime.subscriptions
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.account.surface-policy :as account-surface-policy]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.wallet.address-watcher :as address-watcher]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.domain.model :as model]
            [hyperopen.websocket.user-runtime.common :as common]))

(def ^:private user-subscription-topics
  ["openOrders"
   "userFills"
   "userFundings"
   "userNonFundingLedgerUpdates"
   "twapStates"
   "userTwapHistory"
   "userTwapSliceFills"])

(defn- runtime-streams
  []
  (or (get-in @ws-client/runtime-view [:stream :streams])
      {}))

(defn- subscribed?
  [subscription]
  (true? (get-in (runtime-streams)
                 [(model/subscription-key subscription) :subscribed?])))

(defn- send-subscription-intent!
  [method subscription]
  (ws-client/send-message! {:method method
                            :subscription subscription}))

(defn- user-subscription
  [address topic]
  (cond-> {:type topic
           :user address}
    (= "twapStates" topic)
    (assoc :dex "ALL_DEXS")))

(defn- user-subscriptions
  [address]
  (mapv #(user-subscription address %) user-subscription-topics))

(defn- subscribed-clearinghouse-keys-for-address
  [address]
  (let [address* (common/normalized-address address)]
    (if-not address*
      #{}
      (->> (runtime-streams)
           (keep (fn [[_ {:keys [descriptor subscribed?]}]]
                   (let [descriptor* (or descriptor {})
                         sub-address (common/normalized-address (:user descriptor*))
                         dex (common/normalized-dex (:dex descriptor*))]
                     (when (and subscribed?
                                (= "clearinghouseState" (:type descriptor*))
                                (= address* sub-address)
                                dex)
                       [sub-address dex]))))
           set))))

(defn sync-perp-dex-clearinghouse-subscriptions!
  [address dex-names]
  (when-let [address* (common/normalized-address address)]
    (let [desired (into #{}
                        (keep (fn [dex]
                                (when-let [dex* (common/normalized-dex dex)]
                                  [address* dex*])))
                        (account-surface-policy/normalize-dex-names dex-names))
          existing (subscribed-clearinghouse-keys-for-address address*)]
      (doseq [[sub-address dex] desired
              :when (not (contains? existing [sub-address dex]))]
        (send-subscription-intent! "subscribe"
                                   {:type "clearinghouseState"
                                    :user sub-address
                                    :dex dex}))
      (doseq [[sub-address dex] existing
              :when (not (contains? desired [sub-address dex]))]
        (send-subscription-intent! "unsubscribe"
                                   {:type "clearinghouseState"
                                    :user sub-address
                                    :dex dex})))))

(defn subscribe-user!
  [address]
  (when-let [address* (common/normalized-address address)]
    (doseq [subscription (user-subscriptions address*)
            :when (not (subscribed? subscription))]
      (send-subscription-intent! "subscribe" subscription))
    (telemetry/log! "Subscribed to user streams for:" address*)))

(defn unsubscribe-user!
  [address]
  (when-let [address* (common/normalized-address address)]
    (doseq [subscription (user-subscriptions address*)
            :when (subscribed? subscription)]
      (send-subscription-intent! "unsubscribe" subscription))
    (doseq [[sub-address dex] (subscribed-clearinghouse-keys-for-address address*)]
      (send-subscription-intent! "unsubscribe"
                                 {:type "clearinghouseState"
                                  :user sub-address
                                  :dex dex}))
    (telemetry/log! "Unsubscribed from user streams for:" address*)))

(defn desired-user-stream-address
  [state]
  (when (account-context/user-stream-subscriptions-enabled? state)
    (account-context/effective-account-address state)))

(defn create-user-handler
  [subscribe-fn unsubscribe-fn]
  (reify
    address-watcher/IAddressChangeHandler
    (on-address-changed [_ old-address new-address]
      (when old-address
        (unsubscribe-fn old-address))
      (when new-address
        (subscribe-fn new-address)))
    (get-handler-name [_]
      "user-ws-subscription-handler")

    address-watcher/IWatchedValueHandler
    (watched-value [_ state]
      (desired-user-stream-address state))))
