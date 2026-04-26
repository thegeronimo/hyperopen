(ns hyperopen.views.portfolio.optimize.tracking-panel)

(def trackable-statuses #{:executed :partially-executed :tracking})
(def manual-tracking-source-statuses #{:saved :computed})

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- format-pct
  [value]
  (if (finite-number? value)
    (str (.toLocaleString (* 100 value)
                          "en-US"
                          #js {:minimumFractionDigits 2
                               :maximumFractionDigits 2})
         "%")
    "N/A"))

(defn- format-usdc
  [value]
  (if (finite-number? value)
    (str "$" (.toLocaleString value
                              "en-US"
                              #js {:maximumFractionDigits 2}))
    "N/A"))

(defn- keyword-label
  [value]
  (cond
    (keyword? value) (name value)
    (some? value) (str value)
    :else "N/A"))

(defn- latest-snapshot
  [tracking-record]
  (last (vec (:snapshots tracking-record))))

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
   [:span {:class ["font-semibold" "text-trading-text"]} (:instrument-id row)]
   [:span (format-pct (:current-weight row))]
   [:span (format-pct (:target-weight row))]
   [:span (format-pct (:weight-drift row))]
   [:span (format-usdc (:signed-notional-usdc row))]])

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
  [rows]
  [:section {:class ["mt-4" "rounded-lg" "border" "border-base-300" "bg-base-200/30" "p-3"]
             :data-role "portfolio-optimizer-drift-chart"}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    "Drift Chart"]
   (into
    [:div {:class ["mt-3" "space-y-2"]}]
    (map (fn [row]
           (let [drift (:weight-drift row)
                 width (if (finite-number? drift)
                         (min 100 (* 1000 (js/Math.abs drift)))
                         0)]
             [:div {:class ["grid" "grid-cols-[8rem_minmax(0,1fr)_4rem]" "items-center" "gap-2" "text-xs"]}
              [:span {:class ["truncate" "font-semibold"]} (:instrument-id row)]
              [:div {:class ["h-2" "overflow-hidden" "rounded-full" "bg-base-300"]}
               [:div {:class ["h-full" "rounded-full" "bg-primary/70"]
                      :style {:width (str width "%")}}]]
              [:span {:class ["text-right" "tabular-nums"]} (format-pct drift)]]))
         rows))])

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
        [:p {:class ["mt-1" "tabular-nums"]} (str "Realized " (format-pct (:realized-return snapshot)))]
        [:p {:class ["tabular-nums"]} (str "Predicted " (format-pct (:predicted-return snapshot)))]])
     snapshots))])

(defn- active-scenario-id
  [state]
  (or (get-in state [:portfolio :optimizer :active-scenario :loaded-id])
      (get-in state [:portfolio :optimizer :draft :id])))

(defn- manual-tracking-panel
  [state]
  (let [enableable? (some? (active-scenario-id state))]
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
  (let [scenario-status (get-in state [:portfolio :optimizer :active-scenario :status])
        tracking-record (get-in state [:portfolio :optimizer :tracking])
        latest (latest-snapshot tracking-record)]
    (cond
      (contains? manual-tracking-source-statuses scenario-status)
      (manual-tracking-panel state)

      (contains? trackable-statuses scenario-status)
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
        (if latest
          [:div {:class ["mt-4" "grid" "grid-cols-2" "gap-2" "lg:grid-cols-6"]}
           (metric-card "Status" (keyword-label (:status latest)))
           (metric-card "Weight Drift RMS" (format-pct (:weight-drift-rms latest)))
           (metric-card "Max Drift" (format-pct (:max-abs-weight-drift latest)))
           (metric-card "Predicted Return" (format-pct (:predicted-return latest)))
           (metric-card "Predicted Vol" (format-pct (:predicted-volatility latest)))
           (metric-card "Realized Return" (format-pct (:realized-return latest)))]
          [:p {:class ["mt-4" "rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3" "text-sm" "text-trading-muted"]}
           "No tracking snapshots yet. Refresh tracking after execution to measure weight drift."])]
       (when latest
         (concat [(drift-chart (:rows latest))
                  (tracking-path (:snapshots tracking-record))
                  (tracking-header-row)]
                 (map-indexed tracking-row (:rows latest))))))))
