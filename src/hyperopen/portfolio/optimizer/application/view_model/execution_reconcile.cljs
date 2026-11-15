(ns hyperopen.portfolio.optimizer.application.view-model.execution-reconcile
  "Read-only overlay that reconciles a frozen execution-ledger row (status :resting, captured
  ONCE at submission and never re-derived) against the LIVE order/fill feeds the trading screen
  uses, so a passive limit order that fills on the book stops showing as \"open\".

  Why this is needed: the optimizer execution ledger is an audit snapshot — a resting row records
  {:status :resting :response …} at submission and is sealed into the execution history
  (contracts/execution-history-path). Nothing re-reads exchange state against it, so the row reads
  :resting forever. The trading screen never has this problem because it recomputes order status
  from the live feeds on every render. This overlay gives the Execution tab the same
  derive-live-on-render behaviour, scoped to the ledger's own oids, WITHOUT mutating the persisted
  (audit) ledger.

  Linkage: a resting row carries its exchange oid at [:response :data :statuses 0 :resting :oid]
  (execution/resting-oid); the live feeds key on the same oid:
  - [:orders :fills]       — own-account trade fills (userFills); a fill for the oid is positive
                             evidence the order executed, and carries the realized price/size.
  - [:orders :open-orders] — own-account resting orders (openOrders); an oid still present means
                             the order is still (at least partly) live on the book.

  Decision (conservative — never assert a fill without evidence, so spectate / vault-target /
  not-yet-subscribed all degrade safely to the current :resting display):
  - oid still on the book      => stay :resting (stamp realized partial if it also has fills)
  - oid has fills + off-book    => :submitted (filled) + realized px/size
  - otherwise (no fills)        => row unchanged (:resting), exactly as today."
  (:require [hyperopen.portfolio.optimizer.application.execution :as execution]
            [hyperopen.portfolio.optimizer.application.execution-carryover :as carryover]))

(def ^:private fills-path [:orders :fills])

(def ^:private norm-oid carryover/norm-oid)

(defn- parse-num
  [v]
  (cond
    (number? v) v
    (string? v) (let [n (js/parseFloat v)] (when-not (js/isNaN n) n))
    :else nil))

(defn- fills-by-oid
  "Index of own-account fills by normalized oid."
  [state]
  (reduce (fn [acc f]
            (if-let [oid (norm-oid (:oid f))]
              (update acc oid (fnil conj []) f)
              acc))
          {}
          (get-in state fills-path)))

(defn- aggregate-fill
  "Volume-weighted average price + total filled size across all fills for one oid, or nil when
  no priced size is present. HL userFills carry per-fill :px and :sz, so summing them yields the
  realized average + filled quantity for a (possibly partial, possibly multi-fill) order."
  [fills]
  (let [priced (keep (fn [f]
                       (let [px (parse-num (or (:px f) (:avgPx f) (:price f)))
                             sz (js/Math.abs (or (parse-num (or (:sz f) (:size f))) 0))]
                         (when (and px (pos? sz)) [px sz])))
                     fills)]
    (when (seq priced)
      (let [total-sz (reduce + 0 (map second priced))
            notional (reduce + 0 (map (fn [[px sz]] (* px sz)) priced))]
        (when (pos? total-sz)
          {:avg-px (/ notional total-sz) :filled-qty total-sz})))))

(defn reconcile-row
  "Re-derive the DISPLAY status of one frozen ledger row from the live feeds. Returns the row
  unchanged unless it is :resting with a known oid that has live evidence. `ctx` is built once by
  `reconcile-rows`: {:open-oids <set-or-nil> :fills-by-oid <map>}."
  [{:keys [open-oids fills-by-oid]} row]
  (let [oid (norm-oid (execution/resting-oid row))]
    (if (nil? oid)
      row
      (let [on-book? (and (set? open-oids) (contains? open-oids oid))
            fills (get fills-by-oid oid)
            agg (when (seq fills) (aggregate-fill fills))
            realized (when agg (execution/realized-from-avg row (:avg-px agg) (:filled-qty agg)))]
        (cond
          on-book?
          (cond-> row realized (assoc :realized realized :reconciled :partial))

          (seq fills)
          (cond-> (assoc row :status :submitted :reconciled :filled)
            realized (assoc :realized realized))

          :else row)))))

(defn reconcile-rows
  "Overlay live-fill reconciliation over a sequence of display rows. Pure; reads only the live
  order/fill feeds from `state`. A no-op for any non-resting row, so it is safe to apply to plan
  rows, in-flight run-attempt rows, and the settled ledger alike."
  [state rows]
  (let [ctx {:open-oids (carryover/open-oids state)
             :fills-by-oid (fills-by-oid state)}]
    (mapv #(reconcile-row ctx %) (or rows []))))
