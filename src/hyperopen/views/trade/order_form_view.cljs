(ns hyperopen.views.trade.order-form-view
  (:require [clojure.string :as str]
            [hyperopen.state.trading :as trading]
            [hyperopen.utils.formatting :as fmt]))

(def leverage-presets [2 5 10 20 25 40 50])

(def neutral-input-focus-classes
  ["outline-none"
   "transition-[border-color,box-shadow]"
   "duration-150"
   "hover:border-[#6f7a88]"
   "hover:ring-1"
   "hover:ring-[#6f7a88]/30"
   "hover:ring-offset-0"
   "focus:outline-none"
   "focus:ring-1"
   "focus:ring-[#8a96a6]/40"
   "focus:ring-offset-0"
   "focus:shadow-none"
   "focus:border-[#8a96a6]"])

(defn- section-label [text]
  [:div {:class ["text-xs" "text-gray-400" "mb-1"]} text])

(defn- row-toggle [label-text checked? on-change]
  (let [checkbox-id (str (gensym "trade-toggle-"))]
    [:div {:class ["inline-flex" "items-center" "gap-2" "text-sm" "text-gray-100"]}
     [:input {:id checkbox-id
              :class ["h-4"
                      "w-4"
                      "rounded-[3px]"
                      "border"
                      "border-base-300"
                      "bg-transparent"
                      "trade-toggle-checkbox"
                      "transition-colors"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"
                      "focus:shadow-none"]
              :type "checkbox"
              :checked (boolean checked?)
              :on {:change on-change}}]
     [:label {:for checkbox-id
              :class ["cursor-pointer" "select-none"]}
      label-text]]))

(defn- input [value on-change & {:keys [type placeholder]}]
  [:input {:class (into ["w-full"
                         "h-10"
                         "px-3"
                         "bg-base-200"
                         "border"
                         "border-base-300"
                         "rounded-lg"
                         "text-sm"
                         "text-right"
                         "text-gray-100"
                         "num"
                         "placeholder:text-gray-500"]
                        neutral-input-focus-classes)
           :type (or type "text")
           :placeholder (or placeholder "")
           :value (or value "")
           :on {:input on-change}}])

(defn- row-input [value placeholder on-change accessory & {:keys [input-padding-right]
                                                           :or {input-padding-right "pr-20"}}]
  [:div {:class ["relative" "w-full"]}
   [:span {:class ["order-row-input-label"
                   "pointer-events-none"
                   "absolute"
                   "left-3"
                   "top-1/2"
                   "-translate-y-1/2"
                   "max-w-[52%]"
                   "truncate"
                   "text-sm"
                   "text-gray-500"]}
    placeholder]
   [:input {:class (into ["w-full"
                          "h-11"
                          "pl-24"
                          "bg-base-200"
                          "border"
                          "border-base-300"
                          "rounded-lg"
                          "text-sm"
                          "text-right"
                          "text-gray-100"
                          "num"
                          "placeholder:text-transparent"
                          "appearance-none"]
                         (concat neutral-input-focus-classes
                                 (if accessory [input-padding-right] ["pr-3"])))
            :type "text"
            :aria-label placeholder
            :placeholder placeholder
            :value (or value "")
            :on {:input on-change}}]
   (when accessory
     [:div {:class ["absolute"
                    "right-3"
                    "top-1/2"
                    "-translate-y-1/2"
                    "shrink-0"]}
      accessory])])

(defn- inline-labeled-scale-input [label value on-change]
  [:div {:class ["relative" "w-full"]}
   [:span {:class ["pointer-events-none"
                   "absolute"
                   "left-3"
                   "top-1/2"
                   "-translate-y-1/2"
                   "text-sm"
                   "text-gray-400"
                   "truncate"
                   "max-w-[55%]"]}
    label]
   [:input {:class (into ["w-full"
                          "h-10"
                          "bg-base-200"
                          "border"
                          "border-base-300"
                          "rounded-lg"
                          "text-right"
                          "text-sm"
                          "font-semibold"
                          "text-gray-100"
                          "num"
                          "appearance-none"
                          "pl-24"
                          "pr-3"]
                         neutral-input-focus-classes)
            :type "text"
            :aria-label label
            :value (or value "")
            :on {:input on-change}}]])

