(ns hyperopen.views.portfolio.optimize.workspace-view
  (:require [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.routes :as portfolio-routes]))

(defn- metric-card
  [label value]
  [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/50" "p-3"]}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    label]
   [:p {:class ["mt-2" "text-lg" "font-semibold" "tabular-nums"]}
    value]])

(defn- format-usdc
  [value]
  (if (number? value)
    (str "$" (.toLocaleString value "en-US" #js {:maximumFractionDigits 2}))
    "N/A"))

(defn- route-title
  [route]
  (case (:kind route)
    :optimize-new "New Scenario"
    :optimize-scenario (str "Scenario " (:scenario-id route))
    "Optimizer Workspace"))

(defn workspace-view
  [state route]
  (let [snapshot (current-portfolio/current-portfolio-snapshot state)
        scenario-id (:scenario-id route)]
    [:section {:class ["grid"
                       "grid-cols-1"
                       "gap-4"
                       "text-trading-text"
                       "xl:grid-cols-[260px_minmax(0,1fr)_280px]"]
               :data-role "portfolio-optimizer-workspace"
               :data-scenario-id scenario-id}
     [:aside {:class ["rounded-xl"
                      "border"
                      "border-base-300"
                      "bg-base-100/95"
                      "p-4"]
              :data-role "portfolio-optimizer-left-rail"}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
       "Scenario"]
      [:h1 {:class ["mt-2" "text-xl" "font-semibold" "tracking-tight"]}
       (route-title route)]
      [:nav {:class ["mt-4" "space-y-2" "text-sm"]}
       [:a {:class ["block" "rounded-md" "bg-base-200/70" "px-3" "py-2"]
            :href (portfolio-routes/portfolio-optimize-new-path)
            :on {:click [[:actions/navigate
                          (portfolio-routes/portfolio-optimize-new-path)]]}}
        "Setup"]
       [:a {:class ["block" "rounded-md" "px-3" "py-2" "text-trading-muted"]
            :href (or (when scenario-id
                        (portfolio-routes/portfolio-optimize-scenario-path scenario-id))
                      (portfolio-routes/portfolio-optimize-index-path))
            :on {:click [[:actions/navigate
                          (or (when scenario-id
                                (portfolio-routes/portfolio-optimize-scenario-path scenario-id))
                              (portfolio-routes/portfolio-optimize-index-path))]]}}
        "Results"]
       [:a {:class ["block" "rounded-md" "px-3" "py-2" "text-trading-muted"]
            :href (or (when scenario-id
                        (portfolio-routes/portfolio-optimize-scenario-path scenario-id))
                      (portfolio-routes/portfolio-optimize-index-path))
            :on {:click [[:actions/navigate
                          (or (when scenario-id
                                (portfolio-routes/portfolio-optimize-scenario-path scenario-id))
                              (portfolio-routes/portfolio-optimize-index-path))]]}}
        "Diagnostics"]]]
     [:main {:class ["space-y-4"]}
      [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
             :data-role "portfolio-optimizer-setup-surface"}
       [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
        "Workspace"]
       [:h2 {:class ["mt-2" "text-2xl" "font-semibold" "tracking-tight"]}
        "Objective, Return Model, Risk Model, Constraints"]
       [:p {:class ["mt-2" "max-w-3xl" "text-sm" "text-trading-muted"]}
        "This foundation route keeps the optimizer inside the existing portfolio shell. The next UI phases replace this scaffold with the full desktop workspace from the design pack."]]
      [:div {:class ["grid" "grid-cols-1" "gap-3" "lg:grid-cols-3"]
             :data-role "portfolio-optimizer-current-summary"}
       (metric-card "NAV" (format-usdc (get-in snapshot [:capital :nav-usdc])))
       (metric-card "Gross Exposure" (format-usdc (get-in snapshot [:capital :gross-exposure-usdc])))
       (metric-card "Net Exposure" (format-usdc (get-in snapshot [:capital :net-exposure-usdc])))]
      [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
             :data-role "portfolio-optimizer-signed-exposure-table"}
       [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
        "Current Signed Exposure"]
       [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
        (str (count (:exposures snapshot)) " current exposure rows available for optimizer request assembly.")]]]
     [:aside {:class ["rounded-xl"
                      "border"
                      "border-base-300"
                      "bg-base-100/95"
                      "p-4"]
              :data-role "portfolio-optimizer-right-rail"}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
       "Trust & Freshness"]
      [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
       (if (:loaded? snapshot)
         "Current portfolio snapshot is available."
         "Current portfolio snapshot is not loaded yet.")]
      (when-let [message (get-in snapshot [:account :read-only-message])]
        [:p {:class ["mt-3" "rounded-md" "border" "border-warning/40" "bg-warning/10" "p-2" "text-xs" "text-warning"]}
         message])]]))
