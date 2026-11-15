(ns hyperopen.views.portfolio.optimize.execution-tab
  "Execution surface — where a solved rebalance is staged for review and commit. The
  standalone Rebalance preview tab was retired; \"Rebalance\" CTAs open this tab directly.
  A dense, phase-aware order list (staged → armed → running → done | halted | partial | resting) with
  a Buys/Sells flow summary, a per-order type editor and an Execution-health diagnostics rail.

  This namespace holds the header, phase control bands, the KPI strip, the health rail,
  and the latest-attempt panel. The order table + per-order editor live in the sibling
  namespace hyperopen.views.portfolio.optimize.execution-order-table; the staged-phase
  execution-strategy selector + high-cost warning live in
  hyperopen.views.portfolio.optimize.execution-strategy-band; pure presentation helpers
  shared by all live in hyperopen.views.portfolio.optimize.execution-shared.

  Honesty: each ready row is submitted with its selected order type (Market/Limit/TWAP/
  Passive maker), and the displayed type is the routed type — see execution-order-type."
  (:require [clojure.string :as str]
            [hyperopen.margin-rec.state :as margin-rec-state]
            [hyperopen.portfolio.optimizer.application.view-model :as optimizer-view-model]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.views.portfolio.optimize.execution-order-table :as order-table]
            [hyperopen.views.portfolio.optimize.execution-shared :as shared]
            [hyperopen.views.portfolio.optimize.execution-strategy-band :as strategy-band]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(defn- instrument-group-key
  "Collapses multi-leg instruments under one asset name (e.g. a perp + spot leg of the
  same asset count once), mirroring the retired rebalance preview so the Buys/Sells
  asset counts agree with what the rebalance produced."
  [labels-by-instrument instrument-id]
  (let [value (or (get labels-by-instrument instrument-id)
                  (str instrument-id))
        unprefixed (last (str/split value #":"))
        base (first (str/split unprefixed #"[/-]"))]
    (if (seq base) base value)))

(defn- side-totals
  "Buys/Sells dollar flow across the SENDABLE rows only, so the headline matches
  the staged notional and is never inflated by blocked or skipped legs (a
  within-tolerance skip is real money that will NOT move)."
  [labels-by-instrument rows]
  (reduce
   (fn [acc {:keys [side delta-notional-usd instrument-id]}]
     (let [amt (shared/abs-num delta-notional-usd)
           asset (instrument-group-key labels-by-instrument instrument-id)]
       (case side
         :buy (-> acc (update :buys + amt) (update :buy-assets conj asset))
         :sell (-> acc (update :sells + amt) (update :sell-assets conj asset))
         acc)))
   {:buys 0 :sells 0 :buy-assets #{} :sell-assets #{}}
   (remove #(contains? #{:blocked :skipped} (:status %)) rows)))

(defn- fill-counts
  "Status breakdown of the displayed order rows so every summary surface agrees and a resting
  (open) order is never reported as filled. :total is the run denominator — queued/working +
  accepted (filled or resting) + failed — i.e. every row that belongs to the run."
  [rows]
  (let [by (frequencies (map :status rows))
        filled (get by :submitted 0)
        resting (get by :resting 0)
        failed (get by :failed 0)
        blocked (get by :blocked 0)
        ready (+ (get by :ready 0) (get by :working 0))]
    {:filled filled
     :resting resting
     :failed failed
     :blocked blocked
     :ready ready
     :total (+ ready filled resting failed)}))

(defn- status-tag
  [phase]
  (let [[label tone] (case phase
                       :done ["complete" :long]
                       :resting ["resting" :info]
                       :halted ["halted" :short]
                       :partial ["partial" :warn]
                       :running ["executing" :live]
                       :armed ["armed" :warn]
                       ["staged" :accent])]
    (shared/chip label tone)))

(defn- subtitle
  [phase]
  (case phase
    :done "· all orders filled"
    :resting "· orders resting on the book"
    :halted "· halted — partial fills sent"
    :partial "· partial — resting orders still working"
    :running "· sending live orders"
    :armed "· armed — confirm to send"
    "· staged from the rebalance"))

(defn- overflow-menu
  [{:keys [phase]}]
  [:details {:class ["optimizer-exec-overflow"]
             :data-role "portfolio-optimizer-execution-overflow"}
   [:summary {:class ["border" "border-base-300" "px-3" "py-2" "text-sm" "font-medium"
                      "text-trading-muted" "select-none"]}
    "⋯"]
   [:div {:class ["optimizer-exec-overflow-menu" "border" "border-base-300" "bg-base-100" "py-1"]}
    [:button {:type "button"
              :class ["block" "w-full" "px-3" "py-1.5" "text-left" "text-xs" "text-trading-text"]
              :data-role "portfolio-optimizer-execution-open-ticket"
              :on {:click [[:actions/open-portfolio-optimizer-execution-in-ticket]]}}
     "Open in trade ticket ↗"]
    (when-not (= :running phase)
      [:details {:class ["optimizer-exec-overflow-confirm"]
                 :data-role "portfolio-optimizer-execution-discard-confirm"}
       [:summary {:class ["block" "w-full" "px-3" "py-1.5" "text-left" "text-xs" "text-trading-red"
                          "select-none" "cursor-pointer"]}
        "Abort & discard…"]
       [:div {:class ["px-3" "py-2"]}
        [:p {:class ["text-[0.7rem]" "text-trading-muted"]}
         "Clears the staged plan. You'll re-stage from the rebalance preview. The execution ledger is kept for audit."]
        [:button {:type "button"
                  :class ["mt-2" "w-full" "border" "border-trading-red/60" "px-3" "py-1.5"
                          "text-xs" "font-semibold" "text-trading-red"]
                  :data-role "portfolio-optimizer-execution-discard"
                  :on {:click [[:actions/discard-portfolio-optimizer-execution]]}}
         "Confirm discard"]]])]])

(defn- header
  [{:keys [phase arm-disabled? arm-disabled-message rows] :as model}]
  (let [ready-count (count (filter #(= :ready (:status %)) rows))]
  [:div {:class ["flex" "flex-wrap" "items-end" "justify-between" "gap-3"
                 "border-b" "border-base-300" "bg-base-100/95" "px-5" "py-3"]
         :data-role "portfolio-optimizer-execution-header"}
   [:div
    (shared/eyebrow "Execution · review and commit")
    [:div {:class ["mt-1" "flex" "flex-wrap" "items-center" "gap-2"]}
     [:span {:class ["text-lg" "font-semibold" "tracking-[-0.01em]" "text-trading-text"]}
      "Execution"]
     [:span {:class ["text-xs" "text-trading-muted"]} (subtitle phase)]
     (status-tag phase)]]
   [:div {:class ["flex" "items-center" "gap-2"]}
    (overflow-menu model)
    (if (= :done phase)
      [:button {:type "button"
                :class ["border" "border-base-300" "px-3" "py-2" "text-sm" "font-medium" "text-trading-muted"]
                :data-role "portfolio-optimizer-execution-view-tracking"
                :on {:click [[:actions/set-portfolio-optimizer-results-tab :tracking]]}}
       "View tracking →"]
      [:button {:type "button"
                :class ["border" "border-base-300" "px-3" "py-2" "text-sm" "font-medium" "text-trading-muted"]
                :data-role "portfolio-optimizer-execution-back"
                :on {:click [[:actions/set-portfolio-optimizer-results-tab :recommendation]]}}
       "Back to recommendation"])
    (when (= :staged phase)
      [:button {:type "button"
                :class ["optimizer-primary-action" "border" "px-3" "py-2" "text-sm" "font-semibold"
                        "disabled:cursor-not-allowed" "disabled:border-base-300"
                        "disabled:bg-base-200/40" "disabled:text-trading-muted"]
                :data-role "portfolio-optimizer-execution-arm"
                :disabled (boolean arm-disabled?)
                :title (when arm-disabled? arm-disabled-message)
                :on (when-not arm-disabled?
                      {:click [[:actions/set-portfolio-optimizer-execution-phase :armed]]})}
       (if (pos? ready-count)
         (str "Arm " ready-count " order" (when (not= 1 ready-count) "s"))
         "Arm execution")])]]))

(defn- order-summary-line
  [model rows]
  (shared/type-mix-summary model (filter #(= :ready (:status %)) rows)))

(defn- armed-band
  [{:keys [confirm-disabled? disabled-message summary
           ready-buys-usd ready-sells-usd
           margin-rec-auto-topup? margin-rec-isolated-legs] :as model} rows]
  (let [order-count (count (filter #(= :ready (:status %)) rows))
        type-summary (order-summary-line model rows)
        gross (:gross-ready-notional-usd summary)
        margin (:margin summary)
        after-lev (:after-gross-leverage margin)
        before-lev (:before-gross-leverage margin)
        margin-warn? (shared/margin-warn? margin)]
    [:div {:class ["optimizer-exec-band" "is-armed" "flex" "items-center" "gap-4"
                   "border-b" "border-base-300" "px-5" "py-3"]
           :data-role "portfolio-optimizer-execution-control-band"
           :data-phase "armed"
           :role "status"
           :aria-live "polite"}
     (shared/chip "confirm" :warn)
     [:div {:class ["min-w-0"]}
      [:p {:class ["text-[0.8125rem]" "font-medium" "text-trading-text"]}
       (str "Send " order-count " live order" (when (not= 1 order-count) "s") " to Hyperliquid")]
      [:p {:class ["mt-0.5" "font-mono" "text-[0.7rem]" "tabular-nums" "text-trading-text"]
           :data-role "portfolio-optimizer-execution-armed-figures"}
       (str "+" (opt-format/format-usdc ready-buys-usd) " buys · "
            "−" (opt-format/format-usdc ready-sells-usd) " sells"
            (when (shared/finite gross) (str " · gross " (opt-format/format-usdc gross)))
            (when (shared/finite after-lev)
              (str " · leverage " (opt-format/format-multiple after-lev)
                   (when (shared/finite before-lev)
                     (str " (was " (opt-format/format-multiple before-lev) ")")))))]
      [:p {:class (cond-> ["mt-0.5" "font-mono" "text-[0.72rem]" "text-trading-muted"]
                    margin-warn? (conj "text-trading-red"))}
       (str (when (seq type-summary) (str "Order types: " type-summary ". "))
            "Filled trades can't be undone — closing them later sends new market orders at then-current prices and costs.")]
      (when (pos? (or margin-rec-isolated-legs 0))
        [:label {:class ["mt-1" "flex" "w-fit" "cursor-pointer" "items-center" "gap-1.5"
                         "font-mono" "text-[0.7rem]" "text-trading-muted"]
                 :data-role "portfolio-optimizer-execution-auto-topup"}
         [:input {:type "checkbox"
                  :class ["optimizer-checkbox"]
                  :checked (boolean margin-rec-auto-topup?)
                  :on {:change [[:actions/set-margin-rec-auto-topup
                                 (not margin-rec-auto-topup?)]]}}]
         (str "Auto top-up isolated margin to the modeled recommendation after fills ("
              margin-rec-isolated-legs " isolated leg"
              (when (not= 1 margin-rec-isolated-legs) "s") ")")])]
     [:span {:class ["flex-1"]}]
     [:button {:type "button"
               :class ["border" "border-base-300" "px-3" "py-2" "text-sm" "font-medium" "text-trading-muted"]
               :data-role "portfolio-optimizer-execution-cancel"
               :on {:click [[:actions/set-portfolio-optimizer-execution-phase :staged]]}}
      "Cancel"]
     [:button {:type "button"
               :class ["optimizer-exec-commit" "border" "px-3" "py-2" "text-sm" "font-semibold"
                       "disabled:cursor-not-allowed" "disabled:border-base-300"
                       "disabled:bg-base-200/40" "disabled:text-trading-muted"]
               :data-role "portfolio-optimizer-execution-confirm"
               :disabled (boolean confirm-disabled?)
               :title (when confirm-disabled? disabled-message)
               :on (when-not confirm-disabled?
                     {:click [[:actions/confirm-portfolio-optimizer-execution]]})}
      "🔒 Confirm & send →"]]))

(defn- running-band
  [{:keys [abort-requested?]} rows]
  (let [{:keys [filled resting total]} (fill-counts rows)
        accepted (+ filled resting)
        pct (if (pos? total) (/ accepted total) 0)
        pct-100 (js/Math.round (* 100 pct))]
    [:div {:class ["optimizer-exec-band" "is-running" "flex" "items-center" "gap-4"
                   "border-b" "border-base-300" "px-5" "py-3"]
           :data-role "portfolio-optimizer-execution-control-band"
           :data-phase "running"
           :role "status"
           :aria-live "polite"}
     [:span {:class ["optimizer-exec-pulse"]}]
     [:div {:class ["shrink-0"]}
      [:p {:class ["text-[0.8125rem]" "text-trading-text"]}
       (if abort-requested? "Stopping — no new orders" "Submitting live orders")]
      [:p {:class ["mt-0.5" "font-mono" "text-[0.65rem]" "text-trading-muted"]}
       (str filled " filled"
            (when (pos? resting) (str " · " resting " resting"))
            " / " total
            (when abort-requested? " · in-flight orders still settle"))]]
     [:div {:class ["optimizer-exec-progress" "flex-1" "max-w-[520px]"]
            :role "progressbar"
            :aria-label "Execution progress"
            :aria-valuemin "0"
            :aria-valuemax "100"
            :aria-valuenow (str pct-100)}
      [:div {:class ["optimizer-exec-progress-fill"]
             :style {:width (str (* 100 pct) "%")}}
       [:span {:class ["optimizer-exec-progress-shimmer"]}]]]
     [:span {:class ["font-mono" "text-xs" "text-warning"]}
      (str pct-100 "%")]
     (if abort-requested?
       [:button {:type "button"
                 :class ["border" "border-base-300" "px-3" "py-2" "text-sm" "font-medium"
                         "text-trading-muted" "cursor-not-allowed"]
                 :data-role "portfolio-optimizer-execution-pause"
                 :disabled true}
        "Stopping…"]
       [:button {:type "button"
                 :class ["border" "border-trading-red/60" "px-3" "py-2" "text-sm" "font-medium" "text-trading-red"]
                 :data-role "portfolio-optimizer-execution-pause"
                 :title "Stop releasing new orders. In-flight orders still settle."
                 :on {:click [[:actions/pause-portfolio-optimizer-execution]]}}
        "Pause new orders"])]))

(defn- done-band
  [rows]
  (let [{:keys [filled blocked]} (fill-counts rows)]
    [:div {:class ["optimizer-exec-band" "is-done" "flex" "items-center" "gap-4"
                   "border-b" "border-base-300" "px-5" "py-3"]
           :data-role "portfolio-optimizer-execution-control-band"
           :data-phase "done"
           :role "status"
           :aria-live "polite"}
     (shared/chip "complete" :long)
     [:p {:class ["text-[0.8125rem]" "font-medium" "text-trading-text"]}
      (str "Execution complete — "
           (if (pos? filled)
             (str filled " order" (when (not= 1 filled) "s") " acknowledged")
             "orders acknowledged")
           (when (pos? blocked)
             (str " · " blocked " below minimum (not sent)")))]
     [:span {:class ["flex-1"]}]
     [:button {:type "button"
               :class ["optimizer-primary-action" "border" "px-3" "py-2" "text-sm" "font-semibold"]
               :data-role "portfolio-optimizer-execution-view-tracking"
               :on {:click [[:actions/set-portfolio-optimizer-results-tab :tracking]]}}
      "View tracking →"]]))

(defn- halted-band
  "Attention band for a run stopped by a rejection. `phase` is :halted (nothing live any
  more) or :partial (rejected rows halted the release loop, but earlier resting orders are
  still live on the book and keep filling) — same recovery actions, honest headline."
  [{:keys [error confirm-disabled?] :as _model} rows phase]
  (let [{:keys [filled resting failed]} (fill-counts rows)
        partial? (= :partial phase)
        resume-from (when-let [nos (seq (keep :order-no (filter #(= :failed (:status %)) rows)))]
                      (apply min nos))]
    [:div {:class ["optimizer-exec-band" (if partial? "is-partial" "is-halted")
                   "flex" "items-center" "gap-4"
                   "border-b" "border-base-300" "px-5" "py-3"]
           :data-role "portfolio-optimizer-execution-control-band"
           :data-phase (name phase)
           :role "alert"
           :aria-live "assertive"}
     (shared/chip (if partial? "partial" "halted") (if partial? :warn :short))
     [:div {:class ["min-w-0"]}
      [:p {:class ["text-[0.8125rem]" "font-medium" "text-trading-text"]}
       (str (if partial? "Execution partial — " "Execution halted — ")
            filled " filled · "
            (when (pos? resting) (str resting " resting · "))
            failed " failed")]
      [:p {:class ["mt-0.5" "font-mono" "text-[0.65rem]" "text-trading-muted"]}
       (or error
           (if partial?
             "One or more orders were rejected — no further orders will be released. Resting orders stay live on the book and keep filling."
             "One or more orders were rejected. Subsequent orders are never auto-retried."))]]
     [:span {:class ["flex-1"]}]
     [:button {:type "button"
               :class ["border" "border-trading-red/60" "px-3" "py-2" "text-sm" "font-medium" "text-trading-red"
                       "disabled:cursor-not-allowed" "disabled:border-base-300" "disabled:text-trading-muted"]
               :data-role "portfolio-optimizer-execution-revert"
               :disabled (zero? filled)
               :title (if (zero? filled)
                        "No filled orders to revert."
                        "Sends new reduce-only market orders to unwind the fills — pays spread, impact, and fees again.")
               :on (when (pos? filled)
                     {:click [[:actions/revert-portfolio-optimizer-execution-filled]]})}
      "Revert filled (market orders)"]
     [:button {:type "button"
               :class ["border" "border-base-300" "px-3" "py-2" "text-sm" "font-medium" "text-trading-muted"]
               :data-role "portfolio-optimizer-execution-restage"
               :on {:click [[:actions/restage-portfolio-optimizer-execution-smaller]]}}
      "Re-stage smaller"]
     [:button {:type "button"
               :class ["optimizer-primary-action" "border" "px-3" "py-2" "text-sm" "font-semibold"
                       "disabled:cursor-not-allowed" "disabled:border-base-300"
                       "disabled:bg-base-200/40" "disabled:text-trading-muted"]
               :data-role "portfolio-optimizer-execution-resume"
               :disabled (boolean confirm-disabled?)
               :on (when-not confirm-disabled?
                     {:click [[:actions/resume-portfolio-optimizer-execution]]})}
      (if resume-from (str "Resume from #" resume-from) "Resume")]]))

(defn- resting-band
  [_model rows]
  (let [{:keys [filled resting blocked]} (fill-counts rows)]
    [:div {:class ["optimizer-exec-band" "is-done" "flex" "items-center" "gap-4"
                   "border-b" "border-base-300" "px-5" "py-3"]
           :data-role "portfolio-optimizer-execution-control-band"
           :data-phase "resting"
           :role "status"
           :aria-live "polite"}
     (shared/chip "resting" :info)
     [:div {:class ["min-w-0"]}
      [:p {:class ["text-[0.8125rem]" "font-medium" "text-trading-text"]}
       (str resting " order" (when (not= 1 resting) "s") " resting on the book"
            (when (pos? filled)
              (str " · " filled " filled outright"))
            (when (pos? blocked)
              (str " · " blocked " below minimum (not sent)")))]
      [:p {:class ["mt-0.5" "font-mono" "text-[0.65rem]" "text-trading-muted"]}
       "Open limit orders are live on Hyperliquid — they fill as the market reaches your price. Manage or cancel them from the trade ticket."]]
     [:span {:class ["flex-1"]}]]))

(defn- carryover-note
  "Pre-submit notice that resting orders from a PREVIOUS run are still live on the book
  and will be cancelled before this run's orders are sent — without the cancellation
  those stale orders would fill on top of the new run and over-allocate the account."
  [{:keys [phase carryover-count]}]
  (when (and (contains? #{:staged :armed} phase)
             (pos? (or carryover-count 0)))
    [:div {:replicant/key "execution-carryover-note"
           :class ["flex" "items-center" "gap-2" "border-b" "border-base-300"
                   "bg-base-200/30" "px-5" "py-2"]
           :data-role "portfolio-optimizer-execution-carryover-note"}
     (shared/chip "cleanup" :info)
     [:p {:class ["text-xs" "text-trading-muted"]}
      (str carryover-count " resting order" (when (not= 1 carryover-count) "s")
           " from a previous optimizer run "
           (if (= 1 carryover-count) "is" "are")
           " still on the book — "
           (if (= 1 carryover-count) "it" "they")
           " will be cancelled before new orders are sent.")]]))

(defn- overlap-order-row
  [{:keys [oid coin side sz limit-px cancel?]}]
  [:li {:replicant/key (str "overlap-" oid)
        :class ["flex" "items-center" "justify-between" "gap-3" "py-1.5"]}
   [:div {:class ["min-w-0" "font-mono" "text-[0.7rem]" "text-trading-text"]}
    [:span {:class ["font-semibold"]} coin]
    [:span {:class ["ml-2" "text-trading-muted"]}
     (str (some-> side str) " · " sz (when limit-px (str " @ " limit-px)))]]
   [:button {:type "button"
             :class (into ["border" "px-2" "py-1" "text-[0.7rem]" "font-medium"]
                          (if cancel?
                            ["border-error/60" "text-error"]
                            ["border-base-300" "text-trading-muted"]))
             :data-role "portfolio-optimizer-execution-overlap-toggle"
             :data-oid (str oid)
             :data-cancel (str (boolean cancel?))
             :on {:click [[:actions/set-portfolio-optimizer-execution-overlap-cancel
                           oid (not cancel?)]]}}
    (if cancel? "Will cancel" "Keep")]])

(defn- overlap-decision
  "Cross-session decision surface: open orders that overlap the rebalance but were NOT
  placed by the optimizer (manual orders, or optimizer orders from before cloid tagging).
  The app can't prove they're safe to auto-cancel, so the user chooses per order; checked
  ones are cancelled before new orders are sent."
  [{:keys [phase overlap-orders]}]
  (when (and (contains? #{:staged :armed} phase)
             (seq overlap-orders))
    (let [n (count overlap-orders)
          chosen (count (filter :cancel? overlap-orders))]
      [:div {:replicant/key "execution-overlap-decision"
             :class ["border-b" "border-base-300" "bg-base-200/20" "px-5" "py-3"]
             :data-role "portfolio-optimizer-execution-overlap"}
       [:div {:class ["flex" "items-center" "gap-2"]}
        (shared/chip "review" :short)
        [:p {:class ["text-xs" "text-trading-muted"]}
         (str n " open order" (when (not= 1 n) "s")
              " overlap" (when (= 1 n) "s") " this rebalance and "
              (if (= 1 n) "wasn't" "weren't")
              " placed by the optimizer. Choose which to cancel before executing"
              (when (pos? chosen) (str " — " chosen " selected")) ".")]]
       (into [:ul {:class ["mt-2" "divide-y" "divide-base-300/60"]}]
             (map overlap-order-row overlap-orders))])))

(defn- control-band
  [{:keys [phase] :as model} rows]
  (case phase
    :armed (armed-band model rows)
    :running (running-band model rows)
    :done (done-band rows)
    :resting (resting-band model rows)
    :halted (halted-band model rows :halted)
    :partial (halted-band model rows :partial)
    (strategy-band/staged-band model rows)))

(def ^:private type-aware-costs shared/type-aware-costs)
(def ^:private fee-mix-label shared/fee-mix-label)
(def ^:private price-cost-split-text shared/price-cost-split-text)

(defn- price-cost-sub
  [costs avg-bps]
  (cond
    (price-cost-split-text costs)
    (str (price-cost-split-text costs)
         (when avg-bps (str " · " (shared/format-bps avg-bps) " avg")))
    (pos? (+ (:taker-count costs) (:maker-count costs))) "resting — no market impact"
    :else "no ready rows"))

(defn- kpi
  [{:keys [data-role label value value-class sub info]}]
  [:div {:class ["optimizer-kpi-card" "border-r" "border-base-300" "px-3" "py-2.5" "last:border-r-0"]
         :data-role data-role}
   [:p {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]
        :title info}
    label
    (when info [:span {:class ["ml-1" "cursor-help" "text-trading-muted/50"]} "ⓘ"])]
   [:p {:class ["mt-1" "font-mono" "text-sm" "font-semibold" "tabular-nums" (or value-class "text-trading-text")]}
    value]
   (when (seq sub)
     [:p {:class ["mt-0.5" "font-mono" "text-[0.65rem]" "tabular-nums" "text-trading-muted"]} sub])])

(defn- kpi-strip
  [{:keys [summary phase labels-by-instrument] :as model} rows]
  (let [{:keys [buys sells buy-assets sell-assets]} (side-totals labels-by-instrument rows)
        ready (filter #(contains? #{:ready :working} (:status %)) rows)
        submitted (filter #(= :submitted (:status %)) rows)
        resting (filter #(= :resting (:status %)) rows)
        failed (filter #(= :failed (:status %)) rows)
        blocked (filter #(= :blocked (:status %)) rows)
        total (+ (count ready) (count submitted) (count resting) (count failed))
        filled (count submitted)
        resting-count (count resting)
        filled-notional (reduce + 0 (map #(shared/abs-num (:delta-notional-usd %)) submitted))
        staged-notional (or (:gross-ready-notional-usd summary)
                            (reduce + 0 (map #(shared/abs-num (:delta-notional-usd %))
                                             (concat ready submitted resting failed))))
        costs (type-aware-costs model (concat ready submitted resting))
        slip-bps-samples (:slip-bps costs)
        avg-bps (when (seq slip-bps-samples)
                  (/ (reduce + 0 slip-bps-samples) (count slip-bps-samples)))
        post-run? (contains? #{:done :resting :halted :partial} phase)
        realized-rows (filter #(get-in % [:realized :slippage-bps]) submitted)
        realized-bps (when (seq realized-rows)
                       (/ (reduce + 0 (map #(get-in % [:realized :slippage-bps]) realized-rows))
                          (count realized-rows)))
        realized-usd (reduce + 0 (keep #(get-in % [:realized :slippage-usd]) submitted))
        show-realized? (and post-run? (seq realized-rows))
        price-cost-usd (if show-realized? realized-usd (:slippage-usd costs))
        all-in-usd (+ price-cost-usd (:fees-usd costs))
        margin (:margin summary)
        margin-warn? (shared/margin-warn? margin)
        orders-value (str filled " / " total)]
    [:section {:class ["optimizer-rebalance-kpis" "grid" "grid-cols-2" "border-b" "border-base-300"
                       "bg-base-100/95" "sm:grid-cols-4" "lg:grid-cols-4"]
               :data-role "portfolio-optimizer-execution-kpis"}
     (kpi {:data-role "portfolio-optimizer-execution-kpi-orders"
           :label "Orders filled"
           :value orders-value
           :value-class (cond (= :halted phase) "text-trading-red"
                              (= :partial phase) "text-warning"
                              (= :done phase) "text-trading-green"
                              (= :resting phase) "text-info"
                              :else "text-trading-text")
           :sub (cond (pos? (count blocked)) (str (count blocked) " blocked")
                      (pos? resting-count) (str resting-count " resting on book")
                      (= :done phase) "all venues acked"
                      :else "awaiting release")})
     (kpi {:data-role "portfolio-optimizer-execution-kpi-notional"
           :label "Notional executed"
           :value (shared/format-knotional filled-notional)
           :sub (str "of " (shared/format-knotional staged-notional) " staged")})
     (kpi {:data-role "portfolio-optimizer-execution-kpi-buys"
           :label "Buys"
           :value (str "+" (opt-format/format-usdc buys))
           :value-class "text-trading-green"
           :sub (str (count buy-assets) " assets")})
     (kpi {:data-role "portfolio-optimizer-execution-kpi-sells"
           :label "Sells"
           :value (str "−" (opt-format/format-usdc sells))
           :value-class "text-trading-red"
           :sub (str (count sell-assets) " assets")})
     (kpi {:data-role "portfolio-optimizer-execution-kpi-margin"
           :label "Gross leverage after"
           :info "Projected gross market exposure (perps + spot) ÷ account equity after the rebalance. This portfolio lens counts spot holdings as exposure, so it reads higher than the trade page's Unified Account Leverage, which measures perp notional against collateral only (shown here as \"venue\"). Red if margin headroom runs thin."
           :value (shared/leverage-after-label margin)
           :value-class (if margin-warn? "text-trading-red" "text-trading-text")
           :sub (shared/leverage-headroom-sub margin)})
     (if show-realized?
       (kpi {:data-role "portfolio-optimizer-execution-kpi-price-cost"
             :label "Realized price cost"
             :info "What you actually paid versus the mark the estimate used."
             :value (opt-format/format-usdc realized-usd)
             :sub (str "≈ " (shared/format-bps realized-bps) " avg · est "
                       (if avg-bps (shared/format-bps avg-bps) "—"))})
       (kpi {:data-role "portfolio-optimizer-execution-kpi-price-cost"
             :label "Est. price cost"
             :info "Price paid to execute = crossing the spread + walking the book (impact). Resting Limit/Passive orders pay neither. \"≥\" marks a lower bound: part of an order exceeds the visible book, so its estimate is a capped floor."
             :value (shared/floor-prefixed (:floor? costs)
                                           (opt-format/format-usdc (:slippage-usd costs)))
             :sub (price-cost-sub costs avg-bps)}))
     (kpi {:data-role "portfolio-optimizer-execution-kpi-fees"
           :label "Est. fees"
           :info "Exchange fees: taker for crossing orders, the lower maker fee for resting ones. \"≥\" marks a lower bound: a row's fee is missing from the estimate, so it is not in this total."
           :value (shared/floor-prefixed (:fees-unknown? costs)
                                         (opt-format/format-usdc (:fees-usd costs)))
           :sub (fee-mix-label costs)})
     (kpi {:data-role "portfolio-optimizer-execution-kpi-all-in"
           :label (if show-realized? "Realized all-in" "Est. all-in cost")
           :info "Total cost to execute = price cost + fees."
           :value (shared/floor-prefixed (and (not show-realized?)
                                              (shared/cost-total-incomplete? costs))
                                         (opt-format/format-usdc all-in-usd))
           :sub (if (:fees-unknown? costs)
                  "price cost + fees — a row's fee is unknown"
                  "price cost + fees")})]))

(defn- diag
  ([label value sub] (diag label value sub nil nil))
  ([label value sub status-label status-class]
   [:div {:class ["optimizer-diag"]}
    [:div {:class ["optimizer-diag-label"]}
     [:span label]
     (when status-label [:span {:class (cond-> ["optimizer-diag-status"] status-class (conj status-class))} status-label])]
    [:div {:class ["optimizer-diag-value" "font-mono" "tabular-nums"]} value]
    (when (seq sub)
      [:div {:class ["optimizer-diag-sub" "font-mono" "tabular-nums" "text-trading-muted/70"]} sub])]))

(defn- health-note
  [{:keys [phase]}]
  (let [[title items tone-class] (case phase
                                   :halted ["Halted — your move"
                                            ["Resume retries only the failed rows — already-filled orders are never re-sent."
                                             "Re-stage smaller re-stages the unfilled rows at half size for a fresh arm."
                                             "Revert filled sends NEW reduce-only market orders to unwind the fills — it pays spread, impact, and fees a second time."]
                                            "text-trading-red"]
                                   :partial ["Partial — resting orders still working"
                                             ["A rejection stopped new orders from being released — nothing else is sent automatically."
                                              "Your resting orders stay live on the book and keep filling as the market reaches your price."
                                              "Resume retries only the failed rows; Revert filled unwinds fills with new reduce-only market orders (paying costs again)."]
                                             "text-warning"]
                                   :done ["Execution complete"
                                          ["All ready orders were acknowledged by Hyperliquid."
                                           "Tracking has started against the recommendation."]
                                          "text-trading-green"]
                                   :resting ["Orders resting on the book"
                                             ["Your passive limit orders are live on Hyperliquid and fill as the market reaches your price — they are not filled yet."
                                              "Nothing else is sent automatically. Manage or cancel them from the trade ticket; Re-stage isn't needed."]
                                             "text-info"]
                                   :running ["Live — do not close"
                                             ["Each ready row is submitted with its selected order type."
                                              "Margin and slippage were checked when the plan was staged."]
                                             "text-trading-muted"]
                                   ["Before you arm"
                                    ["Estimated fills assume top-of-book and recent depth; real fills vary."
                                     "Arming requires a second confirm. No orders are live until then."
                                     "Each order routes by its selected type — Passive maker / Limit rest as maker orders and may not fill; TWAP works over time."]
                                    "text-trading-muted"])]
    [:div {:class ["p-3.5" "border-t" "border-base-300"]}
     [:div {:class ["optimizer-note"]
            :data-phase (name phase)}
      [:p {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.12em]" tone-class]} title]
      [:ul {:class ["mt-2" "space-y-1" "text-xs"]}
       (for [item items] [:li item])]]]))

(def ^:private live-book-cost-sources
  "Cost sources priced against a real order book — the L2 snapshot refresh (:snapshot,
  :depth-extrapolated when the order outruns visible depth) and the readiness-time
  orderbook contexts (:live-orderbook / :stale-orderbook, which price off the book's own
  touch either way; staleness rides the age line).

  Everything else is an assumption: :fallback-bps (the normalized :fallback-cost-assumption
  / nil — no book at all) and :untrusted-snapshot-fill (a book arrived but implied an
  implausible fill, so the flat fallback was charged instead)."
  #{:snapshot :depth-extrapolated :live-orderbook :stale-orderbook})

(defn- book-data-diag
  "What the cost estimates are actually standing on. A snapshot fetch that fails, is
  rate-limited, or never fires leaves every row priced from the flat fallback — which
  silently degrades the numbers (no spread/impact split, and TWAP collapses onto Market
  because there is nothing to slice). Say so here rather than letting an assumption
  read like a measurement."
  [priced-rows]
  (let [sources (map #(get-in % [:cost :source]) priced-rows)
        live (filter #(contains? live-book-cost-sources %) sources)
        live-count (count live)
        total (count sources)
        ages (keep #(when (contains? live-book-cost-sources (get-in % [:cost :source]))
                      (get-in % [:cost :age-ms]))
                   priced-rows)
        oldest (when (seq ages) (apply max ages))
        flat-count (- total live-count)]
    (cond
      (zero? total)
      (diag "Book data" "—" "no ready rows sampled")

      (zero? live-count)
      (diag "Book data" "flat estimate"
            "no live book — costs are assumptions and TWAP cannot be sliced"
            "assumed" "text-warning")

      :else
      (diag "Book data"
            (str live-count " of " total " live")
            (str/join " · "
                      (remove nil?
                              [(when oldest (str "book " (opt-format/format-duration oldest) " old"))
                               (when (pos? flat-count)
                                 (str flat-count " priced from a flat assumption"))]))))))

(defn- health-rail
  [{:keys [summary phase] :as model} rows]
  (let [ready (filter #(contains? #{:ready :working} (:status %)) rows)
        submitted (filter #(= :submitted (:status %)) rows)
        resting (filter #(= :resting (:status %)) rows)
        failed (filter #(= :failed (:status %)) rows)
        skipped-count (count (filter #(= :skipped (:status %)) rows))
        blocked-count (count (filter #(= :blocked (:status %)) rows))
        total (+ (count ready) (count submitted) (count resting) (count failed))
        filled (count submitted)
        pct (if (pos? total) (/ filled total) 0)
        margin (:margin summary)
        margin-warn? (shared/margin-warn? margin)
        costs (type-aware-costs model (concat ready submitted resting))
        sources (->> (concat ready submitted resting)
                     (keep #(get-in % [:cost :source]))
                     frequencies
                     (map (fn [[s n]] (str n " " (opt-format/keyword-label s))))
                     (str/join " · ")
                     not-empty)]
    [:aside {:class ["optimizer-exec-rail" "bg-base-100/95" "overflow-y-auto"]
             :data-role "portfolio-optimizer-execution-health"}
     [:div {:class ["border-b" "border-base-300" "px-4" "py-3"]}
      (shared/eyebrow "Execution health")]
     (diag "Fill progress"
           [:span filled [:span {:class ["text-trading-muted/70" "text-sm"]} (str " / " total)]]
           [:div {:class ["optimizer-exec-progress" "mt-2"]
                  :data-phase (name phase)
                  :role "progressbar"
                  :aria-label "Fill progress"
                  :aria-valuemin "0"
                  :aria-valuemax "100"
                  :aria-valuenow (str (js/Math.round (* 100 pct)))}
            [:div {:class ["optimizer-exec-progress-fill"]
                   :style {:width (str (* 100 pct) "%")}}]]
           (case phase :done "complete" :resting "resting" :halted "halted" :partial "partial" :running "live" "staged")
           (case phase :halted "text-trading-red" :partial "text-warning" :running "text-warning" :resting "text-info" nil))
     (diag "Gross leverage after"
           (shared/leverage-after-label margin)
           (shared/leverage-headroom-sub margin true)
           (if margin-warn? "breach" "ok")
           (if margin-warn? "text-trading-red" nil))
     (diag "Routing"
           (let [n (+ (count ready) (count submitted) (count resting))]
             (str n " order" (when (not= 1 n) "s")))
           (str (or (shared/type-mix-summary model (concat ready submitted resting)) "—")
                " · " skipped-count " skipped · " blocked-count " blocked"))
     (diag "Est. price cost"
           (shared/floor-prefixed (:floor? costs)
                                  (opt-format/format-usdc (:slippage-usd costs)))
           (or (not-empty (str/join " · " (remove nil? [(price-cost-split-text costs) sources])))
               "no ready rows sampled"))
     (diag "Est. fees"
           (shared/floor-prefixed (:fees-unknown? costs)
                                  (opt-format/format-usdc (:fees-usd costs)))
           (str (fee-mix-label costs) " on ready notional"))
     (diag "Est. all-in cost"
           (shared/floor-prefixed (shared/cost-total-incomplete? costs)
                                  (opt-format/format-usdc (+ (:slippage-usd costs) (:fees-usd costs))))
           (if (:fees-unknown? costs)
             "price cost + fees — a row's fee is unknown"
             "price cost + fees"))
     (book-data-diag (filter #(get-in % [:cost :source])
                             (concat ready submitted resting)))
     (health-note model)]))

(defn- latest-attempt-panel
  [{:keys [latest-attempt phase]}]
  (when (and (contains? #{:halted :partial :done :resting} phase) (seq (:rows latest-attempt)))
    [:section {:class ["border-t" "border-base-300" "bg-base-200/20" "px-4" "py-4"]
               :data-role "portfolio-optimizer-execution-latest-attempt"}
     [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
      [:div
       (shared/eyebrow "Latest attempt")
       [:p {:class ["mt-1" "text-xs" "text-trading-muted"]}
        "The most recent execution result, including any rejected rows."]]
      (shared/chip (opt-format/keyword-label (:status latest-attempt))
                   (case (:status latest-attempt)
                     :executed :long
                     :resting :info
                     (:failed :partially-executed) :short
                     :muted))]
     (into
      [:table {:class ["optimizer-table" "mt-3"]}
       [:thead [:tr [:th "Instrument"] [:th "Status"] [:th "Side"] [:th {:class ["right"]} "Notional"] [:th "Detail"]]]]
      (for [row (:rows latest-attempt)]
        [:tbody
         [:tr
          [:td [:span {:class ["font-mono" "font-semibold" "text-trading-text"]} (:instrument-label row)]]
          [:td (opt-format/keyword-label (:status row))]
          [:td (opt-format/keyword-label (:side row))]
          [:td {:class ["right" "num"]} (shared/format-knotional (:delta-notional-usd row))]
          [:td {:class ["text-trading-muted"]}
           (or (get-in row [:error :message]) (opt-format/keyword-label (:reason row)) "—")]]]))]))

(defn execution-tab
  [state]
  (let [{:keys [has-plan?] :as model} (optimizer-view-model/execution-tab-model state)
        rows (:rows model)
        model (assoc model
                     :margin-rec-auto-topup?
                     (trading-settings/margin-rec-auto-topup? state)
                     :margin-rec-isolated-legs
                     (count (margin-rec-state/isolated-execution-legs state rows)))]
    [:section {:class ["portfolio-optimizer-execution-tab" "border" "border-base-300" "bg-base-100/95"]
               :data-role "portfolio-optimizer-execution-tab"}
     (if has-plan?
       [:div
        (header model)
        (carryover-note model)
        (overlap-decision model)
        (control-band model rows)
        (kpi-strip model rows)
        [:div {:class ["grid" "grid-cols-1" "xl:grid-cols-[minmax(0,1fr)_380px]"]}
         (order-table/order-table model rows)
         (health-rail model rows)]
        (latest-attempt-panel model)]
       [:div {:class ["p-6"]
              :data-role "portfolio-optimizer-execution-empty"}
        (shared/eyebrow "Execution")
        [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
         "Run or load a scenario to stage its rebalance here for review and execution."]])]))
