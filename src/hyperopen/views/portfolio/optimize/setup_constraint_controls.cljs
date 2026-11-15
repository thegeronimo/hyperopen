(ns hyperopen.views.portfolio.optimize.setup-constraint-controls
  (:require ["lucide/dist/esm/icons/chevron-down.js" :default lucide-chevron-down]
            ["lucide/dist/esm/icons/refresh-cw.js" :default lucide-refresh-cw]
            ["lucide/dist/esm/icons/shield.js" :default lucide-shield]
            [hyperopen.portfolio.optimizer.application.view-model.exposure :as exposure-vm]
            [hyperopen.portfolio.optimizer.application.view-model.setup-summary :as setup-summary]
            [hyperopen.portfolio.optimizer.domain.exposure-policy :as exposure-policy]
            [hyperopen.views.portfolio.optimize.setup-controls :as controls]
            [hyperopen.views.portfolio.optimize.setup-exposure-map :as exposure-map]
            [hyperopen.views.ui.toggle :as toggle]))

(def ^:private default-turnover-cap
  1.0)

(def ^:private constraint-help
  {:long-only? "Restricts target weights to zero or positive values. Turn this off when short or hedged perp exposure is allowed."
   :include-spot? "Allows spot instruments in optimizer recommendations. Leave this off to restrict target legs to perps and vaults."
   :max-asset-weight "Maximum target portfolio weight any single asset can receive. 0.5 means no asset can exceed 50%."
   :gross-min "Minimum total absolute exposure to hold. Seeded from your current gross leverage so the optimizer preserves leverage instead of delevering. Leave blank to allow full delevering."
   :gross-max "Maximum total absolute exposure across all legs. 1 means long exposure plus short exposure can total up to 100% of capital."
   :net-min "Minimum signed net exposure allowed after optimization (before the Net band tolerance, which widens it by its percentage of realized gross). Leave blank when only the maximum net exposure matters."
   :net-max "Maximum signed net exposure allowed after optimization (before the Net band tolerance, which widens it by its percentage of realized gross). 1 means the portfolio can be net long up to 100% of capital."
   :dust-usdc "Small rebalance trades below this USDC notional are ignored so the output avoids noisy dust orders."
   :max-turnover "Maximum total portfolio turnover allowed for the rebalance. Turn this off when current exposure is too far from the target constraints."
   :rebalance-tolerance "Minimum target-vs-current weight difference before a rebalance row is considered actionable. 0.03 means 3 percentage points."})

(defn- constraint-tooltip
  [tooltip-id copy]
  [:span {:class ["pointer-events-none" "absolute" "left-0" "top-[calc(100%+6px)]"
                  "z-30" "w-[min(22rem,calc(100vw-2rem))]" "border"
                  "border-base-300" "bg-base-100" "px-2" "py-1.5"
                  "font-sans" "text-[0.75rem]" "font-normal"
                  "normal-case" "leading-[1.45]" "tracking-normal"
                  "text-trading-muted" "opacity-0" "shadow-[0_12px_32px_rgba(0,0,0,0.45)]"
                  "transition-opacity" "duration-150" "group-hover:opacity-100"
                  "group-focus-within:opacity-100"]
          :id tooltip-id
          :role "tooltip"
          :data-role tooltip-id}
   copy])

(defn- constraint-label
  [label tooltip-id help-copy]
  [:span {:class ["relative" "inline-flex" "min-w-0" "items-center" "gap-1.5"]}
   [:span {:class controls/eyebrow-class} label]
   [:span {:class ["font-mono" "text-[0.625rem]" "text-trading-muted/70"]
           :aria-hidden "true"}
    "?"]
   (constraint-tooltip tooltip-id help-copy)])

(defn- constraint-echo
  "Persistent interpreted-value echo so the unit isn't hidden in the `?` hover tooltip
  (the audit found constraint units lived only there). 0.5 -> \"max 50% per asset\"."
  [unit value]
  (when (and unit (number? value))
    (case unit
      :weight (str "max " (js/Math.round (* 100 value)) "% per asset")
      :mult (str (.toFixed value 2) "× capital")
      :pts (str (.toFixed (* 100 value) 1) " pp band")
      :usd (str "$" value " min trade")
      nil)))

