(ns hyperopen.api.projections
  (:require [clojure.string :as str]))

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

(defn apply-asset-contexts-success
  [state rows]
  (assoc-in state [:asset-contexts] rows))

(defn apply-asset-contexts-error
  [state err]
  (assoc-in state [:asset-contexts :error] (str err)))

(defn apply-perp-dexs-success
  [state dex-names]
  (assoc-in state [:perp-dexs] dex-names))

(defn apply-perp-dexs-error
  [state err]
  (assoc-in state [:perp-dexs-error] (str err)))

(defn apply-candle-snapshot-success
  [state coin interval rows]
  (assoc-in state [:candles coin interval] rows))

(defn apply-candle-snapshot-error
  [state coin interval err]
  (assoc-in state [:candles coin interval :error] (str err)))

(defn apply-open-orders-success
  [state dex rows]
  (if (and dex (not= dex ""))
    (assoc-in state [:orders :open-orders-snapshot-by-dex dex] rows)
    (assoc-in state [:orders :open-orders-snapshot] rows)))

(defn apply-open-orders-error
  [state err]
  (assoc-in state [:orders :open-error] (str err)))

(defn apply-user-fills-success
  [state rows]
  (assoc-in state [:orders :fills] rows))

(defn apply-user-fills-error
  [state err]
  (assoc-in state [:orders :fills-error] (str err)))

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

(defn apply-user-abstraction-snapshot
  [state requested-address snapshot]
  (let [active-address (some-> (get-in state [:wallet :address]) str str/lower-case)]
    (if (= requested-address active-address)
      (assoc state :account snapshot)
      state)))

(defn apply-perp-dex-clearinghouse-success
  [state dex data]
  (assoc-in state [:perp-dex-clearinghouse dex] data))

(defn apply-perp-dex-clearinghouse-error
  [state err]
  (assoc-in state [:perp-dex-clearinghouse-error] (str err)))
