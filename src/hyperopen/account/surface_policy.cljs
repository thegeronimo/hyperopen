(ns hyperopen.account.surface-policy
  (:require [clojure.string :as str]
            [hyperopen.websocket.health-projection :as health-projection]))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn normalize-dex-names
  [dex-names]
  (let [raw (cond
              (map? dex-names) (or (:dex-names dex-names)
                                   (:perp-dexs dex-names))
              (sequential? dex-names) dex-names
              :else [])]
    (->> raw
         (keep non-blank-text)
         distinct
         vec)))

(defn topic-usable-for-address?
  [state topic address]
  (when (and (string? topic)
             (seq address))
    (health-projection/topic-stream-usable?
     (get-in state [:websocket :health])
     topic
     {:user address})))

(defn topic-usable-for-address-and-dex?
  "dex \"\" addresses the base perp dex and is a valid selector."
  [state topic address dex]
  (when (and (string? topic)
             (seq address)
             (string? dex))
    (health-projection/topic-stream-usable?
     (get-in state [:websocket :health])
     topic
     {:user address
      :dex dex})))

(defn topic-subscribed-for-address-and-dex?
  [state topic address dex]
  (when (and (string? topic)
             (seq address)
             (string? dex))
    (health-projection/topic-stream-subscribed?
     (get-in state [:websocket :health])
     topic
     {:user address
      :dex dex})))
