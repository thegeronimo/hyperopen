(ns hyperopen.views.portfolio-view
  (:require [clojure.string :as string]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.portfolio.vm :as portfolio-vm]))

(def ^:private compact-currency-format-options
  {:style "currency"
   :currency "USD"
   :notation "compact"
   :maximumFractionDigits 1})

(def ^:private tooltip-currency-format-options
  {:style "currency"
   :currency "USD"
   :maximumFractionDigits 0
   :minimumFractionDigits 0})

(def ^:private tooltip-time-format-options
  {:hour "2-digit"
   :minute "2-digit"
   :hour12 false})

(def ^:private tooltip-date-format-options
  {:year "numeric"
   :month "short"
   :day "2-digit"})

(def ^:private action-items
  [{:label "Link Staking"
    :action [:actions/navigate "/staking"]}
   {:label "Swap Stablecoins"
    :action [:actions/navigate "/trade"]}
   {:label "Perps ↔ Spot"
    :action [:actions/navigate "/trade"]}
   {:label "EVM ↔ Core"
    :action [:actions/navigate "/trade"]}
   {:label "Portfolio Margin"
    :action [:actions/navigate "/portfolio"]}
   {:label "Send"
    :action [:actions/set-funding-modal :send]}
   {:label "Withdraw"
    :action [:actions/set-funding-modal :withdraw]}
   {:label "Deposit"
    :primary? true
    :action [:actions/set-funding-modal :deposit]}])

(defn- format-currency [value]
  (or (fmt/format-currency value)
      "$0.00"))

(defn- format-compact-currency [value]
  (let [n (if (number? value) value 0)]
    (or (fmt/format-intl-number n compact-currency-format-options)
        "$0")))

(defn- format-fee-pct [pct]
  (let [n (if (number? pct) pct 0)]
    (str (.toFixed n 3) "%")))

(defn- format-percent [pct]
  (or (fmt/format-signed-percent pct {:decimals 2
                                      :signed? false})
      "0.00%"))

(defn- format-signed-percent-from-decimal [value]
  (fmt/format-signed-percent-from-decimal value
                                          {:decimals 2
                                           :signed? true}))

(defn- format-ratio-value [value]
  (fmt/format-ratio value 2))

(defn- format-integer-value [value]
  (fmt/format-integer value))

(defn- format-metric-value [kind value]
  (case kind
    :percent (or (format-signed-percent-from-decimal value) "--")
    :ratio (or (format-ratio-value value) "--")
    :integer (or (format-integer-value value) "--")
    :date (if (and (string? value)
                   (seq (string/trim value)))
            value
            "--")
    "--"))

(defn- format-drawdown [ratio]
  (if (number? ratio)
    (format-percent (* ratio 100))
    "N/A"))

(defn- format-hype [value]
  (let [n (if (number? value) value 0)]
    (str (or (fmt/format-integer n)
             "0")
         " HYPE")))

(defn- format-axis-number [value]
  (let [n (if (number? value) value 0)]
    (or (fmt/format-integer n)
        "0")))

(defn- format-axis-percent [value]
  (or (fmt/format-signed-percent value
                                 {:decimals 2
                                  :signed? true})
      "0.00%"))

(defn- format-axis-label [axis-kind value]
  (if (= axis-kind :percent)
    (format-axis-percent value)
    (format-axis-number value)))

(defn- clamp-number [value min-value max-value]
  (cond
    (< value min-value) min-value
    (> value max-value) max-value
    :else value))

(defn- format-tooltip-date [time-ms]
  (or (fmt/format-intl-date-time time-ms tooltip-date-format-options)
      "--"))

(defn- format-tooltip-time [time-ms]
  (or (fmt/format-intl-date-time time-ms tooltip-time-format-options)
      "--:--"))

(defn- format-tooltip-value [selected-tab value]
  (if (= selected-tab :returns)
    (format-axis-percent value)
    (let [n (if (number? value) value 0)
          n* (if (== n -0) 0 n)]
      (or (fmt/format-intl-number n* tooltip-currency-format-options)
          "$0"))))

(defn- tooltip-metric-label [selected-tab]
  (case selected-tab
    :account-value "Account Value"
    :pnl "PNL"
    :returns "Returns"
    "Value"))

(defn- tooltip-value-classes [selected-tab value]
  (let [n (if (number? value) value 0)
        positive-classes ["text-[#16d6a1]"]
        negative-classes ["text-[#ff7b72]"]
        neutral-classes ["text-[#e6edf2]"]]
    (case selected-tab
      :account-value ["text-[#ff9f1a]"]
      :pnl (cond
             (pos? n) positive-classes
             (neg? n) negative-classes
             :else neutral-classes)
      :returns (cond
                 (pos? n) positive-classes
                 (neg? n) negative-classes
                 :else neutral-classes)
      neutral-classes)))

