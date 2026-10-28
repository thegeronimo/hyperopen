(ns hyperopen.vol-range.application.lifecycle
  "Pure Vol Range grid lifecycle (HO-SPEC-014 v2.1 sections 7.5, 8.5, 8.6, 10).

  A grid is one logical ladder with EXPLICIT membership: each logical member
  (one rung) keeps every CLOID ever sent for it, the last one current. Repairs
  add a new CLOID to an existing member; they never create a second grid.
  Each CLOID has a leg (spec 8.5) whose placement state, order state, cancel
  state and quantities are independent. Grid status, counts and live units are
  projections of the legs and operations.

  Evidence is applied in `lifecycle-evidence`. Everything here is pure: time,
  ids and CLOIDs are injected. Money is decimal strings."
  (:require [hyperopen.domain.trading.precision :as precision]))

(def ^:private zero (precision/parse-decimal "0"))

(defn oid-string
  "Lossless order id or nil: venue oids can exceed 2^53, so an unsafe number
  is never trusted (matching falls back to the CLOID)."
  [x]
  (cond (string? x) (let [t (.trim x)] (when (re-matches #"\d+" t) t))
        (number? x) (when (js/Number.isSafeInteger x) (str x))
        :else nil))
(defn- d [x] (if (map? x) x (or (some-> x precision/parse-decimal) zero)))
(defn- ds [x] (precision/decimal->string x))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(defn new-leg
  "A leg that has not been sent yet."
  [{:keys [cloid logical-id operation-id side rung-index price size]}]
  {:cloid cloid
   :logical-id logical-id
   :operation-id operation-id
   :side side
   :rung-index rung-index
   :price price
   :original-base size
   :oid nil
   :placement-state :pending
   :order-state nil
   :terminal-reason nil
   :reason nil
   :reported-filled-base "0"
   :observed-remaining nil
   :cancel-state nil
   :settled-evidence-at-ms nil
   :last-evidence-at-ms nil})

(defn- operation [{:keys [operation-id kind cloids plan-id expires-after-ms now-ms]}]
  {:operation-id operation-id
   :kind kind
   :expires-after-ms expires-after-ms
   :state :committed
   :cloids (vec cloids)
   :plan-id plan-id
   :attempt-ids []
   :sent-at-ms nil
   :created-at-ms now-ms})

(defn new-grid
  "A grid for a reviewed plan whose legs already carry CLOIDs. Legs keep their
  plan `:leg-id` as the logical member id. `:settings` (optional, see
  `ladder-settings`) records what the ladder was placed with; it is
  immutable, and absent on grids placed before it was recorded."
  [{:keys [grid-id scope scope-key operation-id plan-id legs expires-after-ms model settings now-ms]}]
  (cond->
   {:grid-id grid-id
    :model model
    :scope scope
    :scope-key scope-key
    :created-at-ms now-ms
    :members (into {} (map (fn [{:keys [leg-id side rung-index cloid]}]
                             [leg-id {:logical-id leg-id :side side :rung-index rung-index
                                      :cloids [cloid]}]))
                   legs)
    :legs (into {} (map (fn [l] [(:cloid l) (new-leg (assoc l :logical-id (:leg-id l)
                                                            :operation-id operation-id))]))
                legs)
    :operations {operation-id (operation {:operation-id operation-id :kind :place
                                          :cloids (map :cloid legs) :plan-id plan-id
                                          :expires-after-ms expires-after-ms :now-ms now-ms})}
    :fills {}
    :balance-requested-at-ms nil
    :released-operation-ids #{}}
    (map? settings) (assoc :settings settings)))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn current-cloid [member] (peek (:cloids member)))

(defn current-legs
  "The current leg of every logical member."
  [grid]
  (keep #(get-in grid [:legs (current-cloid %)]) (vals (:members grid))))

(defn fills-for-leg [grid leg]
  (filter (fn [f] (or (= (:cloid f) (:cloid leg))
                      (and (some? (:oid leg)) (= (:oid f) (:oid leg)))))
          (vals (:fills grid))))

(defn- sum [xs] (reduce precision/add zero (map d xs)))

(defn leg-quantities
  "{:filled :remaining :canceled} as decimal strings. Filled is the ledger sum
  of ingested fills, floored by venue-reported filled size (so a fill is never
  counted twice). Remaining is nil while placement is unresolved."
  [grid leg]
  (let [orig (d (:original-base leg))
        filled (precision/dec-min orig (precision/dec-max (sum (map :sz (fills-for-leg grid leg)))
                                                          (d (:reported-filled-base leg))))
        accepted? (= :accepted (:placement-state leg))
        terminal? (= :terminal (:order-state leg))
        open-remaining (let [r (precision/sub orig filled)]
                         (if-let [o (:observed-remaining leg)] (precision/dec-min r (d o)) r))]
    {:filled (ds filled)
     :remaining (cond (= :unknown (:placement-state leg)) nil
                      (or (not accepted?) terminal?) "0"
                      :else (ds open-remaining))
     :canceled (if (and terminal? (= :canceled (:terminal-reason leg)))
                 (ds (precision/sub orig filled))
                 "0")}))

(def ^:private not-placed-states #{:rejected :not-sent})

(defn definitively-not-placed? [leg] (contains? not-placed-states (:placement-state leg)))

(defn leg-unresolved?
  "Spec 8.6: placement unknown, accepted with an unconfirmed order state, or a
  cancel whose outcome is unknown or only says the order is no longer resting.
  A `:failed` cancel (deadline passed, order still open) is resolved: the
  order is live and can be canceled again."
  [leg]
  (or (= :unknown (:placement-state leg))
      (and (= :accepted (:placement-state leg)) (= :unconfirmed (:order-state leg)))
      (contains? #{:unknown :not-resting :acknowledged} (:cancel-state leg))))

(defn live-leg? [leg]
  (and (= :accepted (:placement-state leg)) (= :resting (:order-state leg))))

(defn in-flight-operations
  "Operations whose attempt is committed but whose delivery is not recorded."
  [grid]
  (filter #(contains? #{:committed :sending} (:state %)) (vals (:operations grid))))

(defn unresolved?
  "True when any possible send or cancel outcome is unresolved. Blocks a new
  grid, a replacement and reservation release."
  [grid]
  (boolean (or (seq (in-flight-operations grid))
               (some #(= :unresolved (:state %)) (vals (:operations grid)))
               (some leg-unresolved? (vals (:legs grid))))))

(defn counts
  "Counts over CURRENT members, plus live units per side (spec 8.5, 13.4)."
  [grid]
  (let [legs (current-legs grid)
        live-units (fn [side] (ds (sum (keep #(when (and (live-leg? %) (= side (:side %)))
                                                (:remaining (leg-quantities grid %)))
                                             legs))))]
    {:members (count legs)
     :live (count (filter live-leg? legs))
     :rejected (count (filter definitively-not-placed? legs))
     :checking (count (filter leg-unresolved? legs))
     :pending (count (filter #(= :pending (:placement-state %)) legs))
     :filled (count (filter #(= :filled (:terminal-reason %)) legs))
     :canceled (count (filter #(= :canceled (:terminal-reason %)) legs))
     :historical-rejections (- (count (filter definitively-not-placed? (vals (:legs grid))))
                               (count (filter definitively-not-placed? legs)))
     :live-buy-units (live-units :buy)
     :live-sell-units (live-units :sell)}))

(defn status
  "Grid status (spec 8.5). Unknown outcomes take precedence over any
  reassuring label."
  [grid]
  (let [legs (current-legs grid)
        {:keys [live rejected]} (counts grid)]
    (cond
      (some leg-unresolved? (vals (:legs grid))) :checking
      (some #(= :unresolved (:state %)) (vals (:operations grid))) :checking
      (seq (in-flight-operations grid))
      (if (some #(= :cancel (:kind %)) (in-flight-operations grid)) :canceling :submitting)
      (some #(= :requested (:cancel-state %)) legs) :canceling
      (every? #(= :terminal (:order-state %)) legs)
      (if (some #(= :accepted (:placement-state %)) (vals (:legs grid))) :closed :not-placed)
      (and (pos? rejected) (pos? live)) :partially-deployed
      (pos? live) :live
      :else :checking)))

;; ---------------------------------------------------------------------------
;; Operations bookkeeping
;; ---------------------------------------------------------------------------

(defn mark-sending
  "The send attempt for `operation-id` is committed (spec 8.1 step 7)."
  [grid operation-id attempt-id now-ms]
  (update-in grid [:operations operation-id]
             (fn [op] (-> op
                          (assoc :state :sending :sent-at-ms now-ms)
                          (update :attempt-ids conj attempt-id)))))

(defn add-cancel-operation
  "Freeze `cloids` as the targets of a cancel operation and mark them
  requested. The previous cancel state is kept so a cancel that provably was
  not sent restores it (an earlier unknown cancel may still take effect)."
  [grid {:keys [operation-id cloids expires-after-ms now-ms]}]
  (reduce (fn [g c] (update-in g [:legs c] #(assoc % :cancel-prior (:cancel-state %) :cancel-state :requested
                                                   :updated-at-ms now-ms :cancel-updated-at-ms now-ms)))
          (assoc-in grid [:operations operation-id]
                    (operation {:operation-id operation-id :kind :cancel :cloids cloids
                               :expires-after-ms expires-after-ms :now-ms now-ms}))
          cloids))

(def recovery-margin-ms
  "Slack after an operation's action deadline before another tab or a reload
  may decide its fate. The deadline is checked immediately before HTTP and
  enforced by the venue; the delivery HTTP timeout (35 s) is covered too."
  {:committed 5000 :sending 40000})

(defn request-cancel
  "Record that the user asked to cancel this ladder. An operation still before
  its final pre-send guard is then refused, so nothing new goes live on a
  ladder the user is canceling."
  [grid now-ms]
  (update grid :cancel-requested-at-ms #(or % now-ms)))

;; ---------------------------------------------------------------------------
;; Cancel membership (spec 10.1)
;; ---------------------------------------------------------------------------

(defn cancel-targets
  "Every leg of the logical grid that may still be resting, across revisions
  and repairs, each exactly once. Legs whose cancel was already acknowledged
  or confirmed are not targeted again. Each target carries the oid when known
  (preferred) and always the CLOID."
  [grid]
  (->> (vals (:legs grid))
       (filter #(contains? #{:accepted :unknown} (:placement-state %)))
       (remove #(= :terminal (:order-state %)))
       (remove #(contains? #{:acknowledged :confirmed :requested} (:cancel-state %)))
       (sort-by (juxt (comp name :side) :rung-index :cloid))
       (mapv #(select-keys % [:cloid :oid :side :rung-index]))))

;; ---------------------------------------------------------------------------
;; Repair (spec 8.6, 13.4)
;; ---------------------------------------------------------------------------

(defn repair-candidates
  "Logical members whose CURRENT leg was definitively not placed. Empty while
  anything is unresolved: a possibly-live order can never be repaired."
  [grid]
  (if (unresolved? grid)
    []
    (->> (vals (:members grid))
         (keep (fn [m]
                 (let [leg (get-in grid [:legs (current-cloid m)])]
                   (when (definitively-not-placed? leg)
                     (select-keys leg [:logical-id :side :rung-index :price :original-base :reason])))))
         (sort-by (juxt (comp name :side) :rung-index))
         vec)))

(defn add-repair
  "Add a reviewed repair: one new CLOID per selected logical member, appended
  to that member's history. Returns {:grid g} or {:error kw}. Surviving legs
  are untouched; the original batch is never replayed."
  [grid {:keys [operation-id plan-id legs expires-after-ms now-ms]}]
  (let [allowed (set (map :logical-id (repair-candidates grid)))]
    (cond
      (empty? legs) {:error :repair/empty}
      (not (apply distinct? (map :logical-id legs))) {:error :repair/duplicate-member}
      (not-every? #(contains? allowed (:logical-id %)) legs) {:error :repair/not-a-candidate}
      (some #(contains? (:legs grid) (:cloid %)) legs) {:error :cloid/duplicate}
      :else
      {:grid (as-> grid g
               (reduce (fn [g {:keys [logical-id cloid] :as l}]
                         (let [m (get-in g [:members logical-id])]
                           (-> g
                               (update-in [:members logical-id :cloids] conj cloid)
                               (assoc-in [:legs cloid]
                                         (new-leg (assoc l :side (:side m) :rung-index (:rung-index m)
                                                         :operation-id operation-id))))))
                       g legs)
               (assoc-in g [:operations operation-id]
                         (operation {:operation-id operation-id :kind :repair
                                     :cloids (map :cloid legs) :plan-id plan-id
                                     :expires-after-ms expires-after-ms :now-ms now-ms})))})))

;; ---------------------------------------------------------------------------
;; Replacement basis (spec 10.2, 13.4)
;; ---------------------------------------------------------------------------

(defn fills-covered?
  "True when a COMPLETE fills read covered the grid's whole life up to after
  its newest leg evidence, so filled and canceled quantities are not missing
  a fill (spec 9.2)."
  [grid]
  (let [through (:fills-through-ms grid)
        newest (reduce max 0 (keep :last-evidence-at-ms (vals (:legs grid))))]
    (boolean (and through (>= through newest)))))

(defn note-fills-read
  "A complete fills read covered everything up to `through-ms`."
  [grid through-ms]
  (update grid :fills-through-ms #(max (or % 0) through-ms)))

(defn replacement-basis
  "What a replacement may be built from once the old ladder is canceled.
  `:ready?` only when every leg is terminal or definitively not placed and
  nothing is unresolved (VR-15). Remaining quantity is the canceled remainder
  of uniquely identified working legs (fills during cancellation already
  excluded); rejected legs are listed separately and never revived silently."
  [grid]
  (let [legs (vals (:legs grid))
        open (remove #(or (= :terminal (:order-state %)) (definitively-not-placed? %)) legs)
        canceled (filter #(and (= :accepted (:placement-state %)) (= :canceled (:terminal-reason %))) legs)
        per-side (fn [side] (ds (sum (map #(:canceled (leg-quantities grid %))
                                          (filter #(= side (:side %)) canceled)))))
        blocking (cond-> []
                   (unresolved? grid) (conj :replace/unresolved-outcomes)
                   (seq open) (conj :replace/legs-still-open)
                   (not (fills-covered? grid)) (conj :replace/fills-incomplete))]
    {:ready? (empty? blocking)
     :blocking blocking
     :remaining {:buy (per-side :buy) :sell (per-side :sell)}
     :filled {:buy (ds (sum (map #(:filled (leg-quantities grid %)) (filter #(= :buy (:side %)) legs))))
              :sell (ds (sum (map #(:filled (leg-quantities grid %)) (filter #(= :sell (:side %)) legs))))}
     :rejected (->> (current-legs grid)
                    (filter definitively-not-placed?)
                    (mapv #(select-keys % [:logical-id :side :rung-index :price :original-base :reason])))}))

;; ---------------------------------------------------------------------------
;; Pending reservations (spec 3.3, 8.1 step 10, VR-06)
;; ---------------------------------------------------------------------------

(defn note-balance-snapshot
  "Record the REQUEST time of a successful balance read for this account."
  [grid requested-at-ms]
  (update grid :balance-requested-at-ms #(max (or % 0) requested-at-ms)))

(defn reservation-releasable?
  "An operation's pending reservation may be released only when its outcome
  is resolved and a balance read was REQUESTED after the newest evidence that
  changed any of its legs, so the venue hold or settlement is already inside
  that balance. Order visibility or a fill alone never releases it."
  [grid operation-id]
  (let [op (get-in grid [:operations operation-id])
        legs (keep #(get-in grid [:legs %]) (:cloids op))
        barrier (:balance-requested-at-ms grid)]
    (boolean
     (and op
          (contains? #{:resolved :not-sent} (:state op))
          (not-any? leg-unresolved? legs)
          (not-any? #(= :pending (:placement-state %)) legs)
          (or (= :not-sent (:state op))
              (every? definitively-not-placed? legs)
              (and barrier
                   (every? (fn [l] (or (definitively-not-placed? l)
                                       (and (:settled-evidence-at-ms l)
                                            (< (:settled-evidence-at-ms l) barrier))))
                           legs)))))))

(defn releasable-operations
  "Placement or repair operations whose reservation can now be released."
  [grid]
  (->> (vals (:operations grid))
       (filter #(contains? #{:place :repair} (:kind %)))
       (remove #(contains? (:released-operation-ids grid) (:operation-id %)))
       (filter #(reservation-releasable? grid (:operation-id %)))
       (mapv :operation-id)))

(defn mark-released [grid operation-ids]
  (update grid :released-operation-ids into operation-ids))

;; ---------------------------------------------------------------------------
;; Reconciliation needs (spec 8.6)
;; ---------------------------------------------------------------------------

(defn status-lookup-ids
  "Original identities to query: oid when known, else CLOID. Every leg whose
  outcome or current state is not established is included."
  [grid]
  (->> (vals (:legs grid))
       (filter leg-unresolved?)
       (mapv #(or (oid-string (:oid %)) (:cloid %)))))