(defn- constraint-row
  ([label constraint-key value role highlighted? unit]
   (constraint-row label nil constraint-key value role highlighted? unit false))
  ([label hidden-label constraint-key value role highlighted? unit]
   (constraint-row label hidden-label constraint-key value role highlighted? unit false))
  ([label hidden-label constraint-key value role highlighted? unit disabled?]
   (let [tooltip-id (str role "-tooltip")
         help-copy (get constraint-help constraint-key)
         echo (constraint-echo unit value)]
     [:label {:class (cond-> ["group" "relative" "grid" "grid-cols-[minmax(0,1fr)_92px]" "items-center"
                              "gap-2" "border" "border-base-300" "bg-base-200/20"
                              "px-2" "py-1.5"]
                       highlighted? (conj "border-warning/70" "bg-warning/10"))}
      [:span {:class ["min-w-0"]}
       (if help-copy
         (constraint-label label tooltip-id help-copy)
         [:span {:class controls/eyebrow-class} label])
       (when hidden-label
         [:span {:class ["sr-only"]} hidden-label])
       [:span {:class ["ml-2" "font-mono" "text-[0.625rem]" "uppercase"
                       "tracking-[0.08em]" "text-trading-muted"]}
        (if disabled? "output" "edit")]
       (when echo
         [:span {:class ["mt-0.5" "block" "font-mono" "text-[0.625rem]"
                         "normal-case" "tracking-normal" "text-trading-muted/70"]
                 :data-role (str role "-echo")}
          echo])]
      [:input (cond-> {:type "text"
                       :inputmode "decimal"
                       :class (cond-> controls/input-class
                                disabled? (conj "opacity-50" "cursor-not-allowed"))
                       :data-role role
                       :data-infeasible (when highlighted? "true")
                       :aria-invalid (when highlighted? "true")
                       :aria-describedby (when help-copy tooltip-id)
                       :value (str value)
                       :disabled (boolean disabled?)}
                (not disabled?)
                (assoc :on {:change [[:actions/set-portfolio-optimizer-constraint
                                      constraint-key
                                      [:event.target/value]]]})
                disabled?
                (assoc :aria-disabled "true"))]])))

(defn- turnover-cap-row
  [constraints highlighted?]
  (let [enabled? (some? (:max-turnover constraints))
        tooltip-id "portfolio-optimizer-constraint-max-turnover-input-tooltip"
        help-copy (:max-turnover constraint-help)]
    [:div {:class (cond-> ["group" "relative" "grid"
                           "grid-cols-[minmax(0,1fr)_auto]" "items-center"
                           "gap-2" "border" "border-base-300" "bg-base-200/20"
                           "px-2" "py-1.5"]
                    highlighted? (conj "border-warning/70" "bg-warning/10")
                    (not enabled?) (conj "text-trading-muted"))}
     [:span {:class ["min-w-0"]}
      [:span {:class ["min-w-0"]}
       (constraint-label "Turnover cap" tooltip-id help-copy)
       [:span {:class ["ml-2" "font-mono" "text-[0.625rem]" "uppercase"
                       "tracking-[0.08em]" "text-trading-muted"]}
        (if enabled? "edit" "no cap")]]]
     [:span {:class ["optimizer-turnover-cap-control" "inline-flex" "items-center" "gap-1.5"]}
      (toggle/toggle {:on? enabled?
                      :aria-label "Toggle turnover cap"
                      :data-role "portfolio-optimizer-constraint-max-turnover-toggle"
                      :on-change [[:actions/set-portfolio-optimizer-constraint
                                   :max-turnover
                                   (if enabled? nil default-turnover-cap)]]})
     [:input (cond-> {:type "text"
                      :inputmode "decimal"
                      :class (cond-> (conj controls/input-class "w-[92px]")
                               (not enabled?) (conj "opacity-50" "cursor-not-allowed"))
                      :data-role "portfolio-optimizer-constraint-max-turnover-input"
                      :data-infeasible (when highlighted? "true")
                      :aria-invalid (when highlighted? "true")
                      :aria-describedby tooltip-id
                      :value (if enabled? (str (:max-turnover constraints)) "")
                      :disabled (not enabled?)}
               enabled? (assoc :on {:change [[:actions/set-portfolio-optimizer-constraint
                                              :max-turnover
                                              [:event.target/value]]]}))]]]))

