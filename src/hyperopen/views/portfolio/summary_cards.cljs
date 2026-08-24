(ns hyperopen.views.portfolio.summary-cards
  (:require [hyperopen.views.portfolio.format :as portfolio-format]))

(defn summary-selector
  "Range/scope chip.

  `custom` is optional and only the chart-side range selector passes it. When a
  custom range is live the chip shows its span and grows a clear control; the
  clear dispatches a plain preset selection, because the preset was never
  overwritten and selecting it again is exactly what \"restore what I had\" means."
  [{:keys [label open? options value custom]}
   toggle-action
   select-action
   data-role]
  [:div {:class ["relative" "flex" "items-center"]
         :data-role data-role}
   [:button {:type "button"
             :class ["flex"
                     "items-center"
                     "gap-1.5"
                     "rounded-md"
                     "px-2"
                     "py-1"
                     "text-xs"
                     "font-normal"
                     "text-trading-text"
                     "hover:bg-base-200"]
             :aria-expanded (boolean open?)
             :data-role (str data-role "-trigger")
             :on {:click [[toggle-action]]}}
    [:span label]
    [:svg {:class (into ["h-4" "w-4" "text-trading-text-secondary" "transition-transform"]
                        (when open?
                          ["rotate-180"]))
           :fill "none"
           :stroke "currentColor"
           :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round"
             :stroke-linejoin "round"
             :stroke-width 2
             :d "M19 9l-7 7-7-7"}]]]
   (when (:active? custom)
     [:button {:type "button"
               :class ["ml-0.5"
                       "inline-flex"
                       "h-5"
                       "w-5"
                       "items-center"
                       "justify-center"
                       "rounded"
                       "text-xs"
                       "text-trading-green"
                       "hover:bg-base-200"]
               :aria-label "Clear custom range"
               :data-role (str data-role "-clear")
               :on {:click [(into [(:clear-action custom)] (:clear-args custom))]}}
      "x"])
   [:div {:class (into ["absolute"
                        "right-0"
                        "top-full"
                        "mt-1"
                        "min-w-[160px]"
                        "overflow-hidden"
                        "rounded-md"
                        "border"
                        "border-base-300"
                        "bg-base-100"
                        "spectate-lg"
                        "z-30"]
                       (if open?
                         ["opacity-100" "scale-y-100" "translate-y-0"]
                         ["opacity-0" "scale-y-95" "-translate-y-1" "pointer-events-none"]))
          :style {:transition "all 80ms ease-in-out"}}
    (for [{option-value :value option-label :label} options]
      ^{:key (str data-role "-" (name option-value))}
      [:button {:type "button"
                :class (into ["block"
                              "w-full"
                              "px-3"
                              "py-2"
                              "text-left"
                              "text-xs"
                              "hover:bg-base-200"]
                             (if (= option-value value)
                               ["text-trading-text" "bg-base-200"]
                               ["text-trading-text-secondary"]))
                :aria-pressed (= option-value value)
                :data-role (str data-role "-option-" (name option-value))
                :on {:click [[select-action option-value]]}}
       option-label])
    (when (:open-action custom)
      [:button {:replicant/key (str data-role "-option-custom")
                :type "button"
                :class (into ["flex"
                              "w-full"
                              "items-center"
                              "justify-between"
                              "border-t"
                              "border-base-300"
                              "px-3"
                              "py-2"
                              "text-left"
                              "text-xs"
                              "hover:bg-base-200"]
                             (if (or (:active? custom) (:open? custom))
                               ["text-trading-text" "bg-base-200"]
                               ["text-trading-text-secondary"]))
                :aria-pressed (boolean (:active? custom))
                :data-role (str data-role "-option-custom")
                :on {:click [(into [(:open-action custom)] (:open-args custom))]}}
       [:span "Custom\u2026"]
       [:span {:class ["text-trading-text-secondary"]} "\u2316"]])]])

(defn section-card [data-role & children]
  (into [:div {:class ["rounded-xl"
                       "border"
                       "border-base-300"
                       "bg-base-100/95"
                       "overflow-hidden"]
               :data-role data-role}]
        children))

(defn- summary-row [label value & [value-class]]
  [:div {:class ["summary-kv-row" "grid" "grid-cols-[1fr_auto]" "items-center" "gap-3"]}
   [:span {:class ["text-sm" "text-trading-text-secondary"]}
    label]
   [:span {:class (into ["num" "text-sm" "text-trading-text"] (or value-class []))}
    value]])

(defn- pnl-summary [pnl]
  (let [n (if (number? pnl) pnl 0)
        color-class (cond
                      (pos? n) "text-success"
                      (neg? n) "text-error"
                      :else "text-trading-text")]
    {:value (str (cond
                   (pos? n) "+"
                   (neg? n) "-"
                   :else "")
                (portfolio-format/format-currency (js/Math.abs n)))
     :class [color-class]}))

