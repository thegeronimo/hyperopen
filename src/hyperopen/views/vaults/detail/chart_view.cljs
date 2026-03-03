(ns hyperopen.views.vaults.detail.chart-view
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.vaults.detail.format :as vf]))

(def ^:private tooltip-time-format-options
  {:hour "2-digit"
   :minute "2-digit"
   :hour12 false})

(def ^:private tooltip-date-format-options
  {:year "numeric"
   :month "short"
   :day "2-digit"})

(defn- format-chart-axis-value
  [axis-kind value]
  (let [n (if (fmt/finite-number? value) value 0)]
    (case axis-kind
      :returns (or (fmt/format-signed-percent n {:decimals 2
                                                 :signed? true})
                   "0.00%")
      :pnl (or (fmt/format-large-currency n) "$0")
      :account-value (or (fmt/format-large-currency n) "$0")
      (or (fmt/format-large-currency n) "$0"))))

(defn- format-chart-tooltip-value
  [axis-kind value]
  (let [n (if (fmt/finite-number? value) value 0)]
    (case axis-kind
      :returns (format-chart-axis-value :returns n)
      :pnl (vf/format-currency n {:missing "$0.00"})
      :account-value (vf/format-currency n {:missing "$0.00"})
      (vf/format-currency n {:missing "$0.00"}))))

(defn- format-tooltip-date
  [time-ms]
  (or (fmt/format-intl-date-time time-ms tooltip-date-format-options)
      "—"))

(defn- format-tooltip-time
  [time-ms]
  (or (fmt/format-intl-date-time time-ms tooltip-time-format-options)
      "--:--"))

(defn- tooltip-metric-label
  [axis-kind]
  (case axis-kind
    :account-value "Account Value"
    :pnl "PNL"
    :returns "Returns"
    "Value"))

(defn- tooltip-value-classes
  [axis-kind value]
  (let [n (if (fmt/finite-number? value) value 0)
        positive-classes ["text-[#16d6a1]"]
        negative-classes ["text-[#ff7b72]"]
        neutral-classes ["text-[#e6edf2]"]]
    (case axis-kind
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

(defn- chart-tooltip-model
  [summary-time-range axis-kind {:keys [time-ms value]}]
  {:timestamp (if (= summary-time-range :day)
                (format-tooltip-time time-ms)
                (format-tooltip-date time-ms))
   :metric-label (tooltip-metric-label axis-kind)
   :metric-value (format-chart-tooltip-value axis-kind value)
   :value-classes (tooltip-value-classes axis-kind value)})

(defn- chart-tooltip-benchmark-values
  [axis-kind hovered-index series]
  (if (and (= axis-kind :returns)
           (number? hovered-index))
    (->> (or series [])
         (keep (fn [{:keys [id coin label stroke points]}]
                 (when (and (keyword? id)
                            (not= id :strategy))
                     (let [point (get (or points []) hovered-index)
                         value (:value point)
                         label* (or (some-> label str str/trim)
                                    (some-> coin str str/trim)
                                    (when (keyword? id)
                                      (name id))
                                    "Benchmark")
                         stroke* (if (and (string? stroke)
                                          (seq stroke))
                                   stroke
                                   "#e6edf2")]
                     (when (fmt/finite-number? value)
                       {:coin (or coin label* "benchmark")
                        :label label*
                        :value (format-chart-tooltip-value :returns value)
                        :stroke stroke*})))))
         vec)
    []))

(defn- clamp-number
  [value min-value max-value]
  (cond
    (< value min-value) min-value
    (> value max-value) max-value
    :else value))

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
        ;; Match chart tick labels rendered at 12px for reliable gutter width.
        (set! (.-font context) "12px \"Inter Variable\", system-ui, -apple-system, \"Segoe UI\", sans-serif")
        (-> context
            (.measureText text)
            .-width))
      (* axis-label-fallback-char-width-px (count text)))))

(defn- y-axis-gutter-width [axis-kind y-ticks]
  (let [widest-label-px (->> y-ticks
                             (map (fn [{:keys [value]}]
                                    (axis-label-width-px (format-chart-axis-value axis-kind value))))
                             (reduce max 0))
        gutter-width (+ widest-label-px axis-label-horizontal-padding-px)]
    (js/Math.ceil (max axis-label-min-gutter-width-px gutter-width))))