(defn- chart-tooltip-model [summary-time-range selected-tab {:keys [time-ms value]}]
  {:timestamp (if (= summary-time-range :day)
                (format-tooltip-time time-ms)
                (format-tooltip-date time-ms))
   :metric-label (tooltip-metric-label selected-tab)
   :metric-value (format-tooltip-value selected-tab value)
   :value-classes (tooltip-value-classes selected-tab value)})

(defn- chart-tooltip-benchmark-values
  [selected-tab hovered-index series]
  (if (and (= selected-tab :returns)
           (number? hovered-index))
    (->> (or series [])
         (keep (fn [{:keys [id coin label stroke points]}]
                 (when (and (keyword? id)
                            (not= id :strategy))
                   (let [point (get (or points []) hovered-index)
                         value (:value point)
                         coin* (or (some-> coin str string/trim)
                                   (name id))
                         label* (or (some-> label str string/trim)
                                    coin*
                                    "Benchmark")
                         stroke* (if (and (string? stroke)
                                          (seq stroke))
                                   stroke
                                   "#e6edf2")]
                     (when (fmt/finite-number? value)
                       {:coin coin*
                        :label label*
                        :value (format-tooltip-value :returns value)
                        :stroke stroke*})))))
         vec)
    []))

(def ^:private axis-label-fallback-char-width-px
  7.5)

(def ^:private axis-label-horizontal-padding-px
  30)

(def ^:private axis-label-min-gutter-width-px
  56)

(def ^:private axis-label-measure-context
  (delay
    (when (and (exists? js/document)
               (some? js/document))
      (let [canvas (.createElement js/document "canvas")]
        (.getContext canvas "2d")))))

(defn- axis-label-width-px [text]
  (let [context @axis-label-measure-context]
    (if context
      (do
        ;; Keep axis width in sync with actual rendered 12px label metrics.
        (set! (.-font context) "12px \"Inter Variable\", system-ui, -apple-system, \"Segoe UI\", sans-serif")
        (-> context
            (.measureText text)
            .-width))
      (* axis-label-fallback-char-width-px (count text)))))

(defn- y-axis-gutter-width [axis-kind y-ticks]
  (let [widest-label-px (->> y-ticks
                             (map (fn [{:keys [value]}]
                                    (axis-label-width-px (format-axis-label axis-kind value))))
                             (reduce max 0))
        gutter-width (+ widest-label-px axis-label-horizontal-padding-px)]
    (js/Math.ceil (max axis-label-min-gutter-width-px gutter-width))))

(defn- action-button [{:keys [label action primary?]}]
  [:button {:type "button"
            :class (into ["btn" "btn-sm" "rounded-lg" "border" "border-base-300" "bg-base-100" "text-trading-text-secondary" "hover:text-trading-text" "hover:bg-base-200"]
                         (when primary?
                           ["bg-[#1f5b55]" "text-trading-text" "hover:bg-[#267067]"]))
            :on {:click [action]}}
   label])

(defn- summary-row [label value & [value-class]]
  [:div {:class ["grid" "grid-cols-[1fr_auto]" "items-center" "gap-3"]}
   [:span {:class ["text-sm" "text-trading-text-secondary"]}
    label]
   [:span {:class (into ["num" "text-sm" "text-trading-text"] (or value-class []))}
    value]])

(defn- pnl-summary [pnl]
  (let [n (if (number? pnl) pnl 0)
        color-class (cond
                      (pos? n) "text-success"
                      (neg? n) "text-error"
                      :else "text-trading-text")]
    {:value (str (cond
                   (pos? n) "+"
                   (neg? n) "-"
                   :else "")
                (format-currency (js/Math.abs n)))
     :class [color-class]}))

(defn- summary-selector
  [{:keys [label open? options value]}
   toggle-action
   select-action
   data-role]
  [:div {:class ["relative"]
         :data-role data-role}
   [:button {:type "button"
             :class ["flex"
                     "items-center"
                     "gap-1.5"
                     "rounded-md"
                     "px-2"
                     "py-1"
                     "text-xs"
                     "font-normal"
                     "text-trading-text"
                     "hover:bg-base-200"]
             :aria-expanded (boolean open?)
             :on {:click [[toggle-action]]}}
    [:span label]
    [:svg {:class (into ["h-4" "w-4" "text-trading-text-secondary" "transition-transform"]
                        (when open?
                          ["rotate-180"]))
           :fill "none"
           :stroke "currentColor"
           :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round"
             :stroke-linejoin "round"
             :stroke-width 2
             :d "M19 9l-7 7-7-7"}]]]
   [:div {:class (into ["absolute"
                        "right-0"
                        "top-full"
                        "mt-1"
                        "min-w-[160px]"
                        "overflow-hidden"
                        "rounded-md"
                        "border"
                        "border-base-300"
                        "bg-base-100"
                        "shadow-lg"
                        "z-30"]
                       (if open?
                         ["opacity-100" "scale-y-100" "translate-y-0"]
                         ["opacity-0" "scale-y-95" "-translate-y-1" "pointer-events-none"]))
          :style {:transition "all 80ms ease-in-out"}}
    (for [{option-value :value option-label :label} options]
      ^{:key (str data-role "-" (name option-value))}
      [:button {:type "button"
                :class (into ["block"
                              "w-full"
                              "px-3"
                              "py-2"
                              "text-left"
                              "text-xs"
                              "hover:bg-base-200"]
                             (if (= option-value value)
                               ["text-trading-text" "bg-base-200"]
                               ["text-trading-text-secondary"]))
                :on {:click [[select-action option-value]]}}
       option-label])]])

