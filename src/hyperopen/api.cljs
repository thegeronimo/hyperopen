(ns hyperopen.api
  (:require 
   [hyperopen.utils.data-normalization :refer [normalize-asset-contexts]]
   [hyperopen.utils.interval :refer [interval-to-milliseconds]]))

(defn fetch-asset-contexts! [store]
  (println "Fetching perpetual asset contexts...")
  (-> (js/fetch "https://api.hyperliquid.xyz/info"
                (clj->js {:method "POST"
                          :headers {"Content-Type" "application/json"}
                          :body (js/JSON.stringify (clj->js {"type" "metaAndAssetCtxs"}))}))
      (.then #(.json %))
      (.then #(let [data (js->clj % :keywordize-keys true)
                    normalised (normalize-asset-contexts data)]
                (swap! store assoc-in [:asset-contexts] normalised)
                (println "Loaded" (count normalised) "assets")))
      (.catch #(do (println "Error fetching asset contexts:" %)
                   (swap! store assoc-in [:asset-contexts :error] (str %))))))

(defn fetch-candle-snapshot!
  "Fetch `bars` worth of candles for `coin` at keyword interval (e.g. :1m, :1h)."
  [store coin interval bars]
  (let [now        (js/Date.now)
        ms         (interval-to-milliseconds interval)
        start      (- now (* bars ms))
        interval-s (name interval)                                  ;; "1m", "1h", etc.
        body       (clj->js
                     {"type" "candleSnapshot"
                      "req"  {"coin"     coin
                              "interval" interval-s
                              "startTime" start
                              "endTime"   now}})]
    (println "Fetching" bars interval-s "bars for" coin)
    (-> (js/fetch "https://api.hyperliquid.xyz/info"
                  (clj->js {:method  "POST"
                            :headers {"Content-Type" "application/json"}
                            :body    (js/JSON.stringify body)}))
        (.then #(.json %))
        (.then #(let [data (js->clj % :keywordize-keys true)]
                  (swap! store assoc-in [:candles coin interval] data)
                  (println "Loaded" (count (get data :data [])) "candles for" coin)))
        (.catch #(do (println "Error fetching" %) 
                     (swap! store assoc-in [:candles coin interval :error] (str %))))))) 