(defn- long-only-row
  [constraints]
  (let [enabled? (true? (:long-only? constraints))
        tooltip-id "portfolio-optimizer-constraint-long-only-tooltip"]
    [:div {:class ["group" "relative" "grid" "grid-cols-[minmax(0,1fr)_auto]"
                   "items-center" "gap-2" "border" "border-base-300"
                   "bg-base-200/20" "px-2" "py-1.5"]}
     [:span {:class ["min-w-0"]}
      (constraint-label "Long Only"
                        tooltip-id
                        (:long-only? constraint-help))]
     [:span {:class ["optimizer-long-only-control" "inline-flex" "items-center"]}
      (toggle/toggle {:on? enabled?
                      :aria-label "Toggle long only"
                      :aria-describedby tooltip-id
                      :data-role "portfolio-optimizer-constraint-long-only-input"
                      :on-change [[:actions/set-portfolio-optimizer-constraint
                                   :long-only?
                                   (not enabled?)]]})]]))

(defn- include-spot-row
  [constraints]
  (let [enabled? (true? (:include-spot? constraints))
        tooltip-id "portfolio-optimizer-constraint-include-spot-tooltip"]
    [:div {:class ["group" "relative" "grid" "grid-cols-[minmax(0,1fr)_auto]"
                   "items-center" "gap-2" "border" "border-base-300"
                   "bg-base-200/20" "px-2" "py-1.5"]}
     [:span {:class ["min-w-0"]}
      (constraint-label "Include Spot Assets"
                        tooltip-id
                        (:include-spot? constraint-help))]
     [:span {:class ["optimizer-include-spot-control" "inline-flex" "items-center"]}
      (toggle/toggle {:on? enabled?
                      :aria-label "Toggle include spot assets"
                      :aria-describedby tooltip-id
                      :data-role "portfolio-optimizer-constraint-include-spot-input"
                      :on-change [[:actions/set-portfolio-optimizer-constraint
                                   :include-spot?
                                   (not enabled?)]]})]]))

(defn- card-value-row
  [label value role]
  [:div {:class ["flex" "items-baseline" "justify-between" "gap-2" "py-0.5"]}
   [:span {:class ["text-[0.75rem]" "text-trading-muted"]} label]
   [:span {:class ["text-right" "font-mono" "text-[0.75rem]" "font-medium"
                   "text-trading-text"]
           :data-role role}
    value]])

(defn- pct-value
  [w]
  (if (number? w) (str (js/Math.round (* 100 w)) "%") "--"))

(defn- on-off
  [b]
  (if b "On" "Off"))

(defn- turnover-value
  [constraints]
  (if-let [cap (:max-turnover constraints)]
    (str (.toFixed cap 2) "×")
    "No turnover cap"))

(defn- tolerance-value
  [constraints]
  (let [v (:rebalance-tolerance constraints)]
    (if (number? v) (str (.toFixed (* 100 v) 1) " pp") "--")))

(defn- dust-value
  [constraints]
  (let [v (:dust-usdc constraints)]
    (if (number? v) (str "$" v) "--")))

(defn- constraint-card
  "Read-only-at-rest constraint card: the summary carries the icon, title, an
  Edit affordance (flipping to Done while open via optimizer-section-open-only)
  and the current values (hidden while open via optimizer-section-trailing —
  the editable inputs then show the same numbers); the body is the EXISTING
  editable controls, roles and dispatches untouched. Never :open from state."
  [{:keys [icon title role rows body]}]
  [:details {:class ["flex-1" "border" "border-base-300" "bg-base-200/20"]
             :data-role role
             :replicant/key role}
   [:summary {:class ["optimizer-plain-summary" "cursor-pointer" "select-none" "p-2.5"
                      "focus:outline-none" "focus:text-warning"]}
    [:div {:class ["flex" "items-center" "gap-2"]}
     (controls/lucide-icon icon ["text-trading-muted/70"])
     [:span {:class ["text-[0.8125rem]" "font-semibold" "text-trading-text"]}
      title]
     [:span {:class ["optimizer-section-trailing" "ml-auto" "text-[0.75rem]"
                     "font-semibold" "text-info"]}
      "Edit"]
     [:span {:class ["optimizer-section-open-only" "ml-auto" "text-[0.75rem]"
                     "font-semibold" "text-info"]}
      "Done"]]
    (into [:div {:class ["optimizer-section-trailing" "mt-1.5" "space-y-0.5"]}]
          rows)]
   (into [:div {:class ["space-y-2" "border-t" "border-base-300" "p-2.5"]}]
         body)])

