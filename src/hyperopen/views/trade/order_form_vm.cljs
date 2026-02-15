(ns hyperopen.views.trade.order-form-vm
  (:require [clojure.string :as str]
            [hyperopen.state.trading :as trading]
            [hyperopen.utils.formatting :as fmt]))

(def leverage-presets [2 5 10 20 25 40 50])

(def order-type-config
  {:stop-market {:label "Stop Market"
                 :sections [:trigger]
                 :limit-like? false}
   :stop-limit {:label "Stop Limit"
                :sections [:trigger]
                :limit-like? true}
   :take-market {:label "Take Market"
                 :sections [:trigger]
                 :limit-like? false}
   :take-limit {:label "Take Limit"
                :sections [:trigger]
                :limit-like? true}
   :scale {:label "Scale"
           :sections [:scale]
           :limit-like? false}
   :twap {:label "TWAP"
          :sections [:twap]
          :limit-like? false}})

(def pro-order-type-order [:scale :stop-limit :stop-market :take-limit :take-market :twap])

(defn order-type-label [order-type]
  (or (get-in order-type-config [order-type :label])
      "Stop Market"))

(defn order-type-sections [order-type]
  (or (get-in order-type-config [order-type :sections])
      []))

(defn pro-dropdown-options []
  pro-order-type-order)

(defn pro-tab-label [entry-mode order-type]
  (if (= entry-mode :pro)
    (order-type-label order-type)
    "Pro"))

(defn- next-leverage [current-leverage max-leverage]
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

(defn- submit-tooltip-message [required-fields market-price-missing?]
  (cond
    (seq required-fields)
    (str "Fill required fields: " (str/join ", " required-fields) ".")

    market-price-missing?
    "Load order book data before placing a market order."

    :else nil))

(defn- submit-tooltip-from-policy [submit-policy]
  (submit-tooltip-message (:required-fields submit-policy)
                          (:market-price-missing? submit-policy)))

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

(defn order-form-vm [state]
  (let [form (:order-form state)
        normalized-form (trading/normalize-order-form state form)
        ui-state (trading/order-form-ui-state state)
        {:keys [base-symbol quote-symbol spot? hip3? read-only?] :as market-identity}
        (trading/market-identity state)
        side (:side normalized-form)
        type (:type normalized-form)
        entry-mode (:entry-mode normalized-form)
        pro-dropdown-open? (boolean (:pro-order-type-dropdown-open? ui-state))
        market-mode? (= entry-mode :market)
        pro-mode? (= entry-mode :pro)
        limit-like? (trading/limit-like-type? type)
        show-limit-like-controls? (and (not market-mode?) limit-like?)
        summary (trading/order-summary state normalized-form)
        ui-leverage (:ui-leverage normalized-form)
        max-leverage (trading/market-max-leverage state)
        size-percent (trading/clamp-percent (:size-percent normalized-form))
        raw-price (or (:price normalized-form) "")
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
        scale-preview (when (= :scale type)
                        (trading/scale-preview-boundaries normalized-form
                                                          {:sz-decimals (or (get-in state [:active-market :szDecimals])
                                                                            4)}))
        start-preview-line (format-scale-preview-line state
                                                      (:start scale-preview)
                                                      (get-in normalized-form [:scale :start])
                                                      base-symbol
                                                      quote-symbol)
        end-preview-line (format-scale-preview-line state
                                                    (:end scale-preview)
                                                    (get-in normalized-form [:scale :end])
                                                    base-symbol
                                                    quote-symbol)
        price-context-summary (trading/mid-price-summary state normalized-form)
        mid-available? (= :mid (:source price-context-summary))
        price-context {:label (if mid-available? "Mid" "Ref")
                       :mid-available? mid-available?}
        submitting? (:submitting? normalized-form)
        submit-policy (trading/submit-policy state normalized-form {:mode :view
                                                                    :submitting? submitting?})
        submit-form (:form submit-policy)
        submit-errors (:errors submit-policy)
        required-submit-fields (:required-fields submit-policy)
        submit-tooltip (submit-tooltip-from-policy submit-policy)
        submit-disabled? (:disabled? submit-policy)]
    {:form normalized-form
     :ui-state ui-state
     :identity market-identity
     :side side
     :type type
     :entry-mode entry-mode
     :pro-dropdown-open? pro-dropdown-open?
     :tpsl-panel-open? (boolean (:tpsl-panel-open? ui-state))
     :pro-dropdown-options (pro-dropdown-options)
     :pro-tab-label (pro-tab-label entry-mode type)
     :order-type-sections (order-type-sections type)
     :market-mode? market-mode?
     :pro-mode? pro-mode?
     :limit-like? limit-like?
     :show-limit-like-controls? show-limit-like-controls?
     :spot? spot?
     :hip3? hip3?
     :read-only? read-only?
     :summary summary
     :ui-leverage ui-leverage
     :next-leverage (next-leverage ui-leverage max-leverage)
     :size-percent size-percent
     :display-size-percent (str (int (js/Math.round size-percent)))
     :notch-overlap-threshold 4
     :size-display (:size-display normalized-form)
     :price {:raw raw-price
             :display display-price
             :focused? price-input-focused?
             :fallback fallback-limit-price
             :context price-context}
     :base-symbol base-symbol
     :quote-symbol quote-symbol
     :scale-preview-lines {:start start-preview-line
                           :end end-preview-line}
     :order-value (:order-value summary)
     :margin-required (:margin-required summary)
     :liq-price (:liquidation-price summary)
     :slippage-est (:slippage-est summary)
     :slippage-max (:slippage-max summary)
     :fees (:fees summary)
     :error (:error normalized-form)
     :submitting? submitting?
     :submit {:form submit-form
              :errors submit-errors
              :required-fields required-submit-fields
              :reason (:reason submit-policy)
              :error-message (:error-message submit-policy)
              :tooltip submit-tooltip
              :market-price-missing? (:market-price-missing? submit-policy)
              :disabled? submit-disabled?}}))
