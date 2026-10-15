(ns hyperopen.api.market-loader
  (:require [hyperopen.api.market-metadata.facade :as market-metadata]
            [hyperopen.asset-selector.perp-annotations :as perp-annotations]))

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
           request-perp-annotations!
           request-meta-and-asset-ctxs!
           build-market-state
           log-fn]}]
  (let [phase (if (= :bootstrap (:phase opts)) :bootstrap :full)
        priority (if (= phase :bootstrap) :high :low)
        ;; Bootstrap hydrates only the default-dex perp catalog from the
        ;; metaAndAssetCtxs request the critical path already issues (deduped
        ;; below). Spot/webdata2/outcome catalogs (~320KB of /info JSON) are
        ;; fetched and built by the demand paths (:full phase) — selector open
        ;; or a route that needs them — keeping their network, parsing, and
        ;; build cost out of the post-paint TBT window.
        bootstrap? (= phase :bootstrap)
        perp-dex-names-promise (if bootstrap?
                                 (js/Promise.resolve [])
                                 (market-metadata/ensure-perp-dex-names!
                                  {:ensure-perp-dexs-data! ensure-perp-dexs-data!}
                                  {:priority priority}))
        outcome-meta-promise (if (or bootstrap?
                                     (nil? ensure-outcome-meta-data!))
                               (js/Promise.resolve {:outcomes [] :questions []})
                               (ensure-outcome-meta-data! {:priority priority}))
        spot-meta-promise (if bootstrap?
                            (js/Promise.resolve {})
                            (ensure-spot-meta-data! {:priority priority}))
        webdata2-promise (if bootstrap?
                           (js/Promise.resolve {})
                           (ensure-public-webdata2! {:priority priority}))
        ;; Display names only label HIP-3 markets, which bootstrap never builds.
        ;; A failed fetch must not block the catalog: fall back to coin symbols.
        perp-annotations-promise (if (or bootstrap?
                                         (nil? request-perp-annotations!))
                                   (js/Promise.resolve {})
                                   (-> (request-perp-annotations! {:priority priority})
                                       (.catch (fn [err]
                                                 (log-fn "Perp annotations unavailable:" err)
                                                 {}))))
        base-promises (js/Promise.all
                       (clj->js [perp-dex-names-promise
                                 spot-meta-promise
                                 webdata2-promise
                                 outcome-meta-promise
                                 perp-annotations-promise]))]
    (log-fn "Fetching asset selector markets. phase:" (name phase))
    (.then
     base-promises
     (fn [[dexs* spot-meta-loaded webdata2 outcome-meta annotations]]
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
            (let [market-state (-> (build-market-state active-asset
                                                       phase
                                                       dexs*
                                                       spot-meta-loaded
                                                       spot-asset-ctxs
                                                       (array-seq perp-results)
                                                       outcome-meta)
                                   (perp-annotations/apply-perp-annotations annotations))]
              {:phase phase
               :spot-meta spot-meta-loaded
               :market-state market-state}))))))))