(defn summary-card [{:keys [summary selectors]}]
  (let [pnl-info (pnl-summary (:pnl summary))
        summary-scope (:summary-scope selectors)
        summary-time-range (:summary-time-range selectors)]
    (section-card
     "portfolio-account-summary-card"
     [:div {:class ["summary-card-head" "flex" "items-center" "justify-between" "border-b" "border-base-300" "px-4" "py-3"]}
      (summary-selector summary-scope
                        :actions/toggle-portfolio-summary-scope-dropdown
                        :actions/select-portfolio-summary-scope
                        "portfolio-summary-scope-selector")
      (summary-selector summary-time-range
                        :actions/toggle-portfolio-summary-time-range-dropdown
                        :actions/select-portfolio-summary-time-range
                        "portfolio-summary-time-range-selector")]
     [:div {:class ["space-y-2.5" "px-4" "py-3"]}
      (summary-row "PNL" (:value pnl-info) (:class pnl-info))
      (summary-row "Volume" (portfolio-format/format-currency (:volume summary)))
      (summary-row "Max Drawdown" (portfolio-format/format-drawdown (:max-drawdown-pct summary)))
      (summary-row "Total Equity" (portfolio-format/format-currency (:total-equity summary)))
      (when (:show-perps-account-equity? summary)
        (summary-row "Perps Account Equity" (portfolio-format/format-currency (:perps-account-equity summary))))
      (summary-row (:spot-equity-label summary) (portfolio-format/format-currency (:spot-account-equity summary)))
      (when (:show-vault-equity? summary)
        (summary-row "Vault Equity" (portfolio-format/format-currency (:vault-equity summary))))
      (when (:show-earn-balance? summary)
        (summary-row "Earn Balance" (portfolio-format/format-currency (:earn-balance summary))))
      (when (:show-staking-account? summary)
        ;; An explicit unknown, never a fabricated zero: a vanished or wrong
        ;; number is the exact failure this surface is meant to stop.
        (summary-row "Staking Account"
                     (if-let [hype (:staking-account-hype summary)]
                       (portfolio-format/format-hype hype)
                       "--")))
      ;; "Staking Account" silently folds in HYPE that is mid-unstake and cannot
      ;; be traded. Break that portion out so the headline number is not read as
      ;; spendable.
      (when-let [unstaking-hype (:staking-unstaking-hype summary)]
        [:div {:data-role "portfolio-summary-staking-unstaking"}
         (summary-row "In 7-day unstaking queue"
                      (portfolio-format/format-hype unstaking-hype)
                      ["text-ho-warn"])])])))

(defn metric-cards [{:keys [volume-14d-usd fees fee-schedule]}]
  (let [fee-schedule-open? (if (:open? fee-schedule) "true" "false")]
    [:div {:class ["grid" "grid-cols-2" "gap-3" "lg:grid-cols-1"]}
   (section-card
    "portfolio-14d-volume-card"
    [:div {:class ["space-y-2.5" "px-3" "py-3" "sm:px-4"]}
     [:div {:class ["text-xs" "uppercase" "tracking-wide" "text-trading-text-secondary" "sm:text-sm" "sm:normal-case" "sm:tracking-normal"]}
      "14 Day Volume"]
     [:div {:class ["num" "text-2xl" "font-medium" "text-trading-text" "sm:text-4xl"]}
      (portfolio-format/format-compact-currency volume-14d-usd)]
     [:button {:type "button"
               :class ["btn" "btn-xs" "btn-spectate" "justify-start" "px-0" "text-xs" "text-trading-green" "hover:bg-transparent" "sm:text-xs"]
               :data-role "portfolio-volume-history-trigger"
               :on {:click [[:actions/open-portfolio-volume-history
                              :event.currentTarget/bounds]]}}
      "View Volume"]])
   (section-card
    "portfolio-fees-card"
    [:div {:class ["space-y-2.5" "px-3" "py-3" "sm:px-4"]}
     [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
      [:span {:class ["text-xs" "uppercase" "tracking-wide" "text-trading-text-secondary" "sm:text-sm" "sm:normal-case" "sm:tracking-normal"]}
       "Fees (Taker / Maker)"]
      [:button {:class ["btn" "btn-spectate" "btn-xs" "px-2" "text-xs" "text-trading-text" "sm:text-xs"]}
       "Perps"]]
     [:div {:class ["num" "text-2xl" "font-medium" "leading-tight" "text-trading-text" "sm:text-4xl"]}
      (str (portfolio-format/format-fee-pct (:taker fees)) " / " (portfolio-format/format-fee-pct (:maker fees)))]
     [:button {:type "button"
               :class ["btn" "btn-xs" "btn-spectate" "justify-start" "px-0" "text-xs" "text-trading-green" "hover:bg-transparent" "sm:text-xs"]
               :aria-haspopup "dialog"
               :aria-expanded fee-schedule-open?
               :data-role "portfolio-fee-schedule-trigger"
               :on {:click [[:actions/open-portfolio-fee-schedule
                             :event.currentTarget/bounds]]}}
      "View Fee Schedule"]])]))
