(ns hyperopen.api
  (:require 
   [hyperopen.utils.data-normalization :refer [normalize-asset-contexts]]
   [hyperopen.utils.interval :refer [interval-to-milliseconds]]))

(def info-url "https://api.hyperliquid.xyz/info")

(defn- post-info!
  [body]
  (js/fetch info-url
            (clj->js {:method "POST"
                      :headers {"Content-Type" "application/json"}
                      :body (js/JSON.stringify (clj->js body))})))

(defn fetch-asset-contexts! [store]
  (println "Fetching perpetual asset contexts...")
  (-> (post-info! {"type" "metaAndAssetCtxs"})
      (.then #(.json %))
      (.then #(let [data (js->clj % :keywordize-keys true)
                    normalised (normalize-asset-contexts data)]
                (swap! store assoc-in [:asset-contexts] normalised)
                (println "Loaded" (count normalised) "assets")))
      (.catch #(do (println "Error fetching asset contexts:" %)
                   (swap! store assoc-in [:asset-contexts :error] (str %))))))

(defn fetch-perp-dexs!
  "Fetch the list of available perp DEXes. The default DEX is omitted from
  the response, so we only store named DEXes."
  [store]
  (println "Fetching perp DEX list...")
  (-> (post-info! {"type" "perpDexs"})
      (.then #(.json %))
      (.then #(let [data (js->clj % :keywordize-keys true)
                    dex-names (->> data
                                   (keep (fn [entry]
                                           (when (and (map? entry)
                                                      (seq (:name entry)))
                                             (:name entry))))
                                   vec)]
                (swap! store assoc-in [:perp-dexs] dex-names)
                dex-names))
      (.catch #(do (println "Error fetching perp DEX list:" %)
                   (swap! store assoc-in [:perp-dexs-error] (str %))))))

(defn fetch-candle-snapshot!
  "Fetch `bars` worth of candles for the active asset at keyword interval (e.g. :1m, :1h).
   Defaults to :1d interval and 330 bars if not specified."
  [store & {:keys [interval bars] :or {interval :1d bars 330}}]
  (let [active-asset (:active-asset @store)]
    (if (nil? active-asset)
      (println "No active asset selected, skipping candle fetch")
      (let [now        (js/Date.now)
            ms         (interval-to-milliseconds interval)
            start      (- now (* bars ms))
            interval-s (name interval)                                  ;; "1m", "1h", etc.
            body       (clj->js
                         {"type" "candleSnapshot"
                          "req"  {"coin"     active-asset
                                  "interval" interval-s
                                  "startTime" start
                                  "endTime"   now}})]
        (println "Fetching" bars interval-s "bars for" active-asset)
        (-> (post-info! body)
            (.then #(.json %))
            (.then #(let [data (js->clj % :keywordize-keys true)]
                      (swap! store assoc-in [:candles active-asset interval] data)
                      nil))
            (.catch #(do (println "Error fetching" %) 
                         (swap! store assoc-in [:candles active-asset interval :error] (str %))))))))) 

(defn fetch-frontend-open-orders!
  [store address]
  (-> (post-info! {"type" "frontendOpenOrders" "user" address})
      (.then #(.json %))
      (.then #(let [data (js->clj % :keywordize-keys true)]
                (swap! store assoc-in [:orders :open] data)))
      (.catch #(do (println "Error fetching open orders:" %)
                   (swap! store assoc-in [:orders :open-error] (str %))))))

(defn fetch-user-fills!
  [store address]
  (-> (post-info! {"type" "userFills" "user" address "aggregateByTime" true})
      (.then #(.json %))
      (.then #(let [data (js->clj % :keywordize-keys true)]
                (swap! store assoc-in [:orders :fills] data)))
      (.catch #(do (println "Error fetching user fills:" %)
                   (swap! store assoc-in [:orders :fills-error] (str %)))))) 

(defn fetch-spot-meta!
  [store]
  (println "Fetching spot metadata...")
  (swap! store assoc-in [:spot :loading-meta?] true)
  (-> (post-info! {"type" "spotMeta"})
      (.then #(.json %))
      (.then #(let [data (js->clj % :keywordize-keys true)]
                (swap! store assoc-in [:spot :meta] data)
                (swap! store assoc-in [:spot :loading-meta?] false)
                (swap! store assoc-in [:spot :error] nil)))
      (.catch #(do (println "Error fetching spot meta:" %)
                   (swap! store assoc-in [:spot :loading-meta?] false)
                   (swap! store assoc-in [:spot :error] (str %))))))

(defn fetch-spot-clearinghouse-state!
  [store address]
  (when address
    (println "Fetching spot clearinghouse state...")
    (swap! store assoc-in [:spot :loading-balances?] true)
    (-> (post-info! {"type" "spotClearinghouseState" "user" address})
        (.then #(.json %))
        (.then #(let [data (js->clj % :keywordize-keys true)]
                  (swap! store assoc-in [:spot :clearinghouse-state] data)
                  (swap! store assoc-in [:spot :loading-balances?] false)
                  (swap! store assoc-in [:spot :error] nil)))
        (.catch #(do (println "Error fetching spot balances:" %)
                     (swap! store assoc-in [:spot :loading-balances?] false)
                     (swap! store assoc-in [:spot :error] (str %)))))))

(defn fetch-clearinghouse-state!
  "Fetch clearinghouse state for a specific perp DEX."
  [store address dex]
  (when address
    (let [body (cond-> {"type" "clearinghouseState" "user" address}
                 (and dex (not= dex "")) (assoc "dex" dex))]
      (-> (post-info! body)
          (.then #(.json %))
          (.then #(let [data (js->clj % :keywordize-keys true)]
                    (swap! store assoc-in [:perp-dex-clearinghouse dex] data)))
          (.catch #(do (println "Error fetching clearinghouse state:" %)
                       (swap! store assoc-in [:perp-dex-clearinghouse-error] (str %))))))))

(defn fetch-perp-dex-clearinghouse-states!
  "Fetch clearinghouse state for all named perp DEXes."
  [store address dex-names]
  (when (and address (seq dex-names))
    (doseq [dex dex-names]
      (fetch-clearinghouse-state! store address dex))))
