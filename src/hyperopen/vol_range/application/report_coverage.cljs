(ns hyperopen.vol-range.application.report-coverage
  "Coverage, breaks and balance checks for Vol Range reporting (HO-SPEC-014
  v2.1 sections 9.2, 12.4, 12.6, 13.5 row 8).

  Coverage is proven ONLY by REST reads, per account, stream (:fills or
  :ledger) and tracked base token: a complete read of `[s, now]` requested
  at R covers `[s, R - :read-settle-ms)` for the tokens whose rows it
  classified and persisted; one stopped by the page cap or no progress
  covers `[s, newest row)`, so backfill always moves forward. A fills read
  whose rows hold at least `:venue-fill-retention-count` DISTINCT fill keys
  (all coins, overlap duplicates counted once) proves only what follows its
  oldest row; the time before is PERMANENTLY lost (`:coverage/retention`),
  is never read again and never proven by a later read. A relevant row that
  cannot be parsed ends the proof at its time (`:coverage/unparsed-row`).
  The unproven rest of a read records a failure interval. Over a needed
  interval:

  - covered by a complete read        -> :complete
  - in a recorded failure, uncovered  -> :gap (heals only when a later
                                         complete read covers it)
  - never read                        -> :partial (:coverage/backfill-pending)
  - from a stored row that no longer
    decodes                           -> :gap (:storage/unreadable-rows)
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

;; Reads that stopped early paged forward from their start, so they still
;; prove everything before their newest row.
(def ^:private read-reasons
  {:fills/no-progress :coverage/no-progress :fills/page-cap :coverage/page-cap
   :ledger/no-progress :coverage/no-progress :ledger/page-cap :coverage/page-cap})

