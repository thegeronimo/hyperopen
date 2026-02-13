(ns hyperopen.api.projections)

(defn begin-spot-meta-load
  [state]
  (assoc-in state [:spot :loading-meta?] true))

(defn apply-spot-meta-success
  [state data]
  (-> state
      (assoc-in [:spot :meta] data)
      (assoc-in [:spot :loading-meta?] false)
      (assoc-in [:spot :error] nil)))

(defn apply-spot-meta-error
  [state err]
  (-> state
      (assoc-in [:spot :loading-meta?] false)
      (assoc-in [:spot :error] (str err))))

(defn begin-asset-selector-load
  [state phase]
  (-> state
      (assoc-in [:asset-selector :loading?] true)
      (assoc-in [:asset-selector :phase] phase)))

(defn apply-asset-selector-success
  [state phase {:keys [markets market-by-key active-market loaded-at-ms]}]
  (let [current-phase (get-in state [:asset-selector :phase])
        cache-hydrated? (boolean (get-in state [:asset-selector :cache-hydrated?]))
        prefer-current? (and (= phase :bootstrap)
                             (= current-phase :full)
                             (not cache-hydrated?))
        state* (if prefer-current?
                 (assoc-in state [:asset-selector :loaded-at-ms] loaded-at-ms)
                 (-> state
                     (assoc-in [:asset-selector :markets] markets)
                     (assoc-in [:asset-selector :market-by-key] market-by-key)
                     (assoc :active-market (or active-market (:active-market state)))
                     (assoc-in [:asset-selector :loaded-at-ms] loaded-at-ms)
                     (assoc-in [:asset-selector :phase] phase)
                     (assoc-in [:asset-selector :cache-hydrated?] false)
                     (assoc-in [:asset-selector :error] nil)))]
    (assoc-in state* [:asset-selector :loading?] false)))

(defn apply-asset-selector-error
  [state err]
  (-> state
      (assoc-in [:asset-selector :loading?] false)
      (assoc-in [:asset-selector :error] (str err))))

(defn begin-spot-balances-load
  [state]
  (assoc-in state [:spot :loading-balances?] true))

(defn apply-spot-balances-success
  [state data]
  (-> state
      (assoc-in [:spot :clearinghouse-state] data)
      (assoc-in [:spot :loading-balances?] false)
      (assoc-in [:spot :error] nil)))

(defn apply-spot-balances-error
  [state err]
  (-> state
      (assoc-in [:spot :loading-balances?] false)
      (assoc-in [:spot :error] (str err))))
