(ns hyperopen.views.trade.order-form-vm-selectors
  (:require [clojure.string :as str]
            [hyperopen.state.trading :as trading]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.trade.order-form-presenter :as presenter]))

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
  (presenter/summary-display summary sz-decimals))

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

(defn price-model [state normalized-form ui-state limit-like?]
  (let [raw-price (or (:price normalized-form) "")
        price-input-focused? (boolean (:price-input-focused? ui-state))
        fallback-limit-price (when limit-like?
                               (trading/effective-limit-price-string state normalized-form))
        display-price (cond
                        (not (str/blank? raw-price))
                        raw-price

                        (and (not price-input-focused?) limit-like?)
                        (or fallback-limit-price "")

                        :else
                        "")
        price-context-summary (trading/mid-price-summary state normalized-form)
        mid-available? (= :mid (:source price-context-summary))]
    {:raw raw-price
     :display display-price
     :focused? price-input-focused?
     :fallback fallback-limit-price
     :context {:label (if mid-available? "Mid" "Ref")
               :mid-available? mid-available?}}))

(defn- format-scale-preview-line [state edge raw-price base-symbol quote-symbol]
  (let [size (when (map? edge) (:size edge))
        price (when (map? edge) (:price edge))
        formatted-size (when (number? size)
                         (trading/base-size-string state size))
        formatted-price (when (number? price)
                          (fmt/format-trade-price-plain price raw-price))]
    (if (and (seq formatted-size) (seq formatted-price))
      (str formatted-size " " base-symbol " @ " formatted-price " " quote-symbol)
      "N/A")))

(defn scale-preview-lines [state normalized-form base-symbol quote-symbol sz-decimals]
  (let [order-type (:type normalized-form)
        scale-preview (when (= :scale order-type)
                        (trading/scale-preview-boundaries normalized-form
                                                          {:sz-decimals sz-decimals}))]
    {:start (format-scale-preview-line state
                                       (:start scale-preview)
                                       (get-in normalized-form [:scale :start])
                                       base-symbol
                                       quote-symbol)
     :end (format-scale-preview-line state
                                     (:end scale-preview)
                                     (get-in normalized-form [:scale :end])
                                     base-symbol
                                     quote-symbol)}))
