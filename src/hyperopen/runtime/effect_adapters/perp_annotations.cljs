(ns hyperopen.runtime.effect-adapters.perp-annotations
  (:require [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.asset-selector.annotations :as annotations]))

;; HIP-3 deployers publish market annotations on-chain. The bulk concise
;; payload (category, display name, keywords) is the same request the market
;; catalog makes (`api/request-perp-annotations!`, cache key
;; :perp-annotations), so the two share one network call per cache window.
;; The description is per coin, so it loads lazily for the market viewed.

(defn- log-warning
  [message error]
  (when (exists? js/console)
    (.warn js/console message error)))

(defn- ensure-concise-annotations!
  [store request-perp-annotations!]
  (if (annotations/concise-load-needed? @store)
    (do
      (swap! store annotations/begin-concise-load)
      (-> (request-perp-annotations! {})
          (.then (fn [payload]
                   (swap! store annotations/apply-concise-success payload)))
          (.catch (fn [error]
                    (log-warning "Error fetching perp annotations:" error)
                    (swap! store annotations/apply-concise-error)))))
    (js/Promise.resolve nil)))

(defn- ensure-dex-full-names!
  [store request-perp-dexs!]
  (if (seq (:perp-dex-full-name-by-name @store))
    (js/Promise.resolve nil)
    (-> (request-perp-dexs! {:priority :low})
        (.then (fn [payload]
                 (swap! store api-projections/apply-perp-dexs-success payload)))
        (.catch (fn [error]
                  (log-warning "Error fetching perp DEX names:" error))))))

(defn- ensure-annotation-detail!
  [store request-perp-annotation! coin]
  (if (annotations/detail-load-needed? @store coin)
    (do
      (swap! store annotations/begin-detail-load coin)
      (-> (request-perp-annotation! coin {})
          (.then (fn [payload]
                   (swap! store annotations/apply-detail-success coin payload)))
          (.catch (fn [error]
                    (log-warning "Error fetching perp annotation:" error)
                    (swap! store annotations/apply-detail-error coin)))))
    (js/Promise.resolve nil)))

(defn sync-perp-annotations-with-deps!
  [{:keys [request-perp-annotations!
           request-perp-annotation!
           request-perp-dexs!]}
   store
   coin]
  ;; The bulk payload serves the selector for any active coin; the deployer
  ;; names and the description only matter for a HIP-3 header.
  (js/Promise.all
   (into-array
    (cond-> [(ensure-concise-annotations! store request-perp-annotations!)]
      (annotations/annotatable-coin? coin)
      (conj (ensure-dex-full-names! store request-perp-dexs!)
            (ensure-annotation-detail! store request-perp-annotation! coin))))))

(defn sync-perp-annotations
  [_ store coin]
  (sync-perp-annotations-with-deps!
   {:request-perp-annotations! api/request-perp-annotations!
    :request-perp-annotation! api/request-perp-annotation!
    :request-perp-dexs! api/request-perp-dexs!}
   store
   coin))