(defn chart-series-button
  [{:keys [value label]} selected-series]
  [:button {:type "button"
            :class (into ["rounded-md"
                          "border"
                          "px-2.5"
                          "py-1"
                          "text-xs"
                          "transition-colors"]
                         (if (= value selected-series)
                           ["border-[#2f5e58]" "bg-[#0d252f]" "text-trading-text"]
                           ["border-transparent" "text-[#8ea4ab]" "hover:text-trading-text"]))
            :on {:click [[:actions/set-vault-detail-chart-series value]]}}
   label])

(defn- timeframe-token
  [value]
  (let [token (cond
                (keyword? value) (name value)
                (string? value) (str/trim value)
                :else nil)]
    (when (seq token)
      token)))

(defn chart-timeframe-menu [{:keys [timeframe-options selected-timeframe]}]
  (let [selected-token (or (timeframe-token selected-timeframe)
                           (timeframe-token (some-> timeframe-options first :value))
                           "day")]
    [:label {:class ["inline-flex"
                     "items-center"
                     "gap-1.5"
                     "rounded-md"
                     "border"
                     "border-[#1f3b3c]"
                     "bg-[#071e25]"
                     "px-2.5"
                     "py-1"
                     "text-xs"
                     "text-trading-text"]}
     [:span "Range "]
     [:select {:class ["bg-transparent"
                       "text-trading-text"
                       "outline-none"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:border-transparent"
                       "focus-visible:outline-none"
                       "focus-visible:ring-0"
                       "border-none"
                       "p-0"
                       "pr-4"
                       "text-xs"]
               :value selected-token
               :on {:change [[:actions/set-vaults-snapshot-range [:event.target/value]]]}}
      (for [{:keys [value label]} timeframe-options
            :let [option-token (or (timeframe-token value)
                                   "day")]]
        ^{:key (str "vault-chart-timeframe-" option-token)}
        [:option (cond-> {:value option-token}
                   (= option-token selected-token)
                   (assoc :selected true))
         label])]]))

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

(defn- returns-benchmark-chip [{:keys [value label display-label stroke]}]
  (let [chip-label (or (some-> display-label str str/trim)
                       (some-> label str str/trim)
                       "Benchmark")
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
            :data-role (str "vault-detail-returns-benchmark-chip-" value)}
     [:span {:class ["h-1.5" "w-1.5" "shrink-0" "rounded-full"]
             :style {:background-color accent-color}}]
     [:span {:class ["min-w-0" "truncate" "text-xs" "leading-4" "text-trading-text"]}
      chip-label]
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
               :on {:click [[:actions/remove-vault-detail-returns-benchmark value]]}}
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
            :data-role (str "vault-detail-returns-benchmark-suggestion-" value)
            :on {:mousedown [[:actions/select-vault-detail-returns-benchmark value]]}}
   label])

(defn- returns-benchmark-selector [{:keys [coin-search
                                           suggestions-open?
                                           candidates
                                           top-coin
                                           empty-message]}]
  (let [candidates* (vec (or candidates []))]
    [:div {:class ["relative" "w-[320px]"]
           :data-role "vault-detail-returns-benchmark-selector"}
     [:div {:class ["rounded-md" "border" "border-[#1f3b3c]" "bg-[#071e25]" "px-2"]}
      [:input {:id "vault-detail-returns-benchmark-search"
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
               :on {:input [[:actions/set-vault-detail-returns-benchmark-search [:event.target/value]]]
                    :focus [[:actions/set-vault-detail-returns-benchmark-suggestions-open true]]
                    :blur [[:actions/set-vault-detail-returns-benchmark-suggestions-open false]]
                    :keydown [[:actions/handle-vault-detail-returns-benchmark-search-keydown [:event/key] top-coin]]}}]]
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
                      "border-[#1f3b3c]"
                      "bg-[#081f29]"
                      "p-1"
                      "shadow-lg"
                      "z-40"]}
        (if (seq candidates*)
          (for [option candidates*]
            ^{:key (str "vault-detail-returns-benchmark-suggestion-" (:value option))}
            (returns-benchmark-suggestion-row option))
          [:div {:class ["px-2" "py-1.5" "text-xs" "text-[#8aa0a7]"]}
           (or empty-message "No matching symbols.")])])]))

