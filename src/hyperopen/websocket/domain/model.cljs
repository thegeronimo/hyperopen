(ns hyperopen.websocket.domain.model
  (:require [clojure.string :as str]))

(defn normalize-method [value]
  (some-> value str str/lower-case))

(defn subscription-key [subscription]
  (let [key-fields [(:type subscription)
                    (:coin subscription)
                    (:user subscription)
                    (:dex subscription)
                    (:interval subscription)]]
    (if (some some? key-fields)
      key-fields
      [:raw (pr-str subscription)])))

(defn apply-subscription-intent
  "Return updated desired-subscriptions map after applying one outbound message."
  [desired-subscriptions data]
  (let [method (normalize-method (:method data))
        subscription (:subscription data)]
    (if (map? subscription)
      (case method
        "subscribe" (assoc desired-subscriptions (subscription-key subscription) subscription)
        "unsubscribe" (dissoc desired-subscriptions (subscription-key subscription))
        desired-subscriptions)
      desired-subscriptions)))

(defn make-connection-command
  ([op ts]
   {:op op :ts ts})
  ([op ts attrs]
   (merge {:op op :ts ts} attrs)))

(defn connection-command? [value]
  (and (map? value)
       (keyword? (:op value))
       (number? (:ts value))))

(defn make-domain-message-envelope
  [{:keys [topic tier ts payload source socket-id]}]
  {:topic topic
   :tier tier
   :ts ts
   :payload payload
   :source source
   :socket-id socket-id})

(defn domain-message-envelope? [value]
  (and (map? value)
       (string? (:topic value))
       (keyword? (:tier value))
       (number? (:ts value))
       (map? (:payload value))))

(def transport-event-types
  #{:socket/open
    :socket/message
    :socket/close
    :socket/error
    :lifecycle/focus
    :lifecycle/online
    :lifecycle/offline
    :lifecycle/hidden
    :lifecycle/visible
    :timer/watchdog
    :timer/retry
    :timer/health
    :timer/market-flush})

(defn make-transport-event
  ([event-type ts]
   {:event/type event-type
    :ts ts})
  ([event-type ts attrs]
   (merge {:event/type event-type
           :ts ts}
          attrs)))

(defn transport-event? [value]
  (and (map? value)
       (contains? transport-event-types (:event/type value))
       (number? (:ts value))))

(def runtime-msg-types
  #{:cmd/init-connection
    :cmd/disconnect
    :cmd/force-reconnect
    :cmd/send-message
    :cmd/register-handler
    :evt/socket-open
    :evt/socket-message
    :evt/socket-close
    :evt/socket-error
    :evt/lifecycle-focus
    :evt/lifecycle-online
    :evt/lifecycle-offline
    :evt/lifecycle-hidden
    :evt/lifecycle-visible
    :evt/timer-retry-fired
    :evt/timer-watchdog-fired
    :evt/timer-health-tick
    :evt/timer-market-flush-fired
    :evt/decoded-envelope
    :evt/parse-error})

(defn make-runtime-msg
  ([msg-type ts]
   {:msg/type msg-type
    :ts ts})
  ([msg-type ts attrs]
   (merge {:msg/type msg-type
           :ts ts}
          attrs)))

(defn runtime-msg? [value]
  (and (map? value)
       (contains? runtime-msg-types (:msg/type value))
       (number? (:ts value))))

(def runtime-effect-types
  #{:fx/socket-connect
    :fx/socket-send
    :fx/socket-close
    :fx/socket-detach-handlers
    :fx/timer-set-timeout
    :fx/timer-clear-timeout
    :fx/timer-set-interval
    :fx/timer-clear-interval
    :fx/lifecycle-install-listeners
    :fx/router-register-handler
    :fx/router-dispatch-envelope
    :fx/parse-raw-message
    :fx/project-runtime-view
    :fx/log
    :fx/dead-letter})

(defn make-runtime-effect
  ([fx-type]
   {:fx/type fx-type})
  ([fx-type attrs]
   (merge {:fx/type fx-type}
          attrs)))

(defn runtime-effect? [value]
  (and (map? value)
       (contains? runtime-effect-types (:fx/type value))))

(defn market-coalesce-key [envelope]
  (let [payload (:payload envelope)
        data (:data payload)
        coin (or (:coin payload)
                 (:coin data)
                 (some-> data first :coin)
                 (some-> data first :symbol)
                 (some-> data first :asset))]
    [(:topic envelope) coin]))

(def default-topic->group
  {"l2Book" :market_data
   "trades" :market_data
   "activeAssetCtx" :market_data
   "openOrders" :orders_oms
   "userFills" :orders_oms
   "userFundings" :orders_oms
   "userNonFundingLedgerUpdates" :orders_oms
   "webData2" :account})

(defn topic->group
  ([topic]
   (topic->group default-topic->group topic))
  ([topic-group-map topic]
   (get topic-group-map topic :account)))
