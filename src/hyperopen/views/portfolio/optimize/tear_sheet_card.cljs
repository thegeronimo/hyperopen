(ns hyperopen.views.portfolio.optimize.tear-sheet-card
  "Entry points into the hypothetical tear sheet on the Recommendation tab:
  a header link and a compact performance card. Both render only for solved
  runs that hold at least one vault."
  (:require [hyperopen.portfolio.optimizer.application.tear-sheet.model :as tear-sheet]
            [hyperopen.portfolio.optimizer.application.vault-funding.model :as vault-funding-model]
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

(def ^:private spark-w 600)

(def ^:private spark-h 120)

(defn- spark-scale
  "x/y scales shared by every line on the card so they compare honestly."
  [series]
  (let [pts (mapcat :raw-points series)
        ts (map :time-ms pts)
        vs (map :value pts)]
    (when (seq pts)
      (let [t0 (apply min ts)
            span-t (max 1 (- (apply max ts) t0))
            lo (apply min vs)
            span-v (let [d (- (apply max vs) lo)] (if (pos? d) d 1))
            pad 6]
        {:x (fn [t] (* spark-w (/ (- t t0) span-t)))
         :y (fn [v] (- spark-h pad (* (- spark-h (* 2 pad)) (/ (- v lo) span-v))))}))))

(defn- spark-path
  [{:keys [x y]} points]
  (when (>= (count points) 2)
    (->> points
         (map-indexed (fn [i {:keys [time-ms value]}]
                        (str (if (zero? i) "M" "L") (.toFixed (x time-ms) 1) " " (.toFixed (y value) 1))))
         (apply str))))

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
      (let [series (:raw-series model)
            scale (spark-scale series)
            last-join (:time-ms (peek (get-in model [:run :joins])))
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
          [:div {:class ["flex" "flex-wrap" "gap-2"]}
           ;; A mixed run's verdict CTA stays "Review trades" for its perp legs,
           ;; so its vault legs get their own way in here. An all-vault run's
           ;; verdict CTA is already "Fund this allocation".
           (if (vault-funding-model/all-vault-result? result)
             [:span {:class ["hidden"]}]
             [:button {:type "button"
                       :class ["min-h-9" "rounded-md" "border" "border-base-300" "px-4" "text-sm" "text-trading-text"
                               "hover:bg-base-200/40"]
                       :data-role "portfolio-optimizer-tear-sheet-card-fund"
                       :on {:click [[:actions/portfolio-optimizer-vault-funding-command :open]]}}
              "Fund the vaults"])
           [:button {:type "button"
                     :class ["min-h-9" "rounded-md" "bg-[#a493ff]" "px-4" "text-sm" "font-semibold" "text-[#120f26]"
                             "hover:bg-[#b8aaff]"]
                     :data-role "portfolio-optimizer-tear-sheet-card-open"
                     :on {:click (open-actions scenario-id)}}
            "Open tear sheet"]]]
         [:svg {:viewBox "0 0 600 120" :preserveAspectRatio "none" :class ["mt-2" "block" "h-[120px]" "w-full"]
                :role "img" :aria-label "Hypothetical cumulative return over the last year"}
          ;; Always present (zero width when every asset is live): a nil hole
          ;; next to the keyed lines freezes Replicant's renderer.
          [:rect {:x 0 :y 0 :height spark-h :fill "rgba(164,147,255,0.08)"
                  :width (if (and scale last-join) (.toFixed ((:x scale) last-join) 1) 0)}]
          ;; Benchmarks first so the target draws on top.
          (for [{:keys [id stroke raw-points]} (reverse series)]
            ^{:key (str "spark-" (name id))}
            [:path {:d (or (when scale (spark-path scale raw-points)) "")
                    :fill "none" :stroke stroke
                    :stroke-width (if (= :strategy id) "2" "1.25")
                    :stroke-opacity (if (= :strategy id) "1" "0.75")
                    :vector-effect "non-scaling-stroke"}])]
         [:div {:class ["flex" "flex-wrap" "gap-x-4" "gap-y-1" "px-4" "pb-2" "text-xs" "text-trading-muted"]
                :data-role "portfolio-optimizer-tear-sheet-card-legend"}
          (for [{:keys [id label stroke]} series]
            ^{:key (str "spark-legend-" (name id))}
            [:span {:class ["inline-flex" "items-center" "gap-1.5"]}
             [:span {:class ["h-0.5" "w-3"] :style {:background-color stroke}}]
             label])
          [:span {:class (into ["items-center" "gap-1.5"] (if last-join ["inline-flex"] ["hidden"]))}
           [:span {:class ["h-2.5" "w-3" "rounded-sm" "bg-[#a493ff]/20"]}]
           "Fewer assets live"]]
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
