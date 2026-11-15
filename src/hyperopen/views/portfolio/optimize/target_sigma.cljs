(ns hyperopen.views.portfolio.optimize.target-sigma
  "Target-volatility (σ) selection controls per the target-σ designer spec: the
   setup-card parameter block, the objective-menu inline σ editor, and the
   scenario-detail TARGET Σ dial strip. Pending values live in UI state and
   commit to the draft only on Apply / Re-run — never silently (a dirty draft
   would trigger the stale auto-recompute)."
  (:require [hyperopen.portfolio.optimizer.contracts :as optimizer-contracts]
            [hyperopen.views.portfolio.optimize.format :as opt-format]
            [hyperopen.views.portfolio.optimize.setup-controls :as controls]))

(def slider-min-percent 4)
(def slider-base-max-percent 40)
(def slider-step-percent 0.5)
(def default-sigma 0.2)
(def menu-default-sigma 0.12)
(def ^:private levered-max-headroom 1.2)

(defn sigma-percent
  [sigma]
  (when (opt-format/finite-number? sigma)
    (/ (js/Math.round (* sigma 1000)) 10)))

(defn sigma-percent-text
  [sigma]
  (when-let [pct (sigma-percent sigma)]
    (let [rounded (js/Math.round pct)]
      (if (== pct rounded)
        (str rounded)
        (str pct)))))

(defn sigma-label
  [sigma]
  (some-> (sigma-percent-text sigma)
          (str "%")))

(defn frontier-sigma-bounds
  [result]
  (let [points (->> (or (get-in result [:frontiers :unconstrained])
                        (get-in result [:frontiers :constrained])
                        (:frontier result))
                    (filter #(and (opt-format/finite-number? (:volatility %))
                                  (opt-format/finite-number? (:expected-return %)))))
        current-vol (:current-volatility result)]
    (when (seq points)
      (cond-> {:min-vol (apply min (map :volatility points))
               :max-vol (apply max (map :volatility points))
               :max-return-vol (:volatility (apply max-key :expected-return points))}
        (opt-format/finite-number? current-vol)
        (assoc :current-vol current-vol)))))

(defn dial-max-percent
  "Upper bound of the σ dial. 40% by default, widened (with ~20% headroom) by
   whichever is larger of the achievable frontier's top σ and the current
   portfolio's σ — a book already running at 500% vol gets a ~600% dial so the
   user can lever up. Any committed or staged σ above the bound also rescales
   the dial, so typed targets like 1000% become the new max. Rounded up to the
   nearest 10."
  [sigma-bounds & sigmas]
  (let [levered (some->> [(:max-vol sigma-bounds) (:current-vol sigma-bounds)]
                         (keep sigma-percent)
                         not-empty
                         (apply max)
                         (* levered-max-headroom))
        candidates (->> (keep sigma-percent sigmas)
                        (concat [slider-base-max-percent levered])
                        (filter some?))]
    (* 10 (js/Math.ceil (/ (apply max candidates) 10)))))

(defn sigma-helper-copy
  "Spec copy: exact-σ sentence plus a context sentence — cash-floor warning
   below 8%, gross-cap warning beyond the achievable frontier (30% when no
   frontier exists yet), otherwise the frontier-bounds line (with real bounds
   when a run frontier is available)."
  [sigma sigma-bounds]
  (let [pct (sigma-percent sigma)
        high-threshold (or (some-> (:max-vol sigma-bounds) sigma-percent) 30)]
    (str "Solver maximizes expected return at exactly σ = "
         (or (sigma-label sigma) "--")
         ". "
         (cond
           (and (some? pct) (< pct 8))
           "Very low σ may bind against the cash floor."

           (and (some? pct) (> pct high-threshold))
           "High σ may require leverage beyond the gross cap."

           (and (some? (:min-vol sigma-bounds))
                (some? (:max-return-vol sigma-bounds)))
           (str "Sits between min-vol (≈" (sigma-label (:min-vol sigma-bounds))
                ") and max-return (≈" (sigma-label (:max-return-vol sigma-bounds))
                ") on the frontier.")

           :else
           "Sits between min-vol and max-return on the frontier."))))

(defn- sigma-slider
  [role value-percent max-percent action]
  [:input {:type "range"
           :min slider-min-percent
           :max max-percent
           :step slider-step-percent
           :value (str value-percent)
           :class ["optimizer-target-sigma-slider" "w-full" "accent-warning"]
           :aria-label "Target volatility slider"
           :data-role role
           :on {:input [action]}}])

