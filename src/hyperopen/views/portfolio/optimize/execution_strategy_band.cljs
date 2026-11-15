(ns hyperopen.views.portfolio.optimize.execution-strategy-band
  "The staged-phase control band of the Execution tab: the execution-strategy selector
  (Recommended / Market / Passive maker / TWAP) with live projected costs per strategy,
  plus the high-cost-crossing warning. Extracted from
  hyperopen.views.portfolio.optimize.execution-tab so the strategy surface and its
  warning fit within the namespace size budget.

  Honesty contract: every tile's consequence line is projected from the SAME
  type-aware cost recompute the KPI strip uses, over the state the click would actually
  produce (current per-row overrides preserved) — so the selector is a real tradeoff
  comparison, never marketing copy. The high-cost warning shares its 20bp threshold with
  the routing policy (execution-order-type/high-cost-crossing-bps), so Recommended can
  never route a row the warning still flags."
  (:require [hyperopen.views.portfolio.optimize.execution-shared :as shared]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(defn- ready-rows [rows] (filterv #(= :ready (:status %)) rows))

(defn- selections-for
  "The staging selections that clicking the given strategy tile would produce: the
  default flips, per-row overrides and params stay."
  [model strategy]
  {:default-order-type strategy
   :overrides (or (:overrides model) {})
   :params (or (:params model) {})})

(defn- projected-costs
  [model strategy rows]
  (shared/type-aware-costs (selections-for model strategy) rows))

(defn- all-in-usd
  [costs]
  (+ (:slippage-usd costs) (:fees-usd costs)))

(defn- all-in-label
  [costs]
  (shared/floor-prefixed (shared/cost-total-incomplete? costs)
                         (opt-format/format-usdc (all-in-usd costs))))

(defn- twap-unsliced?
  "True when NO row this strategy would work as a TWAP actually gets the sliced model:
  without a live book to split, twap-cost passes the flat one-shot estimate through
  unchanged, so the tile's number is identical to Market. Saying so beats showing two
  tiles with the same figure and no explanation."
  [model rows]
  (let [selections (selections-for model :twap)
        twap-rows (filter #(= :twap (shared/effective-type selections %)) rows)]
    (and (seq twap-rows)
         (not-any? #(:twap-adjusted? (shared/effective-crossing-cost selections %))
                   twap-rows))))

(def ^:private strategies
  [{:id :recommended
    :title "Recommended"
    :sub "Routes each order by cost and size"}
   {:id :market
    :title "Market"
    :sub "Cross the spread on every order — fastest"}
   {:id :passive
    :title "Passive maker"
    :sub "Post-only maker orders — may not fill"}
   {:id :twap
    :title "TWAP"
    :sub "Work every order over time"}])

(defn- mode-tile
  [{:keys [id title sub consequence active?]}]
  [:button {:type "button"
            :class (cond-> ["optimizer-exec-mode-tile" "flex" "flex-col" "gap-0.5" "px-3" "py-2"
                            "border-l" "border-base-300" "text-left"]
                     active? (conj "is-active"))
            :data-role (str "portfolio-optimizer-execution-mode-" (name id))
            :data-active (str (boolean active?))
            :on {:click [[:actions/set-portfolio-optimizer-execution-default-order-type id]]}}
   [:span {:class ["flex" "items-center" "gap-2"]}
    [:span {:class ["optimizer-exec-mode-dot"]}]
    [:span {:class ["text-xs" "font-medium" "text-trading-text"]} title]]
   [:span {:class ["pl-4" "font-mono" "optimizer-exec-mode-sub" "text-trading-muted"]} sub]
   (when (seq consequence)
     [:span {:class ["pl-4" "font-mono" "optimizer-exec-mode-sub" "tabular-nums" "text-trading-text/80"]
             :data-role (str "portfolio-optimizer-execution-mode-" (name id) "-consequence")}
      consequence])])

(defn- tile-consequence
  "The live projected consequence line for one strategy tile: the est. all-in cost of
  the plan under that strategy (with current overrides kept), plus the routing mix for
  Recommended so 'the algo picks' is inspectable at a glance. nil with no ready rows."
  [model strategy sendable]
  (when (seq sendable)
    (let [costs (projected-costs model strategy sendable)
          cost-text (str "est. all-in " (all-in-label costs))]
      (cond
        (= :recommended strategy)
        (let [mix (shared/type-mix-summary (selections-for model :recommended) sendable)]
          (if mix (str mix " · " cost-text) cost-text))

        (and (= :twap strategy) (twap-unsliced? model sendable))
        (str cost-text " · same as Market — no live book to slice")

        :else cost-text))))

(defn high-cost-crossing-rows
  "Ready rows whose EFFECTIVE type under the current selections crosses the book at a
  high estimated cost (>= shared/high-cost-crossing-bps). Costs are effective-type
  aware: a TWAP row is judged on its sliced TWAP estimate, so working a clip over time
  can legitimately take it under the bar. These are the rows the trader is about to pay
  real spread on — under Recommended they route passive, so this is non-empty only when
  a bulk Market/TWAP default or per-row overrides expose them."
  [model rows]
  (filterv (fn [row]
             (when-let [bps (shared/effective-crossing-cost-bps model row)]
               (>= bps shared/high-cost-crossing-bps)))
           (ready-rows rows)))

(defn- rest-passively-actions
  [rows]
  (mapv (fn [row]
          [:actions/set-portfolio-optimizer-execution-row-order-type (:row-id row) :passive])
        rows))

(defn- high-cost-warning
  [model rows]
  (let [flagged (high-cost-crossing-rows model rows)]
    (when (seq flagged)
      (let [sendable (ready-rows rows)
            current (projected-costs model (or (:default-order-type model) :recommended) sendable)
            fixed-model (update model :overrides
                                (fn [overrides]
                                  (reduce #(assoc %1 (:row-id %2) :passive)
                                          (or overrides {})
                                          flagged)))
            fixed (projected-costs fixed-model (or (:default-order-type model) :recommended) sendable)
            listed (->> flagged
                        (sort-by #(- (or (shared/effective-crossing-cost-bps model %) 0)))
                        (map #(str (:instrument-label %) " "
                                   (shared/format-bps (shared/effective-crossing-cost-bps model %))))
                        (take 6))
            more (- (count flagged) (count listed))]
        [:div {:class ["flex" "flex-wrap" "items-center" "gap-3" "border-t" "border-base-300"
                       "optimizer-exec-highcost" "px-5" "py-2.5"]
               :data-role "portfolio-optimizer-execution-high-cost-warning"
               :role "status"}
         (shared/chip "high cost" :warn)
         [:div {:class ["min-w-0"]}
          [:p {:class ["text-xs" "font-medium" "text-trading-text"]}
           (str (count flagged) " order" (when (not= 1 (count flagged)) "s")
                " cross" (when (= 1 (count flagged)) "es")
                " the spread at ≥ " shared/high-cost-crossing-bps " bp estimated cost: "
                (apply str (interpose " · " listed))
                (when (pos? more) (str " · +" more " more")))]
          [:p {:class ["mt-0.5" "font-mono" "text-[0.68rem]" "tabular-nums" "text-trading-muted"]}
           (str "Resting " (if (= 1 (count flagged)) "it as a passive maker order" "them as passive maker orders")
                " cuts est. all-in cost from "
                (all-in-label current) " to " (all-in-label fixed)
                (if (= 1 (count flagged)) " — but it may not fill." " — but they may not fill."))]]
         [:span {:class ["flex-1"]}]
         [:button {:type "button"
                   :class ["border" "border-warning/60" "px-3" "py-1.5" "text-xs"
                           "font-semibold" "text-warning" "shrink-0"]
                   :data-role "portfolio-optimizer-execution-rest-high-cost"
                   :on {:click (rest-passively-actions flagged)}}
          (str "Rest " (if (= 1 (count flagged)) "it" "these") " passively")]]))))

(defn staged-band
  [{:keys [default-order-type commit-blocked? commit-blocked-message stale? stale-message] :as model} rows]
  (let [sendable (ready-rows rows)]
    [:div {:class ["border-b" "border-base-300"]
           :data-role "portfolio-optimizer-execution-control-band"
           :data-phase "staged"}
     (when commit-blocked?
       [:p {:class ["optimizer-exec-readonly" "border-b" "border-base-300" "px-5" "py-2"
                    "text-xs" "font-semibold" "text-warning"]
            :data-role "portfolio-optimizer-execution-readonly"}
        commit-blocked-message])
     (when stale?
       [:p {:class ["optimizer-exec-readonly" "border-b" "border-base-300" "px-5" "py-2"
                    "text-xs" "font-semibold" "text-warning"]
            :data-role "portfolio-optimizer-execution-stale"}
        stale-message])
     [:div {:class ["flex" "items-stretch"]}
      [:div {:class ["flex" "flex-col" "justify-center" "gap-0.5" "px-5" "py-2" "shrink-0"]}
       [:span {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
        "Execution strategy"]
       [:span {:class ["font-mono" "text-[0.58rem]" "text-trading-muted"]}
        "override any order below →"]]
      (into [:div {:class ["flex" "flex-1" "border-l" "border-base-300"]}]
            (for [{:keys [id title sub]} strategies]
              (mode-tile {:id id
                          :title title
                          :sub sub
                          :consequence (tile-consequence model id sendable)
                          :active? (= id default-order-type)})))]
     (or (high-cost-warning model rows)
         [:p {:class ["border-t" "border-base-300" "bg-base-200/30" "px-5" "py-1.5"
                      "font-mono" "text-[0.65rem]" "text-trading-muted"]}
          "Recommended routes each order by estimated cost and clip size. Passive maker / Limit rest as maker orders and may not fully fill; TWAP works over time."])]))
