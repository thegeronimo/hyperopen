(ns hyperopen.portfolio.optimizer.application.execution-amend
  "Amend one WORKING order from a settled execution run: reprice the resting limit
  (a new bps offset from the LIVE mark, or post-only at the touch) or convert it to a
  market order that crosses immediately for the REMAINING size.

  The client has no Hyperliquid `modify` implementation; the amend primitive is
  cancel + resubmit through the existing execute effect, whose cancel-first /
  halt-on-failure contract guarantees the replacement can never be live at the same
  time as the original. The amend plan is a derived plan (the Resume/Revert pattern):
  the latest ledger's rows with ONLY the target row re-armed — every sibling passes
  through verbatim (`attempt-row` leaves non-:ready rows untouched), so still-resting
  siblings keep their oids/responses, stay reconciled by the live overlay, and re-merge
  idempotently into the resting carryover."
  (:require [hyperopen.domain.trading :as trading-domain]
            [hyperopen.portfolio.optimizer.application.execution :as execution]
            [hyperopen.portfolio.optimizer.application.execution-carryover :as carryover]
            [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def amend-order-types
  "Order types a working order can be amended to. TWAP is excluded — slicing one live
  order over time is a different product surface, not an in-place amendment."
  [:limit :passive :market])

(def ^:private amend-order-type-set (set amend-order-types))

(defn- parse-positive
  [v]
  (let [n (coercion/parse-float-number v)]
    (when (coercion/positive-number? n) n)))

(defn- live-row-field
  "Field off a live open-order row, tolerating the flat and nested {:order …} feed
  shapes (mirrors carryover/order-oid)."
  [row k]
  (or (get row k) (get-in row [:order k])))

(defn live-order-by-oid
  "The live open-order row for an oid, from the merged all-source open-orders view.
  nil when the oid is not on the (known) book."
  [state oid]
  (when-let [oid* (carryover/norm-oid oid)]
    (some #(when (= oid* (carryover/order-oid %)) %)
          (carryover/open-order-rows state))))

(defn live-native-mark
  "Live native mark off a catalog market entry, preferring the precise :markRaw string
  over the display-rounded :mark (the same preference the readiness cost seeding uses).
  nil when neither is a positive number."
  [market]
  (or (parse-positive (:markRaw market))
      (parse-positive (:mark market))))

(defn amend-selections
  "The user's effective amend choice for a row, resolved from the modal's existing
  :overrides/:params maps (the same state the staged per-order editor writes). The type
  is limited to the amendable set — an override outside it (or none) falls back to the
  row's current type, then :limit. :limit-bps defaults to 0 = at the live mark, because
  the whole point of an amend is usually to move ONTO the price."
  [{:keys [overrides params]} row]
  (let [row-id (:row-id row)
        override (get overrides row-id)
        current (:order-type row)
        order-type (cond
                     (contains? amend-order-type-set override) override
                     (contains? amend-order-type-set current) current
                     :else :limit)
        bps (get-in params [row-id :limit-bps])]
    {:order-type order-type
     :limit-bps (if (number? bps) bps 0)}))

(defn- frozen-asset-id
  [row]
  (get-in row [:request :action :orders 0 :a]))

(defn amend-target
  "Live amend context for one ledger row, or nil when the row is not a working order:
  not :resting, no resting oid, the own-account book has not hydrated, or the oid is no
  longer on the book (it filled or was cancelled elsewhere — there is nothing to amend).
  Strictness is the safety: an amend must never cancel-and-replace an order we cannot
  currently see live, because the replacement size comes from the live row's remaining
  (unfilled) :sz — replacing a partially-filled order at its original quantity would
  over-allocate."
  [state market-by-key row]
  (when-let [oid (carryover/norm-oid (execution/resting-oid row))]
    (let [book (carryover/open-oids state)]
      (when (and (set? book) (contains? book oid))
        (when-let [live-row (live-order-by-oid state oid)]
          (let [market (execution/row-market (or market-by-key {}) row)]
            {:oid oid
             :asset-id (frozen-asset-id row)
             :live-row live-row
             :market market
             :remaining-size (or (parse-positive (live-row-field live-row :sz))
                                 (parse-positive (:quantity row)))
             :limit-px (parse-positive (live-row-field live-row :limitPx))
             :live-mark (live-native-mark market)}))))))

(defn- cancel-entry
  "Carryover-style :cancel-orders entry for the amended order. The wire asset index is
  frozen from the row's own original request when present, so the pre-run cancel needs
  no market-metadata resolution; entries without it resolve via the shared
  cancel-request builder inside the effect."
  [row target]
  (cond-> {:oid (:oid target)
           :coin (execution/coin-for-row row)
           :instrument-id (:instrument-id row)
           :side (:side row)
           :quantity (:quantity row)}
    (some? (:asset-id target)) (assoc :asset-id (:asset-id target))))

(defn- amended-row
  "Re-arms the target ledger row as a fresh :ready row for the replacement order:
  quantity = the live REMAINING size, price = the live native mark (falling back to the
  frozen reference), intent = the selected amend type + offset. The settled artifacts
  (:request/:response/:realized/:error/:reconciled) are stripped so a fresh request is
  built; :delta-notional-usd is recomputed from the remaining size so summary notional
  and the order table stay honest about what the replacement actually trades."
  [row target {:keys [order-type limit-bps]}]
  (let [quantity (:remaining-size target)
        price (or (:live-mark target) (:price row))
        signed (case (:side row) :sell -1 1)
        notional (when (and (number? quantity) (number? price))
                   (* signed quantity price))
        spot? (= :spot (:instrument-type row))]
    (-> row
        (dissoc :request :response :realized :error :reconciled
                :pre-action-responses :reason)
        (assoc :status :ready
               :order-type order-type
               :quantity quantity
               :price price
               :delta-notional-usd (or notional (:delta-notional-usd row))
               :intent {:kind (if spot? :spot-order :perp-order)
                        :instrument-id (:instrument-id row)
                        :side (:side row)
                        :quantity quantity
                        :order-type order-type
                        :limit-bps (when (contains? #{:limit :passive} order-type)
                                     limit-bps)
                        :reduce-only? false}))))

(defn build-amend-plan
  "Derived plan that replaces ONE working order: {:plan <amend plan>} or {:error <kw>}.

  The plan is tagged :kind :amend (a forward mutation — replacement orders stay
  cloid-tagged, unlike reverts), carries the latest ledger's rows with only the target
  re-armed, and exactly one :cancel-orders entry: the amended oid. It must NOT be merged
  with the session carryover — the carryover holds this run's OTHER live orders, and
  attaching it would cancel them all.

  Refusals (checked BEFORE anything irreversible; the effect cancels first, so a plan
  whose replacement cannot be built would silently turn 'update my order' into 'your
  order is gone'):
  - :not-amendable        target missing/not live (filled, cancelled, or book unknown)
  - :market-unavailable   no catalog market ⇒ neither replacement nor a clean block
  - :remaining-below-lot  the unfilled remainder floors below one lot for the market"
  [{:keys [plan ledger row-id selections target]}]
  (let [rows (vec (:rows ledger))
        row (some #(when (= row-id (:row-id %)) %) rows)]
    (cond
      (or (nil? row) (nil? target))
      {:error :not-amendable}

      (nil? (:market target))
      {:error :market-unavailable}

      (not (some? (trading-domain/base-size-string
                   {:market (:market target)}
                   (:remaining-size target))))
      {:error :remaining-below-lot}

      :else
      (let [selections* (or selections {})
            replacement (amended-row row target (amend-selections selections* row))
            rows* (mapv #(if (= row-id (:row-id %)) replacement %) rows)
            ready-rows (filter #(= :ready (:status %)) rows*)
            blocked-rows (filter #(= :blocked (:status %)) rows*)
            skipped-rows (filter #(= :skipped (:status %)) rows*)]
        {:plan (assoc plan
                      :kind :amend
                      :status (if (seq blocked-rows) :partially-blocked :ready)
                      :summary (assoc (:summary plan)
                                      :ready-count (count ready-rows)
                                      :blocked-count (count blocked-rows)
                                      :skipped-count (count skipped-rows)
                                      :gross-ready-notional-usd
                                      (reduce + 0 (map #(js/Math.abs
                                                         (or (:delta-notional-usd %) 0))
                                                       ready-rows)))
                      :rows rows*
                      :cancel-orders [(cancel-entry row target)])}))))
