(ns hyperopen.views.vaults.detail.activity.performance-metrics
  (:require [clojure.string :as str]
            [hyperopen.views.ui.performance-metrics-tooltip :as metrics-tooltip]
            [hyperopen.views.vaults.detail.chart-view :as chart]
            [hyperopen.views.vaults.detail.format :as vf]))

(defn format-signed-percent-from-decimal [value]
  (when (vf/finite-number? value)
    (let [pct (* value 100)
          rounded (/ (js/Math.round (* pct 100)) 100)
          rounded* (if (== rounded -0) 0 rounded)
          sign (cond
                 (pos? rounded*) "+"
                 (neg? rounded*) "-"
                 :else "")
          magnitude (.toFixed (js/Math.abs rounded*) 2)]
      (str sign magnitude "%"))))

(defn- format-ratio-value [value]
  (when (vf/finite-number? value)
    (.toFixed value 2)))

(defn- format-integer-value [value]
  (when (vf/finite-number? value)
    (str (js/Math.round value))))

(defn format-metric-value [kind value]
  (case kind
    :percent (or (format-signed-percent-from-decimal value) "--")
    :ratio (or (format-ratio-value value) "--")
    :integer (or (format-integer-value value) "--")
    :date (if (and (string? value)
                   (seq (str/trim value)))
            value
            "--")
    "--"))

(defn- low-confidence-metric-title
  [reason]
  (case reason
    :daily-coverage-gate-failed "Estimated from incomplete daily coverage."
    :psr-gate-failed "Estimated from limited daily history."
    :drawdown-reliability-gate-failed "Estimated from sparse drawdown observations."
    :drawdown-unavailable "Estimated from sparse drawdown observations."
    :rolling-window-span-insufficient "Estimated from limited history in this window."
    :benchmark-coverage-gate-failed "Estimated from limited benchmark overlap."
    "Low-confidence estimate."))

(def ^:private low-confidence-reason-order
  [:daily-coverage-gate-failed
   :psr-gate-failed
   :drawdown-reliability-gate-failed
   :drawdown-unavailable
   :rolling-window-span-insufficient
   :benchmark-coverage-gate-failed])

(defn- ordered-low-confidence-reasons
  [reasons]
  (let [reason-set (disj (set reasons) nil)
        known-reasons (filter reason-set low-confidence-reason-order)
        unknown-reasons (sort (remove (set low-confidence-reason-order) reason-set))]
    (vec (concat known-reasons unknown-reasons))))

