(ns hyperopen.vol-range.application.lifecycle-recovery
  "Pure reload recovery and merging of concurrent grid copies
  (HO-SPEC-014 v2.1 sections 7.6, 8.1, 8.2; VR-13, VR-17)."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.lifecycle
             :refer [in-flight-operations new-leg oid-string recovery-margin-ms]]))

(def ^:private zero (precision/parse-decimal "0"))
(defn- d [x] (if (map? x) x (or (some-> x precision/parse-decimal) zero)))
(defn- ds [x] (precision/decimal->string x))

(defn recover-after-reload
  "Spec 7.6, 8.1: decide in-flight operations that nobody is finishing.
  `attempted` is the set of operation ids with a committed send-attempt
  record: such an operation is treated as possibly sent whatever the grid
  record says.

  - Committed WITHOUT a send attempt and past its deadline: provably never
    sent (the attempt record is committed before any HTTP call, and the
    deadline is checked before HTTP), so its legs are not sent.
  - Send attempt committed and past its deadline plus the HTTP timeout: it may
    have reached the venue, so pending legs become unknown and cancels
    unknown.
  - A cancel is best effort and may be sent without an attempt record, so an
    unfinished cancel is always unknown, never provably unsent.
  - Anything younger is left in flight: another tab may still be sending it."
  [grid now-ms attempted]
  (reduce
   (fn [g {:keys [operation-id kind state cloids expires-after-ms]}]
     (let [unsent? (and (= :committed state) (not (contains? attempted operation-id)))
           place? (contains? #{:place :repair} kind)
           deadline (or expires-after-ms 0)
           due? (> now-ms (+ deadline (get recovery-margin-ms (if unsent? :committed :sending))))]
       (if-not due?
         g
         (as-> g g
           (update-in g [:operations operation-id] assoc
                      :state (if (and unsent? place?) :not-sent :unresolved)
                      :not-sent-source (when (and unsent? place?) :recovery))
           (reduce (fn [g c]
                     (cond
                       (and place? unsent?)
                       (update-in g [:legs c] #(cond-> %
                                                 (= :pending (:placement-state %))
                                                 (assoc :placement-state :not-sent :order-state :terminal
                                                        :terminal-reason :not-sent
                                                        :reason "Not sent before its deadline")))
                       place?
                       (update-in g [:legs c] #(cond-> %
                                                 (= :pending (:placement-state %)) (assoc :placement-state :unknown)))
                       :else (update-in g [:legs c] #(cond-> % (= :requested (:cancel-state %))
                                                       (assoc :cancel-state :unknown
                                                              :cancel-updated-at-ms now-ms)))))
                   g cloids)))))
   grid
   (in-flight-operations grid)))

