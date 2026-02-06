(ns hyperopen.views.trade.order-form-view
  (:require [clojure.string :as str]
            [hyperopen.state.trading :as trading]
            [hyperopen.utils.formatting :as fmt]))

(def leverage-presets [2 5 10 20 25 40 50])

(defn- section-label [text]
  [:div {:class ["text-xs" "text-gray-400" "mb-1"]} text])

(defn- row-toggle [label-text checked? on-change]
  [:label {:class ["inline-flex" "items-center" "gap-2" "cursor-pointer" "text-sm" "text-gray-100"]}
   [:input {:class ["h-4"
                    "w-4"
                    "appearance-none"
                    "rounded-[3px]"
                    "border"
                    "border-base-300"
                    "bg-transparent"
                    "transition-colors"
                    "checked:border-primary"
                    "checked:bg-primary"
                    "focus:outline-none"
                    "focus:ring-0"]
            :type "checkbox"
            :checked (boolean checked?)
            :on {:change on-change}}]
   [:span label-text]])

(defn- input [value on-change & {:keys [type placeholder]}]
  [:input {:class ["w-full"
                   "h-10"
                   "px-3"
                   "bg-base-200"
                   "border"
                   "border-base-300"
                   "rounded-lg"
                   "text-sm"
                   "text-gray-100"
                   "placeholder:text-gray-500"]
           :type (or type "text")
           :placeholder (or placeholder "")
           :value (or value "")
           :on {:input on-change}}])

(defn- row-input [value placeholder on-change accessory]
  [:div {:class ["relative" "w-full"]}
   [:input {:class (into ["w-full"
                          "h-11"
                          "px-3"
                          "bg-base-200"
                          "border"
                          "border-base-300"
                          "rounded-lg"
                          "text-sm"
                          "text-gray-100"
                          "placeholder:text-gray-500"
                          "outline-none"
                          "appearance-none"]
                         (when accessory ["pr-16"]))
            :type "text"
            :placeholder placeholder
            :value (or value "")
            :on {:input on-change}}]
   (when accessory
     [:div {:class ["pointer-events-none"
                    "absolute"
                    "right-3"
                    "top-1/2"
                    "-translate-y-1/2"
                    "shrink-0"]}
      accessory])])

(defn- chip-button [label active? & {:keys [on-click disabled?]}]
  [:button {:type "button"
            :disabled (boolean disabled?)
            :class (into ["flex-1"
                          "h-10"
                          "rounded-lg"
                          "text-sm"
                          "font-semibold"
                          "transition-colors"]
                         (if active?
                           ["bg-base-200" "text-gray-100" "border" "border-base-300"]
                           ["bg-base-200/60" "text-gray-300" "border" "border-base-300/80"]))
            :on (when (and on-click (not disabled?))
                  {:click on-click})}
   label])

(defn- mode-button [label active? on-click]
  [:button {:type "button"
            :class (into ["flex-1"
                          "h-10"
                          "text-sm"
                          "font-medium"
                          "border-b-2"
                          "transition-colors"]
                         (if active?
                           ["text-gray-100" "border-primary"]
                           ["text-gray-400" "border-transparent" "hover:text-gray-200"]))
            :on {:click on-click}}
   label])

(defn- side-button [label active? on-click]
  [:button {:type "button"
            :class (into ["flex-1"
                          "h-10"
                          "text-sm"
                          "font-semibold"
                          "rounded-md"
                          "transition-colors"]
                         (if active?
                           ["bg-primary" "text-primary-content"]
                           ["bg-base-200/70" "text-gray-100"]))
            :on {:click on-click}}
   label])

(defn- order-type-label [order-type]
  (case order-type
    :stop-market "Stop Market"
    :stop-limit "Stop Limit"
    :take-market "Take Market"
    :take-limit "Take Limit"
    :scale "Scale"
    :twap "TWAP"
    "Stop Market"))

(defn- pro-order-types []
  [:stop-market :stop-limit :take-market :take-limit :scale :twap])

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

(defn- format-usdc [value]
  (if (and (number? value) (not (js/isNaN value)))
    (str (.toLocaleString (js/Number. value) "en-US"
                          #js {:minimumFractionDigits 2
                               :maximumFractionDigits 2})
         " USDC")
    "N/A"))

(defn- format-position-label [position sz-decimals]
  (let [size (:abs-size position)
        coin (:coin position)]
    (if (and (number? size) (pos? size) (seq coin))
      (str (.toLocaleString (js/Number. size) "en-US"
                            #js {:minimumFractionDigits (or sz-decimals 4)
                                 :maximumFractionDigits (or sz-decimals 4)})
           " "
           coin)
      (str "0.0000 " (or coin "--")))))

