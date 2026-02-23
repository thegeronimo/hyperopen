(ns hyperopen.api.market-loader)

(defn- perp-dex-names
  [payload]
  (cond
    (map? payload)
    (vec (or (:dex-names payload)
             (:perp-dexs payload)
             []))

    (sequential? payload)
    (vec (keep (fn [entry]
                 (cond
                   (string? entry) entry
                   (map? entry) (:name entry)
                   :else nil))
               payload))

    :else
    []))

(defn request-asset-selector-markets!
  [{:keys [opts
           active-asset
           ensure-perp-dexs-data!
           ensure-spot-meta-data!
           ensure-public-webdata2!
           request-meta-and-asset-ctxs!
           build-market-state
           log-fn]}]
  (let [phase (if (= :bootstrap (:phase opts)) :bootstrap :full)
        priority (if (= phase :bootstrap) :high :low)
        base-promises (js/Promise.all
                       (clj->js [(ensure-perp-dexs-data! {:priority priority})
                                 (ensure-spot-meta-data! {:priority priority})
                                 (ensure-public-webdata2! {:priority priority})]))]
    (log-fn "Fetching asset selector markets. phase:" (name phase))
    (.then
     base-promises
     (fn [[perp-dexs-payload spot-meta-loaded webdata2]]
       (let [dexs* (->> (perp-dex-names perp-dexs-payload)
                        (remove nil?)
                        vec)
             dexs-with-default (if (= phase :bootstrap)
                                 [nil]
                                 (vec (cons nil dexs*)))
             perp-promises (->> dexs-with-default
                                (map (fn [dex]
                                       (request-meta-and-asset-ctxs!
                                        dex
                                        {:priority priority})))
                                (into-array))
             spot-asset-ctxs (:spotAssetCtxs webdata2)]
         (.then
          (js/Promise.all perp-promises)
          (fn [perp-results]
            (let [market-state (build-market-state active-asset
                                                   phase
                                                   dexs*
                                                   spot-meta-loaded
                                                   spot-asset-ctxs
                                                   (array-seq perp-results))]
              {:phase phase
               :spot-meta spot-meta-loaded
               :market-state market-state}))))))))
