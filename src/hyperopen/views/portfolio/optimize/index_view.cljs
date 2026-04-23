(ns hyperopen.views.portfolio.optimize.index-view)

(defn index-view
  [_state]
  [:section {:class ["rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100/95"
                     "p-4"
                     "text-trading-text"
                     "shadow-sm"]
             :data-role "portfolio-optimizer-index"
             :data-parity-id "portfolio-optimizer-index"}
   [:div {:class ["flex" "items-start" "justify-between" "gap-4"]}
    [:div
     [:p {:class ["text-[0.65rem]"
                  "font-semibold"
                  "uppercase"
                  "tracking-[0.24em]"
                  "text-trading-muted"]}
      "Portfolio Optimizer"]
     [:h1 {:class ["mt-2" "text-2xl" "font-semibold" "tracking-tight"]}
      "Optimization Scenarios"]
     [:p {:class ["mt-2" "max-w-2xl" "text-sm" "text-trading-muted"]}
      "Local scenario board for saved, computed, executed, and partially executed optimizer runs."]]
    [:a {:class ["btn" "btn-sm" "btn-primary"]
         :href "/portfolio/optimize/new"
         :on {:click [[:actions/navigate "/portfolio/optimize/new"]]}}
     "New Scenario"]]
   [:div {:class ["mt-4"
                  "grid"
                  "grid-cols-1"
                  "gap-3"
                  "lg:grid-cols-[260px_minmax(0,1fr)]"]}
    [:aside {:class ["rounded-lg"
                     "border"
                     "border-base-300"
                     "bg-base-200/60"
                     "p-3"]
             :data-role "portfolio-optimizer-scenario-filters"}
     [:p {:class ["text-xs" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
      "Scenario Filters"]
     [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
      "Active, saved, executed, partial, and archived filters bind to optimizer-owned query params."]]
    [:div {:class ["rounded-lg"
                   "border"
                   "border-dashed"
                   "border-base-300"
                   "bg-base-200/40"
                   "p-6"
                   "text-sm"
                   "text-trading-muted"]
           :data-role "portfolio-optimizer-empty-scenarios"}
     "No local optimizer scenarios are loaded yet."]]])