(defn- fine-tune-drawer
  "Everything that shapes the envelope beyond dragging the dot: the band
  sliders, the current-portfolio line with its quiet verdict, and the
  remembered-profile (save-as-default) row. Collapsed by default — the
  resting section is pad + readout + cards only."
  [exposure-model]
  [:details {:class ["w-full"]
             :data-role "portfolio-optimizer-exposure-fine-tune"
             :replicant/key "exposure-fine-tune"}
   [:summary {:class ["optimizer-plain-summary" "flex" "cursor-pointer" "select-none"
                      "focus:outline-none"]}
    [:span {:class ["flex" "w-full" "items-center" "justify-between" "gap-1.5" "border"
                    "border-base-300" "bg-base-200/30" "px-3" "py-1.5"
                    "text-[0.75rem]" "font-medium" "text-trading-text"
                    "hover:border-warning/50"]}
     "Fine-tune exposure"
     [:span {:class ["optimizer-section-trailing" "inline-flex"]}
      (controls/lucide-icon lucide-chevron-down ["text-trading-muted"])]
     [:span {:class ["optimizer-section-open-only" "inline-flex"]}
      (controls/lucide-icon lucide-chevron-down ["rotate-180" "text-trading-muted"])]]]
   [:div {:class ["mt-3" "space-y-3" "border" "border-base-300"
                  "bg-base-200/10" "p-3"]}
    (exposure-map/bands-block exposure-model)
    (exposure-map/preview-block (:preview exposure-model))
    (exposure-map/profile-row (:profile exposure-model))]])

(defn- advanced-drawer
  "The raw gross/net min/max fields the exposure pad abstracts, kept for experts, plus the exact
  'Sent to solver' echo (implementation-facing audit detail — the primary column shows the
  user-facing readout instead). Editing any raw field writes the canonical key directly, which
  flips the active preset to Custom (the exposure-map derives the preset from the values). Lives
  behind a nested disclosure so the common path never sees it. Each field keeps its original
  data-role and appears exactly once in the panel — these are the only place
  gross-min/gross-max/net-min/net-max are editable."
  [constraints highlighted-controls exposure-model]
  (controls/disclosure-panel
   "portfolio-optimizer-constraints-advanced"
   (controls/disclosure-heading "Advanced solver limits" "raw min/max")
   [:div {:class ["mt-3"]}
    (exposure-map/solver-echo exposure-model)]
   (let [net-disabled? (false? (:net-editable? exposure-model))]
     [:div {:class ["mt-3" "grid" "grid-cols-1" "gap-2"]}
      (constraint-row "Gross exposure min" :gross-min (:gross-min constraints)
                      "portfolio-optimizer-constraint-gross-min-input"
                      (contains? highlighted-controls :gross-min)
                      :mult)
      (constraint-row "Gross exposure max" "Gross Leverage"
                      :gross-max (:gross-max constraints)
                      "portfolio-optimizer-constraint-gross-max-input"
                      (contains? highlighted-controls :gross-max)
                      :mult)
      (constraint-row "Net exposure min" nil
                      :net-min (:net-min constraints)
                      "portfolio-optimizer-constraint-net-min-input"
                      (and (not net-disabled?)
                           (contains? highlighted-controls :net-min))
                      :mult
                      net-disabled?)
      (constraint-row "Net exposure max" nil
                      :net-max (:net-max constraints)
                      "portfolio-optimizer-constraint-net-max-input"
                      (and (not net-disabled?)
                           (contains? highlighted-controls :net-max))
                      :mult
                      net-disabled?)])))

