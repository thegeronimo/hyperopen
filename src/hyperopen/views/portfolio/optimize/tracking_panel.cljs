(ns hyperopen.views.portfolio.optimize.tracking-panel
  (:require [hyperopen.portfolio.optimizer.application.view-model :as optimizer-view-model]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(defn- metric-card
  [label value]
  [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/50" "p-3"]}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    label]
   [:p {:class ["mt-2" "text-lg" "font-semibold" "tabular-nums"]}
    value]])

(defn- tracking-row
  [idx row]
  [:div {:class ["grid"
                 "grid-cols-[minmax(8rem,1.1fr)_repeat(4,minmax(5rem,0.8fr))]"
                 "gap-3"
                 "rounded-lg"
                 "border"
                 "border-base-300"
                 "bg-base-200/40"
                 "p-3"
                 "text-xs"
                 "tabular-nums"]
         :data-role (str "portfolio-optimizer-tracking-row-" idx)}
   [:span {:class ["font-semibold" "text-trading-text"]}
    (:instrument-label row)]
   [:span (opt-format/format-pct (:current-weight row))]
   [:span (opt-format/format-pct (:target-weight row))]
   [:span (opt-format/format-pct (:weight-drift row))]
   [:span (opt-format/format-usdc (:signed-notional-usdc row))]])

(defn- tracking-header-row
  []
  [:div {:class ["grid"
                 "grid-cols-[minmax(8rem,1.1fr)_repeat(4,minmax(5rem,0.8fr))]"
                 "gap-3"
                 "rounded-lg"
                 "border"
                 "border-base-300"
                 "bg-base-200/40"
                 "p-3"
                 "text-xs"
                 "tabular-nums"]}
   [:span {:class ["font-semibold" "text-trading-muted"]} "Instrument"]
   [:span {:class ["font-semibold" "text-trading-muted"]} "Current"]
   [:span {:class ["font-semibold" "text-trading-muted"]} "Target"]
   [:span {:class ["font-semibold" "text-trading-muted"]} "Drift"]
   [:span {:class ["font-semibold" "text-trading-muted"]} "Notional"]])

(defn- drift-chart
  [rows max-drift]
  (let [scale (max (if (opt-format/finite-number? max-drift) (js/Math.abs max-drift) 0)
                   0.0001)]
    [:section {:class ["mt-4" "rounded-lg" "border" "border-base-300" "bg-base-200/30" "p-3"]
               :data-role "portfolio-optimizer-drift-chart"}
     [:div {:class ["flex" "items-baseline" "justify-between" "gap-2"]}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
       "Drift Chart"]
      [:span {:class ["font-mono" "text-[0.6rem]" "tabular-nums" "text-trading-muted/70"]}
       (str "full bar = " (opt-format/format-pct scale))]]
     (into
      [:div {:class ["mt-3" "space-y-2"]}]
      (map (fn [row]
             (let [drift (:weight-drift row)
                   width (if (opt-format/finite-number? drift)
                           (min 100 (* 100 (/ (js/Math.abs drift) scale)))
                           0)]
               [:div {:class ["grid" "grid-cols-[8rem_minmax(0,1fr)_4rem]" "items-center" "gap-2" "text-xs"]}
                [:span {:class ["truncate" "font-semibold"]}
                 (:instrument-label row)]
                [:div {:class ["h-2" "overflow-hidden" "rounded-full" "bg-base-300"]}
                 [:div {:class ["h-full" "rounded-full" "bg-primary/70"]
                        :style {:width (str width "%")}}]]
                [:span {:class ["text-right" "tabular-nums"]} (opt-format/format-pct drift)]]))
           rows))]))

(defn- tracking-path
  [snapshots]
  [:section {:class ["mt-4" "rounded-lg" "border" "border-base-300" "bg-base-200/30" "p-3"]
             :data-role "portfolio-optimizer-tracking-path"}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    "Realized vs Predicted"]
   (into
    [:div {:class ["mt-3" "grid" "grid-cols-1" "gap-2" "lg:grid-cols-3"]}]
    (map-indexed
     (fn [idx snapshot]
       [:div {:class ["rounded-md" "border" "border-base-300" "bg-base-100/80" "p-2" "text-xs"]}
        [:p {:class ["font-semibold" "uppercase" "tracking-[0.14em]" "text-trading-muted"]}
         (str "Snapshot " (inc idx))]
        [:p {:class ["mt-1" "tabular-nums"]} (str "Realized " (opt-format/format-pct (:realized-return snapshot)))]
        [:p {:class ["tabular-nums"]} (str "Predicted " (opt-format/format-pct (:predicted-return snapshot)))]])
     snapshots))])

