(ns hyperopen.views.portfolio.optimize.setup-v4-header)

(def ^:private eyebrow-class
  ["font-mono" "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"])

(defn- route-title
  [route]
  (case (:kind route)
    :optimize-new "Untitled scenario"
    :optimize-scenario (str "Scenario " (:scenario-id route))
    "Optimizer scenario"))

(defn- active-preset
  [draft]
  (let [objective-kind (get-in draft [:objective :kind])
        return-kind (get-in draft [:return-model :kind])]
    (cond
      (= :black-litterman return-kind) :use-my-views
      (= :max-sharpe objective-kind) :risk-adjusted
      :else :conservative)))

(defn setup-header
  [{:keys [draft route running? run-triggerable? saving-scenario? solved-run? result-path]}]
  [:header {:class ["border" "border-base-300" "bg-base-100/90" "px-3" "py-2"]
            :data-role "portfolio-optimizer-setup-header"}
   [:div {:class ["flex" "items-center" "justify-between" "gap-4"]}
    [:div {:class ["min-w-0"]}
     [:p {:class eyebrow-class} "Optimizer · portfolio / optimize / new"]
     [:div {:class ["mt-1" "flex" "flex-wrap" "items-center" "gap-2"]}
      [:h1 {:class ["text-lg" "font-semibold" "tracking-tight" "text-trading-text"]}
       (route-title route)]
      [:span {:class ["text-sm" "text-trading-muted"]}
       "- configure your target portfolio"]
      [:span {:class ["border" "border-base-300" "bg-base-200/40" "px-2" "py-0.5"
                      "font-mono" "text-[0.6rem]" "font-semibold" "uppercase"
                      "tracking-[0.12em]" "text-trading-muted"]
              :data-role "portfolio-optimizer-setup-status-tag"}
       (if (= :computed (:status draft)) "computed" "draft")]]
     [:span {:class ["sr-only"]
             :data-role "portfolio-optimizer-draft-state"}
      (if (get-in draft [:metadata :dirty?])
        "Draft has unsaved changes"
        "Draft clean")]]
    [:div {:class ["flex" "shrink-0" "items-center" "gap-2"]}
     [:button {:type "button"
               :class ["border" "border-base-300" "bg-base-200/20" "px-2.5" "py-1"
                       "font-mono" "text-xs" "font-semibold" "text-trading-muted"]
               :aria-label "More setup actions"
               :data-role "portfolio-optimizer-setup-overflow"}
      "..."]
     [:button {:type "button"
               :class ["border" "border-base-300" "bg-base-200/30" "px-2.5" "py-1"
                       "text-xs" "font-semibold" "text-trading-text"
                       "disabled:cursor-not-allowed" "disabled:text-trading-muted"]
               :data-role "portfolio-optimizer-save-scenario"
               :disabled (or (not solved-run?) saving-scenario?)
               :on {:click [[:actions/save-portfolio-optimizer-scenario-from-current]]}}
      (if saving-scenario? "Saving" "Save draft")]
     [:button {:type "button"
               :class ["border" "border-warning/60" "bg-warning/10" "px-2.5" "py-1"
                       "text-xs" "font-semibold" "text-warning"
                       "disabled:cursor-not-allowed" "disabled:border-base-300"
                       "disabled:bg-base-200/30" "disabled:text-trading-muted"]
               :data-role "portfolio-optimizer-run-draft"
               :disabled (not run-triggerable?)
               :on {:click [[:actions/run-portfolio-optimizer-from-draft]]}}
      (if running? "Running Optimization" "Run optimization")]
     (when solved-run?
       [:button {:type "button"
                 :class ["border" "border-warning/60" "bg-warning/10" "px-2.5" "py-1"
                         "text-xs" "font-semibold" "text-warning"]
                 :data-role "portfolio-optimizer-view-weights"
                 :on {:click [[:actions/navigate result-path]]}}
        "View weights"])]]])

(defn- preset-card
  [draft preset title subtitle kicker]
  (let [selected? (= preset (active-preset draft))]
    [:button {:type "button"
              :class (cond-> ["border" "border-base-300" "bg-base-100/70" "px-3" "py-2.5" "text-left"
                              "transition-colors" "hover:border-warning/50"]
                       selected? (conj "border-warning/70" "bg-warning/10"))
              :aria-pressed (str selected?)
              :data-role (str "portfolio-optimizer-setup-preset-" (name preset))
              :on {:click [[:actions/apply-portfolio-optimizer-setup-preset preset]]}}
     [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
      [:div
       [:p {:class ["text-xs" "font-semibold" (if selected? "text-warning" "text-trading-text")]}
        (str (if selected? "◉ " "○ ") title)]
       [:p {:class ["mt-1.5" "text-[0.68rem]" "text-trading-muted"]} subtitle]
       [:p {:class ["mt-1.5" "font-mono" "text-[0.55rem]" "uppercase" "tracking-[0.16em]"
                    "text-trading-muted"]}
        kicker]]
      (when selected?
        [:span {:class ["border" "border-base-300" "px-1.5" "py-0.5" "font-mono"
                        "text-[0.55rem]" "uppercase" "tracking-[0.12em]" "text-trading-muted"]}
         "default"])]]))

(defn preset-row
  [draft]
  [:section {:class ["border" "border-base-300" "bg-base-100/80" "px-3" "py-2.5"]
             :data-role "portfolio-optimizer-setup-preset-row"}
   [:div {:class ["grid" "grid-cols-1" "gap-2" "xl:grid-cols-[82px_minmax(0,1fr)]"]}
    [:p {:class (conj eyebrow-class "pt-1.5")} "Start with"]
    [:div {:class ["grid" "grid-cols-1" "gap-2" "lg:grid-cols-3"]}
     (preset-card draft :conservative "Conservative"
                  "Minimum variance - stabilized historical returns"
                  "Recommended for first runs")
     (preset-card draft :risk-adjusted "Risk-adjusted"
                  "Maximum Sharpe - stabilized historical returns"
                  "Best risk-adjusted return")
     (preset-card draft :use-my-views "Use my views"
                  "Combine the market reference with your absolute / relative beliefs"
                  "For experienced users")]]])
