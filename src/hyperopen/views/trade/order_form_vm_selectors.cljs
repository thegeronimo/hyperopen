(ns hyperopen.views.trade.order-form-vm-selectors
  (:require [hyperopen.views.trade.order-form-summary-display :as summary-display]))

(def leverage-presets [2 5 10 20 25 40 50])
(def notch-overlap-threshold 4)

(defn next-leverage [current-leverage max-leverage]
  (let [cap (or max-leverage (last leverage-presets))
        options (->> leverage-presets
                     (filter #(<= % cap))
                     vec)
        options* (if (seq options) options leverage-presets)
        idx (.indexOf (clj->js options*) current-leverage)
        next-idx (if (= idx -1)
                   0
                   (mod (inc idx) (count options*)))]
    (nth options* next-idx)))

(defn summary-display [summary sz-decimals]
  (summary-display/summary-display summary sz-decimals))

(defn display-size-percent [size-percent]
  (str (int (js/Math.round size-percent))))

(defn order-type-controls
  [{:keys [entry-mode
           pro-mode?
           tpsl-panel-open?
           order-type-capabilities]}]
  (let [limit-like? (boolean (:limit-like? order-type-capabilities))
        supports-tpsl? (boolean (:supports-tpsl? order-type-capabilities))
        supports-post-only? (boolean (:supports-post-only? order-type-capabilities))]
    {:limit-like? limit-like?
     :show-limit-like-controls? (and (not= entry-mode :market) limit-like?)
     :show-tpsl-toggle? supports-tpsl?
     :show-tpsl-panel? (and supports-tpsl? tpsl-panel-open?)
     :show-post-only? (and pro-mode? supports-post-only?)
     :show-scale-preview? (boolean (:show-scale-preview? order-type-capabilities))
     :show-liquidation-row? (boolean (:show-liquidation-row? order-type-capabilities))
     :show-slippage-row? (boolean (:show-slippage-row? order-type-capabilities))}))

(defn price-model [pricing-policy]
  {:raw (:raw-price pricing-policy)
   :display (:display-price pricing-policy)
   :focused? (:focused? pricing-policy)
   :fallback (:fallback-price pricing-policy)
   :context {:label (:context-label pricing-policy)
             :mid-available? (boolean (:mid-available? pricing-policy))}})