(defn- returns-benchmark-chip-rail [{:keys [selected-options]}]
  (let [chips (vec (or selected-options []))]
    (when (seq chips)
      [:div {:class ["rounded-md"
                     "border"
                     "border-[#1f3b3c]"
                     "bg-[#081f29]"
                     "p-1.5"
                     "shadow-md"]
             :data-role "vault-detail-returns-benchmark-chip-rail"}
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-1.5" "pr-1"]}
        (for [{:keys [value] :as option} chips]
          ^{:key (str "vault-detail-returns-benchmark-chip-rail-item-" value)}
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
            :stroke-width 1.0
            :vector-effect "non-scaling-stroke"
            :stroke-linecap "square"
            :stroke-linejoin "miter"
            :data-role (if (= id :strategy)
                         "vault-detail-chart-path"
                         (str "vault-detail-chart-path-" (name id)))}]))

(defn- chart-series-area-layers
  [{:keys [id area-path area-fill area-positive-fill area-negative-fill zero-y-ratio]}]
  (when (seq area-path)
    (cond
      (string? area-fill)
      [:path {:d area-path
              :fill area-fill
              :data-role (if (= id :strategy)
                           "vault-detail-chart-area"
                           (str "vault-detail-chart-area-" (name id)))}]

      (and (string? area-positive-fill)
           (string? area-negative-fill)
           (number? zero-y-ratio))
      (let [clip-ratio (clamp-number zero-y-ratio 0 1)
            clip-y (max 0 (min 100 (* 100 clip-ratio)))
            positive-clip-id (str "vault-detail-chart-area-clip-positive-" (name id))
            negative-clip-id (str "vault-detail-chart-area-clip-negative-" (name id))]
        [:g {:data-role (if (= id :strategy)
                          "vault-detail-chart-area-split"
                          (str "vault-detail-chart-area-split-" (name id)))}
         [:defs
          [:clipPath {:id positive-clip-id}
           [:rect {:x 0
                   :y 0
                   :width 100
                   :height clip-y}]]
          [:clipPath {:id negative-clip-id}
           [:rect {:x 0
                   :y clip-y
                   :width 100
                   :height (- 100 clip-y)}]]]
         [:path {:d area-path
                 :fill area-positive-fill
                 :clip-path (str "url(#" positive-clip-id ")")
                 :data-role (if (= id :strategy)
                              "vault-detail-chart-area-positive"
                              (str "vault-detail-chart-area-positive-" (name id)))}]
         [:path {:d area-path
                 :fill area-negative-fill
                 :clip-path (str "url(#" negative-clip-id ")")
                 :data-role (if (= id :strategy)
                              "vault-detail-chart-area-negative"
                              (str "vault-detail-chart-area-negative-" (name id)))}]])

      :else
      nil)))

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
                     "bg-[#081f29]"
                     "px-2"
                     "py-1.5"]}
       (for [{:keys [id label stroke]} visible-series]
         ^{:key (str "vault-detail-chart-legend-item-" (name id))}
         [:div {:class ["flex" "items-center" "gap-1.5"]}
          [:span {:class ["h-2" "w-2" "rounded-full"]
                  :style {:background-color stroke}}]
          [:span {:class ["text-xs" "text-[#8aa0a7]"]}
           label]])])))

