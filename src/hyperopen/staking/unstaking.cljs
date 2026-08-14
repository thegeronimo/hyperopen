(ns hyperopen.staking.unstaking
  "Pure derivations for HYPE that is mid-unstake.

  Two separate locks exist and users conflate them:

  1. The delegation lockup (~1 day). Delegated HYPE cannot be undelegated until
     the delegation's own `:locked-until-timestamp` passes.
  2. The unstaking queue (~7 days). Moving HYPE from the staking balance back to
     the spot balance is queued; the HYPE leaves the staking balance at once and
     lands in spot 7 days later. In between it is in neither place.

  The queue has no ETA field on the wire. `delegatorSummary` reports only the
  in-flight amount and a count, so arrival times are attributed from
  `delegatorHistory`.

  The attribution rule below was derived from live `delegatorHistory` responses
  for 25 validator accounts (1,330 rows) on 2026-08-14, because the phase
  vocabulary is not documented anywhere in this repo:

    - Queue events arrive ONLY as `{:kind :withdrawal :phase …}`. The `cWithdraw`
      shape that `submit-staking-withdraw` sends, which normalizes to
      `{:kind :withdraw}`, appeared zero times in that sample. It is therefore
      not a usable anchor even though it is the action that starts the queue.
    - The observed phases are exactly `initiated` and `finalized` (400/388 rows).
    - An `initiated` row with no later matching `finalized` row is a queue entry
      still in flight. Counting those matched `nPendingWithdrawals` exactly for
      every account sampled.
    - Every paired initiated/finalized gap was 7.000 days, min and max, which
      independently confirms `queue-duration-ms`.

  Phase names are normalized rather than compared verbatim, so a provider rename
  to e.g. `initiatedWithdrawal` still resolves. Anything unrecognized is treated
  as non-terminal, which degrades to a hedged or absent ETA rather than a wrong
  one. The summary remains authoritative for how much is in flight; history only
  supplies timestamps, and must reconcile against the summary before any date is
  reported."
  (:require [clojure.string :as str]
            [hyperopen.staking.account-scope :as account-scope]))

(def queue-duration-ms
  "Staking balance -> spot balance takes 7 days."
  604800000)

(def delegation-lockup-ms
  "A fresh delegation cannot be undelegated for 1 day."
  86400000)

(def ^:private ready-grace-ms
  "A queue entry whose 7 days have elapsed can still be reported as pending by
  the summary for a while. Keep such entries as candidates so they read as
  arriving rather than silently dropping out of the model."
  86400000)

(def ^:private reconcile-epsilon
  "HYPE carries 8 decimals, so double-precision error when summing realistic
  amounts stays far below this. Anything larger is a genuine mismatch."
  1e-6)

(defn- finite-number?
  [value]
  (and (number? value)
       (js/isFinite value)
       (not (js/isNaN value))))

(defn- optional-number
  [value]
  (cond
    (number? value)
    (when (finite-number? value)
      value)

    (string? value)
    (let [parsed (js/Number (str/trim value))]
      (when (finite-number? parsed)
        parsed))

    :else
    nil))

(defn- default-now-ms
  []
  (.now js/Date))

