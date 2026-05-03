(ns hyperopen.api.market-loader
  (:require [hyperopen.api.market-metadata.facade :as market-metadata]))

(defn- meta-and-asset-ctxs-request-opts
  [phase priority dex]
  (cond-> {:priority priority}
    (and (= phase :bootstrap)
         (nil? dex))
    ;; Startup already issues a high-priority default `metaAndAssetCtxs` request
    ;; via `fetch-asset-contexts!`. Share that single-flight instead of sending
    ;; a second identical `/info` request during bootstrap selector hydration.
    (assoc :dedupe-key :asset-contexts)))

(defn request-asset-selector-markets!
  [{:keys [opts
           active-asset
           ensure-perp-dexs-data!
           ensure-spot-meta-data!
           ensure-outcome-meta-data!
           ensure-public-webdata2!
           request-meta-and-asset-ctxs!
           build-market-state
           log-fn]}]
  (let [phase (if (= :bootstrap (:phase opts)) :bootstrap :full)
        priority (if (= phase :bootstrap) :high :low)
        perp-dex-names-promise (if (= phase :bootstrap)
                                 (js/Promise.resolve [])
                                 (market-metadata/ensure-perp-dex-names!
                                  {:ensure-perp-dexs-data! ensure-perp-dexs-data!}
                                  {:priority priority}))
        outcome-meta-promise (if (nil? ensure-outcome-meta-data!)
                               (js/Promise.resolve {:outcomes [] :questions []})
                               (ensure-outcome-meta-data! {:priority priority}))
        base-promises (js/Promise.all
                       (clj->js [perp-dex-names-promise
                                 (ensure-spot-meta-data! {:priority priority})
                                 (ensure-public-webdata2! {:priority priority})
                                 outcome-meta-promise]))]
    (log-fn "Fetching asset selector markets. phase:" (name phase))
    (.then
     base-promises
     (fn [[dexs* spot-meta-loaded webdata2 outcome-meta]]
       (let [dexs-with-default (if (= phase :bootstrap)
                                 [nil]
                                 (vec (cons nil dexs*)))
             perp-promises (->> dexs-with-default
                                (map (fn [dex]
                                       (request-meta-and-asset-ctxs!
                                        dex
                                        (meta-and-asset-ctxs-request-opts phase priority dex))))
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
                                                   (array-seq perp-results)
                                                   outcome-meta)]
              {:phase phase
               :spot-meta spot-meta-loaded
               :market-state market-state}))))))))