(defn- section-card [data-role & children]
  (into [:div {:class ["rounded-xl"
                       "border"
                       "border-base-300"
                       "bg-base-100/95"
                       "overflow-hidden"]
               :data-role data-role}]
        children))

(defn- hex-color->rgba [hex alpha]
  (let [hex* (if (and (string? hex)
                      (= \# (first hex)))
               (subs hex 1)
               hex)]
    (when (and (string? hex*)
               (re-matches #"[0-9A-Fa-f]{6}" hex*))
      (let [r (js/Number.parseInt (subs hex* 0 2) 16)
            g (js/Number.parseInt (subs hex* 2 4) 16)
            b (js/Number.parseInt (subs hex* 4 6) 16)]
        (str "rgba(" r ", " g ", " b ", " alpha ")")))))

(defn- returns-benchmark-chip [{:keys [value label stroke]}]
  (let [display-label (let [raw-label (some-> label clojure.core/str string/trim)
                            label-without-suffix (some-> raw-label
                                                         (string/replace #"\s*\([^)]*\)\s*$" ""))
                            primary-token (some-> label-without-suffix
                                                  (string/split #"-" 2)
                                                  first
                                                  string/trim)]
                        (if (seq primary-token)
                          primary-token
                          (or raw-label "")))
        accent-color (or stroke "#9fb3be")
        border-color (or (hex-color->rgba stroke 0.58)
                         "rgba(120, 141, 154, 0.5)")
        background-color (or (hex-color->rgba stroke 0.14)
                             "rgba(120, 141, 154, 0.16)")]
    [:span {:class ["inline-flex"
                    "max-w-full"
                    "items-center"
                    "gap-1"
                    "rounded-md"
                    "border"
                    "px-1"
                    "py-0.5"]
            :style {:border-color border-color
                    :background-color background-color}
            :data-role (str "portfolio-returns-benchmark-chip-" value)}
     [:span {:class ["h-1.5" "w-1.5" "shrink-0" "rounded-full"]
             :style {:background-color accent-color}}]
     [:span {:class ["min-w-0" "truncate" "text-xs" "leading-4" "text-trading-text"]}
      display-label]
     [:button {:type "button"
               :class ["inline-flex"
                       "h-6"
                       "w-6"
                       "items-center"
                       "justify-center"
                       "rounded"
                       "transition-colors"
                       "hover:bg-base-300"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"]
               :style {:color accent-color}
               :aria-label (str "Remove benchmark " label)
               :on {:click [[:actions/remove-portfolio-returns-benchmark value]]}}
      "x"]]))

(defn- returns-benchmark-suggestion-row [{:keys [value label]}]
  [:button {:type "button"
            :class ["flex"
                    "w-full"
                    "items-center"
                    "justify-start"
                    "rounded"
                    "px-2"
                    "py-1.5"
                    "text-left"
                    "text-xs"
                    "text-trading-text"
                    "transition-colors"
                    "hover:bg-base-300"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"]
            :data-role (str "portfolio-returns-benchmark-suggestion-" value)
            :on {:mousedown [[:actions/select-portfolio-returns-benchmark value]]}}
   label])

(defn- returns-benchmark-selector [{:keys [coin-search
                                           suggestions-open?
                                           candidates
                                           top-coin
                                           empty-message]}]
  (let [candidates* (vec (or candidates []))]
    [:div {:class ["relative" "w-[320px]"]
           :data-role "portfolio-returns-benchmark-selector"}
     [:div {:class ["rounded-md" "border" "border-base-300" "bg-base-100" "px-2"]}
      [:input {:id "portfolio-returns-benchmark-search"
               :class ["h-9"
                       "w-full"
                       "border-0"
                       "bg-transparent"
                       "px-1"
                       "text-xs"
                       "text-trading-text"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"]
               :type "search"
               :placeholder "Search benchmarks and press Enter"
               :aria-label "Search benchmark symbols"
               :autocomplete "off"
               :spellcheck false
               :value (or coin-search "")
               :on {:input [[:actions/set-portfolio-returns-benchmark-search [:event.target/value]]]
                    :focus [[:actions/set-portfolio-returns-benchmark-suggestions-open true]]
                    :blur [[:actions/set-portfolio-returns-benchmark-suggestions-open false]]
                    :keydown [[:actions/handle-portfolio-returns-benchmark-search-keydown [:event/key] top-coin]]}}]]
     (when suggestions-open?
       [:div {:class ["absolute"
                      "left-0"
                      "right-0"
                      "top-full"
                      "mt-1"
                      "max-h-44"
                      "overflow-y-auto"
                      "rounded-md"
                      "border"
                      "border-base-300"
                      "bg-base-100"
                      "p-1"
                      "shadow-lg"
                      "z-40"]}
        (if (seq candidates*)
          (for [option candidates*]
            ^{:key (str "portfolio-returns-benchmark-suggestion-" (:value option))}
            (returns-benchmark-suggestion-row option))
          [:div {:class ["px-2" "py-1.5" "text-xs" "text-trading-text-secondary"]}
           (or empty-message "No matching symbols.")])])]))

(defn- returns-benchmark-chip-rail [{:keys [selected-options]}]
  (let [chips (vec (or selected-options []))]
    (when (seq chips)
      [:div {:class ["rounded-md"
                     "border"
                     "border-base-300"
                     "bg-base-100/92"
                     "p-1.5"
                     "shadow-md"]
             :data-role "portfolio-returns-benchmark-chip-rail"}
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-1.5" "pr-1"]}
        (for [{:keys [value] :as option} chips]
          ^{:key (str "portfolio-returns-benchmark-chip-rail-item-" value)}
          (returns-benchmark-chip option))]])))

(defn- benchmark-series-color-by-coin [series]
  (reduce (fn [acc {:keys [coin stroke]}]
            (if (and (string? coin)
                     (seq coin)
                     (string? stroke)
                     (seq stroke))
              (assoc acc coin stroke)
              acc))
          {}
          (or series [])))

(defn- add-benchmark-chip-colors [returns-benchmark series]
  (let [color-by-coin (benchmark-series-color-by-coin series)]
    (update returns-benchmark
            :selected-options
            (fn [options]
              (mapv (fn [{:keys [value] :as option}]
                      (assoc option :stroke (get color-by-coin value)))
                    (or options []))))))

(defn- chart-series-path [{:keys [id path stroke]}]
  (when (seq path)
    [:path {:d path
            :fill "none"
            :stroke stroke
            :stroke-width 1.4
            :vector-effect "non-scaling-stroke"
            :stroke-linecap "round"
            :stroke-linejoin "round"
            :data-role (if (= id :strategy)
                         "portfolio-chart-path"
                         (str "portfolio-chart-path-" (name id)))}]))

(defn- chart-legend [series]
  (let [visible-series (->> series
                            (filter :has-data?)
                            vec)]
    (when (> (count visible-series) 1)
      [:div {:class ["flex"
                     "flex-wrap"
                     "items-center"
                     "gap-3"
                     "rounded-md"
                     "bg-base-100/95"
                     "px-2"
                     "py-1"]
             :style {:padding-top "15px"}
             :data-role "portfolio-chart-legend"}
       (for [{:keys [id label stroke]} visible-series]
         ^{:key (str "portfolio-chart-legend-item-" (name id))}
         [:div {:class ["flex" "items-center" "gap-1.5"]
                :data-role (str "portfolio-chart-legend-item-" (name id))}
          [:span {:class ["h-2" "w-2" "rounded-full"]
                  :style {:background-color stroke}}]
          [:span {:class ["text-xs" "text-trading-text-secondary"]}
           label]])])))

(defn- summary-card [{:keys [summary selectors]}]
  (let [pnl-info (pnl-summary (:pnl summary))
        summary-scope (:summary-scope selectors)
        summary-time-range (:summary-time-range selectors)]
    (section-card
     "portfolio-account-summary-card"
     [:div {:class ["flex" "items-center" "justify-between" "border-b" "border-base-300" "px-4" "py-3"]}
      (summary-selector summary-scope
                        :actions/toggle-portfolio-summary-scope-dropdown
                        :actions/select-portfolio-summary-scope
                        "portfolio-summary-scope-selector")
      (summary-selector summary-time-range
                        :actions/toggle-portfolio-summary-time-range-dropdown
                        :actions/select-portfolio-summary-time-range
                        "portfolio-summary-time-range-selector")]
     [:div {:class ["space-y-2.5" "px-4" "py-3"]}
      (summary-row "PNL" (:value pnl-info) (:class pnl-info))
      (summary-row "Volume" (format-currency (:volume summary)))
      (summary-row "Max Drawdown" (format-drawdown (:max-drawdown-pct summary)))
      (summary-row "Total Equity" (format-currency (:total-equity summary)))
      (when (:show-perps-account-equity? summary)
        (summary-row "Perps Account Equity" (format-currency (:perps-account-equity summary))))
      (summary-row (:spot-equity-label summary) (format-currency (:spot-account-equity summary)))
      (when (:show-vault-equity? summary)
        (summary-row "Vault Equity" (format-currency (:vault-equity summary))))
      (when (:show-earn-balance? summary)
        (summary-row "Earn Balance" (format-currency (:earn-balance summary))))
      (when (:show-staking-account? summary)
        (summary-row "Staking Account" (format-hype (:staking-account-hype summary))))])))

(defn- chart-card [{:keys [chart selectors]}]
  (let [{:keys [tabs selected-tab axis-kind y-ticks series points hover]} chart
        returns-benchmark (:returns-benchmark selectors)
        returns-benchmark* (add-benchmark-chip-colors returns-benchmark series)
        summary-time-range (get-in selectors [:summary-time-range :value])
        y-axis-width (y-axis-gutter-width axis-kind y-ticks)
        plot-left (+ y-axis-width 10)
        point-count (count points)
        hovered-point (:point hover)
        hovered-index (:index hover)
        hover-active? (boolean (:active? hover))
        hover-line-left-pct (when hover-active?
                             (* 100 (:x-ratio hovered-point)))
        hover-tooltip-top-pct (when hover-active?
                               (clamp-number (- (* 100 (:y-ratio hovered-point)) 8)
                                             8
                                             92))
        hover-tooltip-right? (when hover-active?
                               (> hover-line-left-pct 74))
        hover-benchmark-values (when hover-active?
                                 (chart-tooltip-benchmark-values selected-tab
                                                                 hovered-index
                                                                 series))
        hover-tooltip (when hover-active?
                        (chart-tooltip-model summary-time-range
                                             selected-tab
                                             hovered-point))]
    (section-card
     "portfolio-chart-card"
     [:div {:class ["flex" "items-center" "gap-2" "border-b" "border-base-300" "px-3" "py-2"]}
      (for [{tab-value :value
             tab-label :label} tabs]
        ^{:key (str "portfolio-chart-tab-" (name tab-value))}
        [:button {:type "button"
                  :class (into ["rounded-md"
                                "border"
                                "px-2.5"
                                "py-1"
                                "text-xs"
                                "transition-colors"]
                               (if (= tab-value selected-tab)
                                 ["border-[#2f5e58]" "bg-[#0d252f]" "text-trading-text"]
                                 ["border-transparent" "text-[#8ea4ab]" "hover:text-trading-text"]))
                  :data-role (str "portfolio-chart-tab-" (name tab-value))
                  :aria-pressed (= tab-value selected-tab)
                  :on {:click [[:actions/select-portfolio-chart-tab tab-value]]}}
         tab-label])
     (when (= selected-tab :returns)
        [:div {:class ["ml-auto" "px-3" "py-2"]}
         (returns-benchmark-selector returns-benchmark*)])]
     [:div {:class ["px-4" "py-3" "space-y-2"]
            :data-role "portfolio-chart-shell"}
      [:div {:class ["relative" "h-[182px]" "xl:h-[210px]"]}
       [:div {:class ["absolute" "left-0" "top-0" "bottom-0"]
              :data-role "portfolio-chart-y-axis"
              :style {:width (str y-axis-width "px")}}
        (for [{:keys [value y-ratio]} y-ticks]
          ^{:key (str "portfolio-chart-tick-" y-ratio "-" value)}
          [:span {:class ["absolute"
                          "right-2"
                          "-translate-y-1/2"
                          "num"
                          "text-right"
                          "text-xs"
                          "text-trading-text-secondary"]
                  :style {:top (str (* 100 y-ratio) "%")}}
           (format-axis-label axis-kind value)])
        [:div {:class ["absolute"
                       "right-0"
                       "top-0"
                       "bottom-0"
                       "border-l"
                       "border-base-300"]}]
        (for [{:keys [y-ratio]} y-ticks]
          ^{:key (str "portfolio-chart-axis-tick-" y-ratio)}
          [:div {:class ["absolute"
                         "right-0"
                         "w-1.5"
                         "border-t"
                         "border-base-300"]
                 :style {:top (str (* 100 y-ratio) "%")}}])]
       [:div {:class ["absolute" "right-2" "top-0" "bottom-0" "cursor-crosshair"]
              :style {:left (str plot-left "px")}
              :data-role "portfolio-chart-plot-area"
              :on {:mousemove [[:actions/set-portfolio-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                   :mouseenter [[:actions/set-portfolio-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                   :pointermove [[:actions/set-portfolio-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                   :pointerenter [[:actions/set-portfolio-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                   :mouseleave [[:actions/clear-portfolio-chart-hover]]
                   :pointerleave [[:actions/clear-portfolio-chart-hover]]
                   :mouseout [[:actions/clear-portfolio-chart-hover]]}}
        [:svg {:viewBox "0 0 100 100"
               :preserveAspectRatio "none"
               :class ["h-full" "w-full"]}
         [:line {:x1 0
                 :y1 100
                 :x2 100
                 :y2 100
                 :stroke "#28414a"
                 :stroke-width 0.8
                 :vector-effect "non-scaling-stroke"}]
         (for [{series-id :id :as series-entry} (or series [])]
           ^{:key (str "portfolio-chart-path-" (name series-id))}
           (chart-series-path series-entry))]
        (when hover-active?
          [:div {:class ["absolute"
                         "top-0"
                         "bottom-0"
                         "w-px"
                         "-translate-x-1/2"
                         "pointer-events-none"
                         "bg-[#9fb3be]"
                         "z-10"]
                 :data-role "portfolio-chart-hover-line"
                 :style {:left (str hover-line-left-pct "%")}}])
        (when hover-active?
          [:div {:class ["absolute"
                         "pointer-events-none"
                         "min-w-[188px]"
                         "rounded-xl"
                         "border"
                         "px-3"
                         "py-2"
                         "shadow-lg"
                         "z-20"]
                 :data-role "portfolio-chart-hover-tooltip"
                 :style {:left (str hover-line-left-pct "%")
                         :top (str hover-tooltip-top-pct "%")
                         :transform (if hover-tooltip-right?
                                      "translate(calc(-100% - 8px), -50%)"
                                      "translate(8px, -50%)")
                         :white-space "nowrap"
                         :border-color "rgba(98, 114, 130, 0.65)"
                         :background "linear-gradient(138deg, rgba(24, 35, 47, 0.95) 0%, rgba(16, 25, 38, 0.95) 56%, rgba(43, 36, 25, 0.86) 100%)"}}
           [:div {:class ["num"
                          "text-[12px]"
                          "font-medium"
                          "leading-4"
                          "text-[#8ea1b3]"]}
            (:timestamp hover-tooltip)]
           [:div {:class ["mt-2"
                          "grid"
                          "grid-cols-[1fr_auto]"
                          "items-center"
                          "gap-3"]}
            [:span {:class ["text-[12px]"
                            "font-medium"
                            "leading-4"
                            "text-[#909fac]"]}
             (:metric-label hover-tooltip)]
            [:span {:class (into ["num"
                                  "text-[16px]"
                                  "font-semibold"
                                  "leading-[1]"
                                  "tracking-tight"]
                                 (:value-classes hover-tooltip))}
             (:metric-value hover-tooltip)]]
           (when (seq hover-benchmark-values)
             [:div {:class ["mt-1.5" "space-y-1"]}
              (for [{:keys [coin label value stroke]} hover-benchmark-values]
                ^{:key (str "portfolio-chart-hover-tooltip-benchmark-row-" coin)}
                [:div {:class ["grid"
                               "grid-cols-[1fr_auto]"
                               "items-center"
                               "gap-3"]
                       :data-role (str "portfolio-chart-hover-tooltip-benchmark-row-" coin)}
                 [:span {:class ["text-[12px]"
                                 "font-medium"
                                 "leading-4"
                                 "text-[#909fac]"]}
                 label]
                 [:span {:class ["num"
                                 "text-sm"
                                 "font-semibold"
                                 "leading-[1.1]"
                                 "tracking-tight"]
                         :data-role (str "portfolio-chart-hover-tooltip-benchmark-value-" coin)
                         :style {:color stroke}}
                  value]])])])]]
      (chart-legend series)
      (when (= selected-tab :returns)
        (returns-benchmark-chip-rail returns-benchmark*))])))

(defn- performance-metric-value-cell
  ([kind value]
   (performance-metric-value-cell kind value nil))
  ([kind value attrs]
   [:span (merge {:class (into ["justify-self-start" "text-sm" "text-trading-text" "text-left"]
                               (when (not= kind :date)
                                 ["num"]))}
                 attrs)
    (format-metric-value kind value)]))

(defn- resolved-benchmark-metric-columns
  [{:keys [benchmark-columns benchmark-selected? benchmark-label benchmark-coin]}]
  (let [columns (->> (or benchmark-columns [])
                     (keep (fn [{:keys [coin label]}]
                             (let [coin* (some-> coin str string/trim)
                                   label* (some-> label str string/trim)]
                               (when (seq coin*)
                                 {:coin coin*
                                  :label (or label* coin*)}))))
                     vec)]
    (if (seq columns)
      columns
      [{:coin (or (some-> benchmark-coin str string/trim)
                  "__benchmark__")
        :label (if benchmark-selected?
                 (or benchmark-label "Benchmark")
                 "Benchmark")}])))

(defn- benchmark-row-value
  [row coin]
  (let [values (:benchmark-values row)]
    (if (and (map? values)
             (contains? values coin))
      (get values coin)
      (:benchmark-value row))))

(defn- metric-value-present?
  [kind value]
  (not= "--" (format-metric-value kind value)))

(defn- performance-metric-row-visible?
  [{:keys [kind value] :as row} benchmark-columns]
  (let [portfolio-value (if (contains? row :portfolio-value)
                          (:portfolio-value row)
                          value)]
    (or (metric-value-present? kind portfolio-value)
        (some (fn [{:keys [coin]}]
                (metric-value-present? kind (benchmark-row-value row coin)))
              benchmark-columns))))

(defn- performance-metrics-grid-style
  [benchmark-column-count]
  {:grid-template-columns (string/join " "
                                       (concat ["220px"]
                                               (repeat benchmark-column-count "132px")
                                               ["132px"]))})

(defn- performance-metric-row [{:keys [key label kind value] :as row} benchmark-columns grid-style]
  (let [portfolio-value (if (contains? row :portfolio-value)
                          (:portfolio-value row)
                          value)]
    [:div {:class ["grid"
                   "items-center"
                   "justify-items-start"
                   "gap-3"
                   "hover:bg-base-300"]
           :style grid-style
           :data-role (str "portfolio-performance-metric-" (name key))}
     [:span {:class ["text-sm"]
             :style {:color "#9CA3AF"}}
      label]
     (for [{:keys [coin]} benchmark-columns]
       ^{:key (str "portfolio-performance-metric-" (name key) "-benchmark-" coin)}
       (performance-metric-value-cell kind
                                      (benchmark-row-value row coin)
                                      {:data-role (str "portfolio-performance-metric-" (name key) "-benchmark-value-" coin)}))
     (performance-metric-value-cell kind portfolio-value)]))

(defn- performance-metrics-card [{:keys [loading?
                                         benchmark-selected?
                                         benchmark-label
                                         benchmark-columns
                                         benchmark-coin
                                         groups
                                         time-range-selector]}]
  (let [benchmark-columns* (resolved-benchmark-metric-columns {:benchmark-columns benchmark-columns
                                                               :benchmark-selected? benchmark-selected?
                                                               :benchmark-label benchmark-label
                                                               :benchmark-coin benchmark-coin})
        grid-style (performance-metrics-grid-style (count benchmark-columns*))
        visible-groups (->> (or groups [])
                            (keep (fn [{:keys [rows] :as group}]
                                    (let [rows* (->> (or rows [])
                                                     (filter #(performance-metric-row-visible? % benchmark-columns*))
                                                     vec)]
                                      (when (seq rows*)
                                        (assoc group :rows rows*)))))
                            vec)]
    [:div {:class ["flex" "h-full" "min-h-0" "flex-col" "relative"]
           :data-role "portfolio-performance-metrics-card"}
     (when loading?
       [:div {:class ["absolute" "inset-0" "z-10" "flex" "items-center" "justify-center" "bg-base-100/50" "backdrop-blur-sm"]}
        [:span {:class ["loading" "loading-spinner" "loading-lg"]}]])
     [:div {:class ["grid"
                    "items-center"
                    "justify-items-start"
                    "gap-3"
                    "border-b"
                    "border-base-300"
                    "bg-base-200/35"
                    "px-4"
                    "py-2.5"]
            :style grid-style}
      [:div {:class ["flex" "min-w-0" "items-center" "justify-between" "gap-2"]}
       [:span {:class ["text-xs" "font-medium" "uppercase" "tracking-wide" "text-trading-text-secondary"]
               :data-role "portfolio-performance-metrics-metric-label"}
        "Metric"]
       (when (map? time-range-selector)
         [:div {:class ["flex" "items-center" "gap-1.5"]}
          [:span {:class ["text-xs" "font-medium" "uppercase" "tracking-wide" "text-trading-text-secondary"]}
           "Range"]
          (summary-selector time-range-selector
                            :actions/toggle-portfolio-performance-metrics-time-range-dropdown
                            :actions/select-portfolio-summary-time-range
                            "portfolio-performance-metrics-time-range-selector")])]
      (for [[idx {:keys [coin label]}] (map-indexed vector benchmark-columns*)]
        ^{:key (str "portfolio-performance-metrics-benchmark-label-" coin)}
        [:span {:class ["justify-self-start" "text-xs" "font-medium" "uppercase" "tracking-wide" "text-left" "text-trading-text-secondary"]
                :data-role (if (zero? idx)
                             "portfolio-performance-metrics-benchmark-label"
                             (str "portfolio-performance-metrics-benchmark-label-" coin))}
         label])
      [:span {:class ["justify-self-start" "text-xs" "font-medium" "uppercase" "tracking-wide" "text-left" "text-trading-text-secondary"]
              :data-role "portfolio-performance-metrics-portfolio-label"}
       "Portfolio"]]
     [:div {:class ["flex-1" "min-h-0" "space-y-2.5" "overflow-y-auto" "scrollbar-hide" "px-4" "py-3"]}
      (for [[idx {:keys [id rows]}] (map-indexed vector visible-groups)]
        ^{:key (str "portfolio-performance-metrics-group-" (name id))}
        [:div {:class (into ["space-y-1.5"]
                            (when (pos? idx)
                              ["border-t" "border-base-300" "pt-2.5"]))
               :data-role (str "portfolio-performance-metrics-group-" (name id))}
         (for [{:keys [key] :as row} rows]
           ^{:key (str "portfolio-performance-metric-row-" (name key))}
           (performance-metric-row row benchmark-columns* grid-style))])]]))

(def ^:private portfolio-account-tab-click-actions-by-tab
  (into
   {:performance-metrics [[:actions/set-portfolio-account-info-tab :performance-metrics]]}
   (map (fn [tab]
          [tab
           [[:actions/set-portfolio-account-info-tab tab]
            [:actions/select-account-info-tab tab]]])
        account-info-view/available-tabs)))

(defn- metric-cards [{:keys [volume-14d-usd fees]}]
  [:div {:class ["grid" "grid-cols-1" "gap-3" "md:grid-cols-2" "xl:grid-cols-1"]}
   (section-card
    "portfolio-14d-volume-card"
    [:div {:class ["space-y-3" "px-4" "py-3"]}
     [:div {:class ["text-sm" "text-trading-text-secondary"]}
      "14 Day Volume"]
     [:div {:class ["num" "text-4xl" "font-medium" "text-trading-text"]}
      (format-compact-currency volume-14d-usd)]
     [:button {:class ["btn" "btn-xs" "btn-ghost" "justify-start" "px-0" "text-trading-green" "hover:bg-transparent"]}
      "View Volume"]])
   (section-card
    "portfolio-fees-card"
    [:div {:class ["space-y-3" "px-4" "py-3"]}
     [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
      [:span {:class ["text-sm" "text-trading-text-secondary"]}
       "Fees (Taker / Maker)"]
      [:button {:class ["btn" "btn-ghost" "btn-xs" "text-trading-text"]}
       "Perps"]]
     [:div {:class ["num" "text-4xl" "font-medium" "leading-tight" "text-trading-text"]}
      (str (format-fee-pct (:taker fees)) " / " (format-fee-pct (:maker fees)))]
     [:button {:class ["btn" "btn-xs" "btn-ghost" "justify-start" "px-0" "text-trading-green" "hover:bg-transparent"]}
      "View Fee Schedule"]])])

(defn- header-actions []
  [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3"]}
   [:h1 {:class ["text-5xl" "font-medium" "tracking-tight" "text-trading-text"]}
    "Portfolio"]
   [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]
          :data-role "portfolio-actions-row"}
    (for [{:keys [label] :as item} action-items]
      ^{:key label}
      (action-button item))]])

(defn portfolio-view [state]
  (let [view-model (portfolio-vm/portfolio-vm state)]
    [:div {:class ["flex-1"
                   "min-h-0"
                   "overflow-y-auto"
                   "app-shell-gutter"
                   "py-4"
                   "space-y-4"
                   "md:py-5"]
           :style {:background-image "radial-gradient(circle at 15% 0%, rgba(0, 212, 170, 0.10), transparent 35%), radial-gradient(circle at 85% 100%, rgba(0, 212, 170, 0.08), transparent 40%)"}
           :data-parity-id "portfolio-root"}
     (header-actions)
     [:div {:class ["grid"
                    "grid-cols-1"
                    "gap-3"
                    "xl:grid-cols-[320px_minmax(280px,0.8fr)_minmax(520px,1.8fr)]"]}
     (metric-cards view-model)
      (summary-card view-model)
      (chart-card view-model)]
     [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "overflow-hidden"]
            :data-role "portfolio-account-table"}
      (account-info-view/account-info-view
       state
       {:extra-tabs [{:id :performance-metrics
                      :label "Performance Metrics"
                      :content (performance-metrics-card (assoc (:performance-metrics view-model)
                                                                :time-range-selector (get-in view-model [:selectors :performance-metrics-time-range])))}]
        :selected-tab-override (get-in state [:portfolio-ui :account-info-tab] :performance-metrics)
        :default-selected-tab :performance-metrics
        :tab-click-actions-by-tab portfolio-account-tab-click-actions-by-tab})]]))