(defn recovered-grid
  "A grid for live Vol Range-prefixed orders found for this exact account and
  market with no local manifest (spec 7.6, VR-17). Original settings, plan and
  performance are unavailable; the orders can be canceled explicitly and
  block a second local deployment."
  [{:keys [grid-id scope scope-key rows now-ms]}]
  (let [legs (map-indexed
              (fn [i row]
                (let [cloid (some-> (:cloid row) str .toLowerCase)
                      orig (or (:origSz row) (:sz row))]
                  (assoc (new-leg {:cloid cloid :logical-id (str "recovered-" i)
                                   :operation-id nil
                                   :side (if (contains? #{"B" "b" "buy"} (:side row)) :buy :sell)
                                   :rung-index i :price (some-> (:limitPx row) str) :size (some-> orig str)})
                         :oid (oid-string (:oid row))
                         :placement-state :accepted
                         :order-state :resting
                         :observed-remaining (some-> (:sz row) str)
                         :settled-evidence-at-ms now-ms
                         :last-evidence-at-ms now-ms)))
              rows)]
    {:grid-id grid-id
     :scope scope
     :scope-key scope-key
     :recovered? true
     :created-at-ms now-ms
     :members (into {} (map (fn [l] [(:logical-id l) {:logical-id (:logical-id l) :side (:side l)
                                                      :rung-index (:rung-index l) :cloids [(:cloid l)]}]))
                    legs)
     :legs (into {} (map (fn [l] [(:cloid l) l])) legs)
     :operations {}
     :fills {}
     :balance-requested-at-ms nil
     :released-operation-ids #{}}))

;; ---------------------------------------------------------------------------
;; Merging concurrent copies (spec 8.2)
;; ---------------------------------------------------------------------------

(def ^:private op-rank
  "Evidence-based resolution outranks a not-sent decision, which outranks
  every in-flight state."
  {:committed 0 :sending 1 :unresolved 2 :not-sent 3 :resolved 4})

(defn- merge-operation [a b]
  (cond (nil? a) b
        (nil? b) a
        :else (let [winner (if (> (op-rank (:state b) 0) (op-rank (:state a) 0)) b a)]
                (assoc winner
                       :attempt-ids (vec (distinct (concat (:attempt-ids a) (:attempt-ids b))))
                       :sent-at-ms (or (:sent-at-ms a) (:sent-at-ms b))))))

(def ^:private placement-rank
  "Positive venue evidence (accepted) dominates every other placement state:
  a not-sent or rejected copy can never hide a live order."
  {:pending 0 :unknown 1 :not-sent 2 :rejected 2 :accepted 3})

(defn- leg-rank [l]
  [(placement-rank (:placement-state l) 0)
   (if (= :terminal (:order-state l)) 1 0)
   (or (:updated-at-ms l) (:last-evidence-at-ms l) 0)])

(defn- cancel-newest [a b]
  (if (> (or (:cancel-updated-at-ms b) 0) (or (:cancel-updated-at-ms a) 0)) b a))

(defn- merge-leg [a b]
  (cond (nil? a) b
        (nil? b) a
        :else (let [winner (if (pos? (compare (leg-rank b) (leg-rank a))) b a)]
                (assoc winner
                       ;; Cancel state is merged on its own clock: a copy with
                       ;; newer order evidence may predate the cancel request.
                       :cancel-state (:cancel-state (cancel-newest a b))
                       :cancel-prior (:cancel-prior (cancel-newest a b))
                       :cancel-updated-at-ms (:cancel-updated-at-ms (cancel-newest a b))
                       :oid (or (:oid winner) (:oid a) (:oid b))
                       :reported-filled-base (let [x (d (:reported-filled-base a)) y (d (:reported-filled-base b))]
                                               (ds (precision/dec-max x y)))
                       :settled-evidence-at-ms (max (or (:settled-evidence-at-ms a) 0)
                                                    (or (:settled-evidence-at-ms b) 0))))))

(defn- merge-cloids [a b] (vec (distinct (concat a b))))

(defn merge-grids
  "Merge two copies of the same grid written by different flows or tabs.
  Monotonic: membership, operations, fills and releases are unions; an
  operation only advances; a leg keeps its most advanced evidence (terminal
  over open, definitive over unknown over pending, then newest)."
  [stored ours]
  (if (nil? stored)
    ours
    (let [union-by (fn [k f] (merge-with f (get stored k) (get ours k)))]
      (assoc ours
             :members (merge-with (fn [a b] (assoc a :cloids (merge-cloids (:cloids a) (:cloids b))))
                                  (:members stored) (:members ours))
             :legs (union-by :legs merge-leg)
             :operations (union-by :operations merge-operation)
             :fills (merge (:fills stored) (:fills ours))
             :released-operation-ids (into (or (:released-operation-ids stored) #{}) (:released-operation-ids ours))
             :balance-requested-at-ms (max (or (:balance-requested-at-ms stored) 0)
                                           (or (:balance-requested-at-ms ours) 0))
             :fills-through-ms (max (or (:fills-through-ms stored) 0) (or (:fills-through-ms ours) 0))
             :cancel-requested-at-ms (or (:cancel-requested-at-ms stored) (:cancel-requested-at-ms ours))))))