(defn distinct-fill-count
  "Distinct fill keys among raw fill `rows` (every coin)."
  [network account-address rows]
  (count (into #{} (map #(evidence/fill-key network account-address %)) rows)))

(defn- row-times [rows] (filter number? (map :time rows)))

(defn- read->coverage
  "Per tracked token, what one read over `[start-ms, ...]` proves:

  - a complete read, `[from, R - settle)`; one cut short by the page cap or
    no progress, `[from, newest row time)`; a failed one, nothing;
  - `from` is `start-ms`, or just after the oldest row when the venue's
    retention may have dropped older fills (`oldest-retained`): the part
    before it is recorded as a PERMANENT `:coverage/retention` failure,
    since the venue never serves those fills again;
  - a row that could not be parsed and could concern the token (`unparsed`,
    {token-id earliest-time}) ends the proof at its time
    (`:coverage/unparsed-row`).

  The unproven tail `[end, R)` is a failure with the read's reason."
  [stream {:keys [read start-ms base-token-ids unparsed]} oldest-retained]
  (let [r (:requested-at-ms read)
        at (or (:received-at-ms read) r)
        times (row-times (:rows read))
        read-end (cond (:complete? read) (- r (policy :read-settle-ms))
                       (and (contains? read-reasons (:reason read)) (seq times)) (apply max times))
        from (if (number? oldest-retained) (max start-ms (inc oldest-retained)) start-ms)
        read-failure (when-not (:complete? read) (get read-reasons (:reason read) :coverage/read-error))
        failure (fn [a reason] {:from-ms a :to-ms (max r (inc a)) :reason reason :at-ms at})
        token-deltas
        (fn [token]
          (let [u (get unparsed token)
                cut? (and (number? u) (or (nil? read-end) (< u read-end)))
                end (if cut? u read-end)
                reason (cond cut? :coverage/unparsed-row read-failure read-failure)]
            (cond-> []
              (> from start-ms)
              (conj {:stream stream :base-token-id token :complete []
                     :failure (assoc (failure start-ms :coverage/retention) :to-ms from :permanent? true)})
              :always
              (conj {:stream stream :base-token-id token
                     :complete (if (number? end) (iv/normalize [[from end]]) [])
                     :failure (when reason (failure (if (and (number? end) (> end from)) end from) reason))}))))]
    (if-not (and (number? start-ms) (number? r))
      []
      (into [] (mapcat token-deltas) (sort (distinct (map str base-token-ids)))))))

(defn fills-read->coverage
  "Coverage deltas [{:stream :fills :base-token-id :complete :failure}] for a
  `reads/read-fills-window!` result over `[start-ms, now]`, one or two per
  base token in `base-token-ids` (the tokens tracked BEFORE the read was
  requested; only their rows were persisted). A read holding at least
  `:venue-fill-retention-count` DISTINCT fill keys (all coins) proves only
  what lies after its oldest row. `unparsed` is {token-id earliest time} of
  relevant rows that could not be parsed."
  [{:keys [read network account-address] :as args}]
  (read->coverage :fills args
                  (when (>= (distinct-fill-count network account-address (:rows read))
                            (policy :venue-fill-retention-count))
                    (let [times (row-times (:rows read))]
                      (if (seq times) (apply min times) (:requested-at-ms read))))))

(defn ledger-read->coverage
  "Coverage deltas for a `report-reads/read-ledger-window!` result. The
  ledger's retention is unknown, so only completeness and unparsed rows
  apply."
  [args]
  (read->coverage :ledger args nil))

(defn coverage-record-id [account stream base-token-id]
  (str "coverage:" account "|" (name stream) "|" base-token-id))

(defn lost-intervals
  "The PERMANENT failures of a coverage record (time the venue no longer
  serves), as intervals."
  [record]
  (iv/normalize (keep #(when (:permanent? %) [(:from-ms %) (:to-ms %)]) (:failures record))))

(defn merge-coverage
  "`existing` coverage record (or nil) with `delta` merged: complete
  intervals only grow. A delta's complete intervals never add time already
  known to be lost (a permanent failure): a read made after the venue
  dropped those fills cannot prove them, while what an earlier read proved
  stays proven. Failures keep the newest 20 permanent ones and the newest 20
  others."
  [existing {:keys [stream base-token-id complete failure]} {:keys [account-address now-ms]}]
  (let [all (cond-> (vec (:failures existing)) failure (conj failure))
        failures (into (vec (take-last max-failures (filter :permanent? all)))
                       (take-last max-failures (remove :permanent? all)))]
    {:schema-version contracts/ledger-schema-version
     :record-id (coverage-record-id account-address stream base-token-id)
     :kind :coverage
     :account-address account-address
     :stream stream
     :base-token-id base-token-id
     :complete (iv/union (:complete existing) (iv/subtract complete (lost-intervals {:failures failures})))
     :failures failures
     :saved-at-ms now-ms}))

(defn- campaign-start [c]
  (- (:t0-ms c) (or (get-in c [:baseline :boundary :skew-ms]) (policy :boundary-skew-ms))))

(defn read-start
  "Where the next backfill read of one stream starts: the earliest point any
  tracked token still needs, minus `overlap`. A token needs `[T0 - skew,
  end)` of each of its campaigns (`end` is the closure time, or `now-ms`
  while open), less what its `coverage` record ({base-token-id record})
  proves complete and less what the venue no longer serves (permanent
  failures), so a gap that can never heal does not hold every later read
  back. `now-ms - overlap` when nothing is needed."
  [{:keys [campaigns closures coverage overlap now-ms]}]
  (let [token-of #(some-> (get-in % [:scope :base-token-id]) str)
        starts (for [[token cs] (group-by token-of (vals campaigns))
                     :when token
                     :let [rec (get coverage token)
                           need (for [c cs]
                                  [(campaign-start c) (or (get-in closures [(:campaign-id c) :ended-at-ms]) now-ms)])
                           gaps (iv/subtract need (iv/union (:complete rec) (lost-intervals rec)))]
                     :when (seq gaps)]
                 (ffirst gaps))]
    (max 0 (- (if (seq starts) (apply min starts) now-ms) overlap))))

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
          ;; Newest first, permanent losses before transient failures.
          failures (let [newest (reverse (:failures record))]
                     (concat (filter :permanent? newest) (remove :permanent? newest)))
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
  `storage-ok?` false (the ledger could not be read) is `:unavailable`.
  `unreadable` ([{:time}] from `campaign-findings`) are stored rows that no
  longer decode: from the earliest one on, coverage is a
  `:storage/unreadable-rows` gap."
  [{:keys [campaign coverage breaks balance-checks until-ms storage-ok? unreadable] :or {storage-ok? true}}]
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
            unreadable-from (some->> (seq (filter #(and (number? %) (< % to)) (map :time unreadable)))
                                     (apply min)
                                     (max from))
            fills (stream-intervals (:fills coverage) [from to])
            ledger-ivs (stream-intervals (:ledger coverage) [from to])
            points (concat (mapcat (juxt :from-ms :to-ms) (concat fills ledger-ivs)) (map :at-ms all-breaks)
                           (when unreadable-from [unreadable-from]))
            intervals (merge-adjacent
                       (for [[a b] (segments [from to] points)]
                         (let [candidates (cond-> [(state-at fills a) (state-at ledger-ivs a)]
                                            (and unreadable-from (>= a unreadable-from))
                                            (conj {:state :gap :reason :storage/unreadable-rows})
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