(defn- low-confidence-banner-summary
  [reasons]
  (let [reason-set (set reasons)]
    (cond
      (empty? reason-set)
      nil

      (every? #{:daily-coverage-gate-failed :psr-gate-failed} reason-set)
      "Some metrics are estimated from incomplete daily data."

      (every? #{:drawdown-reliability-gate-failed :drawdown-unavailable} reason-set)
      "Some metrics are estimated from sparse drawdown data."

      (= reason-set #{:benchmark-coverage-gate-failed})
      "Some metrics are estimated from limited benchmark overlap."

      :else
      "Some metrics are estimated from incomplete or limited data.")))

(defn- low-confidence-info-icon
  [classes]
  [:svg {:viewBox "0 0 20 20"
         :fill "none"
         :stroke "currentColor"
         :class classes
         :aria-hidden true}
   [:circle {:cx "10"
             :cy "10"
             :r "7.25"
             :stroke-width "1.6"}]
   [:path {:d "M10 8.2v4.1"
           :stroke-linecap "round"
           :stroke-width "1.6"}]
   [:circle {:cx "10"
             :cy "5.8"
             :r "0.95"
             :fill "currentColor"
             :stroke "none"}]])

(defn- estimated-metrics-banner
  [reasons]
  (when-let [summary (low-confidence-banner-summary reasons)]
    [:div {:class ["group"
                   "relative"
                   "rounded-lg"
                   "border"
                   "px-3"
                   "py-2.5"]
           :style {:border-color "rgba(66, 102, 128, 0.52)"
                   :background "linear-gradient(135deg, rgba(18, 53, 79, 0.58) 0%, rgba(14, 40, 61, 0.52) 100%)"}
           :data-role "vault-detail-performance-metrics-estimated-banner"
           :tab-index 0}
     [:div {:class ["flex" "min-w-0" "items-start" "gap-2.5"]}
      (low-confidence-info-icon ["mt-0.5" "h-4" "w-4" "shrink-0" "text-[#8fc7ff]"])
      [:div {:class ["min-w-0" "text-sm" "leading-5" "text-[#d5e8ff]"]}
       summary]]
     (metrics-tooltip/estimated-banner-tooltip reasons
                                               "vault-detail-performance-metrics-estimated-banner"
                                               low-confidence-metric-title)]))

(defn- performance-metric-value-cell
  ([kind value]
   (performance-metric-value-cell kind value nil))
  ([kind value {:keys [status] :as attrs}]
   (let [attrs* (dissoc attrs :status :reason :metric-label :metric-description)
         formatted-value (format-metric-value kind value)
         tone-class (if (= status :low-confidence)
                      "text-[#9fb4bb]"
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

(defn resolved-benchmark-metric-columns
  [{:keys [benchmark-columns benchmark-selected? benchmark-label benchmark-coin]}]
  (let [columns (->> (or benchmark-columns [])
                     (keep (fn [{:keys [coin label]}]
                             (let [coin* (some-> coin str str/trim)
                                   label* (some-> label str str/trim)]
                               (when (seq coin*)
                                 {:coin coin*
                                  :label (or label* coin*)}))))
                     vec)]
    (if (seq columns)
      columns
      [{:coin (or (some-> benchmark-coin str str/trim)
                  "__benchmark__")
        :label (if benchmark-selected?
                 (or benchmark-label "Benchmark")
                 "Benchmark")}])))

(defn benchmark-row-value
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
  (ordered-low-confidence-reasons
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
  (ordered-low-confidence-reasons
   (mapcat #(performance-metric-row-reasons % benchmark-columns)
           (mapcat :rows groups))))

(defn- metric-value-present?
  [kind value]
  (not= "--" (format-metric-value kind value)))

(defn performance-metric-row-visible?
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
  {:grid-template-columns (str/join " "
                                    (concat ["220px"]
                                            (repeat benchmark-column-count "132px")
                                            ["132px"]))})

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- performance-metric-row [{:keys [key label description kind value] :as row} benchmark-columns grid-style]
  (let [portfolio-value (if (contains? row :portfolio-value)
                          (:portfolio-value row)
                          value)
        estimated-row? (performance-metric-row-estimated? row benchmark-columns)]
    [:div {:class ["grid"
                   "items-center"
                   "justify-items-start"
                   "gap-3"
                   "hover:bg-[#0e2630]"]
           :style grid-style
           :data-role (str "vault-detail-performance-metric-" (name key))}
     [:span {:class ["group"
                     "relative"
                     "inline-flex"
                     "items-center"
                     "gap-1"
                     "text-sm"]
             :style {:color (if estimated-row?
                              "#95A8B0"
                              "#F6FEFD")}
             :data-role (str "vault-detail-performance-metric-" (name key) "-label")}
      label
      (when estimated-row?
        [:span {:class ["text-xs" "font-semibold" "leading-none" "text-[#8fc7ff]"]
                :data-role (str "vault-detail-performance-metric-" (name key) "-estimated-mark")}
         "~"])
      (metrics-tooltip/metric-label-tooltip label
                                            description
                                            (str "vault-detail-performance-metric-" (name key) "-label"))]
     (for [{:keys [coin]} benchmark-columns]
       (let [cell-data-role (str "vault-detail-performance-metric-" (name key) "-benchmark-value-" coin)]
         ^{:key (str "vault-detail-performance-metric-" (name key) "-benchmark-" coin)}
         (performance-metric-value-cell kind
                                        (benchmark-row-value row coin)
                                        {:status (benchmark-row-status row coin)
                                         :data-role cell-data-role})))
     (performance-metric-value-cell kind
                                    portfolio-value
                                    {:status (:portfolio-status row)
                                     :data-role (str "vault-detail-performance-metric-" (name key) "-vault-value")})]))

(defn performance-metrics-card [{:keys [benchmark-selected?
                                        benchmark-label
                                        benchmark-columns
                                        benchmark-coin
                                        vault-label
                                        loading?
                                        groups
                                        timeframe-options
                                        timeframe-menu-open?
                                        selected-timeframe]}]
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
                            vec)
        visible-reasons (visible-low-confidence-reasons visible-groups benchmark-columns*)]
    [:div {:class ["relative" "flex" "flex-1" "min-h-0" "flex-col"]
           :data-role "vault-detail-performance-metrics-card"}
     (when loading?
       [:div {:class ["absolute" "inset-0" "z-10" "flex" "items-center" "justify-center" "bg-[#071820]/70" "backdrop-blur-sm"]
              :data-role "vault-detail-performance-metrics-loading-overlay"
              :role "status"
              :aria-live "polite"}
        [:div {:class ["flex" "max-w-[250px]" "flex-col" "items-center" "gap-2.5" "px-4" "text-center"]}
         [:span {:class ["loading" "loading-spinner" "loading-lg" "text-[#66e3c5]"]
                 :aria-hidden true}]
         [:span {:class ["text-sm" "font-medium" "text-trading-text"]}
          "Loading benchmark history"]
         [:span {:class ["text-xs" "leading-5" "text-[#9fb4bb]"]}
          "Vault metrics stay visible while benchmark comparisons finish in the background."]]])
     [:div {:class ["grid"
                    "items-center"
                    "justify-items-start"
                    "gap-3"
                    "border-b"
                    "border-[#1f3b3c]"
                    "bg-[#0a232d]"
                    "px-4"
                    "py-2.5"]
            :style grid-style}
      [:div {:class ["flex" "min-w-0" "items-center" "justify-between" "gap-2"]}
       [:span {:class ["text-xs" "font-medium" "uppercase" "tracking-wide" "text-[#8aa0a7]"]}
        "Metric"]
       (when (and (seq timeframe-options)
                  (keyword? selected-timeframe))
         (chart/chart-timeframe-menu {:timeframe-options timeframe-options
                                      :open? timeframe-menu-open?
                                      :toggle-action :actions/toggle-vault-detail-performance-metrics-timeframe-dropdown
                                      :close-action :actions/close-vault-detail-performance-metrics-timeframe-dropdown
                                      :selected-timeframe selected-timeframe
                                      :data-role-prefix "vault-detail-performance-metrics-timeframe"}))]
      (for [[idx {:keys [coin label]}] (map-indexed vector benchmark-columns*)]
        ^{:key (str "vault-detail-performance-metrics-benchmark-label-" coin)}
        [:span {:class ["justify-self-start" "text-xs" "font-medium" "uppercase" "tracking-wide" "text-left" "text-[#8aa0a7]"]
                :data-role (if (zero? idx)
                             "vault-detail-performance-metrics-benchmark-label"
                             (str "vault-detail-performance-metrics-benchmark-label-" coin))}
         label])
      [:span {:class ["justify-self-start" "text-xs" "font-medium" "uppercase" "tracking-wide" "text-left" "text-[#8aa0a7]"]
              :data-role "vault-detail-performance-metrics-vault-label"}
       (or (non-blank-text vault-label)
           "Vault")]]
     [:div {:class ["flex-1"
                    "min-h-0"
                    "space-y-2.5"
                    "overflow-y-auto"
                    "scrollbar-hide"
                    "px-4"
                    "py-3"
                    "focus:outline-none"
                    "focus:ring-1"
                    "focus:ring-inset"
                    "focus:ring-[#2c6666]"]
            :data-role "vault-detail-performance-metrics-scroll-region"
            :role "region"
            :aria-label "Vault performance metrics"
            :tab-index 0}
      (estimated-metrics-banner visible-reasons)
      (for [[idx {:keys [id rows]}] (map-indexed vector visible-groups)]
        ^{:key (str "vault-detail-performance-metrics-group-" (name id))}
        [:div {:class (into ["space-y-1.5"]
                            (when (pos? idx)
                              ["border-t" "border-[#1f3b3c]" "pt-2.5"]))}
         (for [{:keys [key] :as row} rows]
           ^{:key (str "vault-detail-performance-metric-row-" (name key))}
           (performance-metric-row row benchmark-columns* grid-style))])]]))
