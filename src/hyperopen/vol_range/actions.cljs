(ns hyperopen.vol-range.actions
  "Vol Range action handlers (HO-SPEC-014 v2.1 sections 4, 7; VR-01, VR-23, VR-24).

  Handlers are pure functions of state that return effect vectors. Handlers that
  need time have a public arity reading the clock and a pure arity taking
  `now-ms`. The ONLY effects emitted are `:effects/save-many` and
  `:effects/fetch-candle-snapshot` (never an order, signing or storage effect).

  Order-form writes pass through `order-form-ownership/enforce-field-ownership`
  and are emitted as ONE save-many together with the `[:vol-range ...]` path
  values. Draft numeric text is parsed only at the locale-aware boundary inside
  `hyperopen.trading.vol-range-transitions`."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.trading.order-form-ownership :as ownership]
            [hyperopen.trading.vol-range-transitions :as transitions]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.vol-range.application.freshness :as freshness]
            [hyperopen.vol-range.application.plan-state :as plan-state]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.application.ticket-model :as ticket-model]
            [hyperopen.vol-range.contracts :as contracts]))

(defn- now [] (.now js/Date))

(defn- transition->path-values
  "Order-form writes (after ownership enforcement) plus the explicit path values."
  [state {:keys [order-form path-values]}]
  (let [owned (when (map? order-form)
                (ownership/enforce-field-ownership state {:order-form order-form}))]
    (cond-> (vec path-values)
      (map? (:order-form owned)) (conj [[:order-form] (:order-form owned)])
      (map? (:order-form-ui owned)) (conj [[:order-form-ui] (:order-form-ui owned)]))))

(defn- save-many-effects [state transition extra-path-values]
  (let [path-values (into (transition->path-values state transition) extra-path-values)]
    (if (seq path-values)
      [[:effects/save-many path-values]]
      [])))

;; ---------------------------------------------------------------------------
;; Daily candles
;; ---------------------------------------------------------------------------

(defn- active-spot-coin [state]
  (let [market (:active-market state)]
    (when (and (= :spot (:market-type market)) (seq (:coin market)))
      (:coin market))))

(defn daily-refresh-due?
  "True when the Vol Range ticket is showing for an active spot market and its
  daily candles are stale (not fetched successfully this UTC day or within the
  TTL) with no recent request. Drives the freshness watcher (spec 5.1)."
  [state now-ms]
  (boolean
   (and (contracts/preview-enabled?)
        (ticket-model/visible? state)
        (when-let [coin (active-spot-coin state)]
          (freshness/refetch-due? state coin now-ms)))))

(defn- fetch-record-path-value
  "Stamp the REQUEST time only (retry throttle). The success stamp
  `:completed-at-ms` is written by the candle success projection and is kept."
  [state coin now-ms]
  [[:vol-range :daily-fetch]
   (assoc (get-in state [:vol-range :daily-fetch])
          coin
          (assoc (get-in state [:vol-range :daily-fetch coin]) :requested-at-ms now-ms))])

(defn- fetch-effect [coin]
  [:effects/fetch-candle-snapshot
   :coin coin
   :interval :1d
   :bars (:daily-fetch-bars contracts/product-policy)])

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn select-scale-mode
  "Select Price range or Volatility range. Selecting Volatility range rebinds the
  scoped draft and, when the daily candles are missing, stale or from a previous
  UTC day, requests 16 daily bars AFTER the state write."
  ([state mode] (select-scale-mode state mode (now)))
  ([state mode now-ms]
   (if-let [transition (transitions/select-scale-mode state mode)]
     (let [volatility? (= :volatility-range (get-in transition [:order-form :scale :mode]))
           coin (when volatility? (active-spot-coin state))
           fetch? (and coin (freshness/refetch-due? state coin now-ms))
           writes (save-many-effects state transition
                                     (when fetch? [(fetch-record-path-value state coin now-ms)]))]
       (cond-> writes
         fetch? (conj (fetch-effect coin))))
     [])))

(defn- unit-switch-patch
  "Carry the current plan's size across a unit switch, so the other unit's
  field starts at the same ladder: USDC mode gets the plan's maximum debit
  (buys) or gross order value (sells) rounded up to the cent; base mode gets
  the plan's quantity. Nothing is carried without legs on that side."
  [plan draft side unit]
  (let [t (get-in plan [:totals side])]
    (when (and (pos? (:rung-count t 0)) (not= unit (:amount-unit t)))
      (if (= :quote unit)
        (when-let [x (some-> (if (= side :buy) (:max-debit t) (:gross t)) precision/parse-decimal)]
          [[[side :quote-amount] (precision/decimal->string (precision/quantize x 2 :ceil))]])
        (cond-> [[[side :quantity] (:quantity t)]]
          ;; The amount was the buy cap; keep it as the cap if none is set.
          (and (= side :buy) (some? (:quote-amount t)) (str/blank? (str (:max-usdc-debit draft))))
          (conj [[:buy :max-usdc-debit] (:quote-amount t)]))))))

