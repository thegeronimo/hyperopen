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

(defn market-coalesce-key [envelope]
  (let [payload (:payload envelope)
        data (:data payload)
        coin (or (:coin payload)
                 (:coin data)
                 (some-> data first :coin)
                 (some-> data first :symbol)
                 (some-> data first :asset))]
    [(:topic envelope) coin]))