(defn chart-section
  [chart]
  (let [axis-kind (:axis-kind chart)
        y-ticks (:y-ticks chart)
        selected-series (:selected-series chart)
        series (:series chart)
        returns-benchmark* (add-benchmark-chip-colors (:returns-benchmark chart) series)
        y-axis-width (y-axis-gutter-width axis-kind y-ticks)
        plot-left (+ y-axis-width 10)
        point-count (count (:points chart))
        hovered-index (get-in chart [:hover :index])
        hovered-point (get-in chart [:hover :point])
        hover-active? (boolean (get-in chart [:hover :active?]))
        hover-line-left-pct (when hover-active?
                              (* 100 (:x-ratio hovered-point)))
        hover-tooltip-top-pct (when hover-active?
                                (clamp-number (- (* 100 (:y-ratio hovered-point)) 8)
                                              8
                                              92))
        hover-tooltip-right? (when hover-active?
                               (> hover-line-left-pct 74))
        hover-benchmark-values (when hover-active?
                                 (chart-tooltip-benchmark-values axis-kind
                                                                 hovered-index
                                                                 series))
        hover-tooltip (when hover-active?
                        (chart-tooltip-model (:selected-timeframe chart)
                                             axis-kind
                                             hovered-point))]
    [:section {:class ["rounded-2xl"
                       "border"
                       "border-[#1b393a]"
                       "bg-[#071820]"
                       "p-3"]}
     [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-2" "border-b" "border-[#1f3b3c]" "pb-2"]}
      [:div {:class ["flex" "items-center" "gap-2"]}
       (for [{:keys [value label]} (:series-tabs chart)]
         ^{:key (str "chart-series-" (name value))}
         (chart-series-button {:value value
                               :label label}
                              selected-series))]
      [:div {:class ["ml-auto" "flex" "items-center" "gap-2"]}
       (when (= selected-series :returns)
         (returns-benchmark-selector returns-benchmark*))
       (chart-timeframe-menu {:timeframe-options (:timeframe-options chart)
                              :selected-timeframe (:selected-timeframe chart)})]]
     [:div {:class ["space-y-2"]}
      [:div {:class ["relative" "mt-3" "h-[260px]"]}
       [:div {:class ["absolute" "left-0" "top-0" "bottom-0"]
              :style {:width (str y-axis-width "px")}}
        (for [{:keys [value y-ratio]} y-ticks]
          ^{:key (str "vault-chart-tick-" y-ratio "-" value)}
          [:span {:class ["absolute"
                          "right-2"
                          "-translate-y-1/2"
                          "num"
                          "text-right"
                          "text-xs"
                          "text-[#8aa0a7]"]
                  :style {:top (str (* 100 y-ratio) "%")}}
           (format-chart-axis-value axis-kind value)])
        [:div {:class ["absolute"
                       "right-0"
                       "top-0"
                       "bottom-0"
                       "border-l"
                       "border-[#1f3b3c]"]}]
        (for [{:keys [y-ratio]} y-ticks]
          ^{:key (str "vault-chart-axis-tick-" y-ratio)}
          [:div {:class ["absolute"
                         "right-0"
                         "w-1.5"
                         "border-t"
                         "border-[#1f3b3c]"]
                 :style {:top (str (* 100 y-ratio) "%")}}])]
       [:div {:class ["absolute" "right-2" "top-0" "bottom-0" "cursor-crosshair"]
              :style {:left (str plot-left "px")}
              :on {:mousemove [[:actions/set-vault-detail-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                   :mouseenter [[:actions/set-vault-detail-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                   :pointermove [[:actions/set-vault-detail-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                   :pointerenter [[:actions/set-vault-detail-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                   :mouseleave [[:actions/clear-vault-detail-chart-hover]]
                   :pointerleave [[:actions/clear-vault-detail-chart-hover]]
                   :mouseout [[:actions/clear-vault-detail-chart-hover]]}}
        [:svg {:viewBox "0 0 100 100"
               :preserveAspectRatio "none"
               :class ["h-full" "w-full"]}
         [:line {:x1 0
                 :x2 100
                 :y1 100
                 :y2 100
                 :stroke "rgba(140, 157, 165, 0.30)"
                 :stroke-width 0.8
                 :vector-effect "non-scaling-stroke"}]
         (for [{series-id :id :as series-entry} (or series [])]
           ^{:key (str "vault-detail-chart-area-" (name series-id))}
           (chart-series-area-layers series-entry))
         (for [{series-id :id :as series-entry} (or series [])]
           ^{:key (str "vault-detail-chart-path-" (name series-id))}
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
                 :data-role "vault-detail-chart-hover-tooltip"
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
                ^{:key (str "vault-detail-chart-hover-tooltip-benchmark-row-" coin)}
                [:div {:class ["grid"
                               "grid-cols-[1fr_auto]"
                               "items-center"
                               "gap-3"]
                       :data-role (str "vault-detail-chart-hover-tooltip-benchmark-row-" coin)}
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
                         :data-role (str "vault-detail-chart-hover-tooltip-benchmark-value-" coin)
                         :style {:color stroke}}
                  value]])])])]]
      (chart-legend series)
      (when (= selected-series :returns)
        (returns-benchmark-chip-rail returns-benchmark*))]]))
