(ns hyperopen.trading.order-form-application
  (:require [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-type-registry :as order-types]
            [hyperopen.utils.formatting :as fmt]))

(def ^:private order-type-capability-keys
  [:limit-like?
   :supports-tpsl?
   :supports-post-only?
   :show-scale-preview?
   :show-liquidation-row?
   :show-slippage-row?])

(defn- state->order-form-inputs
  [state]
  {:draft (trading/order-form-draft state)
   :ui-state (trading/order-form-ui-state state)
   :runtime-state (trading/order-form-runtime-state state)
   :market-info (trading/market-info state)})

(defn- grouped-ui-state
  [ui-state]
  {:entry {:mode (:entry-mode ui-state)
           :pro-dropdown-open? (boolean (:pro-order-type-dropdown-open? ui-state))}
   :interaction {:price-input-focused? (boolean (:price-input-focused? ui-state))}
   :panels {:tpsl-open? (boolean (:tpsl-panel-open? ui-state))}
   :sizing {:ui-leverage (:ui-leverage ui-state)
            :size-display (:size-display ui-state)}})

(defn- format-scale-preview-line
  [state edge raw-price base-symbol quote-symbol]
  (let [size (when (map? edge) (:size edge))
        price (when (map? edge) (:price edge))
        formatted-size (when (number? size)
                         (trading/base-size-string state size))
        formatted-price (when (number? price)
                          (fmt/format-trade-price-plain price raw-price))]
    (if (and (seq formatted-size) (seq formatted-price))
      (str formatted-size " " base-symbol " @ " formatted-price " " quote-symbol)
      "N/A")))

(defn- projected-scale-preview-lines
  [state draft market-info]
  (let [{:keys [type scale]} draft
        {:keys [base-symbol quote-symbol sz-decimals]} market-info
        preview (when (= :scale type)
                  (trading/scale-preview-boundaries draft {:sz-decimals sz-decimals}))]
    {:start (format-scale-preview-line state
                                       (:start preview)
                                       (:start scale)
                                       base-symbol
                                       quote-symbol)
     :end (format-scale-preview-line state
                                     (:end preview)
                                     (:end scale)
                                     base-symbol
                                     quote-symbol)}))

(def ^:private default-scale-preview-lines
  {:start "N/A"
   :end "N/A"})

(defn build-order-form-context
  [state {:keys [draft ui-state runtime-state market-info]}]
  (let [order-type (:type draft)
        capabilities (select-keys (order-types/order-type-entry order-type)
                                  order-type-capability-keys)
        pricing-policy (trading/order-price-policy state draft ui-state)
        scale-preview-lines (if (:show-scale-preview? capabilities)
                              (projected-scale-preview-lines state draft market-info)
                              default-scale-preview-lines)
        summary (trading/order-summary state draft)
        submitting? (:submitting? runtime-state)
        submit-policy (trading/submit-policy state draft {:mode :view
                                                          :submitting? submitting?})]
    {:draft draft
     :ui-state ui-state
     :ui (grouped-ui-state ui-state)
     :runtime-state runtime-state
     :market-info market-info
     :order-type-capabilities capabilities
     :pricing-policy pricing-policy
     :scale-preview-lines scale-preview-lines
     :summary summary
     :submitting? submitting?
     :submit-policy submit-policy}))

(defn order-form-context [state]
  (build-order-form-context state (state->order-form-inputs state)))
