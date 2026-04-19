(ns hyperopen.views.trading-chart.actions
  (:require [nexus.registry :as nxr]
            [replicant.core :as replicant-core]
            [hyperopen.system :as app-system]))

(defn- memoize-last
  [f]
  (let [cache (atom nil)]
    (fn [& args]
      (let [cached @cache]
        (if (and (map? cached)
                 (= args (:args cached)))
          (:result cached)
          (let [result (apply f args)]
            (reset! cache {:args args
                           :result result})
            result))))))

(defn- runtime-dispatch-fn
  []
  (when app-system/store
    (fn [event actions]
      (nxr/dispatch app-system/store event actions))))

(defn current-dispatch-fn
  []
  (let [dispatch-fn replicant-core/*dispatch*]
    (or (when (ifn? dispatch-fn)
          dispatch-fn)
        (runtime-dispatch-fn))))

(defn- dispatch-chart-actions!
  [dispatch-fn trigger actions]
  (when (and (ifn? dispatch-fn)
             (seq actions))
    (dispatch-fn {:replicant/trigger trigger}
                 actions)))

(defn dispatch-chart-cancel-order!
  ([order]
   (dispatch-chart-cancel-order! (current-dispatch-fn) order))
  ([dispatch-fn order]
   (when (map? order)
     (dispatch-chart-actions! dispatch-fn
                              :chart-order-overlay-cancel
                              [[:actions/cancel-order order]]))))

(defn dispatch-hide-volume-indicator!
  ([]
   (dispatch-hide-volume-indicator! (current-dispatch-fn)))
  ([dispatch-fn]
   (dispatch-chart-actions! dispatch-fn
                            :chart-volume-indicator-remove
                            [[:actions/hide-volume-indicator]])))

(defn chart-liquidation-drag-prefill-actions
  [position-data suggestion]
  (when (and (map? position-data)
             (map? suggestion))
    [[:actions/select-account-info-tab :positions]
     [:actions/open-position-margin-modal
      (merge position-data
             {:prefill-source :chart-liquidation-drag
              :prefill-margin-mode (:mode suggestion)
              :prefill-margin-amount (:amount suggestion)
              :prefill-liquidation-target-price (:target-liquidation-price suggestion)
              :prefill-liquidation-current-price (:current-liquidation-price suggestion)})
      (:anchor suggestion)]]))

(defn- dispatch-chart-liquidation-drag-margin-prefill!
  ([trigger position-data suggestion]
   (dispatch-chart-liquidation-drag-margin-prefill!
    (current-dispatch-fn)
    trigger
    position-data
    suggestion))
  ([dispatch-fn trigger position-data suggestion]
   (let [actions (chart-liquidation-drag-prefill-actions position-data suggestion)]
     (dispatch-chart-actions! dispatch-fn trigger actions))))

(defn dispatch-chart-liquidation-drag-margin-preview!
  ([position-data suggestion]
   (dispatch-chart-liquidation-drag-margin-preview!
    (current-dispatch-fn)
    position-data
    suggestion))
  ([dispatch-fn position-data suggestion]
   (dispatch-chart-liquidation-drag-margin-prefill!
    dispatch-fn
    :chart-liquidation-drag-margin-preview
    position-data
    suggestion)))

(defn dispatch-chart-liquidation-drag-margin-confirm!
  ([position-data suggestion]
   (dispatch-chart-liquidation-drag-margin-confirm!
    (current-dispatch-fn)
    position-data
    suggestion))
  ([dispatch-fn position-data suggestion]
   (dispatch-chart-liquidation-drag-margin-prefill!
    dispatch-fn
    :chart-liquidation-drag-margin-confirm
    position-data
    suggestion)))

(def ^:private memoized-cancel-order-callback
  (memoize-last
   (fn [dispatch-fn]
     (fn [order]
       (dispatch-chart-cancel-order! dispatch-fn order)))))

(def ^:private memoized-hide-volume-indicator-callback
  (memoize-last
   (fn [dispatch-fn]
     (fn []
       (dispatch-hide-volume-indicator! dispatch-fn)))))

(def ^:private memoized-liquidation-drag-preview-callback
  (memoize-last
   (fn [dispatch-fn active-position-data]
     (fn [suggestion]
       (dispatch-chart-liquidation-drag-margin-preview!
        dispatch-fn
        active-position-data
        suggestion)))))

(def ^:private memoized-liquidation-drag-confirm-callback
  (memoize-last
   (fn [dispatch-fn active-position-data]
     (fn [suggestion]
       (dispatch-chart-liquidation-drag-margin-confirm!
        dispatch-fn
        active-position-data
        suggestion)))))

(defn cancel-order-callback
  [dispatch-fn]
  (memoized-cancel-order-callback dispatch-fn))

(defn hide-volume-indicator-callback
  [dispatch-fn]
  (memoized-hide-volume-indicator-callback dispatch-fn))

(defn liquidation-drag-preview-callback
  [dispatch-fn active-position-data]
  (memoized-liquidation-drag-preview-callback dispatch-fn active-position-data))

(defn liquidation-drag-confirm-callback
  [dispatch-fn active-position-data]
  (memoized-liquidation-drag-confirm-callback dispatch-fn active-position-data))
