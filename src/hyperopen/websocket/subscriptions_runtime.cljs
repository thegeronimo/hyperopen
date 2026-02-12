(ns hyperopen.websocket.subscriptions-runtime)

(defn subscribe-active-asset!
  [{:keys [store
           coin
           log-fn
           resolve-market-by-coin-fn
           persist-active-asset!
           persist-active-market-display!
           subscribe-active-asset-ctx!
           fetch-candle-snapshot!]}]
  (log-fn "Subscribing to active asset context for:" coin)
  (let [market-by-key (get-in @store [:asset-selector :market-by-key] {})
        market (resolve-market-by-coin-fn market-by-key coin)
        canonical-coin (or (:coin market) coin)
        resolved-market (or market
                           (resolve-market-by-coin-fn
                            market-by-key
                            canonical-coin))]
    (persist-active-asset! canonical-coin)
    (persist-active-market-display! resolved-market)
    (swap! store
           (fn [state]
             (let [market (or resolved-market
                              (resolve-market-by-coin-fn
                               (get-in state [:asset-selector :market-by-key] {})
                               canonical-coin))]
               (-> state
                   (assoc-in [:active-assets :loading] true)
                   (assoc-in [:active-asset] canonical-coin)
                   (assoc-in [:selected-asset] canonical-coin)
                   (assoc :active-market (or market (:active-market state)))))))
    (subscribe-active-asset-ctx! canonical-coin)
    (fetch-candle-snapshot! (get-in @store [:chart-options :selected-timeframe] :1d))))

(defn unsubscribe-active-asset!
  [{:keys [store coin log-fn unsubscribe-active-asset-ctx!]}]
  (log-fn "Unsubscribing from active asset context for:" coin)
  (unsubscribe-active-asset-ctx! coin)
  (swap! store update-in [:active-assets :contexts] dissoc coin))

(defn subscribe-orderbook!
  [{:keys [store
           coin
           log-fn
           normalize-mode-fn
           mode->subscription-config-fn
           subscribe-orderbook-fn]}]
  (log-fn "Subscribing to orderbook for:" coin)
  (let [selected-mode (get-in @store [:orderbook-ui :price-aggregation-by-coin coin] :full)
        mode (normalize-mode-fn selected-mode)
        aggregation-config (mode->subscription-config-fn mode)]
    (subscribe-orderbook-fn coin aggregation-config)))

(defn subscribe-trades!
  [{:keys [coin log-fn subscribe-trades-fn]}]
  (log-fn "Subscribing to trades for:" coin)
  (subscribe-trades-fn coin))

(defn unsubscribe-orderbook!
  [{:keys [store coin log-fn unsubscribe-orderbook-fn]}]
  (log-fn "Unsubscribing from orderbook for:" coin)
  (unsubscribe-orderbook-fn coin)
  (swap! store update-in [:orderbooks] dissoc coin))

(defn unsubscribe-trades!
  [{:keys [coin log-fn unsubscribe-trades-fn]}]
  (log-fn "Unsubscribing from trades for:" coin)
  (unsubscribe-trades-fn coin))

(defn subscribe-webdata2!
  [{:keys [address log-fn subscribe-webdata2-fn]}]
  (log-fn "Subscribing to WebData2 for address:" address)
  (subscribe-webdata2-fn address))

(defn unsubscribe-webdata2!
  [{:keys [address log-fn unsubscribe-webdata2-fn]}]
  (log-fn "Unsubscribing from WebData2 for address:" address)
  (unsubscribe-webdata2-fn address))
