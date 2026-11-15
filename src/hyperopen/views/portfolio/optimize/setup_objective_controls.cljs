(ns hyperopen.views.portfolio.optimize.setup-objective-controls
  (:require [hyperopen.portfolio.optimizer.application.return-views :as return-views]
            [hyperopen.views.portfolio.optimize.setup-controls :as controls]
            [hyperopen.views.portfolio.optimize.target-sigma :as target-sigma]))

(declare primary-goal-card secondary-goal-card)

(defn- goal-summary
  "Header one-liner for the collapsed Optimization goal panel: the selected goal
  plus what choosing it means, so the panel reads without opening it."
  [objective-kind]
  (case objective-kind
    :max-sharpe "Maximum Sharpe — best risk-adjusted return, uses your views"
    :equal-risk "Equal Risk — balance each position's share of portfolio risk"
    :inverse-volatility "Risk-weighted sizing — size each asset by its own volatility"
    :target-volatility "Target volatility — max return at a pinned σ"
    :target-return "Target return — lowest risk at a required return"
    "Minimum risk — lowest volatility, no return forecast needed"))

(defn- max-sharpe-kicker
  [draft]
  (if (= :black-litterman (get-in draft [:return-model :kind]))
    (return-views/returns-contract-label
     (return-views/summary
      (return-views/rows {:universe (:universe draft)
                          :views (get-in draft [:return-model :views])})))
    "Uses your saved views where available"))

(defn objective-section
  ([draft highlighted-controls]
   (objective-section draft highlighted-controls nil))
  ([draft highlighted-controls sigma-bounds]
   (let [objective-kind (get-in draft [:objective :kind])]
     (controls/disclosure-panel-open
      "portfolio-optimizer-objective-panel"
      (controls/disclosure-heading "Optimization goal" (goal-summary objective-kind))
      [:p {:class ["mt-2" "text-[0.8125rem]" "text-trading-muted"]}
       "Choose what the optimizer should prioritize."]
      [:div {:class ["mt-3" "optimizer-goal-grid-container"]}
       [:div {:class ["grid" "gap-1.5" "optimizer-goal-grid-primary"]}
       (primary-goal-card
        "Minimum risk" "Lowest volatility · no return forecast needed"
        nil
        (= :minimum-variance objective-kind)
        "portfolio-optimizer-objective-minimum-variance"
        [:actions/apply-portfolio-optimizer-setup-preset :conservative])
       (primary-goal-card
        "Equal Risk" "Balance each position's share of portfolio risk · no return forecast needed"
        nil
        (= :equal-risk objective-kind)
        "portfolio-optimizer-objective-equal-risk"
        [:actions/set-portfolio-optimizer-objective-kind :equal-risk])
       (primary-goal-card
        "Maximum Sharpe" "Best risk-adjusted return · sensitive to noisy return estimates"
        (max-sharpe-kicker draft)
        (= :max-sharpe objective-kind)
        "portfolio-optimizer-objective-max-sharpe"
        [:actions/apply-portfolio-optimizer-setup-preset :max-sharpe])]]
      (let [parameterized? (contains? #{:target-volatility :target-return} objective-kind)]
        [:details (cond-> {:class ["mt-3"]
                           :data-role "portfolio-optimizer-more-goals"}
                    parameterized? (assoc :open true))
         [:summary {:class (into ["cursor-pointer" "select-none" "focus:outline-none"
                                  "focus:text-warning"]
                                 controls/eyebrow-class)}
          "More goals"]
         [:div {:class ["mt-2" "grid" "grid-cols-1" "gap-1.5" "sm:grid-cols-2"]}
          (secondary-goal-card "Target volatility" "Pin σ to a fixed level, max return at that σ"
                               (= :target-volatility objective-kind)
                               "portfolio-optimizer-objective-target-volatility"
                               [:actions/set-portfolio-optimizer-objective-kind :target-volatility])
          (secondary-goal-card "Target return" "Aim for a specific return"
                               (= :target-return objective-kind)
                               "portfolio-optimizer-objective-target-return"
                               [:actions/set-portfolio-optimizer-objective-kind :target-return])
          (secondary-goal-card "Risk-weighted sizing"
                               "Size by each asset's own volatility · keeps every asset · ignores correlations"
                               (= :inverse-volatility objective-kind)
                               "portfolio-optimizer-objective-inverse-volatility"
                               [:actions/set-portfolio-optimizer-objective-kind :inverse-volatility])]
         (when parameterized?
           [:div {:class ["mt-2"]}
            (case objective-kind
              :target-volatility
              (target-sigma/objective-parameter-block draft sigma-bounds)
              :target-return
              (controls/percent-input "Target Return"
                                      (controls/decimal->percent-text
                                       (or (get-in draft [:objective :target-return]) 0.15))
                                      "portfolio-optimizer-objective-target-return-input"
                                      [:actions/set-portfolio-optimizer-objective-parameter-percent
                                       :target-return [:event.target/value]]
                                      (contains? highlighted-controls :target-return)
                                      "Type a percent — 15 = 15%"))])])))))

(defn- primary-goal-card
  "Rich goal card for the canonical paths (Minimum risk, Equal Risk, Maximum
  Sharpe): a title, a plain-language subtitle, an optional mono kicker line
  (live views count), and an active badge. Minimum risk and Maximum Sharpe
  dispatch the matching setup preset so the objective AND its return-model
  pairing move together — this is what the retired top-of-page preset cards
  did. Equal Risk sets only the objective: it never reads return forecasts, so
  there is no return-model pairing to apply."
  [title subtitle kicker selected? role action]
  [:button {:type "button"
            :class (cond-> ["optimizer-choice-card" "optimizer-goal-card" "optimizer-goal-card-primary"
                            "border" "border-base-300" "bg-base-100/70" "px-3" "py-2.5" "text-left"
                            "transition-colors" "hover:border-warning/50"]
                     selected? (conj "border-warning/70" "bg-warning/10"))
            :aria-pressed (str selected?)
            :data-role role
            :on {:click [action]}}
   [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
    [:div
     [:p {:class ["text-[0.8125rem]" "font-medium" "text-trading-text"]}
      [:span {:class (if selected? "text-warning" "text-trading-muted")} (if selected? "◉ " "○ ")]
      title]
     [:p {:class ["mt-1.5" "text-[0.75rem]" "text-trading-muted"]} subtitle]
     (when kicker
       [:p {:class ["mt-1.5" "font-mono" "text-[0.625rem]" "uppercase" "tracking-[0.16em]"
                    "text-trading-muted/70"]}
        kicker])]]])

(defn- secondary-goal-card
  "Compact goal card for the parameterized targets (Target volatility / Target
  return): sets only the objective, leaving the return model untouched. Rendered
  smaller and under a 'More goals' label so it never competes with the canonical
  paths."
  [title subtitle selected? role action]
  [:button {:type "button"
            :class (cond-> ["optimizer-choice-card" "optimizer-goal-card" "optimizer-goal-card-secondary"
                            "border" "border-base-300" "bg-base-200/20" "p-2"
                            "text-left" "transition-colors" "hover:border-warning/50"]
                     selected? (conj "border-warning/60" "bg-warning/10"))
            :aria-pressed (str selected?)
            :data-role role
            :on {:click [action]}}
   [:p {:class ["text-[0.8125rem]" "font-medium" "text-trading-text"]}
    [:span {:class (if selected? "text-warning" "text-trading-muted")} (if selected? "◉ " "○ ")]
    title
    [:span {:class ["sr-only"]} title]]
   [:p {:class ["mt-1" "text-[0.75rem]" "text-trading-muted"]} subtitle]])
