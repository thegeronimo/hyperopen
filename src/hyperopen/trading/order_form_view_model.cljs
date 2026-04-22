(ns hyperopen.trading.order-form-view-model
  (:require [clojure.string :as str]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-application :as application]
            [hyperopen.trading.order-form-display :as display]
            [hyperopen.trading.order-type-registry :as order-types]))

(def order-type-config
  order-types/order-type-config)

(def leverage-presets [2 5 10 20 25 40 50])
(def notch-overlap-threshold 4)

(defn order-type-label [order-type]
  (order-types/order-type-label order-type))

(defn order-type-sections [order-type]
  (order-types/order-type-sections order-type))

(defn pro-dropdown-options []
  (order-types/pro-order-types))

(defn pro-tab-label [entry-mode order-type]
  (if (= entry-mode :pro)
    (order-type-label order-type)
    "Pro"))

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
  (display/summary-display summary sz-decimals))

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

(defn submit-tooltip-message [required-fields market-price-missing? reason error-message]
  (cond
    (= reason :spectate-mode-read-only)
    error-message

    (seq required-fields)
    (str "Fill required fields: " (str/join ", " required-fields) ".")

    market-price-missing?
    "Load order book data before placing a market order."

    (seq error-message)
    error-message

    :else nil))

(defn submit-tooltip-from-policy [submit-policy]
  (submit-tooltip-message (:required-fields submit-policy)
                          (:market-price-missing? submit-policy)
                          (:reason submit-policy)
                          (:error-message submit-policy)))

(defn order-form-vm [state]
  (let [{:keys [draft
                ui
                runtime-state
                market-info
                order-type-capabilities
                pricing-policy
                scale-preview-lines
                summary
                submitting?
                submit-policy]}
        (application/order-form-context state)
        normalized-form draft
        {:keys [quote-symbol
                base-symbol
                spot?
                hip3?
                read-only?
                sz-decimals
                max-leverage]}
        market-info
        side (:side normalized-form)
        type (:type normalized-form)
        entry-mode (:entry-mode normalized-form)
        pro-dropdown-open? (boolean (get-in ui [:entry :pro-dropdown-open?]))
        pro-mode? (= entry-mode :pro)
        tpsl-panel-open? (boolean (get-in ui [:panels :tpsl-open?]))
        controls (order-type-controls {:entry-mode entry-mode
                                       :pro-mode? pro-mode?
                                       :tpsl-panel-open? tpsl-panel-open?
                                       :order-type-capabilities order-type-capabilities})
        summary-display (summary-display summary sz-decimals)
        ui-leverage (:ui-leverage normalized-form)
        size-percent (trading/clamp-percent (:size-percent normalized-form))
        price (price-model pricing-policy)
        submit-form (:form submit-policy)
        submit-errors (:errors submit-policy)
        required-submit-fields (:required-fields submit-policy)
        submit-tooltip (submit-tooltip-from-policy submit-policy)
        submit-disabled? (:disabled? submit-policy)]
    {:form normalized-form
     :side side
     :type type
     :entry-mode entry-mode
     :pro-dropdown-open? pro-dropdown-open?
     :tpsl-panel-open? tpsl-panel-open?
     :pro-dropdown-options (pro-dropdown-options)
     :pro-tab-label (pro-tab-label entry-mode type)
     :controls controls
     :spot? spot?
     :hip3? hip3?
     :read-only? read-only?
     :display summary-display
     :ui-leverage ui-leverage
     :next-leverage (next-leverage ui-leverage max-leverage)
     :size-percent size-percent
     :display-size-percent (display-size-percent size-percent)
     :notch-overlap-threshold notch-overlap-threshold
     :size-input-mode (:size-input-mode normalized-form)
     :size-display (:size-display normalized-form)
     :price price
     :quote-symbol quote-symbol
     :base-symbol base-symbol
     :scale-preview-lines scale-preview-lines
     :error (:error runtime-state)
     :submitting? submitting?
     :submit {:form submit-form
              :errors submit-errors
              :required-fields required-submit-fields
              :reason (:reason submit-policy)
              :error-message (:error-message submit-policy)
              :tooltip submit-tooltip
              :market-price-missing? (:market-price-missing? submit-policy)
              :disabled? submit-disabled?}}))
