(ns hyperopen.api.projections.asset-selector
  (:require [hyperopen.api.errors :as api-errors]))

(defn- normalized-error
  [err]
  (api-errors/normalize-error err))

(defn begin-asset-selector-load
  [state phase]
  (-> state
      (assoc-in [:asset-selector :loading?] true)
      (assoc-in [:asset-selector :phase] phase)))

(defn- build-market-index-by-key
  [markets]
  (reduce-kv (fn [acc idx market]
               (if-let [market-key (:key market)]
                 (assoc acc market-key idx)
                 acc))
             {}
             (vec (or markets []))))

(defn apply-asset-selector-success
  [state phase {:keys [markets market-by-key market-index-by-key active-market loaded-at-ms]}]
  (let [current-phase (get-in state [:asset-selector :phase])
        cache-hydrated? (boolean (get-in state [:asset-selector :cache-hydrated?]))
        prefer-current? (and (= phase :bootstrap)
                             (= current-phase :full)
                             (not cache-hydrated?))
        market-index-by-key* (if (map? market-index-by-key)
                               market-index-by-key
                               (build-market-index-by-key markets))
        state* (if prefer-current?
                 (assoc-in state [:asset-selector :loaded-at-ms] loaded-at-ms)
                 (-> state
                     (assoc-in [:asset-selector :markets] markets)
                     (assoc-in [:asset-selector :market-by-key] market-by-key)
                     (assoc-in [:asset-selector :market-index-by-key] market-index-by-key*)
                     (assoc :active-market (or active-market (:active-market state)))
                     (assoc-in [:asset-selector :loaded-at-ms] loaded-at-ms)
                     (assoc-in [:asset-selector :phase] phase)
                     (assoc-in [:asset-selector :loaded-phase] phase)
                     (assoc-in [:asset-selector :cache-hydrated?] false)
                     (assoc-in [:asset-selector :error] nil)
                     (assoc-in [:asset-selector :error-category] nil)))]
    (assoc-in state* [:asset-selector :loading?] false)))

(defn apply-asset-selector-error
  "A failed load is an error state, not a loaded catalog: the phase rolls back
   to the last phase that actually loaded (`begin-asset-selector-load` advanced
   it before the request), so the next open or retry fetches again instead of
   latching a failed full load as `:full`."
  [state err]
  (let [{:keys [message category]} (normalized-error err)
        loaded-phase (get-in state [:asset-selector :loaded-phase] :bootstrap)]
    (-> state
        (assoc-in [:asset-selector :loading?] false)
        (assoc-in [:asset-selector :phase] loaded-phase)
        (assoc-in [:asset-selector :error] message)
        (assoc-in [:asset-selector :error-category] category))))