(defn constraints-section
  ([draft highlighted-controls]
   (constraints-section draft highlighted-controls nil))
  ([draft highlighted-controls {:keys [current-exposure has-saved-default? exposure-zoom-level]}]
   (let [constraints (:constraints draft)
         objective-kind (get-in draft [:objective :kind])
         exposure-model (exposure-vm/exposure-map-model
                         {:objective-kind objective-kind
                          :constraints constraints
                          :current-exposure current-exposure
                          :highlighted-controls highlighted-controls
                          :has-saved-default? has-saved-default?
                          :zoom-level exposure-zoom-level})
         active-label (get exposure-policy/preset-labels
                           (:active-preset exposure-model) "Custom")
         active-label (if (and (= "Custom" active-label)
                               (= :holdings
                                  (get-in draft [:metadata :universe-source :kind])))
                        "Custom · from holdings"
                        active-label)]
     (controls/disclosure-panel-open
      "portfolio-optimizer-constraints-panel"
      (controls/disclosure-heading
       "Portfolio exposure"
       [:span {:class ["flex" "min-w-0" "flex-col" "items-end" "gap-0.5" "text-right"]}
        [:span active-label]
        [:span {:class ["normal-case" "tracking-normal" "text-trading-muted"]
                :data-role "portfolio-optimizer-constraints-header-summary"}
         (setup-summary/constraints-summary-line constraints
                                                 {:objective-kind objective-kind})]])
      [:div {:class ["mt-3" "space-y-4"]}
       (if (:net-output-only? exposure-model)
         [:p {:class ["text-[0.8125rem]" "leading-[1.45]" "text-trading-muted"]
              :data-role "portfolio-optimizer-constraints-description"}
          [:span {:class ["font-semibold" "text-trading-text"]} "Resulting net"]
          [:span {:class ["ml-2"]} (:net-output-copy exposure-model)]]
         [:p {:class ["text-[0.8125rem]" "leading-[1.45]" "text-trading-muted"]
              :data-role "portfolio-optimizer-constraints-description"}
          "Set target leverage and net long/short bias."])
       (when (= "Custom · from holdings" active-label)
         [:p {:class ["flex" "flex-wrap" "items-center" "gap-2"]
              :data-role "portfolio-optimizer-preset-holdings-constraints-note"}
          [:span {:class ["border" "border-warning/50" "bg-warning/10" "px-1.5" "py-0.5"
                          "font-mono" "text-[0.625rem]" "font-semibold" "uppercase"
                          "tracking-[0.08em]" "text-warning"]}
           "Custom from holdings"]
          [:span {:class ["text-[0.6875rem]" "text-trading-muted"]}
           "Review before running"]])
       [:div {:class ["grid" "grid-cols-1" "gap-3"
                      "lg:grid-cols-[minmax(0,1.15fr)_minmax(15rem,1fr)]"]}
        [:div {:class ["flex" "min-w-0" "items-stretch" "gap-4"]}
         (exposure-map/pad-frame exposure-model)
         (exposure-map/readout exposure-model)]
        [:div {:class ["flex" "min-h-0" "flex-col" "gap-3"]}
         (fine-tune-drawer exposure-model)
         (constraint-card
          {:icon lucide-shield
           :title "Risk guards"
           :role "portfolio-optimizer-risk-guards-card"
           :rows [(card-value-row "Per-asset cap"
                                  (pct-value (:max-asset-weight constraints))
                                  "portfolio-optimizer-risk-guards-cap-value")
                  (card-value-row "Long-only"
                                  (on-off (true? (:long-only? constraints)))
                                  "portfolio-optimizer-risk-guards-long-only-value")
                  (card-value-row "Include spot"
                                  (on-off (true? (:include-spot? constraints)))
                                  "portfolio-optimizer-risk-guards-include-spot-value")]
           :body [(constraint-row "Per-asset cap" "Max Asset Weight"
                                  :max-asset-weight (:max-asset-weight constraints)
                                  "portfolio-optimizer-constraint-max-asset-weight-input"
                                  (contains? highlighted-controls :max-asset-weight)
                                  :weight)
                  (long-only-row constraints)
                  (include-spot-row constraints)]})
         (constraint-card
          {:icon lucide-refresh-cw
           :title "Rebalancing"
           :role "portfolio-optimizer-rebalancing-card"
           :rows [(card-value-row "Turnover cap"
                                  (turnover-value constraints)
                                  "portfolio-optimizer-rebalancing-turnover-value")
                  (card-value-row "Rebalance tolerance"
                                  (tolerance-value constraints)
                                  "portfolio-optimizer-rebalancing-tolerance-value")
                  (card-value-row "Dust threshold"
                                  (dust-value constraints)
                                  "portfolio-optimizer-rebalancing-dust-value")]
           :body [(constraint-row "Rebalance tolerance" "Rebalance Tolerance"
                                  :rebalance-tolerance (:rebalance-tolerance constraints)
                                  "portfolio-optimizer-constraint-rebalance-tolerance-input" false
                                  :pts)
                  (turnover-cap-row constraints
                                    (contains? highlighted-controls :max-turnover))
                  (constraint-row "Dust threshold" :dust-usdc (:dust-usdc constraints)
                                  "portfolio-optimizer-constraint-dust-usdc-input" false
                                  :usd)]})]]
       (exposure-map/policy-warning (:preview exposure-model))
       (advanced-drawer constraints highlighted-controls exposure-model)]))))
