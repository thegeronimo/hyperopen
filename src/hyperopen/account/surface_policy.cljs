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
  [state topic address dex]
  (when (and (string? topic)
             (seq address)
             (seq dex))
    (health-projection/topic-stream-usable?
     (get-in state [:websocket :health])
     topic
     {:user address
      :dex dex})))

(defn topic-subscribed-for-address-and-dex?
  [state topic address dex]
  (when (and (string? topic)
             (seq address)
             (seq dex))
    (health-projection/topic-stream-subscribed?
     (get-in state [:websocket :health])
     topic
     {:user address
      :dex dex})))

(defn spot-refresh-surface-active?
  [state]
  (let [route (some-> (get-in state [:router :path]) str str/trim)
        selected-tab (get-in state [:account-info :selected-tab])
        balances-tab-active? (or (nil? selected-tab)
                                 (= selected-tab :balances))
        outcomes-tab-active? (= selected-tab :outcomes)
        trade-route-active? (or (str/blank? route)
                                (str/starts-with? route "/trade"))
        funding-modal-open? (true? (get-in state [:funding-ui :modal :open?]))
        position-margin-modal-open? (true? (get-in state [:positions-ui :margin-modal :open?]))
        vault-transfer-modal-open? (true? (get-in state [:vaults-ui :vault-transfer-modal :open?]))]
    (or funding-modal-open?
        position-margin-modal-open?
        vault-transfer-modal-open?
        (and trade-route-active?
             (or balances-tab-active?
                 outcomes-tab-active?)))))