(defn- non-blank-string [value]
  (let [s (when (some? value) (str value))
        trimmed (some-> s str/trim)]
    (when (seq trimmed) trimmed)))

(defn- base-symbol-from-value [value]
  (let [text (non-blank-string value)]
    (cond
      (and text (str/includes? text "/"))
      (non-blank-string (first (str/split text #"/" 2)))

      (and text (str/includes? text ":"))
      (non-blank-string (second (str/split text #":" 2)))

      (and text (str/includes? text "-"))
      (non-blank-string (first (str/split text #"-" 2)))

      :else
      text)))

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

(defn- pro-dropdown-options []
  [:scale :stop-limit :stop-market :take-limit :take-market :twap])

(defn- pro-tab-label [entry-mode order-type]
  (if (= entry-mode :pro)
    (order-type-label order-type)
    "Pro"))

(defn- pro-dropdown-open? [form]
  (boolean (:pro-order-type-dropdown-open? form)))

(defn- entry-mode-tabs [entry-mode order-type pro-dropdown-open?]
  [:div {:class ["relative"]}
   (when pro-dropdown-open?
     [:div {:class ["fixed" "inset-0" "z-[180]"]
            :on {:click [[:actions/close-pro-order-type-dropdown]]}}])
   [:div {:class ["relative" "z-[190]" "flex" "items-center" "border-b" "border-base-300"]}
    (mode-button "Market"
                 (= entry-mode :market)
                 [[:actions/select-order-entry-mode :market]])
    (mode-button "Limit"
                 (= entry-mode :limit)
                 [[:actions/select-order-entry-mode :limit]])
    [:div {:class ["relative" "flex-1"]}
     [:button {:type "button"
               :class (into ["w-full"
                             "h-10"
                             "text-sm"
                             "font-medium"
                             "border-b-2"
                             "transition-colors"
                             "inline-flex"
                             "items-center"
                             "justify-center"
                             "gap-1.5"]
                            (if (= entry-mode :pro)
                              ["text-gray-100" "border-primary"]
                              ["text-gray-400" "border-transparent" "hover:text-gray-200"]))
               :on {:click [[:actions/toggle-pro-order-type-dropdown]]
                    :keydown [[:actions/handle-pro-order-type-dropdown-keydown [:event/key]]]}}
      [:span (pro-tab-label entry-mode order-type)]
      [:svg {:class (into ["h-3.5" "w-3.5" "transition-transform"]
                          (if pro-dropdown-open?
                            ["rotate-180"]
                            ["rotate-0"]))
             :viewBox "0 0 20 20"
             :fill "currentColor"}
       [:path {:fill-rule "evenodd"
               :clip-rule "evenodd"
               :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]
      ]
     (when pro-dropdown-open?
       [:div {:class ["absolute"
                      "right-0"
                      "top-full"
                      "mt-1"
                      "w-36"
                      "overflow-hidden"
                      "rounded-lg"
                      "border"
                      "border-base-300"
                      "bg-base-100"
                      "shadow-lg"
                      "z-[210]"]}
        (for [pro-order-type (pro-dropdown-options)]
          ^{:key (name pro-order-type)}
          [:button {:type "button"
                    :class (into ["block"
                                  "w-full"
                                  "px-3"
                                  "py-2"
                                  "text-left"
                                  "text-sm"
                                  "transition-colors"]
                                 (if (= order-type pro-order-type)
                                   ["bg-base-200" "text-gray-100"]
                                   ["text-gray-300" "hover:bg-base-200" "hover:text-gray-100"]))
                    :on {:click [[:actions/select-pro-order-type pro-order-type]]}}
           (order-type-label pro-order-type)])])]]])

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

(defn- format-percent
  ([value]
   (format-percent value 2))
  ([value decimals]
   (if (and (number? value) (not (js/isNaN value)))
     (str (fmt/safe-to-fixed value decimals) "%")
     "N/A")))

(defn- metric-row
  ([title value]
   (metric-row title value nil))
  ([title value value-class]
  [:div {:class ["flex" "items-center" "justify-between"]}
   [:span {:class ["text-sm" "text-gray-400"]} title]
   [:span {:class (into ["text-sm" "font-semibold" "num"]
                        (if (seq value-class)
                          [value-class]
                          ["text-gray-100"]))}
    value]]))

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

(defn- resolve-quote-symbol [active-asset active-market]
  (or (non-blank-string (:quote active-market))
      (let [symbol (non-blank-string (:symbol active-market))]
        (cond
          (and symbol (str/includes? symbol "/"))
          (non-blank-string (second (str/split symbol #"/" 2)))

          (and symbol (str/includes? symbol "-"))
          (non-blank-string (second (str/split symbol #"-" 2)))

          :else nil))
      (when (and (string? active-asset) (str/includes? active-asset "/"))
        (non-blank-string (second (str/split active-asset #"/" 2))))
      "USDC"))

(defn- resolve-base-symbol [active-asset active-market]
  (or (base-symbol-from-value active-asset)
      (non-blank-string (:base active-market))
      (base-symbol-from-value (:coin active-market))
      (base-symbol-from-value (:symbol active-market))
      "Asset"))

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

(defn- price-context-accessory [state form]
  (let [{:keys [source]} (trading/mid-price-summary state form)
        mid-available? (= :mid source)]
    [:button {:type "button"
              :disabled (not mid-available?)
              :class (into ["text-xs" "font-semibold" "transition-colors"]
                           (if mid-available?
                             ["text-primary" "cursor-pointer" "hover:text-primary/80"]
                             ["text-gray-500" "cursor-default"]))
              :on (when mid-available?
                    {:click [[:actions/set-order-price-to-mid]]})}
     (if mid-available? "Mid" "Ref")]))

(defn- tif-inline-control [form]
  [:div {:class ["relative" "flex" "items-center" "gap-2"]}
   [:span {:class ["text-xs" "uppercase" "tracking-wide" "text-gray-400"]} "TIF"]
   [:select {:class ["appearance-none"
                     "bg-transparent"
                     "text-sm"
                     "font-semibold"
                     "text-gray-100"
                     "outline-none"
                     "focus:outline-none"
                     "focus:ring-0"
                     "focus:ring-offset-0"
                     "focus:shadow-none"
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
        entry-mode (:entry-mode normalized-form)
        pro-dropdown-open?* (pro-dropdown-open? normalized-form)
        market-mode? (= entry-mode :market)
        pro-mode? (= entry-mode :pro)
        show-limit-like-controls? (and (not market-mode?) (trading/limit-like-type? type))
        limit-like? (trading/limit-like-type? type)
        summary (trading/order-summary state normalized-form)
        available-to-trade (:available-to-trade summary)
        position (:current-position summary)
        ui-leverage (:ui-leverage normalized-form)
        max-leverage (trading/market-max-leverage state)
        next-lev (next-leverage ui-leverage max-leverage)
        size-percent (trading/clamp-percent (:size-percent normalized-form))
        notch-overlap-threshold 4
        raw-price (or (:price normalized-form) "")
        fallback-limit-price (when limit-like?
                               (trading/effective-limit-price-string state normalized-form))
        display-price (if (str/blank? raw-price)
                        (or fallback-limit-price "")
                        raw-price)
        sz-decimals (or (:szDecimals active-market) 4)
        base-symbol (resolve-base-symbol active-asset active-market)
        quote-symbol (resolve-quote-symbol active-asset active-market)
        scale-preview (when (= :scale type)
                        (trading/scale-preview-boundaries normalized-form {:sz-decimals sz-decimals}))
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
        order-value (:order-value summary)
        margin-required (:margin-required summary)
        size-display (:size-display normalized-form)
        display-size-percent (str (int (js/Math.round size-percent)))
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

      (entry-mode-tabs entry-mode type pro-dropdown-open?*)

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
        [:span {:class ["text-sm" "font-semibold" "text-gray-100" "num"]}
         (format-usdc available-to-trade)]]
       [:div {:class ["flex" "items-center" "justify-between"]}
        [:span {:class ["text-sm" "text-gray-400"]} "Current position"]
        [:span {:class ["text-sm" "font-semibold" "text-gray-100" "num"]}
         (format-position-label position sz-decimals)]]]

      (when show-limit-like-controls?
        (row-input display-price
                   (str "Price (" quote-symbol ")")
                   [[:actions/update-order-form [:price] [:event.target/value]]]
                   (price-context-accessory state normalized-form)
                   :input-padding-right "pr-14"))

      (row-input size-display
                 "Size"
                 [[:actions/set-order-size-display [:event.target/value]]]
                 (quote-accessory quote-symbol))

      [:div {:class ["flex" "items-center" "gap-2"]}
       [:div {:class ["relative" "flex-1"]}
        [:input {:class ["order-size-slider" "range" "range-sm" "w-full" "relative" "z-20"]
                 :type "range"
                 :min 0
                 :max 100
                 :step 1
                 :style {"--order-size-slider-progress" (str size-percent "%")}
                 :value size-percent
                 :on {:input [[:actions/set-order-size-percent [:event.target/value]]]}}]
        [:div {:class ["order-size-slider-notches"
                       "pointer-events-none"
                       "absolute"
                       "inset-x-0"
                       "top-1/2"
                       "z-30"
                       "flex"
                       "items-center"
                       "justify-between"
                       "px-0.5"]}
         (for [pct [0 25 50 75 100]]
           ^{:key (str "size-slider-notch-" pct)}
           [:span {:class (into ["order-size-slider-notch"
                            "block"
                           "h-[7px]"
                           "w-[7px]"
                           "-translate-y-1/2"
                           "rounded-full"]
                          (remove nil?
                                  [(if (>= size-percent pct)
                                     "order-size-slider-notch-active"
                                     "order-size-slider-notch-inactive")
                                   (when (<= (js/Math.abs (- size-percent pct))
                                             notch-overlap-threshold)
                                     "opacity-0")]))}])]]
       [:div {:class ["relative" "w-[82px]"]}
        [:input {:class (into ["order-size-percent-input"
                               "h-10"
                               "w-full"
                               "bg-base-200/80"
                               "border"
                               "border-base-300"
                               "rounded-lg"
                               "text-right"
                               "text-sm"
                               "font-semibold"
                               "text-gray-100"
                               "num"
                               "appearance-none"
                               "pl-2.5"
                               "pr-6"]
                              neutral-input-focus-classes)
                 :type "text"
                 :inputmode "numeric"
                 :pattern "[0-9]*"
                 :value display-size-percent
                 :on {:input [[:actions/set-order-size-percent [:event.target/value]]]}}]
        [:span {:class ["pointer-events-none"
                        "absolute"
                        "right-2.5"
                        "top-1/2"
                        "-translate-y-1/2"
                        "text-sm"
                        "font-semibold"
                        "text-gray-300"]}
         "%"]]]

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
         [:div {:class ["grid" "grid-cols-2" "gap-2"]}
          (inline-labeled-scale-input "Total Orders"
                                      (get-in normalized-form [:scale :count])
                                      [[:actions/update-order-form [:scale :count] [:event.target/value]]])
          (inline-labeled-scale-input "Size Skew"
                                      (get-in normalized-form [:scale :skew])
                                      [[:actions/update-order-form [:scale :skew] [:event.target/value]]])]])

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
       (when show-limit-like-controls?
         (tif-inline-control normalized-form))]

      (when (not= :scale type)
        (row-toggle "Take Profit / Stop Loss"
                    (:tpsl-panel-open? normalized-form)
                    [[:actions/toggle-order-tpsl-panel]]))

      (when (and (not= :scale type) (:tpsl-panel-open? normalized-form))
        (tp-sl-panel normalized-form))

      (when (and pro-mode? limit-like?)
        (row-toggle "Post Only"
                    (:post-only normalized-form)
                    [[:actions/update-order-form [:post-only] [:event.target/checked]]]))

      [:div {:class ["flex-1"]}]

      (when (= :scale type)
        [:div {:class ["space-y-1.5"]}
         (metric-row "Start" start-preview-line)
         (metric-row "End" end-preview-line)])

      [:div {:class ["border-t" "border-base-300" "pt-3" "space-y-2"]}
       (when (not= :scale type)
         (metric-row "Liquidation Price"
                     (if liq-price
                       (or (fmt/format-trade-price liq-price) "N/A")
                       "N/A")))
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
                     (str "Est " (format-percent slippage-est 4)
                          " / Max " (format-percent slippage-max 2))
                     "text-primary"))
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
