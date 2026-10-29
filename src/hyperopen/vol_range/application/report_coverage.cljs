(ns hyperopen.vol-range.application.report-coverage
  "Coverage, breaks and balance checks for Vol Range reporting (HO-SPEC-014
  v2.1 sections 9.2, 12.4, 12.6, 13.5 row 8).

  Coverage is proven ONLY by complete REST reads, per account, stream
  (:fills or :ledger) and tracked base token: a complete read of `[s, now]`
  requested at R covers `[s, R - :read-settle-ms)` for the tokens whose rows
  it classified and persisted. A fills read whose rows hold at least
  `:venue-fill-retention-count` DISTINCT fill keys (all coins, overlap
  duplicates counted once) proves nothing (`:coverage/retention`). An
  incomplete read records a failure interval. Over a needed interval:

  - covered by a complete read        -> :complete
  - in a recorded failure, uncovered  -> :gap (heals only when a later
                                         complete read covers it)
  - never read                        -> :partial (:coverage/backfill-pending)
  - from a break onward               -> :attribution-break

  combined with precedence :unavailable > :attribution-break > :gap >
  :partial > :complete. Matching balances never upgrade coverage. Pure."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.campaign :as campaign]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.report-intervals :as iv]
            [hyperopen.vol-range.domain.report-ledger :as ledger]))

(def states [:complete :partial :gap :attribution-break :unavailable])
(def ^:private rank (zipmap states (range)))

(defn worst
  "The state with the highest precedence (:complete for none)."
  [xs]
  (or (last (sort-by rank (remove nil? xs))) :complete))

(def ^:private max-failures 20)

(defn- policy [k] (get contracts/report-policy k))

;; ---------------------------------------------------------------------------
;; Reads -> coverage deltas
;; ---------------------------------------------------------------------------

(def ^:private read-reasons
  {:fills/no-progress :coverage/no-progress :fills/page-cap :coverage/page-cap
   :ledger/no-progress :coverage/no-progress :ledger/page-cap :coverage/page-cap})