(defn- format-percent [value]
  (if (and (number? value) (not (js/isNaN value)))
    (str (fmt/safe-to-fixed value 2) "%")
    "N/A"))

(defn- metric-row [title value]
  [:div {:class ["flex" "items-center" "justify-between"]}
   [:span {:class ["text-sm" "text-gray-400"]} title]
   [:span {:class ["text-sm" "font-semibold" "text-gray-100" "tabular-nums"]} value]])

(defn- pro-order-type-select [form]
  [:div
   (section-label "Pro Order Type")
   [:select {:class ["w-full"
                     "h-10"
                     "px-3"
                     "bg-base-200"
                     "border"
                     "border-base-300"
                     "rounded-lg"
                     "text-sm"
                     "text-gray-100"]
             :value (name (trading/normalize-pro-order-type (:type form)))
             :on {:change [[:actions/select-pro-order-type [:event.target/value]]]}}
    (for [order-type (pro-order-types)]
      ^{:key (name order-type)}
      [:option {:value (name order-type)}
       (order-type-label order-type)])]])

(defn- tp-sl-panel [form]
  [:div {:class ["space-y-2"]}
   (row-toggle "Enable TP"
               (get-in form [:tp :enabled?])
               [[:actions/update-order-form [:tp :enabled?] [:event.target/checked]]])
   (when (get-in form [:tp :enabled?])
     [:div {:class ["space-y-2"]}
      (input (get-in form [:tp :trigger])
             [[:actions/update-order-form [:tp :trigger] [:event.target/value]]]
             :placeholder "TP trigger")
      (row-toggle "TP Market"
                  (get-in form [:tp :is-market])
                  [[:actions/update-order-form [:tp :is-market] [:event.target/checked]]])
      (when (not (get-in form [:tp :is-market]))
        (input (get-in form [:tp :limit])
               [[:actions/update-order-form [:tp :limit] [:event.target/value]]]
               :placeholder "TP limit price"))])
   (row-toggle "Enable SL"
               (get-in form [:sl :enabled?])
               [[:actions/update-order-form [:sl :enabled?] [:event.target/checked]]])
   (when (get-in form [:sl :enabled?])
     [:div {:class ["space-y-2"]}
      (input (get-in form [:sl :trigger])
             [[:actions/update-order-form [:sl :trigger] [:event.target/value]]]
             :placeholder "SL trigger")
      (row-toggle "SL Market"
                  (get-in form [:sl :is-market])
                  [[:actions/update-order-form [:sl :is-market] [:event.target/checked]]])
      (when (not (get-in form [:sl :is-market]))
        (input (get-in form [:sl :limit])
               [[:actions/update-order-form [:sl :limit] [:event.target/value]]]
               :placeholder "SL limit price"))])])

