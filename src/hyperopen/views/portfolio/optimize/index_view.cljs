(ns hyperopen.views.portfolio.optimize.index-view
  (:require [clojure.string :as str]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.system :as app-system]
            [nexus.registry :as nxr]))

(defn- scenario-index
  [state]
  (or (get-in state [:portfolio :optimizer :scenario-index])
      {:ordered-ids []
       :by-id {}}))

(defn- scenario-summaries
  [state]
  (let [{:keys [ordered-ids by-id]} (scenario-index state)]
    (keep #(get by-id %) ordered-ids)))

(defn- title-label
  [value]
  (if (keyword? value)
    (->> (str/split (name value) #"-")
         (map str/capitalize)
         (str/join " "))
    "N/A"))

(defn- percent-label
  [value]
  (if (number? value)
    (str (.toFixed (* value 100) 2) "%")
    "N/A"))

(defn- row-action-click-handler
  [action]
  (fn [event]
    (when (fn? (.-preventDefault event))
      (.preventDefault event))
    (when (fn? (.-stopPropagation event))
      (.stopPropagation event))
    (when app-system/store
      (nxr/dispatch app-system/store nil [action]))))

(defn- row-action-button
  [label data-role action]
  [:button {:type "button"
            :class ["rounded-md"
                    "border"
                    "border-base-300"
                    "bg-base-100/80"
                    "px-2"
                    "py-1"
                    "text-[0.65rem]"
                    "font-semibold"
                    "uppercase"
                    "tracking-[0.14em]"
                    "text-trading-muted"
                    "hover:border-primary/50"
                    "hover:text-trading-text"]
            :data-role data-role
            :on {:click (row-action-click-handler action)}}
   label])

(defn- scenario-row
  [summary]
  (let [scenario-id (:id summary)
        path (portfolio-routes/portfolio-optimize-scenario-path scenario-id)]
    [:tr {:class ["cursor-pointer"
                  "border-t"
                  "border-base-300"
                  "text-sm"
                  "hover:bg-base-200/60"]
          :data-role (str "portfolio-optimizer-scenario-row-" scenario-id)
          :on {:click [[:actions/navigate path]]}}
     [:td {:class ["px-3" "py-3" "font-semibold" "text-trading-text"]}
      (:name summary)]
     [:td {:class ["px-3" "py-3"]}
       [:span {:class ["rounded-full"
                      "border"
                      "border-base-300"
                      "bg-base-200/60"
                      "px-2"
                      "py-1"
                      "text-[0.65rem]"
                      "font-semibold"
                      "uppercase"
                      "tracking-[0.14em]"
                      "text-trading-muted"]}
       (or (some-> (:status summary) name)
           "unknown")]]
     [:td {:class ["px-3" "py-3" "text-trading-muted"]}
      (title-label (:objective-kind summary))]
     [:td {:class ["px-3" "py-3" "text-trading-muted"]}
      (title-label (:return-model-kind summary))]
     [:td {:class ["px-3" "py-3" "text-trading-muted"]}
      (title-label (:risk-model-kind summary))]
     [:td {:class ["px-3" "py-3" "text-right" "font-semibold" "tabular-nums"]}
      (percent-label (:expected-return summary))]
     [:td {:class ["px-3" "py-3" "text-right" "font-semibold" "tabular-nums"]}
      (percent-label (:volatility summary))]
     [:td {:class ["px-3" "py-3" "text-right"]}
      [:div {:class ["flex" "justify-end" "gap-2"]}
       (row-action-button
        "Duplicate"
        (str "portfolio-optimizer-scenario-duplicate-" scenario-id)
        [:actions/duplicate-portfolio-optimizer-scenario scenario-id])
       (row-action-button
        "Archive"
        (str "portfolio-optimizer-scenario-archive-" scenario-id)
        [:actions/archive-portfolio-optimizer-scenario scenario-id])]]]))

(defn- scenario-board
  [summaries]
  [:div {:class ["overflow-hidden"
                 "rounded-lg"
                 "border"
                 "border-base-300"
                 "bg-base-200/30"]
         :data-role "portfolio-optimizer-scenario-board"}
   [:table {:class ["w-full" "border-collapse"]}
    [:thead
     [:tr {:class ["text-left"
                   "text-[0.65rem]"
                   "font-semibold"
                   "uppercase"
                   "tracking-[0.16em]"
                   "text-trading-muted"]}
      [:th {:class ["px-3" "py-2"]} "Scenario"]
      [:th {:class ["px-3" "py-2"]} "Status"]
      [:th {:class ["px-3" "py-2"]} "Objective"]
      [:th {:class ["px-3" "py-2"]} "Return"]
      [:th {:class ["px-3" "py-2"]} "Risk"]
      [:th {:class ["px-3" "py-2" "text-right"]} "Exp Return"]
      [:th {:class ["px-3" "py-2" "text-right"]} "Vol"]
      [:th {:class ["px-3" "py-2" "text-right"]} "Actions"]]]
    (into [:tbody] (map scenario-row summaries))]])

(defn index-view
  [state]
  (let [summaries (vec (scenario-summaries state))]
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
    (if (seq summaries)
      (scenario-board summaries)
      [:div {:class ["rounded-lg"
                     "border"
                     "border-dashed"
                     "border-base-300"
                     "bg-base-200/40"
                     "p-6"
                     "text-sm"
                     "text-trading-muted"]
             :data-role "portfolio-optimizer-empty-scenarios"}
       "No local optimizer scenarios are loaded yet."])]]))
