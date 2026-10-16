(ns hyperopen.views.portfolio.optimize.tear-sheet-card
  "Entry points into the hypothetical tear sheet on the Recommendation tab:
  a header link and a compact performance card. Both render only for solved
  runs that hold at least one vault."
  (:require [hyperopen.portfolio.optimizer.application.tear-sheet.model :as tear-sheet]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.vaults.detail.activity.performance-metrics :as metrics-view]))

(def hypothetical-badge-classes
  ["rounded-full" "border" "border-[#a493ff]/70" "bg-[#a493ff]/10" "px-2" "py-0.5"
   "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.14em]" "text-[#d6cfff]"])

(defn open-actions
  [scenario-id]
  [[:actions/navigate (portfolio-routes/portfolio-optimize-tear-sheet-path scenario-id)]])

(defn header-link
  "The scenario header's \"Hypothetical tear sheet\" button, or nil."
  [{:keys [scenario-id result]}]
  (when (tear-sheet/vault-run? result)
    [:button {:type "button"
              :class ["inline-flex" "min-h-8" "items-center" "gap-1.5" "rounded-md" "border"
                      "border-[#a493ff]/70" "bg-[#a493ff]/10" "px-3" "text-xs" "font-medium"
                      "text-trading-text" "hover:bg-[#a493ff]/20"]
              :data-role "portfolio-optimizer-tear-sheet-link"
              :on {:click (open-actions scenario-id)}}
     [:svg {:viewBox "0 0 16 16" :class ["h-3.5" "w-3.5"] :fill "none" :stroke "currentColor"
            :stroke-width "1.5" :aria-hidden true}
      [:path {:d "M2 13l4-5 3 3 5-7"}]
      [:path {:d "M2 2v12h12"}]]
     "Hypothetical tear sheet"]))

(defn format-volatility
  [v]
  (if (number? v)
    (str (.toFixed (* 100 v) 2) "%")
    "--"))

(defn- sparkline-path
  [rows width height]
  (when (>= (count rows) 2)
    (let [t0 (first (first rows))
          t1 (first (peek rows))
          vs (map second rows)
          lo (apply min vs)
          hi (apply max vs)
          span-t (max 1 (- t1 t0))
          span-v (if (== hi lo) 1 (- hi lo))
          pad 6]
      (->> rows
           (map-indexed (fn [i [t v]]
                          (str (if (zero? i) "M" "L")
                               (.toFixed (* width (/ (- t t0) span-t)) 1) " "
                               (.toFixed (- height pad (* (- height (* 2 pad)) (/ (- v lo) span-v))) 1))))
           (apply str)))))

(defn- stat
  [label value data-role tone]
  [:div {:class ["min-w-0" "border-r" "border-base-300" "px-4" "py-3" "last:border-r-0"]}
   [:div {:class ["text-xs" "text-trading-muted"]} label]
   [:div {:class (into ["num" "mt-1" "text-base"] tone)
          :data-role data-role}
    value]])

(defn card
  "The compact \"Hypothetical performance\" card, or nil for runs without
  vaults or without history to backtest."
  [state {:keys [scenario-id result]}]
  (when-let [{:keys [model stats legs]} (tear-sheet/cached-card-model state result)]
    (when model
      (let [rows (get-in model [:run :rows])
            joined (count (filter :joins-in-window? (:legs model)))
            ret (:cumulative-return stats)]
        [:section {:class ["mx-4" "mt-4" "overflow-hidden" "rounded-xl" "border" "border-[#a493ff]/40" "bg-[#a493ff]/5"]
                   :replicant/key "optimizer-tear-sheet-card"
                   :data-role "portfolio-optimizer-tear-sheet-card"}
         [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-3" "px-4" "pt-4"]}
          [:div
           [:div {:class ["flex" "items-center" "gap-2"]}
            [:span {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-[#d6cfff]"]}
             "Hypothetical performance · 1Y"]
            [:span {:class hypothetical-badge-classes} "Backtest"]]
           [:p {:class ["mt-1.5" "text-sm" "text-trading-muted"]}
            "These target weights, rebalanced daily, over each vault's own history."]]
          [:button {:type "button"
                    :class ["min-h-9" "rounded-md" "bg-[#a493ff]" "px-4" "text-sm" "font-semibold" "text-[#120f26]"
                            "hover:bg-[#b8aaff]"]
                    :data-role "portfolio-optimizer-tear-sheet-card-open"
                    :on {:click (open-actions scenario-id)}}
           "Open tear sheet"]]
         [:svg {:viewBox "0 0 600 120" :preserveAspectRatio "none" :class ["mt-2" "block" "h-[120px]" "w-full"]
                :role "img" :aria-label "Hypothetical cumulative return over the last year"}
          [:path {:d (or (sparkline-path rows 600 120) "")
                  :fill "none" :stroke tear-sheet/strategy-stroke :stroke-width "2"
                  :vector-effect "non-scaling-stroke"}]]
         [:div {:class ["grid" "grid-cols-2" "border-t" "border-base-300" "sm:grid-cols-4"]}
          (stat "1Y return" (metrics-view/format-metric-value :percent ret)
                "portfolio-optimizer-tear-sheet-card-return"
                [(if (and (number? ret) (neg? ret)) "text-trading-red" "text-trading-green")])
          (stat "Max drawdown" (metrics-view/format-metric-value :percent (:max-drawdown stats))
                "portfolio-optimizer-tear-sheet-card-drawdown" ["text-trading-red"])
          (stat "Sharpe" (metrics-view/format-metric-value :ratio (:sharpe stats))
                "portfolio-optimizer-tear-sheet-card-sharpe" ["text-trading-text"])
          (stat "Volatility" (format-volatility (:volatility stats))
                "portfolio-optimizer-tear-sheet-card-volatility" ["text-trading-text"])]
         [:div {:class ["flex" "flex-col" "gap-1" "border-t" "border-base-300" "px-4" "py-3" "text-xs" "text-trading-muted"]}
          [:span (if (pos? joined)
                   (str (- legs joined) " of " legs " assets had history a year ago; until each one starts, its weight is spread over the others.")
                   (str "All " legs " assets have history across the whole year."))]
          [:span {:class ["text-[#e2b545]"]}
           "In-sample: the optimizer chose these weights using this same history."]]]))))
