(ns hyperopen.views.portfolio.optimize.scenario-objective-menu
  (:require [hyperopen.platform :as platform]
            [hyperopen.portfolio.optimizer.contracts :as optimizer-contracts]
            [hyperopen.views.portfolio.optimize.return-views-panel :as return-views-panel]
            [hyperopen.views.portfolio.optimize.target-sigma :as target-sigma]))

(defn- target-volatility-menu-sigma
  ;; The menu preset is 12%, but applying the option preserves a user-chosen
  ;; sigma when target-volatility is already the active objective — the label
  ;; reflects the value Apply & re-run would actually use.
  [draft]
  (or (when (= :target-volatility (get-in draft [:objective :kind]))
        (get-in draft [:objective :target-volatility]))
      target-sigma/menu-default-sigma))

(defn- objective-menu-options
  []
  [{:key :minimum-volatility
    :title "Minimum risk"
    :description "Smallest feasible sigma - defensive baseline. Recommended"}
   {:key :max-sharpe
    :title "Maximum Sharpe"
    :description "Best risk-adjusted return — uses your return views where you have them, implied returns otherwise"}
   {:key :equal-risk
    :title "Equal Risk"
    :description "Balances each position's contribution to portfolio volatility as closely as possible while preserving your long/short sides, gross leverage, and position limits. Covariance-only — return forecasts never move the weights; resulting net is determined by the risk balance"}
   {:key :inverse-volatility
    :title "Risk-weighted sizing"
    :description "Sizes every selected asset on its chosen side by the inverse of its own volatility — keeps every asset, ignores correlations, never reads return forecasts"
    :specialist? true}
   {:key :target-volatility
    :title "Target volatility"
    :description "Pin σ to a fixed level, max return at that σ"}
   {:key :maximum-return
    :title "Maximum return"
    :description "Aggressive. Drives toward the right of the frontier"}])

(defn current-objective-menu-key
  ;; Keyed by the OBJECTIVE only. Return views are an input policy, not an
  ;; objective, so a Black-Litterman return model no longer claims its own entry.
  [draft result]
  (let [objective-kind (or (get-in draft [:objective :kind])
                           (get-in result [:solver :objective-kind]))]
    (cond
      (= :minimum-variance objective-kind) :minimum-volatility
      (= :equal-risk objective-kind) :equal-risk
      (= :inverse-volatility objective-kind) :inverse-volatility
      (= :target-volatility objective-kind) :target-volatility
      (= :target-return objective-kind) :maximum-return
      :else :max-sharpe)))

(defn objective-label
  ([objective-key]
   (objective-label objective-key nil))
  ([objective-key draft]
   (if (= :target-volatility objective-key)
     (str "Target volatility · "
          (or (target-sigma/sigma-label (target-volatility-menu-sigma draft))
              "12%"))
     (or (:title (some #(when (= objective-key (:key %)) %)
                       (objective-menu-options)))
         "Maximum Sharpe"))))

(defn objective-menu-open?
  [state]
  (true? (get-in state optimizer-contracts/ui-objective-menu-open-path)))

