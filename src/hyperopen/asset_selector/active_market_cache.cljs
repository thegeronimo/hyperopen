(ns hyperopen.asset-selector.active-market-cache
  (:require [hyperopen.platform :as platform]))

(def ^:private active-market-display-local-storage-key
  "active-market-display")

(defn normalize-active-market-display
  [market {:keys [normalize-display-text normalize-market-type parse-max-leverage]}]
  (when (map? market)
    (let [coin (normalize-display-text (:coin market))
          key (normalize-display-text (:key market))
          symbol (normalize-display-text (:symbol market))
          base (normalize-display-text (:base market))
          quote (normalize-display-text (:quote market))
          dex (normalize-display-text (:dex market))
          market-type (normalize-market-type (:market-type market))
          max-leverage (parse-max-leverage (:maxLeverage market))]
      (when (seq coin)
        (cond-> {:coin coin}
          (seq key) (assoc :key key)
          (seq symbol) (assoc :symbol symbol)
          (seq base) (assoc :base base)
          (seq quote) (assoc :quote quote)
          (seq dex) (assoc :dex dex)
          market-type (assoc :market-type market-type)
          (some? max-leverage) (assoc :maxLeverage max-leverage))))))

(defn persist-active-market-display!
  [market normalize-deps]
  (when-let [normalized (normalize-active-market-display market normalize-deps)]
    (try
      (platform/local-storage-set! active-market-display-local-storage-key
                                   (js/JSON.stringify (clj->js normalized)))
      (catch :default e
        (js/console.warn "Failed to persist active market display metadata:" e)))))

(defn load-active-market-display
  [active-asset normalize-deps]
  (when (seq active-asset)
    (try
      (let [raw (platform/local-storage-get active-market-display-local-storage-key)]
        (when (seq raw)
          (let [parsed (-> raw
                           js/JSON.parse
                           (js->clj :keywordize-keys true)
                           (normalize-active-market-display normalize-deps))]
            (when (= active-asset (:coin parsed))
              parsed))))
      (catch :default _
        nil))))
