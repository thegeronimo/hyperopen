(ns hyperopen.api.endpoints.market
  (:require [hyperopen.asset-selector.markets :as markets]
            [hyperopen.utils.data-normalization :refer [normalize-asset-contexts]]
            [hyperopen.utils.interval :refer [interval-to-milliseconds]]))

(defn- dex-names-from-response
  [data]
  (->> data
       (keep (fn [entry]
               (when (and (map? entry)
                          (seq (:name entry)))
                 (:name entry))))
       vec))

(defn request-asset-contexts!
  [post-info! opts]
  (-> (post-info! {"type" "metaAndAssetCtxs"}
                  (merge {:priority :high} opts))
      (.then normalize-asset-contexts)))

(defn request-meta-and-asset-ctxs!
  [post-info! dex opts]
  (let [body (cond-> {"type" "metaAndAssetCtxs"}
               (and dex (not= dex "")) (assoc "dex" dex))
        dedupe-key (or (:dedupe-key opts)
                       (if (seq dex)
                         [:meta-and-asset-ctxs dex]
                         :meta-and-asset-ctxs-default))]
    (post-info! body
                (merge {:priority :high
                        :dedupe-key dedupe-key}
                       opts))))

(defn request-perp-dexs!
  [post-info! opts]
  (-> (post-info! {"type" "perpDexs"}
                  (merge {:priority :high} opts))
      (.then dex-names-from-response)))

(defn request-candle-snapshot!
  [post-info! now-ms-fn coin {:keys [interval bars priority]
                              :or {interval :1d bars 330 priority :high}}]
  (if (nil? coin)
    (js/Promise.resolve nil)
    (let [now (now-ms-fn)
          ms (interval-to-milliseconds interval)
          start (- now (* bars ms))
          interval-s (name interval)
          body {"type" "candleSnapshot"
                "req" {"coin" coin
                       "interval" interval-s
                       "startTime" start
                       "endTime" now}}]
      (post-info! body {:priority priority}))))

(defn request-spot-meta!
  [post-info! opts]
  (post-info! {"type" "spotMeta"}
              (merge {:priority :high}
                     opts)))

(defn request-public-webdata2!
  [post-info! opts]
  (post-info! {"type" "webData2"
               "user" "0x0000000000000000000000000000000000000000"}
              (merge {:priority :high}
                     opts)))

(defn build-market-state
  [now-ms-fn active-asset phase dexs spot-meta spot-asset-ctxs perp-results]
  (let [dexs-with-default (if (= phase :bootstrap)
                            [nil]
                            (vec (cons nil (vec dexs))))
        token-by-index (into {}
                             (map (fn [{:keys [index name]}]
                                    [index name]))
                             (:tokens spot-meta))
        perp-markets (->> (map-indexed vector (map vector dexs-with-default perp-results))
                          (mapcat (fn [[perp-dex-index [dex [meta asset-ctxs]]]]
                                    (markets/build-perp-markets
                                     meta
                                     asset-ctxs
                                     token-by-index
                                     :dex dex
                                     :perp-dex-index perp-dex-index)))
                          vec)
        spot-markets (markets/build-spot-markets spot-meta spot-asset-ctxs)
        all-markets (vec (concat perp-markets spot-markets))
        market-by-key (into {}
                            (map (fn [m] [(:key m) m]))
                            all-markets)
        active-market (when active-asset
                        (markets/resolve-market-by-coin
                         market-by-key
                         active-asset))]
    {:markets all-markets
     :market-by-key market-by-key
     :active-market active-market
     :loaded-at-ms (now-ms-fn)}))
