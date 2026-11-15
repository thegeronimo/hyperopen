(ns hyperopen.views.portfolio.optimize.scenario-picker
  "Workspace-header scenario library: the Scenarios menu (open a saved
  scenario, duplicate/archive it, start a new one) and the header-level Save
  button. The menu is a state-controlled popover (objective-menu pattern) —
  a native <details> would be reset by unkeyed sibling churn."
  (:require [hyperopen.portfolio.optimizer.application.view-model :as view-model]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.system :as app-system]
            [hyperopen.views.portfolio.optimize.format :as opt-format]
            [nexus.registry :as nxr]))

(defn- updated-label
  [updated-at-ms]
  (when (number? updated-at-ms)
    (.toLocaleDateString (js/Date. updated-at-ms)
                         js/undefined
                         #js {:month "short" :day "numeric"})))

(defn- row-metric-label
  [{:keys [expected-return volatility]}]
  (when (and (number? expected-return) (number? volatility))
    (str (opt-format/format-pct expected-return)
         " · σ " (opt-format/format-pct volatility))))

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
            :class ["border" "border-base-300" "bg-base-100/80" "px-1.5" "py-0.5"
                    "font-mono" "text-[0.58rem]" "font-semibold" "uppercase"
                    "tracking-[0.08em]" "text-trading-muted"
                    "hover:border-primary/50" "hover:text-trading-text"]
            :data-role data-role
            :on {:click (row-action-click-handler action)}}
   label])

(defn- scenario-row
  [{:keys [id status updated-at-ms] :as summary} active-scenario-id]
  (let [path (portfolio-routes/portfolio-optimize-scenario-path id)
        active? (= id active-scenario-id)]
    [:div {:class ["group" "flex" "cursor-pointer" "items-center" "gap-3"
                   "border-b" "border-base-300/60" "px-3" "py-2"
                   "last:border-b-0" "hover:bg-base-200/60"]
           :replicant/key id
           :data-role (str "portfolio-optimizer-scenario-row-" id)
           :on {:click [[:actions/close-portfolio-optimizer-scenario-menu]
                        [:actions/navigate path]]}}
     [:div {:class ["min-w-0" "flex-1"]}
      [:p {:class ["truncate" "text-[0.8125rem]" "font-semibold" "text-trading-text"]}
       (or (:name summary) "Untitled Optimization")
       (when active?
         [:span {:class ["ml-2" "font-mono" "text-[0.58rem]" "font-normal"
                         "uppercase" "tracking-[0.08em]" "text-primary"]}
          "open"])]
      [:p {:class ["mt-0.5" "font-mono" "text-[0.62rem]" "text-trading-muted"]}
       (let [metrics (row-metric-label summary)]
         (str (some-> status cljs.core/name)
              (when-let [updated (updated-label updated-at-ms)]
                (str " · " updated))
              (when metrics (str " · " metrics))))]]
     [:div {:class ["flex" "shrink-0" "items-center" "gap-1.5"]}
      (row-action-button
       "Duplicate"
       (str "portfolio-optimizer-scenario-duplicate-" id)
       [:actions/duplicate-portfolio-optimizer-scenario id])
      (row-action-button
       "Archive"
       (str "portfolio-optimizer-scenario-archive-" id)
       [:actions/archive-portfolio-optimizer-scenario id])]]))

(defn- menu-empty-state
  [has-address?]
  [:p {:class ["px-3" "py-4" "text-[0.75rem]" "text-trading-muted"]
       :data-role "portfolio-optimizer-scenario-menu-empty"}
   (if has-address?
     "No saved scenarios yet. Configure the setup and use Save scenario to keep it."
     "Connect a wallet to save and load scenarios.")])

(defn- new-scenario-row
  []
  [:button {:type "button"
            :class ["flex" "w-full" "items-center" "gap-2" "border-b"
                    "border-base-300" "bg-base-200/40" "px-3" "py-2" "text-left"
                    "text-[0.8125rem]" "font-semibold" "text-trading-text"
                    "hover:bg-base-200/70"]
            :data-role "portfolio-optimizer-scenario-menu-new"
            :on {:click [[:actions/new-portfolio-optimizer-scenario]]}}
   [:span {:class ["font-mono" "text-primary"]} "+"]
   "New scenario"
   [:span {:class ["ml-auto" "font-mono" "text-[0.62rem]" "font-normal"
                   "text-trading-muted"]}
    "fresh setup from holdings"]])

(defn- scenario-menu
  [{:keys [has-address? active-scenario-id scenario-summaries]}]
  [:section {:class ["absolute" "right-0" "top-full" "z-[240]" "mt-2" "flex"
                     "max-h-[60vh]" "w-[26rem]" "max-w-[90vw]" "flex-col"
                     "overflow-y-auto" "border" "border-base-300" "bg-base-100"
                     "shadow-2xl"]
             :data-role "portfolio-optimizer-scenario-menu"
             :role "region"
             :aria-label "Saved scenarios"
             :on {:keydown [[:actions/handle-portfolio-optimizer-scenario-menu-keydown
                             [:event/key]]]}}
   (new-scenario-row)
   (if (seq scenario-summaries)
     (into [:div {:data-role "portfolio-optimizer-scenario-menu-list"}]
           (map #(scenario-row % active-scenario-id) scenario-summaries))
     (menu-empty-state has-address?))])

(defn scenario-strip
  "Header-right controls: the Scenarios menu trigger and the Save button.
  Rendered by setup-header; needs the full app state for the library model."
  [state {:keys [saving-scenario?]}]
  (let [{:keys [open?] :as model} (view-model/scenario-library-model state)]
    [:div {:class ["relative" "flex" "shrink-0" "items-center" "gap-2"]
           :data-role "portfolio-optimizer-scenario-strip"}
     [:button {:type "button"
               :class ["border" "border-base-300" "bg-base-200/30" "px-3" "py-1.5"
                       "text-[0.75rem]" "font-semibold" "text-trading-text"
                       "hover:border-primary/50"]
               :data-role "portfolio-optimizer-scenario-menu-trigger"
               :aria-haspopup "true"
               :aria-expanded (if open? "true" "false")
               :on {:click [[:actions/toggle-portfolio-optimizer-scenario-menu]]}}
      (str "Scenarios" (if open? " ▴" " ▾"))]
     [:button {:type "button"
               :class ["border" "border-primary/50" "bg-primary/10" "px-3" "py-1.5"
                       "text-[0.75rem]" "font-semibold" "text-primary"
                       "disabled:cursor-not-allowed" "disabled:border-base-300"
                       "disabled:bg-base-200/30" "disabled:text-trading-muted"]
               :data-role "portfolio-optimizer-header-save-scenario"
               :disabled (boolean saving-scenario?)
               :on {:click [[:actions/open-portfolio-optimizer-scenario-save-modal]]}}
      (if saving-scenario? "Saving…" "Save scenario")]
     (when open?
       (scenario-menu model))]))
