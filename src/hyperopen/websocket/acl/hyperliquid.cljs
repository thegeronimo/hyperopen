(ns hyperopen.websocket.acl.hyperliquid
  (:require [hyperopen.websocket.domain.model :as model]))

(defn parse-raw-envelope
  "Anti-corruption layer: map provider websocket raw payload into domain envelope.
   Returns {:ok envelope} or {:error ex}."
  [{:keys [raw socket-id now-ms topic->tier source]}]
  (try
    (let [provider-message (js->clj (js/JSON.parse raw) :keywordize-keys true)
          topic (:channel provider-message)]
      (if (string? topic)
        (let [tier (topic->tier topic)
              envelope (model/make-domain-message-envelope
                         {:topic topic
                          :tier tier
                          :ts (now-ms)
                          :payload provider-message
                          :source (or source :hyperliquid/ws)
                          :socket-id socket-id})]
          {:ok envelope})
        {:error (js/Error. "Provider payload missing :channel")}))
    (catch :default e
      {:error e})))
