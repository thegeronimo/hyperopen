(ns hyperopen.views.portfolio.chart-view
  (:require [clojure.string :as string]
            [hyperopen.views.chart.d3.runtime :as chart-d3-runtime]
            [hyperopen.views.portfolio.format :as portfolio-format]
            [hyperopen.views.portfolio.summary-cards :as summary-cards]
            [hyperopen.views.portfolio.vm.chart-tooltip :as chart-tooltip]))

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
                      "spectate-lg"
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
                     "spectate-md"]
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

(def ^:private portfolio-chart-d3-theme
  {:data-role-prefix "portfolio-chart"
   :baseline-stroke "#28414a"
   :baseline-stroke-width 0.8
   :hover-line-stroke "#9fb3be"
   :line-stroke-width 1.4
   :line-linecap "round"
   :line-linejoin "round"
   :tooltip-border-color "rgba(98, 114, 130, 0.65)"
   :tooltip-background "linear-gradient(138deg, rgba(24, 35, 47, 0.95) 0%, rgba(16, 25, 38, 0.95) 56%, rgba(43, 36, 25, 0.86) 100%)"})

(defn- portfolio-d3-spec
  [{:keys [chart summary-time-range]}]
  (let [base-spec {:surface :portfolio
                   :axis-kind (:axis-kind chart)
                   :time-range summary-time-range
                   :points (:points chart)
                   :series (:series chart)
                   :y-ticks (:y-ticks chart)
                   :theme portfolio-chart-d3-theme}]
    (assoc base-spec
           :update-key (chart-d3-runtime/spec-update-key base-spec)
           :build-tooltip (fn [hover series]
                            (chart-tooltip/build-chart-hover-tooltip summary-time-range
                                                                     (:selected-tab chart)
                                                                     hover
                                                                     series)))))

(defn chart-card [{:keys [chart selectors]}]
  (let [{:keys [tabs selected-tab axis-kind y-ticks series]} chart
        returns-benchmark (:returns-benchmark selectors)
        returns-benchmark* (add-benchmark-chip-colors returns-benchmark series)
        y-axis-width (portfolio-format/y-axis-gutter-width axis-kind y-ticks)
        plot-left (+ y-axis-width 10)
        d3-spec (portfolio-d3-spec {:chart chart
                                    :summary-time-range (get-in selectors [:summary-time-range :value])})]
    (summary-cards/section-card
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
           (portfolio-format/format-axis-label axis-kind value)])
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
              :data-role "portfolio-chart-plot-area"}
        [:div {:class ["h-full" "w-full"]
               :data-role "portfolio-chart-d3-host"
               :replicant/on-render (chart-d3-runtime/on-render d3-spec)}]]]
      (chart-legend series)
      (when (= selected-tab :returns)
        (returns-benchmark-chip-rail returns-benchmark*))])))