(defn update-vol-range-draft
  "Write one whitelisted draft path; unknown paths and invalid values are no-ops.
  Switching a side's amount unit also carries the current size across."
  ([state path value] (update-vol-range-draft state path value (now)))
  ([state path value now-ms]
   (let [path* (transitions/normalize-path path)
         unit-side (when (and (= 2 (count path*)) (= :amount-unit (second path*))) (first path*))
         unit (when unit-side (:value (transitions/coerce-draft-value state path* value)))
         carry (when unit (unit-switch-patch (plan-state/plan-for-state state now-ms)
                                              (get (draft-scope/effective-draft state) unit-side)
                                              unit-side unit))]
     (if-let [transition (if (seq carry)
                           (transitions/apply-patch state (into [[path* unit]] carry))
                           (transitions/update-draft state path value))]
       (save-many-effects state transition nil)
       []))))

(defn- percent-of-excess [excess pct sz]
  (precision/quantize (precision/divide (precision/mul excess (precision/parse-decimal (str pct)))
                                        (precision/parse-decimal "100") 20 :floor)
                      sz :floor))

(defn set-vol-range-sell-quantity-percent
  "Set the sell quantity to 25, 50 or 100 percent of the CURRENT plan's sellable
  excess above the protected core, floored to the lot. No-op without excess."
  ([state pct] (set-vol-range-sell-quantity-percent state pct (now)))
  ([state pct now-ms]
   (let [pct* (some-> pct str js/Number)
         plan (plan-state/plan-for-state state now-ms)
         excess (some-> (get-in plan [:totals :sellable-excess]) precision/parse-decimal)
         sz (get-in plan [:scope :sz-decimals])]
     (if (and (contains? #{25 50 100} pct*) excess sz (precision/pos-dec? excess))
       (let [q (percent-of-excess excess pct* sz)]
         (if (precision/pos-dec? q)
           (save-many-effects state
                              (transitions/apply-patch state [[[:sell :amount-unit] :base]
                                                              [[:sell :quantity] (precision/decimal->string q)]])
                              nil)
           []))
       []))))

(defn apply-vol-range-remediation
  "Apply the OFFERED remediation with `id` to the draft only. Ids that the
  current plan does not offer are no-ops (VR-22, VR-23)."
  ([state id] (apply-vol-range-remediation state id (now)))
  ([state id now-ms]
   (let [id* (cond (keyword? id) id (string? id) (keyword id) :else nil)
         plan (plan-state/plan-for-state state now-ms)
         offer (some #(when (= id* (:id %)) %) (:remediations plan))]
     ;; Re-check against the live book: a memoized offer may predate a tick.
     (if (and offer (plan-state/offer-still-ready? state now-ms offer))
       (save-many-effects state (transitions/apply-patch state (:patch offer)) nil)
       []))))

(defn open-vol-range-review
  "Freeze the current plan for the read-only review. Only a :ready plan opens."
  ([state] (open-vol-range-review state (now)))
  ([state now-ms]
   (let [{:keys [plan market]} (plan-state/context-for-state state now-ms)]
     (if (= :ready (:status plan))
       (save-many-effects state
                          (transitions/open-review plan {:bbo (:bbo market) :frozen-at-ms now-ms
                                                         :provenance (snapshots/provenance state)})
                          nil)
       []))))

(defn close-vol-range-review
  [state]
  (save-many-effects state (transitions/close-review) nil))

(defn handle-vol-range-review-keydown
  "Escape on the review dialog closes it; every other key is a no-op, so Enter
  in a field never opens, confirms or submits anything."
  [state key]
  (if (= key "Escape")
    (close-vol-range-review state)
    []))

(defn set-vol-range-ui-flag
  [state flag value]
  (if-let [transition (transitions/set-ui-flag flag value)]
    (save-many-effects state transition nil)
    []))

(defn refresh-vol-range-daily-candles
  "Force a refresh of the 16 daily candles for the active spot market."
  ([state] (refresh-vol-range-daily-candles state (now)))
  ([state now-ms]
   (if-let [coin (active-spot-coin state)]
     [[:effects/save-many [(fetch-record-path-value state coin now-ms)]]
      (fetch-effect coin)]
     [])))
