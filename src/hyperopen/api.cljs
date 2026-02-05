(ns hyperopen.api
  (:require 
   [hyperopen.utils.data-normalization :refer [normalize-asset-contexts]]
   [hyperopen.utils.interval :refer [interval-to-milliseconds]]
   [hyperopen.asset-selector.markets :as markets]))

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

(defn fetch-meta-and-asset-ctxs!
  "Fetch metaAndAssetCtxs for the default perp DEX or a named DEX."
  ([] (fetch-meta-and-asset-ctxs! nil))
  ([dex]
   (let [body (cond-> {"type" "metaAndAssetCtxs"}
                (and dex (not= dex "")) (assoc "dex" dex))]
     (-> (post-info! body)
         (.then #(.json %))
         (.then #(js->clj % :keywordize-keys true))))))

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
  ([store address] (fetch-frontend-open-orders! store address nil))
  ([store address dex]
   (let [body (cond-> {"type" "frontendOpenOrders" "user" address}
                (and dex (not= dex "")) (assoc "dex" dex))]
     (-> (post-info! body)
         (.then #(.json %))
         (.then #(let [data (js->clj % :keywordize-keys true)]
                   (if (and dex (not= dex ""))
                     (swap! store assoc-in [:orders :open-orders-snapshot-by-dex dex] data)
                     (swap! store assoc-in [:orders :open-orders-snapshot] data))))
         (.catch #(do (println "Error fetching open orders:" %)
                      (swap! store assoc-in [:orders :open-error] (str %))))))))

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
                (swap! store assoc-in [:spot :error] nil)
                data))
      (.catch #(do (println "Error fetching spot meta:" %)
                   (swap! store assoc-in [:spot :loading-meta?] false)
                   (swap! store assoc-in [:spot :error] (str %))))))

(defn fetch-spot-meta-raw!
  "Fetch spot meta and return the parsed response without touching state."
  []
  (-> (post-info! {"type" "spotMeta"})
      (.then #(.json %))
      (.then #(js->clj % :keywordize-keys true))))

(defn fetch-public-webdata2!
  "Fetch a public WebData2 snapshot to access spotAssetCtxs."
  []
  (-> (post-info! {"type" "webData2"
                   "user" "0x0000000000000000000000000000000000000000"})
      (.then #(.json %))
      (.then #(js->clj % :keywordize-keys true))))

(defn fetch-asset-selector-markets!
  "Fetch and build a unified market list for the asset selector."
  [store]
  (println "Fetching asset selector markets...")
  (let [dexs (get-in @store [:perp-dexs])
        dexs-p (if (seq dexs)
                 (js/Promise.resolve dexs)
                 (fetch-perp-dexs! store))
        spot-meta (get-in @store [:spot :meta])
        spot-meta-p (if spot-meta
                      (js/Promise.resolve spot-meta)
                      (fetch-spot-meta-raw!))
        webdata2-p (fetch-public-webdata2!)]
    (-> (js/Promise.all (clj->js [dexs-p spot-meta-p webdata2-p]))
        (.then
         (fn [[dexs-loaded spot-meta-loaded webdata2]]
           (let [dexs* (vec (remove nil? dexs-loaded))
                 dexs-with-default (cons nil dexs*)
                 perp-promises (->> dexs-with-default
                                    (map fetch-meta-and-asset-ctxs!)
                                    (into-array))
                 token-by-index (into {}
                                      (map (fn [{:keys [index name]}]
                                             [index name]))
                                      (:tokens spot-meta-loaded))
                 spot-asset-ctxs (:spotAssetCtxs webdata2)]
             (.then (js/Promise.all perp-promises)
                    (fn [perp-results]
                      (let [perp-results (array-seq perp-results)
                            perp-markets (->> (map vector dexs-with-default perp-results)
                                              (mapcat (fn [[dex [meta asset-ctxs]]]
                                                        (markets/build-perp-markets
                                                         meta
                                                         asset-ctxs
                                                         token-by-index
                                                         :dex dex)))
                                              vec)
                            spot-markets (markets/build-spot-markets
                                          spot-meta-loaded
                                          spot-asset-ctxs)
                            all-markets (vec (concat perp-markets spot-markets))
                            market-by-key (into {}
                                                (map (fn [m] [(:key m) m]))
                                                all-markets)
                            active-asset (:active-asset @store)
                            active-market (when active-asset
                                            (get market-by-key
                                                 (markets/coin->market-key active-asset)))]
                        (swap! store assoc-in [:asset-selector :markets] all-markets)
                        (swap! store assoc-in [:asset-selector :market-by-key] market-by-key)
                        (swap! store assoc :active-market active-market))))))))
        (.catch (fn [err]
                  (println "Error fetching asset selector markets:" err)))))

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
