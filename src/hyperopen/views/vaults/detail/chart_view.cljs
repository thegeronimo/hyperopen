(ns hyperopen.views.vaults.detail.chart-view
  (:require [clojure.string :as str]
            [hyperopen.ui.fonts :as fonts]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.chart.d3.runtime :as chart-d3-runtime]
            [hyperopen.views.vaults.detail.chart-tooltip :as chart-tooltip]))

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
        (set! (.-font context) (fonts/canvas-font 12))
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

(defn- selected-timeframe-label
  [timeframe-options selected-token]
  (or (some (fn [{:keys [value label]}]
              (when (= selected-token (timeframe-token value))
                label))
            timeframe-options)
      "24H"))

(defn- timeframe-option-button
  [{:keys [value label]} selected-token data-role-prefix]
  (let [option-token (or (timeframe-token value) "day")]
    [:button {:type "button"
              :role "menuitemradio"
              :aria-checked (= option-token selected-token)
              :class (into ["flex"
                            "w-full"
                            "items-center"
                            "justify-between"
                            "rounded-md"
                            "px-2.5"
                            "py-1.5"
                            "text-xs"
                            "transition-colors"]
                           (if (= option-token selected-token)
                             ["bg-[#123a36]" "text-[#97fce4]"]
                             ["text-[#9fb4bb]" "hover:bg-[#0d252f]" "hover:text-trading-text"]))
              :data-role (str data-role-prefix "-option-" option-token)
              :on {:click [[:actions/set-vaults-snapshot-range value]]}}
     [:span label]
     (when (= option-token selected-token)
       [:span {:aria-hidden true} "ON"])]))

(defn chart-timeframe-menu [{:keys [timeframe-options
                                    selected-timeframe
                                    data-role-prefix
                                    open?
                                    toggle-action
                                    close-action]}]
  (let [selected-token (or (timeframe-token selected-timeframe)
                           (timeframe-token (some-> timeframe-options first :value))
                           "day")
        selected-label (selected-timeframe-label timeframe-options selected-token)
        role-prefix (or data-role-prefix "vault-detail-timeframe")
        toggle-action* (or toggle-action :actions/toggle-vault-detail-chart-timeframe-dropdown)
        close-action* (or close-action :actions/close-vault-detail-chart-timeframe-dropdown)]
    [:div {:class ["relative"]
           :data-role (str role-prefix "-menu")}
     (when open?
       [:button {:type "button"
                 :class ["fixed" "inset-0" "z-20" "cursor-default"]
                 :aria-label "Close timeframe menu"
                 :on {:click [[close-action*]]}}])
     [:button {:type "button"
               :class ["relative"
                       "z-[21]"
                       "flex"
                       "h-8"
                       "items-center"
                       "gap-1.5"
                       "rounded-md"
                       "border"
                       "border-[#1f3b3c]"
                       "bg-[#071e25]"
                       "px-2.5"
                       "text-xs"
                       "text-trading-text"
                       "transition-colors"
                       "hover:bg-[#0d252f]"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"]
               :aria-expanded (boolean open?)
               :aria-haspopup "menu"
               :data-role (str role-prefix "-trigger")
               :on {:click [[toggle-action*]]}}
      [:span "Range "]
      [:span selected-label]
      [:svg {:class ["h-3.5"
                     "w-3.5"
                     "text-[#8aa0a7]"
                     "transition-transform"
                     "duration-150"
                     "ease-out"]
             :style {:transform (when open? "rotate(180deg)")}
             :viewBox "0 0 20 20"
             :fill "currentColor"
             :aria-hidden true}
       [:path {:fill-rule "evenodd"
               :clip-rule "evenodd"
               :d "M5.23 7.21a.75.75 0 0 1 1.06.02L10 11.168l3.71-3.938a.75.75 0 1 1 1.08 1.04l-4.25 4.5a.75.75 0 0 1-1.08 0l-4.25-4.5a.75.75 0 0 1 .02-1.06Z"}]]]
     [:div {:class ["ui-dropdown-panel"
                    "absolute"
                    "right-0"
                    "top-full"
                    "z-[21]"
                    "mt-1.5"
                    "min-w-[140px]"
                    "rounded-xl"
                    "border"
                    "border-[#1f3b3c]"
                    "bg-[#071e25]"
                    "p-2"
                    "shadow-2xl"]
            :data-ui-state (if open? "open" "closed")
            :role "menu"
            :data-role (str role-prefix "-options")}
      (for [{:keys [value] :as option} timeframe-options
            :let [option-token (or (timeframe-token value) "day")]]
        ^{:key (str role-prefix "-option-" option-token)}
        (timeframe-option-button option selected-token role-prefix))]]))

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
                      "spectate-lg"
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
                     "spectate-md"]
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

(def ^:private vault-chart-d3-theme
  {:data-role-prefix "vault-detail-chart"
   :baseline-stroke "rgba(140, 157, 165, 0.30)"
   :baseline-stroke-width 0.8
   :hover-line-stroke "#9fb3be"
   :line-stroke-width 1.0
   :line-linecap "square"
   :line-linejoin "miter"
   :tooltip-border-color "rgba(98, 114, 130, 0.65)"
   :tooltip-background "linear-gradient(138deg, rgba(24, 35, 47, 0.95) 0%, rgba(16, 25, 38, 0.95) 56%, rgba(43, 36, 25, 0.86) 100%)"})

(defn- vault-d3-spec
  [chart]
  (let [base-spec {:surface :vaults
                   :axis-kind (:axis-kind chart)
                   :time-range (:selected-timeframe chart)
                   :points (:points chart)
                   :series (:series chart)
                   :y-ticks (:y-ticks chart)
                   :theme vault-chart-d3-theme}]
    (assoc base-spec
           :update-key (chart-d3-runtime/spec-update-key base-spec)
           :build-tooltip (fn [hover series]
                            (chart-tooltip/build-chart-hover-tooltip (:selected-timeframe chart)
                                                                     (:selected-series chart)
                                                                     hover
                                                                     series)))))

(defn chart-section
  [chart]
  (let [axis-kind (:axis-kind chart)
        y-ticks (:y-ticks chart)
        selected-series (:selected-series chart)
        series (:series chart)
        d3-spec (vault-d3-spec chart)
        returns-benchmark* (add-benchmark-chip-colors (:returns-benchmark chart) series)
        y-axis-width (y-axis-gutter-width axis-kind y-ticks)
        plot-left (+ y-axis-width 10)]
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
                              :open? (:timeframe-menu-open? chart)
                              :toggle-action :actions/toggle-vault-detail-chart-timeframe-dropdown
                              :close-action :actions/close-vault-detail-chart-timeframe-dropdown
                              :selected-timeframe (:selected-timeframe chart)
                              :data-role-prefix "vault-detail-chart-timeframe"})]]
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
              :data-role "vault-detail-chart-plot-area"}
        [:div {:class ["h-full" "w-full"]
               :data-role "vault-detail-chart-d3-host"
               :replicant/on-render (chart-d3-runtime/on-render d3-spec)}]]]
      (chart-legend series)
      (when (= selected-series :returns)
        (returns-benchmark-chip-rail returns-benchmark*))]]))