(defn distinct-fill-count
  "Distinct fill keys among raw fill `rows` (every coin)."
  [network account-address rows]
  (count (into #{} (map #(evidence/fill-key network account-address %)) rows)))

(defn- deltas [stream base-token-ids complete failure]
  (mapv (fn [b] {:stream stream :base-token-id (str b) :complete complete :failure failure})
        (sort (distinct (map str base-token-ids)))))

(defn- read->coverage [stream {:keys [read start-ms base-token-ids]} retained?]
  (let [r (:requested-at-ms read)
        end (- r (policy :read-settle-ms))
        failure (fn [reason] {:from-ms start-ms :to-ms (max r (inc start-ms)) :reason reason
                              :at-ms (or (:received-at-ms read) r)})]
    (cond
      (not (and (number? start-ms) (number? r))) []
      (not (:complete? read))
      (deltas stream base-token-ids [] (failure (get read-reasons (:reason read) :coverage/read-error)))
      (not retained?) (deltas stream base-token-ids [] (failure :coverage/retention))
      :else (deltas stream base-token-ids (iv/normalize [[start-ms end]]) nil))))

(defn fills-read->coverage
  "Coverage deltas [{:stream :fills :base-token-id :complete :failure}] for a
  `reads/read-fills-window!` result over `[start-ms, now]`, one per base
  token in `base-token-ids` (the tokens tracked BEFORE the read was
  requested; only their rows were persisted)."
  [{:keys [read network account-address] :as args}]
  (read->coverage :fills args
                  (< (distinct-fill-count network account-address (:rows read))
                     (policy :venue-fill-retention-count))))

(defn ledger-read->coverage
  "Coverage deltas for a `report-reads/read-ledger-window!` result. The
  ledger's retention is unknown, so only completeness applies."
  [args]
  (read->coverage :ledger args true))

(defn coverage-record-id [account stream base-token-id]
  (str "coverage:" account "|" (name stream) "|" base-token-id))

(defn merge-coverage
  "`existing` coverage record (or nil) with `delta` merged: complete
  intervals only grow; failures keep the newest 20."
  [existing {:keys [stream base-token-id complete failure]} {:keys [account-address now-ms]}]
  {:schema-version contracts/ledger-schema-version
   :record-id (coverage-record-id account-address stream base-token-id)
   :kind :coverage
   :account-address account-address
   :stream stream
   :base-token-id base-token-id
   :complete (iv/union (:complete existing) complete)
   :failures (vec (take-last max-failures (cond-> (vec (:failures existing)) failure (conj failure))))
   :saved-at-ms now-ms})

;; ---------------------------------------------------------------------------
;; Intervals
;; ---------------------------------------------------------------------------

(defn- segments
  "Split [from to) at `points` into [[a b] ...]."
  [[from to] points]
  (let [cuts (->> points (filter #(and (number? %) (< from % to))) (concat [from to]) distinct sort)]
    (mapv vector cuts (rest cuts))))

(defn- merge-adjacent [xs]
  (reduce (fn [acc x]
            (let [p (peek acc)]
              (if (and p (= (:to-ms p) (:from-ms x)) (= (:state p) (:state x)) (= (:reason p) (:reason x)))
                (conj (pop acc) (assoc p :to-ms (:to-ms x)))
                (conj acc x))))
          [] xs))

(defn stream-intervals
  "[{:from-ms :to-ms :state :reason}] covering `[from to)` for one coverage
  record (nil = never read)."
  [record [from to]]
  (if-not (< from to)
    []
    (let [complete (:complete record)
          failures (reverse (:failures record))
          points (concat (mapcat identity complete) (mapcat (juxt :from-ms :to-ms) failures))]
      (merge-adjacent
       (for [[a b] (segments [from to] points)]
         (if (iv/contains-point? complete a)
           {:from-ms a :to-ms b :state :complete :reason nil}
           (if-let [f (first (filter #(and (<= (:from-ms %) a) (< a (:to-ms %))) failures))]
             {:from-ms a :to-ms b :state :gap :reason (:reason f)}
             {:from-ms a :to-ms b :state :partial :reason :coverage/backfill-pending})))))))

(defn- state-at [intervals t]
  (first (filter #(and (<= (:from-ms %) t) (< t (:to-ms %))) intervals)))

(defn balance-check-breaks
  "Breaks for the campaign's mismatched balance checks: the unexplained
  change happened in (last-match-at, R], so dollar figures are suppressed
  from the last match (or T0) onward."
  [campaign checks]
  (->> checks
       (filter #(and (= :mismatch (:state %)) (= (:campaign-id campaign) (:campaign-id %))))
       (mapv (fn [c] {:at-ms (max (:t0-ms campaign) (or (:last-match-at-ms c) (:t0-ms campaign)))
                      :time (:requested-at-ms c) :reason :break/unexplained-balance-change
                      :source :balance-check :event-id (:record-id c)}))))

(defn- unavailable [reason from to]
  {:state :unavailable :reason reason :from-ms from :to-ms to
   :intervals [{:from-ms from :to-ms to :state :unavailable :reason reason}]
   :breaks [] :headline-allowed? false})

(defn campaign-coverage
  "Combined coverage of `[T0 - skew, until-ms)` for `campaign` (for a window
  `[a,b)` pass b): {:state :reason :from-ms :to-ms :intervals :breaks
  :headline-allowed?}. `coverage` is {:fills record :ledger record} for the
  campaign's base token; `breaks` come from `report-attribution/
  campaign-findings`; mismatched `balance-checks` add breaks.
  `storage-ok?` false (the ledger could not be read) is `:unavailable`."
  [{:keys [campaign coverage breaks balance-checks until-ms storage-ok?] :or {storage-ok? true}}]
  (let [skew (or (get-in campaign [:baseline :boundary :skew-ms]) (policy :boundary-skew-ms))
        from (- (:t0-ms campaign) skew)
        to until-ms]
    (cond
      (not storage-ok?) (unavailable :storage/unavailable from to)
      (nil? (get-in campaign [:scope :base-token-id])) (unavailable :baseline/missing from to)
      (= :unavailable (get-in campaign [:baseline :p0-source])) (unavailable :baseline/price-unavailable from to)
      (not (and (number? to) (< from to))) (unavailable :window/before-tracking from to)
      :else
      (let [all-breaks (->> (concat breaks (balance-check-breaks campaign balance-checks))
                            (filter #(< (:at-ms %) to))
                            (sort-by (juxt :at-ms :time #(str (:event-id %))))
                            vec)
            first-break (first all-breaks)
            fills (stream-intervals (:fills coverage) [from to])
            ledger-ivs (stream-intervals (:ledger coverage) [from to])
            points (concat (mapcat (juxt :from-ms :to-ms) (concat fills ledger-ivs)) (map :at-ms all-breaks))
            intervals (merge-adjacent
                       (for [[a b] (segments [from to] points)]
                         (let [candidates (cond-> [(state-at fills a) (state-at ledger-ivs a)]
                                            (and first-break (>= a (:at-ms first-break)))
                                            (conj {:state :attribution-break :reason (:reason first-break)}))
                               top (last (sort-by (comp rank :state) candidates))]
                           {:from-ms a :to-ms b :state (:state top) :reason (:reason top)})))
            state (worst (map :state intervals))]
        {:state state
         :reason (when-not (= :complete state)
                   (:reason (first (filter #(= state (:state %)) intervals))))
         :from-ms from :to-ms to
         :intervals intervals
         :breaks all-breaks
         :headline-allowed? (boolean (and (= :complete state) (nil? first-break) (seq intervals)))}))))

;; ---------------------------------------------------------------------------
;; Balance checks
;; ---------------------------------------------------------------------------

(defn pending-balance-checks
  "A :pending check per open campaign from a complete barrier read
  `snapshot` ({:requested-at-ms :received-at-ms :balances}); [] otherwise.
  A campaign that still has a pending check (`existing`) gets no second
  one, so undecidable checks never pile up."
  [{:keys [snapshot campaigns existing account-address now-ms]}]
  (if-not (and (:complete? snapshot) (number? (:requested-at-ms snapshot)))
    []
    (vec (for [c campaigns
               :let [observed (campaign/base-total snapshot (:scope c))]
               :when (and observed
                          (not-any? #(and (= :pending (:state %)) (= (:campaign-id c) (:campaign-id %))) existing))]
           {:schema-version contracts/ledger-schema-version
            :record-id (str "balance-check:" (:campaign-id c) "|" (:requested-at-ms snapshot))
            :kind :balance-check
            :account-address account-address
            :campaign-id (:campaign-id c)
            :requested-at-ms (:requested-at-ms snapshot)
            :received-at-ms (:received-at-ms snapshot)
            :observed-total observed
            :expected nil
            :state :pending
            :last-match-at-ms nil
            :evaluated-at-ms nil
            :saved-at-ms now-ms}))))

(defn latest-match-id [campaign-id] (str "balance-check:" campaign-id "|latest-match"))

(defn evaluate-balance-check
  "The check re-evaluated, with `:state` :pending (not yet decidable),
  :inconclusive, :match or :mismatch. Decidable only after a complete fills
  read requested at least `:balance-check-settle-ms` after the check's R
  (`fills-read-requested-at-ms`) and once fills and ledger coverage cover
  `[T0 - skew, R)`. Inconclusive when any same-base event or break falls at
  or before `R + skew`, or any tracked event within skew of R. Otherwise
  the observed total is compared EXACTLY with q0 plus the tracked net base
  deltas before R."
  [check {:keys [campaign events breaks coverage fills-read-requested-at-ms latest-match now-ms]}]
  (let [r (:requested-at-ms check)
        skew (or (get-in campaign [:baseline :boundary :skew-ms]) (policy :boundary-skew-ms))
        need [(- (:t0-ms campaign) skew) r]
        decided (fn [state & [expected]]
                  (assoc check :state state :expected expected :evaluated-at-ms now-ms
                         :last-match-at-ms (:requested-at-ms latest-match) :saved-at-ms now-ms))]
    (cond
      (not (and campaign (number? r))) (decided :inconclusive)
      (not (and (number? fills-read-requested-at-ms)
                (>= fills-read-requested-at-ms (+ r (policy :balance-check-settle-ms)))))
      check
      (not (and (iv/covers? (get-in coverage [:fills :complete]) need)
                (iv/covers? (get-in coverage [:ledger :complete]) need)))
      check
      (some #(<= (or (:time %) (:at-ms %)) (+ r skew)) breaks) (decided :inconclusive)
      (some #(<= (js/Math.abs (- (:time %) r)) skew) events) (decided :inconclusive)
      :else
      (let [expected (:q (ledger/state-before campaign events r))
            observed (some-> (:observed-total check) str precision/parse-decimal)]
        (cond
          (nil? observed) (decided :inconclusive)
          (zero? (precision/cmp observed (precision/parse-decimal expected))) (decided :match expected)
          :else (decided :mismatch expected))))))