(defn- sigma-percent-input
  ([role value-text action]
   (sigma-percent-input role value-text action nil))
  ([role value-text action {:keys [size]}]
   [:span {:class ["inline-flex" "items-center" "border" "border-base-300"
                   "bg-base-100" "shrink-0"]}
    [:input {:type "text"
             :inputmode "decimal"
             :class (into ["border-0" "bg-transparent" "pl-2" "pr-0.5"
                           "text-right" "font-mono" "text-trading-text"
                           "outline-none" "focus:outline-none"]
                          (if (= :menu size)
                            ["w-[52px]" "py-1" "text-[0.75rem]"]
                            ["w-12" "py-[3px]" "text-[0.8125rem]"]))
             :aria-label "Target volatility percent"
             :data-role role
             :value (str value-text)
             :on {:change [action]}}]
    [:span {:class ["pr-1.5" "pl-0.5" "font-mono" "text-[0.75rem]"
                    "text-trading-muted"]}
     "%"]]))

(defn- sigma-scale-row
  [max-percent]
  [:div {:class ["mt-0.5" "flex" "items-center" "justify-between" "font-mono"
                 "text-[0.625rem]" "text-trading-muted/70"]}
   [:span (str slider-min-percent "% · defensive")]
   [:span (str max-percent "% · aggressive")]])

(defn objective-parameter-block
  "Setup-card block for the :target-volatility objective: percent input,
   4–40% slider, and helper copy. sigma-bounds is optional frontier context
   ({:min-vol σ :max-return-vol σ}) from the last successful run."
  [draft sigma-bounds]
  (let [sigma (or (get-in draft [:objective :target-volatility]) default-sigma)
        max-percent (dial-max-percent sigma-bounds sigma)
        action [:actions/set-portfolio-optimizer-objective-parameter-percent
                :target-volatility
                [:event.target/value]]]
    [:div {:class ["border" "border-base-300" "bg-base-200/20" "p-2"]
           :data-role "portfolio-optimizer-target-sigma-controls"}
     [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
      [:span {:class controls/eyebrow-class} "Target σ (annualized)"]
      (sigma-percent-input "portfolio-optimizer-objective-target-volatility-input"
                           (sigma-percent-text sigma)
                           action)]
     [:div {:class ["mt-2"]}
      (sigma-slider "portfolio-optimizer-objective-target-volatility-slider"
                    (sigma-percent sigma)
                    max-percent
                    action)]
     (sigma-scale-row max-percent)
     [:p {:class ["mt-2" "text-[0.75rem]" "leading-[1.45]" "text-trading-muted"]
          :data-role "portfolio-optimizer-target-sigma-helper"}
      (sigma-helper-copy sigma sigma-bounds)]]))

(defn menu-pending-sigma
  "The σ the objective menu's inline editor shows / would apply: the staged
   menu value, else the draft value when target-volatility is already the
   active objective, else the 12% menu preset."
  [state draft]
  (or (get-in state optimizer-contracts/ui-objective-menu-target-sigma-path)
      (when (= :target-volatility (get-in draft [:objective :kind]))
        (get-in draft [:objective :target-volatility]))
      menu-default-sigma))

(defn menu-sigma-changed?
  [state draft]
  (let [pending (get-in state optimizer-contracts/ui-objective-menu-target-sigma-path)]
    (and (some? (sigma-percent pending))
         (not= (sigma-percent pending)
               (sigma-percent (get-in draft [:objective :target-volatility]))))))

(defn menu-sigma-editor
  "Inline σ editor inside the objective menu's Target volatility option —
   stages a pending σ that Apply & re-run commits with the objective."
  [state draft sigma-bounds]
  (let [sigma (menu-pending-sigma state draft)
        max-percent (dial-max-percent sigma-bounds
                                      sigma
                                      (get-in draft [:objective :target-volatility]))
        action [:actions/set-portfolio-optimizer-objective-menu-target-sigma
                [:event.target/value]]]
    [:div {:class ["mt-2.5" "pl-[22px]"]
           :data-role "portfolio-optimizer-objective-menu-target-sigma-editor"}
     [:div {:class ["mb-1.5" "flex" "items-center" "gap-2.5"]}
      [:span {:class ["flex-1" "text-[0.75rem]" "text-trading-muted"]}
       "Target σ (annualized)"]
      (sigma-percent-input "portfolio-optimizer-objective-menu-target-sigma-input"
                           (sigma-percent-text sigma)
                           action
                           {:size :menu})]
     (sigma-slider "portfolio-optimizer-objective-menu-target-sigma-slider"
                   (sigma-percent sigma)
                   max-percent
                   action)
     (sigma-scale-row max-percent)
     [:div {:class ["mt-1.5" "text-[0.6875rem]" "leading-[1.45]" "text-trading-muted"]
            :data-role "portfolio-optimizer-objective-menu-target-sigma-helper"}
      (sigma-helper-copy sigma sigma-bounds)]]))

