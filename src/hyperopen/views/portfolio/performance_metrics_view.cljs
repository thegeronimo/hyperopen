(ns hyperopen.views.portfolio.performance-metrics-view
  (:require [clojure.string :as string]
            [hyperopen.views.chart.range-strip :as chart-range-strip]
            [hyperopen.views.portfolio.format :as portfolio-format]
            [hyperopen.views.portfolio.low-confidence :as low-confidence]
            [hyperopen.views.portfolio.summary-cards :as summary-cards]
            [hyperopen.views.ui.performance-metrics-tooltip :as metrics-tooltip]))

(defn- performance-metric-value-cell
  ([kind value]
   (performance-metric-value-cell kind value nil))
  ([kind value {:keys [status] :as attrs}]
   (let [attrs* (dissoc attrs :status :reason :metric-label :metric-description)
         formatted-value (portfolio-format/format-metric-value kind value)
         tone-class (if (= status :low-confidence)
                      "text-trading-text-secondary"
                      "text-trading-text")]
     [:span (merge {:class ["justify-self-start"
                            "text-left"
                            "text-sm"
                            tone-class]}
                   attrs*)
      [:span {:class (into []
                           (when (not= kind :date)
                             ["num"]))}
       formatted-value]])))

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

(defn- benchmark-row-status
  [row coin]
  (let [statuses (:benchmark-statuses row)]
    (if (and (map? statuses)
             (contains? statuses coin))
      (get statuses coin)
      (:benchmark-status row))))

(defn- benchmark-row-reason
  [row coin]
  (let [reasons (:benchmark-reasons row)]
    (if (and (map? reasons)
             (contains? reasons coin))
      (get reasons coin)
      (:benchmark-reason row))))

(defn- performance-metric-row-reasons
  [row benchmark-columns]
  (low-confidence/ordered-low-confidence-reasons
   (concat [(when (= :low-confidence (:portfolio-status row))
              (:portfolio-reason row))]
           (keep (fn [{:keys [coin]}]
                   (when (= :low-confidence (benchmark-row-status row coin))
                     (benchmark-row-reason row coin)))
                 benchmark-columns))))

(defn- performance-metric-row-estimated?
  [row benchmark-columns]
  (boolean (seq (performance-metric-row-reasons row benchmark-columns))))

(defn- visible-low-confidence-reasons
  [groups benchmark-columns]
  (low-confidence/ordered-low-confidence-reasons
   (mapcat #(performance-metric-row-reasons % benchmark-columns)
           (mapcat :rows groups))))

(defn- metric-value-present?
  [kind value]
  (not= "--" (portfolio-format/format-metric-value kind value)))

(defn- performance-metric-row-visible?
  [{:keys [kind value] :as row} benchmark-columns]
  (let [portfolio-value (if (contains? row :portfolio-value)
                          (:portfolio-value row)
                          value)]
    (or (metric-value-present? kind portfolio-value)
        (some (fn [{:keys [coin]}]
                (metric-value-present? kind (benchmark-row-value row coin)))
              benchmark-columns))))

(def ^:private default-metric-column-width
  "220px")

(def ^:private custom-range-metric-column-width
  "Room for a custom span in the header chip — see the vault twin for why the
  column has to be widened explicitly rather than content-sized."
  "320px")

(defn- performance-metrics-grid-style
  [benchmark-column-count metric-column-width]
  {:grid-template-columns (string/join " "
                                       (concat [(or metric-column-width
                                                    default-metric-column-width)]
                                               (repeat benchmark-column-count "132px")
                                               ["132px"]))})

(defn- performance-metric-row [{:keys [key label description kind value] :as row} benchmark-columns grid-style]
  (let [portfolio-value (if (contains? row :portfolio-value)
                          (:portfolio-value row)
                          value)
        estimated-row? (performance-metric-row-estimated? row benchmark-columns)]
    [:div {:class ["grid"
                   "min-w-full"
                   "w-max"
                   "items-center"
                   "justify-items-start"
                   "gap-3"
                   "hover:bg-base-300"]
           :style grid-style
           :data-role (str "portfolio-performance-metric-" (name key))}
     [:span {:class ["group"
                     "relative"
                     "inline-flex"
                     "items-center"
                     "gap-1"
                     "text-sm"
                     (if estimated-row?
                       "text-ho-text-muted"
                       "text-ho-text")]
             :data-role (str "portfolio-performance-metric-" (name key) "-label")}
      label
      (when estimated-row?
        [:span {:class ["text-xs" "font-semibold" "leading-none" "text-[#7fb5ff]"]
                :data-role (str "portfolio-performance-metric-" (name key) "-estimated-mark")}
         "~"])
      (metrics-tooltip/metric-label-tooltip label
                                            description
                                            (str "portfolio-performance-metric-" (name key) "-label"))]
     (for [{:keys [coin]} benchmark-columns]
       (let [cell-data-role (str "portfolio-performance-metric-" (name key) "-benchmark-value-" coin)]
         ^{:key (str "portfolio-performance-metric-" (name key) "-benchmark-" coin)}
         (performance-metric-value-cell kind
                                        (benchmark-row-value row coin)
                                        {:status (benchmark-row-status row coin)
                                         :data-role cell-data-role})))
     (performance-metric-value-cell kind
                                    portfolio-value
                                    {:status (:portfolio-status row)
                                     :data-role (str "portfolio-performance-metric-" (name key) "-portfolio-value")})]))

