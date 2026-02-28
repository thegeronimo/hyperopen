(ns hyperopen.asset-selector.active-market-cache
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]))

(def ^:private active-market-display-local-storage-key
  "active-market-display")

(defn- parse-optional-boolean
  [value]
  (cond
    (boolean? value) value
    (string? value) (= "true" (some-> value str/trim str/lower-case))
    :else nil))

(defn- normalize-margin-mode
  [value]
  (let [token (cond
                (keyword? value) (name value)
                (string? value) value
                :else nil)
        normalized (some-> token
                          str/trim
                          str/lower-case
                          (str/replace #"[_-]" ""))]
    (case normalized
      "normal" :normal
      "nocross" :no-cross
      "strictisolated" :strict-isolated
      nil)))

(defn normalize-active-market-display
  [market {:keys [normalize-display-text normalize-market-type parse-max-leverage parse-market-index]}]
  (when (map? market)
    (let [parse-index (or parse-market-index (fn [_] nil))
          coin (normalize-display-text (:coin market))
          key (normalize-display-text (:key market))
          symbol (normalize-display-text (:symbol market))
          base (normalize-display-text (:base market))
          quote (normalize-display-text (:quote market))
          dex (normalize-display-text (:dex market))
          market-type (normalize-market-type (:market-type market))
          only-isolated? (parse-optional-boolean
                          (or (:only-isolated? market)
                              (:onlyIsolated market)))
          margin-mode (normalize-margin-mode
                       (or (:margin-mode market)
                           (:marginMode market)))
          market-idx (parse-index (:idx market))
          perp-dex-index (some parse-index
                               [(:perp-dex-index market)
                                (:perpDexIndex market)])
          explicit-asset-id (some parse-index
                                  [(:asset-id market)
                                   (:assetId market)])
          asset-id (or explicit-asset-id
                       (when (and (some? market-idx)
                                  (not (seq dex)))
                         market-idx))
          max-leverage (parse-max-leverage (:maxLeverage market))]
      (when (seq coin)
        (cond-> {:coin coin}
          (seq key) (assoc :key key)
          (seq symbol) (assoc :symbol symbol)
          (seq base) (assoc :base base)
          (seq quote) (assoc :quote quote)
          (seq dex) (assoc :dex dex)
          market-type (assoc :market-type market-type)
          (some? only-isolated?) (assoc :only-isolated? only-isolated?)
          margin-mode (assoc :margin-mode margin-mode)
          (some? market-idx) (assoc :idx market-idx)
          (some? perp-dex-index) (assoc :perp-dex-index perp-dex-index)
          (some? asset-id) (assoc :asset-id asset-id)
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