(defn pending-sigma
  [state]
  (get-in state optimizer-contracts/ui-target-sigma-draft-path))

(defn target-sigma-strip
  "Scenario-detail dial strip under the tabs: slider + input edit a pending σ;
   Re-run commits it to the draft objective and runs the solver. Per spec the
   clean state carries no buttons — they appear once the dial is dirty."
  [{:keys [state draft result running? loading?]}]
  (when (and (not loading?)
             (= :target-volatility (get-in draft [:objective :kind])))
    (let [current (or (get-in draft [:objective :target-volatility])
                      default-sigma)
          pending (pending-sigma state)
          effective (or pending current)
          max-percent (dial-max-percent (frontier-sigma-bounds result)
                                        current
                                        pending)
          changed? (and (some? (sigma-percent pending))
                        (not= (sigma-percent pending) (sigma-percent current)))
          rerun-disabled? (true? running?)
          set-action [:actions/set-portfolio-optimizer-target-sigma-draft
                      [:event.target/value]]]
      [:section {:class (cond-> ["optimizer-target-sigma-strip" "flex"
                                 "flex-wrap" "items-center" "gap-3.5"
                                 "border-b" "border-base-300" "px-4" "py-2"]
                          changed? (conj "bg-warning/5")
                          (not changed?) (conj "bg-base-100/95"))
                 :data-role "portfolio-optimizer-target-sigma-strip"}
       [:span {:class ["shrink-0" "font-mono" "text-[0.625rem]" "uppercase"
                       "tracking-[0.08em]" "text-trading-muted/70"]}
        "Target σ"]
       [:div {:class ["flex" "min-w-[220px]" "flex-[0_1_380px]" "items-center"
                      "gap-2.5"]}
        [:span {:class ["shrink-0" "font-mono" "text-[0.625rem]"
                        "text-trading-muted/70"]}
         (str slider-min-percent "%")]
        (sigma-slider "portfolio-optimizer-target-sigma-slider"
                      (sigma-percent effective)
                      max-percent
                      set-action)
        [:span {:class ["shrink-0" "font-mono" "text-[0.625rem]"
                        "text-trading-muted/70"]}
         (str max-percent "%")]]
       (sigma-percent-input "portfolio-optimizer-target-sigma-input"
                            (sigma-percent-text effective)
                            set-action)
       [:span {:class ["text-[0.75rem]" "text-trading-muted"]
               :data-role "portfolio-optimizer-target-sigma-status"}
        (if changed?
          [:span "σ " (sigma-label current) " → "
           [:span {:class ["text-warning"]} (sigma-label pending)]
           " · estimates refresh on re-run"]
          [:span "Solving for max return at exactly this σ"])]
       [:span {:class ["flex-1"]}]
       (when changed?
         [:div {:class ["flex" "items-center" "gap-2"]}
          [:button {:type "button"
                    :class ["border" "border-base-300" "bg-base-200/40" "px-2.5"
                            "py-1" "text-[0.75rem]" "font-semibold"
                            "text-trading-text"]
                    :data-role "portfolio-optimizer-target-sigma-reset"
                    :on {:click [[:actions/reset-portfolio-optimizer-target-sigma-draft]]}}
           "Reset"]
          [:button {:type "button"
                    :class ["optimizer-primary-action" "border" "border-base-300"
                            "px-2.5" "py-1" "text-[0.75rem]" "font-semibold"
                            "disabled:cursor-not-allowed"
                            "disabled:text-trading-muted"]
                    :data-role "portfolio-optimizer-target-sigma-rerun"
                    :disabled rerun-disabled?
                    :on (when-not rerun-disabled?
                          {:click [[:actions/rerun-portfolio-optimizer-at-target-sigma]]})}
           (str "Re-run at σ = " (sigma-label pending))]])])))