(defn- normalize-phase
  "Lower-cases the phase and drops a trailing `withdrawal`, so both the observed
  `initiated`/`finalized` and a hypothetical `initiatedWithdrawal` resolve the
  same way."
  [phase]
  (some-> phase
          name
          str/lower-case
          str/trim
          (str/replace #"withdrawal$" "")
          str/trim
          not-empty))

(defn- initiated-phase?
  [phase]
  (= "initiated" (normalize-phase phase)))

(defn- finalized-phase?
  [phase]
  (= "finalized" (normalize-phase phase)))

(defn- withdrawal-events
  [history]
  (->> (or history [])
       (keep (fn [row]
               (let [delta (:delta row)
                     started-at-ms (optional-number (:time-ms row))
                     amount (optional-number (:amount delta))]
                 (when (and (= :withdrawal (:kind delta))
                            (finite-number? started-at-ms)
                            (finite-number? amount)
                            (pos? amount))
                   {:amount amount
                    :started-at-ms started-at-ms
                    :phase (:phase delta)}))))
       (sort-by :started-at-ms)
       vec))

(defn- unpaired-initiated
  "Initiated queue entries with no later matching finalized entry, oldest first.

  Pairs FIFO on equal amount, which is how the queue actually drains: entries
  finalize in the order they were initiated, exactly one queue duration later."
  [history]
  (let [events (withdrawal-events history)]
    (loop [initiated (filterv #(initiated-phase? (:phase %)) events)
           pool (filterv #(finalized-phase? (:phase %)) events)
           pending []]
      (if-let [entry (first initiated)]
        (if-let [idx (first (keep-indexed
                             (fn [index final]
                               (when (and (= (:amount final) (:amount entry))
                                          (> (:started-at-ms final) (:started-at-ms entry)))
                                 index))
                             pool))]
          (recur (subvec initiated 1)
                 (into (subvec pool 0 idx) (subvec pool (inc idx)))
                 pending)
          (recur (subvec initiated 1) pool (conj pending entry)))
        pending))))

(defn- queue-candidates
  "Queue entries that could still be in flight, newest first.

  An unpaired initiated entry older than the queue duration plus a grace window
  is a history gap rather than a live entry, so it is dropped; reconciliation
  against the summary then reports the shortfall honestly."
  [history now-ms]
  (let [oldest-relevant-ms (- now-ms queue-duration-ms ready-grace-ms)]
    (->> (unpaired-initiated history)
         (filter (fn [{:keys [started-at-ms]}]
                   (and (> started-at-ms oldest-relevant-ms)
                        (<= started-at-ms now-ms))))
         (mapv #(select-keys % [:amount :started-at-ms]))
         (sort-by :started-at-ms >)
         vec)))

(defn- reconciles?
  "True when the candidate rows account for exactly what the summary reports.

  The count is only compared when the provider supplied one; a matching total is
  sufficient evidence on its own."
  [candidates amount expected-count]
  (and (seq candidates)
       (or (not (finite-number? expected-count))
           (= (count candidates) (int expected-count)))
       (< (js/Math.abs (- (reduce + (map :amount candidates)) amount))
          reconcile-epsilon)))

(defn- with-timing
  [{:keys [started-at-ms] :as candidate} now-ms]
  (let [arrives-at-ms (+ started-at-ms queue-duration-ms)]
    (assoc candidate
           :arrives-at-ms arrives-at-ms
           :remaining-ms (max 0 (- arrives-at-ms now-ms))
           :ready? (<= arrives-at-ms now-ms))))

(defn pending-unstake
  "What is currently sitting in the 7-day queue, and when it lands.

  `:timing` is `:known` when history reconciles against the authoritative
  summary, `:estimated` when there are candidate rows that do not reconcile, and
  `:unknown` when there is nothing to attribute. Callers must not render an
  arrival time under `:unknown`, and must qualify one under `:estimated`."
  ([state]
   (pending-unstake state (default-now-ms)))
  ([state now-ms]
   (let [summary (get-in state [:staking :delegator-summary])
         amount (optional-number (:total-pending-withdrawal summary))
         expected-count (optional-number (:pending-withdrawals summary))
         in-flight? (boolean (and (finite-number? amount) (pos? amount)))
         candidates (if in-flight?
                      (queue-candidates (get-in state [:staking :history]) now-ms)
                      [])
         known? (and in-flight? (reconciles? candidates amount expected-count))
         entries (if known?
                   (mapv #(with-timing % now-ms) candidates)
                   [])
         arrivals (map #(+ (:started-at-ms %) queue-duration-ms) candidates)]
     {:amount (if in-flight? amount 0)
      :count (when (and in-flight?
                        (finite-number? expected-count)
                        (pos? expected-count))
               (int expected-count))
      :in-flight? in-flight?
      :timing (cond
                (not in-flight?) :unknown
                known? :known
                (seq candidates) :estimated
                :else :unknown)
      :entries entries
      :next-arrival-ms (when (seq arrivals) (apply min arrivals))
      :last-arrival-ms (when (seq arrivals) (apply max arrivals))})))

(defn queue-progress-fraction
  "How far a single queue entry has travelled, as 0..1, or nil when unknowable."
  [{:keys [remaining-ms]}]
  (when (finite-number? remaining-ms)
    (-> (- queue-duration-ms remaining-ms)
        (/ queue-duration-ms)
        (max 0)
        (min 1))))

(defn locked-delegation
  "The delegation for `validator` when its lockup has not expired, else nil.

  Shares `account-scope/delegation-locked-after?` with the undelegate submit
  guard so a pre-submit warning and the guard cannot disagree about the boundary
  (a lock exactly equal to `now-ms` is not locked)."
  ([state validator]
   (locked-delegation state validator (default-now-ms)))
  ([state validator now-ms]
   (let [row (account-scope/delegation-row-by-validator state validator)]
     (when (account-scope/delegation-locked-after? row now-ms)
       (assoc row :remaining-ms (max 0 (- (:locked-until-timestamp row) now-ms)))))))

(defn projected-arrival-ms
  "When a staking -> spot transfer started at `now-ms` would land."
  [now-ms]
  (when (finite-number? now-ms)
    (+ now-ms queue-duration-ms)))
