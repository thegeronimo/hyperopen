(ns hyperopen.api.market-metadata.facade
  (:require [hyperopen.api.market-metadata.perp-dexs :as perp-dexs]))

(defn payload->dex-names
  [payload]
  (perp-dexs/payload->dex-names payload))

(defn payload->named-dex-names
  [payload]
  (->> (payload->dex-names payload)
       (remove nil?)
       vec))

(defn fetch-and-apply-perp-dex-metadata!
  [{:keys [store
           log-fn
           request-perp-dexs!
           apply-perp-dexs-success
           apply-perp-dexs-error]}
   opts]
  (-> (request-perp-dexs! opts)
      (.then (fn [payload]
               (swap! store apply-perp-dexs-success payload)
               (payload->dex-names payload)))
      (.catch (fn [err]
                (when log-fn
                  (log-fn "Error fetching perp DEX list:" err))
                (swap! store apply-perp-dexs-error err)
                (js/Promise.reject err)))))

(defn ensure-and-apply-perp-dex-metadata!
  [{:keys [store
           ensure-perp-dexs-data!
           apply-perp-dexs-success
           apply-perp-dexs-error]}
   opts]
  (-> (ensure-perp-dexs-data! store opts)
      (.then (fn [payload]
               (swap! store apply-perp-dexs-success payload)
               (payload->dex-names payload)))
      (.catch (fn [err]
                (swap! store apply-perp-dexs-error err)
                (js/Promise.reject err)))))

(defn ensure-perp-dex-names!
  [{:keys [ensure-perp-dexs-data!]}
   opts]
  (-> (ensure-perp-dexs-data! opts)
      (.then payload->named-dex-names)))