(defn- manual-tracking-panel
  [model]
  (let [enableable? (:manual-tracking-enableable? model)]
    [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
               :data-role "portfolio-optimizer-tracking-panel"}
     [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
      "Tracking Not Active"]
     [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
      (if enableable?
        "Tracking starts after execution or after explicit manual enablement for read-only scenario review."
        "Save scenario before enabling manual tracking.")]
     [:button (cond-> {:type "button"
                       :class ["mt-4" "rounded-lg" "border" "border-primary/50" "bg-primary/10" "px-3" "py-2"
                               "text-xs" "font-semibold" "text-primary"]
                       :data-role "portfolio-optimizer-enable-manual-tracking"}
                enableable?
                (assoc :on {:click [[:actions/enable-portfolio-optimizer-manual-tracking]]})

                (not enableable?)
                (assoc :disabled true
                       :class ["mt-4" "rounded-lg" "border" "border-base-300" "bg-base-200/40" "px-3" "py-2"
                               "text-xs" "font-semibold" "text-trading-muted" "opacity-60"]))
      "Enable Manual Tracking"]]))

(defn tracking-panel
  [state]
  (let [{:keys [manual-tracking?
                trackable?
                tracking-record
                latest-snapshot
                latest-rows] :as model} (optimizer-view-model/tracking-model state)]
    (cond
      manual-tracking?
      (manual-tracking-panel model)

      trackable?
      (into
       [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
                  :data-role "portfolio-optimizer-tracking-panel"}
        [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-3"]}
         [:div
          [:p {:class ["text-[0.65rem]"
                       "font-semibold"
                       "uppercase"
                       "tracking-[0.24em]"
                       "text-trading-muted"]}
           "Tracking"]
          [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
           "Weight drift is measured against the saved target after execution."]]
         [:button {:type "button"
                   :class ["rounded-lg"
                           "border"
                           "border-primary/50"
                           "bg-primary/10"
                           "px-3"
                           "py-2"
                           "text-xs"
                           "font-semibold"
                           "text-primary"]
                   :data-role "portfolio-optimizer-refresh-tracking"
                   :on {:click [[:actions/refresh-portfolio-optimizer-tracking]]}}
          "Refresh Tracking"]
         [:button {:type "button"
                   :class ["rounded-lg"
                           "border"
                           "border-base-300"
                           "bg-base-200/50"
                           "px-3"
                           "py-2"
                           "text-xs"
                           "font-semibold"
                           "text-trading-text"]
                   :data-role "portfolio-optimizer-reoptimize-current"
                   :on {:click [[:actions/run-portfolio-optimizer-from-draft]]}}
          "Re-optimize From Current"]]
        (if latest-snapshot
          [:div {:class ["mt-4" "grid" "grid-cols-2" "gap-2" "lg:grid-cols-6"]}
           (metric-card "Status" (opt-format/keyword-label (:status latest-snapshot)))
           (metric-card "Weight Drift RMS" (opt-format/format-pct (:weight-drift-rms latest-snapshot)))
           (metric-card "Max Drift" (opt-format/format-pct (:max-abs-weight-drift latest-snapshot)))
           (metric-card "Predicted Return" (opt-format/format-pct (:predicted-return latest-snapshot)))
           (metric-card "Predicted Vol" (opt-format/format-pct (:predicted-volatility latest-snapshot)))
           (metric-card "Realized Return" (opt-format/format-pct (:realized-return latest-snapshot)))]
          [:p {:class ["mt-4" "rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3" "text-sm" "text-trading-muted"]}
           "No tracking snapshots yet. Refresh tracking after execution to measure weight drift."])]
       (when latest-snapshot
         (concat [(drift-chart latest-rows (:max-abs-weight-drift latest-snapshot))
                  (tracking-path (:snapshots tracking-record))
                  (tracking-header-row)]
                 (map-indexed tracking-row latest-rows)))))))