(defn objective-trigger
  [label open?]
  [:button {:type "button"
            :class ["optimizer-provenance-objective-trigger"
                    "group"
                    "mt-0.5"
                    "inline-flex"
                    "items-center"
                    "gap-1"
                    "border-0"
                    "bg-transparent"
                    "p-0"
                    "font-medium"
                    "text-trading-text"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"]
            :data-role "portfolio-optimizer-objective-menu-trigger"
            :aria-haspopup "true"
            :aria-expanded (if open? "true" "false")
            :on {:click [[:actions/open-portfolio-optimizer-objective-menu]]}}
   [:span {:class ["optimizer-provenance-objective-label"]} label]
   [:span {:class ["text-[0.6rem]" "text-trading-muted"]} "›"]])

(defn- objective-option-row
  [{:keys [key title description]} {:keys [state draft current-key pending-key]}]
  (let [selected? (= key pending-key)
        current? (= key current-key)
        role (str "portfolio-optimizer-objective-menu-option-" (name key))]
    [:button {:type "button"
              :class ["flex"
                      "w-full"
                      "items-start"
                      "gap-3"
                      "border-0"
                      "bg-transparent"
                      "p-0"
                      "text-left"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"]
              :data-role role
              :data-selected (str selected?)
              :aria-pressed (str selected?)
              :on {:click [[:actions/select-portfolio-optimizer-objective-menu-option key]]}}
     [:span {:class ["optimizer-objective-menu-check"
                     "mt-0.5"
                     "inline-flex"
                     "h-3"
                     "w-3"
                     "shrink-0"
                     "items-center"
                     "justify-center"
                     "border"
                     "font-mono"
                     "text-[0.55rem]"
                     "leading-none"]
             :aria-hidden "true"}
      (when selected? "✓")]
     [:span {:class ["min-w-0"]}
      [:span {:class ["block" "text-[0.8125rem]" "font-semibold" "text-trading-text"]}
       title
       (when (and (= :target-volatility key) (not selected?))
         [:span {:class ["ml-1.5" "font-mono" "text-[0.6875rem]" "font-normal"
                         "text-trading-muted"]}
          (str "· " (target-sigma/sigma-label
                     (target-sigma/menu-pending-sigma state draft)))])]
      [:span {:class ["mt-1" "block" "text-[0.6875rem]" "text-trading-muted"]}
       (str description (when current? ". Current."))]]]))

(defn- objective-menu-option
  [{:keys [key] :as option}
   {:keys [state draft pending-key sigma-bounds] :as ctx}]
  (let [selected? (= key pending-key)
        sigma-editor? (and (= :target-volatility key) selected?)]
    ;; The whole padded card selects (per spec); editor clicks bubbling here
    ;; re-select the already-selected option, which is a harmless no-op.
    [:div {:class ["optimizer-objective-menu-option"
                   "cursor-pointer"
                   "border"
                   "p-3"
                   "transition-colors"]
           :data-selected (str selected?)
           :on {:click [[:actions/select-portfolio-optimizer-objective-menu-option key]]}}
     (objective-option-row option ctx)
     ;; Inline σ editor — expands when Target volatility is the pending
     ;; selection so the level is set before the first run (designer spec).
     (when sigma-editor?
       (target-sigma/menu-sigma-editor state draft sigma-bounds))]))

(defn views-editor-section
  "Back-compat entry point for the Return views panel (setup rail, results page,
  and this menu all render it). `result` is no longer consulted — implied values
  come from the readiness baseline so a prior run's posterior can never pose as
  an implied input."
  ([draft state result readiness]
   (views-editor-section draft state result readiness {}))
  ([draft state _result readiness opts]
   (return-views-panel/return-views-panel
    (merge {:draft draft
            :state state
            :readiness readiness
            :now-ms (return-views-panel/panel-now-ms)}
           opts))))


(defn- objective-menu-mount-focus!
  [render-arg]
  (let [node (or (:replicant/node render-arg)
                 render-arg)]
    (platform/queue-microtask!
     (fn []
       (when (and node
                  (.-isConnected node)
                  (fn? (.-focus node))
                  (not (and (fn? (.-contains node))
                            (.contains node (.-activeElement js/document)))))
         (.focus node))))))

(defn objective-menu
  ([state draft result]
   (objective-menu state draft result nil))
  ([state draft result readiness]
  (let [open? (objective-menu-open? state)
        current-key (current-objective-menu-key draft result)
        pending-key (or (get-in state optimizer-contracts/ui-objective-menu-selection-path)
                        current-key)
        ;; :specialist? goals are discovered through the More-goals card and
        ;; the floored-state cross-link, not the quick-switch menu: their rows
        ;; render by exception, only while one is the current or pending
        ;; objective (so the menu can honestly show and re-apply the
        ;; selection). The mainstream goals always render.
        rendered-options (vec (remove (fn [{:keys [key specialist?]}]
                                        (and specialist?
                                             (not= key current-key)
                                             (not= key pending-key)))
                                      (objective-menu-options)))
        option-ctx {:state state
                    :draft draft
                    :current-key current-key
                    :pending-key pending-key
                    :sigma-bounds (target-sigma/frontier-sigma-bounds result)}
        ;; Same objective re-applied is a no-op — view edits commit live and the
        ;; stale-run watcher reruns — EXCEPT when applying would change something:
        ;; a new sigma, or switching an old historical-mean draft onto the
        ;; views-aware return model that Maximum Sharpe now carries.
        apply-disabled? (and (= current-key pending-key)
                             (not (and (= :max-sharpe pending-key)
                                       (not= :black-litterman
                                             (get-in draft [:return-model :kind]))))
                             (not (and (= :target-volatility pending-key)
                                       (target-sigma/menu-sigma-changed? state draft))))]
    (when open?
      [:section {:class ["optimizer-objective-menu"
                         "optimizer-objective-popover"
                         "absolute"
                         "left-0"
                         "top-full"
                         "z-50"
                         "mt-2"
                         "border"
                         "shadow-2xl"
                         "flex"
                         "flex-col"
                         "focus:outline-none"
                         "focus:ring-0"
                         "focus:ring-offset-0"]
                 :data-role "portfolio-optimizer-objective-menu"
                 :role "region"
                 :tabindex -1
                 :replicant/on-render objective-menu-mount-focus!
                 :aria-label "Change objective"
                 :on {:keydown [[:actions/handle-portfolio-optimizer-objective-menu-keydown
                                  [:event/key]]]}}
       [:header {:class ["flex" "shrink-0" "items-start" "justify-between" "gap-4" "border-b" "border-base-300" "px-3" "py-3"]}
        [:div
         [:p {:class ["font-mono" "text-[0.58rem]" "uppercase" "tracking-[0.18em]" "text-trading-muted/70"]}
          "Edit"]
         [:h2 {:class ["mt-1" "text-sm" "font-semibold" "text-trading-text"]}
          "Change objective"]
         [:p {:class ["mt-1.5" "text-[0.7rem]" "text-trading-muted"]}
          "Re-runs the solver with the same universe and constraints"]]
        [:button {:type "button"
                  :class ["border-0"
                          "bg-transparent"
                          "px-1"
                          "py-0"
                          "text-sm"
                          "text-trading-muted"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                  :aria-label "Close objective menu"
                  :data-role "portfolio-optimizer-objective-menu-close"
                  :on {:click [[:actions/close-portfolio-optimizer-objective-menu]]}}
         "x"]]
       [:div {:class ["optimizer-objective-menu-body"
                      "flex"
                      "flex-col"
                      "min-h-0"
                      "overflow-y-auto"]}
        (into
         [:div {:class ["shrink-0" "space-y-2" "px-3" "py-3"]}]
         (map #(objective-menu-option % option-ctx)
              rendered-options))
        (when (= :max-sharpe pending-key)
          (views-editor-section draft state result readiness))]
       [:footer {:class ["flex" "shrink-0" "items-center" "justify-between" "gap-3" "border-t" "border-base-300" "px-3" "py-3"]}
        [:span {:class ["font-mono" "text-[0.62rem]" "text-trading-muted"]}
         "Esc to cancel"]
        [:div {:class ["flex" "items-center" "gap-2"]}
         [:button {:type "button"
                   :class ["border" "border-base-300" "bg-base-200/40" "px-3" "py-1.5" "text-[0.7rem]" "font-semibold" "text-trading-text"]
                   :data-role "portfolio-optimizer-objective-menu-cancel"
                   :on {:click [[:actions/close-portfolio-optimizer-objective-menu]]}}
          "Cancel"]
         [:button {:type "button"
                   :class ["optimizer-primary-action"
                           "border"
                           "border-base-300"
                           "px-3"
                           "py-1.5"
                           "text-[0.7rem]"
                           "font-semibold"
                           "disabled:cursor-not-allowed"
                           "disabled:text-trading-muted"]
                   :data-role "portfolio-optimizer-objective-menu-apply"
                   :disabled apply-disabled?
                   :on (when-not apply-disabled?
                         {:click [[:actions/apply-portfolio-optimizer-objective-menu-selection-and-run]]})}
          "Apply & re-run"]]]]))))