(defn performance-metrics-card [{:keys [range-strip
                                        loading?
                                        stale?
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
        grid-style (performance-metrics-grid-style
                    (count benchmark-columns*)
                    (when (:active? (:custom time-range-selector))
                      custom-range-metric-column-width))
        visible-groups (->> (or groups [])
                            (keep (fn [{:keys [rows] :as group}]
                                    (let [rows* (->> (or rows [])
                                                     (filter #(performance-metric-row-visible? % benchmark-columns*))
                                                     vec)]
                                      (when (seq rows*)
                                        (assoc group :rows rows*)))))
                            vec)
        visible-reasons (visible-low-confidence-reasons visible-groups benchmark-columns*)]
    [:div {:class ["flex" "h-full" "min-h-0" "flex-col" "relative"]
           :data-role "portfolio-performance-metrics-card"}
     (when loading?
       [:div {:class ["absolute" "inset-0" "z-10" "flex" "items-center" "justify-center" "bg-base-100/65" "backdrop-blur-sm"]
              :data-role "portfolio-performance-metrics-loading-overlay"
              :role "status"
              :aria-live "polite"}
        [:div {:class ["flex" "max-w-[240px]" "flex-col" "items-center" "gap-2.5" "px-4" "text-center"]}
         [:span {:class ["ho-spinner" "ho-spinner-lg"]
                 :aria-hidden true}]
         [:span {:class ["text-sm" "font-medium" "text-trading-text"]}
          "Calculating performance metrics"]
         [:span {:class ["text-xs" "leading-5" "text-trading-text-secondary"]}
          "Returns stay visible while the remaining analytics finish in the background."]]])
     [:div {:class ["flex-1"
                    "min-h-0"
                    "overflow-x-auto"
                    "overflow-y-auto"
                    "scrollbar-hide"]}
      [:div {:class ["sticky"
                     "top-0"
                     "z-[1]"
                     "grid"
                     "min-w-full"
                     "w-max"
                     "items-center"
                     "justify-items-start"
                     "gap-3"
                     "border-b"
                     "border-base-300"
                     "bg-base-200"
                     "px-4"
                     "py-2.5"]
             :style grid-style}
       [:div {:class ["flex" "min-w-0" "items-center" "justify-between" "gap-2"]}
        [:span {:class ["text-xs" "font-medium" "uppercase" "tracking-wide" "text-trading-text-secondary"]
                :data-role "portfolio-performance-metrics-metric-label"}
         "Metric"]
        (when (map? time-range-selector)
          [:div {:class ["flex" "items-center" "gap-1.5"]}
           ;; The numbers below stay on screen while the new window is
           ;; computed — blanking them is the churn this work removed. Without
           ;; this chip they would sit under the NEW range label with nothing
           ;; saying they belong to the old one, which is the dishonest half of
           ;; that trade. Always rendered (it hides itself) so the surrounding
           ;; flex row never reflows when it toggles.
           [:span {:class (into ["ho-spinner" "ho-spinner-sm" "text-trading-text-secondary"]
                                (when-not stale? ["invisible"]))
                   :aria-hidden true}]
           [:span {:class (into ["text-xs" "font-medium" "uppercase" "tracking-wide" "text-trading-text-secondary"]
                                (when-not stale? ["invisible"]))
                   :data-role "portfolio-performance-metrics-stale-badge"}
            "Updating…"]
           [:span {:class ["text-xs" "font-medium" "uppercase" "tracking-wide" "text-trading-text-secondary"]}
            "Range"]
           (summary-cards/summary-selector time-range-selector
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
      [:div {:class ["space-y-2.5" "px-4" "py-3"]}
       ;; The tearsheet edits the same window as the chart, so Custom... here
       ;; reveals a strip HERE rather than one on a card the trader may have
       ;; scrolled past. Always rendered (it hides itself) to keep it out of the
       ;; nil-hole shape that freezes Replicant's renderer.
       (chart-range-strip/range-strip
        {:model range-strip
         :label "Portfolio metrics"
         :data-role-prefix "portfolio-performance-metrics-range-strip"
         :actions {:start-action :actions/start-portfolio-summary-custom-range-drag
                   :update-action :actions/update-portfolio-summary-custom-range-drag
                   :end-action :actions/end-portfolio-summary-custom-range-drag
                   :done-action :actions/close-portfolio-summary-custom-range}})
       (low-confidence/estimated-metrics-banner visible-reasons)
       (for [[idx {:keys [id rows]}] (map-indexed vector visible-groups)]
         ^{:key (str "portfolio-performance-metrics-group-" (name id))}
         [:div {:class (into ["min-w-full" "w-max" "space-y-1.5"]
                             (when (pos? idx)
                               ["border-t" "border-base-300" "pt-2.5"]))
                :data-role (str "portfolio-performance-metrics-group-" (name id))}
          (for [{:keys [key] :as row} rows]
            ^{:key (str "portfolio-performance-metric-row-" (name key))}
            (performance-metric-row row benchmark-columns* grid-style))])]]]))