(defn- quote-accessory [quote-symbol]
  [:div {:class ["flex" "items-center" "gap-1.5" "text-sm" "font-semibold" "text-gray-100"]}
   [:span quote-symbol]
   [:svg {:class ["w-3.5" "h-3.5" "text-gray-400"]
          :viewBox "0 0 20 20"
          :fill "currentColor"}
    [:path {:fill-rule "evenodd"
            :clip-rule "evenodd"
            :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]])

(defn- tif-inline-control [form]
  [:div {:class ["relative" "flex" "items-center" "gap-2"]}
   [:span {:class ["text-xs" "uppercase" "tracking-wide" "text-gray-400"]} "TIF"]
   [:select {:class ["appearance-none"
                     "bg-transparent"
                     "text-sm"
                     "font-semibold"
                     "text-gray-100"
                     "pr-4"]
             :value (name (:tif form))
             :on {:change [[:actions/update-order-form [:tif] [:event.target/value]]]}}
    [:option {:value "gtc"} "GTC"]
    [:option {:value "ioc"} "IOC"]
    [:option {:value "alo"} "ALO"]]
   [:svg {:class ["pointer-events-none"
                  "absolute"
                  "right-0"
                  "top-1/2"
                  "-translate-y-1/2"
                  "w-3.5"
                  "h-3.5"
                  "text-gray-400"]
          :viewBox "0 0 20 20"
          :fill "currentColor"}
    [:path {:fill-rule "evenodd"
            :clip-rule "evenodd"
            :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]])

(defn order-form-view [state]
  (let [form (:order-form state)
        normalized-form (trading/normalize-order-form state form)
        active-market (:active-market state)
        active-asset (:active-asset state)
        inferred-spot? (and (string? active-asset) (str/includes? active-asset "/"))
        inferred-hip3? (and (string? active-asset) (str/includes? active-asset ":") (not inferred-spot?))
        spot? (or (= :spot (:market-type active-market)) inferred-spot?)
        hip3? (or (some? (:dex active-market)) inferred-hip3?)
        read-only? (or spot? hip3?)
        side (:side normalized-form)
        type (:type normalized-form)
        entry-mode (trading/entry-mode-for-type type)
        limit-like? (trading/limit-like-type? type)
        summary (trading/order-summary state normalized-form)
        available-to-trade (:available-to-trade summary)
        position (:current-position summary)
        ui-leverage (:ui-leverage normalized-form)
        max-leverage (trading/market-max-leverage state)
        next-lev (next-leverage ui-leverage max-leverage)
        size-percent (trading/clamp-percent (:size-percent normalized-form))
        raw-price (or (:price normalized-form) "")
        fallback-limit-price (when limit-like?
                               (trading/effective-limit-price-string state normalized-form))
        display-price (if (str/blank? raw-price)
                        (or fallback-limit-price "")
                        raw-price)
        quote-symbol (or (:quote active-market) "USDC")
        sz-decimals (or (:szDecimals active-market) 4)
        order-value (:order-value summary)
        margin-required (:margin-required summary)
        liq-price (:liquidation-price summary)
        slippage-est (:slippage-est summary)
        slippage-max (:slippage-max summary)
        fees (:fees summary)
        error (:error normalized-form)
        submitting? (:submitting? normalized-form)]
    [:div {:class ["bg-base-100"
                   "border"
                   "border-base-300"
                   "rounded-none"
                   "shadow-none"
                   "p-3"
                   "font-sans"
                   "min-h-[560px]"
                   "xl:min-h-[640px]"
                   "flex"
                   "flex-col"
                   "gap-3"]}
     (when spot?
       [:div {:class ["px-3" "py-2" "bg-base-200" "border" "border-base-300" "rounded-lg" "text-xs" "text-gray-300"]}
        "Spot trading is not supported yet. You can still view spot charts and order books."])
     (when hip3?
       [:div {:class ["px-3" "py-2" "bg-base-200" "border" "border-base-300" "rounded-lg" "text-xs" "text-gray-300"]}
        "HIP-3 trading is not supported yet. You can still view these markets."])

     [:div {:class (into ["flex" "flex-col" "flex-1" "gap-3"]
                         (when read-only? ["opacity-60" "pointer-events-none"]))}
      [:div {:class ["grid" "grid-cols-3" "gap-2"]}
       (chip-button "Cross" true :disabled? true)
       (chip-button (str ui-leverage "x")
                    true
                    :on-click [[:actions/set-order-ui-leverage next-lev]])
       (chip-button "Classic" true :disabled? true)]

      [:div {:class ["flex" "items-center" "border-b" "border-base-300"]}
       (mode-button "Market"
                    (= entry-mode :market)
                    [[:actions/select-order-entry-mode :market]])
       (mode-button "Limit"
                    (= entry-mode :limit)
                    [[:actions/select-order-entry-mode :limit]])
       (mode-button "Pro"
                    (= entry-mode :pro)
                    [[:actions/select-order-entry-mode :pro]])]

      (when (= entry-mode :pro)
        (pro-order-type-select normalized-form))

      [:div {:class ["flex" "items-center" "gap-2" "bg-base-200" "rounded-md" "p-1"]}
       (side-button "Buy / Long"
                    (= side :buy)
                    [[:actions/update-order-form [:side] :buy]])
       (side-button "Sell / Short"
                    (= side :sell)
                    [[:actions/update-order-form [:side] :sell]])]

      [:div {:class ["space-y-1.5"]}
       [:div {:class ["flex" "items-center" "justify-between"]}
        [:span {:class ["text-sm" "text-gray-400"]} "Available to Trade"]
        [:span {:class ["text-sm" "font-semibold" "text-gray-100" "tabular-nums"]}
         (format-usdc available-to-trade)]]
       [:div {:class ["flex" "items-center" "justify-between"]}
        [:span {:class ["text-sm" "text-gray-400"]} "Current position"]
        [:span {:class ["text-sm" "font-semibold" "text-gray-100" "tabular-nums"]}
         (format-position-label position sz-decimals)]]]

      (when limit-like?
        (row-input display-price
                   (str "Price (" quote-symbol ")")
                   [[:actions/update-order-form [:price] [:event.target/value]]]
                   nil))

      (row-input (:size normalized-form)
                 "Size"
                 [[:actions/update-order-form [:size] [:event.target/value]]]
                 (quote-accessory quote-symbol))

      [:div {:class ["flex" "items-center" "gap-2"]}
       [:input {:class ["range" "range-primary" "range-sm" "w-full"]
                :type "range"
                :min 0
                :max 100
                :step 1
                :value size-percent
                :on {:input [[:actions/set-order-size-percent [:event.target/value]]]}}]
       [:div {:class ["w-[82px]"
                      "h-10"
                      "px-2"
                      "bg-base-200"
                      "border"
                      "border-base-300"
                      "rounded-lg"
                      "flex"
                      "items-center"
                      "justify-center"
                      "tabular-nums"]}
        [:span {:class ["text-sm" "font-semibold" "text-gray-100"]}
         (str (int (js/Math.round size-percent)) " %")]]]

      (when (#{:stop-market :stop-limit :take-market :take-limit} type)
        [:div
         (section-label "Trigger")
         (input (:trigger-px normalized-form)
                [[:actions/update-order-form [:trigger-px] [:event.target/value]]]
                :placeholder "Trigger price")])

      (when (= :scale type)
        [:div {:class ["space-y-2"]}
         (section-label "Scale")
         (input (get-in normalized-form [:scale :start])
                [[:actions/update-order-form [:scale :start] [:event.target/value]]]
                :placeholder "Start price")
         (input (get-in normalized-form [:scale :end])
                [[:actions/update-order-form [:scale :end] [:event.target/value]]]
                :placeholder "End price")
         (input (get-in normalized-form [:scale :count])
                [[:actions/update-order-form [:scale :count] [:event.target/value]]]
                :placeholder "Order count")])

      (when (= :twap type)
        [:div {:class ["space-y-2"]}
         (section-label "TWAP")
         (input (get-in normalized-form [:twap :minutes])
                [[:actions/update-order-form [:twap :minutes] [:event.target/value]]]
                :placeholder "Minutes")
         (row-toggle "Randomize"
                     (get-in normalized-form [:twap :randomize])
                     [[:actions/update-order-form [:twap :randomize] [:event.target/checked]]])])

      [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
       (row-toggle "Reduce Only"
                   (:reduce-only normalized-form)
                   [[:actions/update-order-form [:reduce-only] [:event.target/checked]]])
       (when limit-like?
         (tif-inline-control normalized-form))]

      (row-toggle "Take Profit / Stop Loss"
                  (:tpsl-panel-open? normalized-form)
                  [[:actions/toggle-order-tpsl-panel]])

      (when (:tpsl-panel-open? normalized-form)
        (tp-sl-panel normalized-form))

      (when (and (= entry-mode :pro) limit-like?)
        (row-toggle "Post Only"
                    (:post-only normalized-form)
                    [[:actions/update-order-form [:post-only] [:event.target/checked]]]))

      [:div {:class ["flex-1"]}]

      [:div {:class ["border-t" "border-base-300" "pt-3" "space-y-2"]}
       (metric-row "Liquidation Price"
                   (if liq-price
                     (or (fmt/format-trade-price liq-price) "N/A")
                     "N/A"))
       (metric-row "Order Value"
                   (if order-value
                     (or (fmt/format-currency order-value) "N/A")
                     "N/A"))
       (metric-row "Margin Required"
                   (if margin-required
                     (or (fmt/format-currency margin-required) "N/A")
                     "N/A"))
       (when (= :market type)
         (metric-row "Slippage"
                     (str "Est " (format-percent slippage-est)
                          " / Max " (format-percent slippage-max))))
       (metric-row "Fees"
                   (if (and (number? (:taker fees)) (number? (:maker fees)))
                     (str (fmt/safe-to-fixed (:taker fees) 3)
                          "% / "
                          (fmt/safe-to-fixed (:maker fees) 3)
                          "%")
                     "N/A"))]

      (when error
        [:div {:class ["text-xs" "text-red-400"]} error])

      [:button {:class ["btn" "btn-primary" "w-full" "h-11" "text-sm" "font-semibold"]
                :disabled (or submitting? read-only?)
                :on {:click [[:actions/submit-order]]}}
       (if submitting? "Submitting..." "Submit Order")]]]))
