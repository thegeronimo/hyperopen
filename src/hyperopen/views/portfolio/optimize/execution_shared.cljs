(ns hyperopen.views.portfolio.optimize.execution-shared
  "Pure, presentation-level helpers shared by the Execution tab surface
  (hyperopen.views.portfolio.optimize.execution-tab), its extracted order-table
  namespace (hyperopen.views.portfolio.optimize.execution-order-table), and the
  execution-strategy band (hyperopen.views.portfolio.optimize.execution-strategy-band).
  Kept in one place so no view file carries the others' bulk while all reuse identical
  formatting, order-type, cost-recompute, and chip helpers."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.execution-order-type :as execution-order-type]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(def order-type-labels
  ;; "Passive maker" (not bare "Passive") everywhere the type is named: post-only maker
  ;; posture is the meaning, and it must never read as a synonym for Limit.
  {:market "Market" :limit "Limit" :twap "TWAP" :passive "Passive maker"})

(def order-types [:market :limit :twap :passive])

(defn resting-type?
  "Limit and Passive orders rest at a price — they don't cross the book, so the
  book-crossing slippage estimate doesn't apply to them."
  [order-type]
  (contains? #{:limit :passive} order-type))

(defn crossing-type?
  "Market and TWAP orders cross the book — they pay market-impact slippage and the taker
  fee. Limit and Passive rest as maker orders: no market impact, the lower maker fee."
  [order-type]
  (not (resting-type? order-type)))

(defn abs-num [value] (if (number? value) (js/Math.abs value) 0))

(defn finite [value] (opt-format/finite-number? value))

(defn format-bps
  [value]
  (if (finite value)
    (str (opt-format/format-decimal value {:maximum-fraction-digits 1}) " bp")
    "—"))

(defn format-knotional
  "Compact $Nk notional for the dense order list aggregates."
  [value]
  (let [amount (abs-num value)]
    (if (>= amount 1000)
      (str "$" (opt-format/format-decimal (/ amount 1000) {:maximum-fraction-digits 1}) "k")
      (opt-format/format-usdc amount))))

(def recommend-exec-type execution-order-type/recommend-exec-type)
(def effective-type execution-order-type/effective-type)
(def row-params execution-order-type/row-params)
(def high-cost-crossing-bps execution-order-type/high-cost-crossing-bps)
(def high-cost-crossing-row? execution-order-type/high-cost-crossing-row?)
(def crossing-cost-bps execution-order-type/crossing-cost-bps)
(def effective-crossing-cost execution-order-type/effective-crossing-cost)

(defn twap-clip-schedule-label
  "Editor copy for how a row's TWAP is actually worked. With a usable notional both the
  venue's clip count and the gap between clips are known (\"41 clips · one every 30s\").
  Without one only the bounds are: the venue spaces clips wider than the 30s floor rather
  than let a clip fall under $10, so the copy states the bound instead of asserting a
  cadence it cannot know."
  [row minutes]
  (let [{:keys [clips interval-seconds notional-known?]}
        (execution-order-type/twap-clip-schedule row minutes)
        gap (cond
              (not (finite interval-seconds)) nil
              (< interval-seconds 90) (str (js/Math.round interval-seconds) "s")
              :else (str "~" (js/Math.round (/ interval-seconds 60)) "m"))]
    (cond
      (nil? gap) (str clips " clips")
      notional-known? (str clips " clips · one every " gap)
      :else (str "up to " clips " clips · no closer than " gap " apart"))))

;; ── live type-aware cost recompute ────────────────────────────────────────

(defn effective-crossing-cost-bps
  "The price-cost bps the row's EFFECTIVE type would pay (TWAP rows pay the sliced TWAP
  model, not the one-shot walk) -- for the high-cost warning and the row cost cell.
  nil for resting rows or rows with no finite estimate."
  [model row]
  (let [{:keys [crossing? slippage-bps]} (effective-crossing-cost model row)]
    (when (and crossing? (finite slippage-bps))
      (abs-num slippage-bps))))

(defn floor-prefixed
  "Prefixes a formatted cost with the lower-bound marker when the estimate carries
  floor semantics (a depth-overrun estimate is a capped floor, not a point estimate)."
  [floor? text]
  (if floor? (str "≥ " text) text))

(defn type-aware-costs
  "Recomputes price cost (spread + book impact) + fees from each row's LIVE effective order
  type so the KPI strip, health rail, and strategy tiles react to type changes without
  re-staging. Market (and other one-shot crossing) rows pay their full spread + impact +
  taker fee; :twap rows pay the sliced TWAP estimate (effective-crossing-cost) -- impact
  divided across venue suborders with a permanent-impact residue -- plus the taker fee;
  resting (limit/passive) rows contribute no spread/impact and the maker fee. Returns the
  totals, the spread/impact split, the maker/taker split, the crossing-row price-cost bps
  samples for the average, :floor? true when any included crossing estimate is a
  depth-overrun lower bound, and :fees-unknown? true when a row's cost map carries no fee
  for its effective type (surfaces render such totals with a \"≥\" prefix — the sum is
  missing a term, and a resting row pays no spread or impact, so silently reading its fee
  as 0 would publish a confident $0.00 all-in for a real order)."
  [model rows]
  (reduce
   (fn [acc row]
     (let [{:keys [crossing? slippage-bps estimated-slippage-usd spread-usd impact-usd
                   estimate-floor?]} (effective-crossing-cost model row)
           cost (:cost row)
           fee-usd (if crossing? (:estimated-fee-usd cost) (:maker-fee-usd cost))
           slip-usd (if crossing? (or estimated-slippage-usd 0) 0)
           has-split? (some? spread-usd)
           spread-usd* (if (and crossing? has-split?) spread-usd 0)
           ;; Attribute an un-splittable crossing cost (flat fallback / no book) entirely to
           ;; impact so spread + impact always reconciles to the price-cost total.
           impact-usd* (cond (not crossing?) 0
                             has-split? (or impact-usd 0)
                             :else slip-usd)]
       (cond-> acc
         true (update :slippage-usd + slip-usd)
         true (update :spread-usd + spread-usd*)
         true (update :impact-usd + impact-usd*)
         true (update :fees-usd + (if (finite fee-usd) fee-usd 0))
         true (update (if crossing? :taker-count :maker-count) inc)
         (not (finite fee-usd)) (assoc :fees-unknown? true)
         (and crossing? estimate-floor?) (assoc :floor? true)
         (and crossing? (finite slippage-bps)) (update :slip-bps conj (abs-num slippage-bps)))))
   {:slippage-usd 0 :spread-usd 0 :impact-usd 0 :fees-usd 0
    :taker-count 0 :maker-count 0 :slip-bps [] :floor? false :fees-unknown? false}
   rows))

(defn fee-mix-label
  [{:keys [taker-count maker-count fees-unknown?]}]
  (str (cond
         (and (pos? taker-count) (pos? maker-count)) (str taker-count " taker · " maker-count " maker")
         (pos? maker-count) "maker · resting rows"
         :else "taker · ready rows")
       ;; Never let a missing fee assumption pass as a cheap total.
       (when fees-unknown? " · fee unknown for some rows")))

(defn cost-total-incomplete?
  "True when a cost total understates what will be paid — a depth-overrun floor, or a row
  whose fee the estimate doesn't know. Both make the printed number a lower bound (\"≥\")."
  [{:keys [floor? fees-unknown?]}]
  (boolean (or floor? fees-unknown?)))

(defn price-cost-split-text
  "\"spread $X + impact $Y\" for the crossing rows, or nil when nothing crosses the book."
  [{:keys [spread-usd impact-usd taker-count]}]
  (when (pos? taker-count)
    (str "spread " (opt-format/format-usdc spread-usd)
         " + impact " (opt-format/format-usdc impact-usd))))

(defn type-mix-summary
  "\"7 market · 3 passive maker\" summary of the effective types across the given rows,
  in the canonical order-type order; nil when there are no rows."
  [model rows]
  (let [counts (frequencies (map #(effective-type model %) rows))
        parts (keep (fn [t]
                      (when-let [n (get counts t)]
                        (str n " " (.toLowerCase (order-type-labels t)))))
                    order-types)]
    (when (seq parts)
      (apply str (interpose " · " parts)))))

;; ── commit-moment margin / leverage copy ──────────────────────────────────
;; The execution figure is account leverage (gross notional ÷ equity, the same
;; metric the account-equity panels show as "Cross/Unified Account Leverage"),
;; not a maintenance-margin ratio. The :warning still rides margin utilization
;; (margin used ÷ equity) so a thin-headroom commit is flagged red.

(defn margin-warn?
  [margin]
  (boolean (and (:warning margin) (not= :none (:warning margin)))))

(defn format-compact-usd
  "Compact $ with M/k suffixes for headroom figures (e.g. $4.62M, $8.6k, $940)."
  [value]
  (let [amount (abs-num value)]
    (cond
      (>= amount 1e6) (str "$" (opt-format/format-decimal (/ amount 1e6) {:maximum-fraction-digits 2}) "M")
      (>= amount 1000) (str "$" (opt-format/format-decimal (/ amount 1000) {:maximum-fraction-digits 1}) "k")
      :else (opt-format/format-usdc amount {:maximum-fraction-digits 0}))))

(defn leverage-after-label
  "Projected account leverage multiple after the rebalance, e.g. \"1.85x\"."
  [margin]
  (opt-format/format-multiple (:after-gross-leverage margin)))

(defn leverage-headroom-sub
  "Sub-line under the leverage figure: prior leverage + free-margin headroom + the
  venue-lens bridge (perp notional ÷ collateral — the trade page's Unified Account
  Leverage formula, shown ≈ because collateral is approximated), or a thin-headroom
  caution when margin utilization is in warning range. `full?` appends the equity
  base (for the wider health rail)."
  ([margin] (leverage-headroom-sub margin false))
  ([margin full?]
   (if (margin-warn? margin)
     "thin margin headroom — review before arming"
     (let [before (:before-gross-leverage margin)
           free (:free-margin-usd margin)
           equity (:capital-usd margin)
           venue (:after-venue-leverage margin)
           was (when (finite before) (str "was " (opt-format/format-multiple before)))
           head (when (finite free)
                  (str (format-compact-usd free) " free"
                       (when (and full? (finite equity))
                         (str " of " (format-compact-usd equity) " equity"))))
           venue-part (when (finite venue)
                        (str "venue ≈" (opt-format/format-multiple venue)))]
       (str/join " · " (remove nil? [was head venue-part]))))))

;; ── shared bits ─────────────────────────────────────────────────────────

(defn eyebrow
  [text]
  [:p {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.18em]" "text-trading-muted/70"]}
   text])

(defn chip
  [label tone]
  [:span {:class ["optimizer-chip" "border" "px-1.5" "py-[1px]" "font-mono"
                  "text-[0.53125rem]" "font-semibold" "uppercase" "tracking-[0.12em]"]
          :data-optimizer-chip "true"
          :data-tone (name tone)}
   label])
