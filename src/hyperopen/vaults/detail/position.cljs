(ns hyperopen.vaults.detail.position
  "Pure read model for the viewer's own position in a vault.

   Hyperliquid's `followerState` is the source of truth for the money:
   `vaultEquity - pnl` is the cost basis of the CURRENT position, `pnl` is its
   unrealized P&L, and `allTimePnl - pnl` is P&L already realized through
   withdrawals (net of leader commission). The viewer's own
   `userNonFundingLedgerUpdates` only add the timeline: when the current
   position started (the first deposit after the last full withdrawal --
   `vaultEntryTime` is never reset) and the individual transfers."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.metrics :as portfolio-metrics]))

(def ^:private basis-epsilon
  ;; Withdraw `basis` and deposit amounts are float strings; a fully withdrawn
  ;; position can leave a sub-cent residue behind.
  0.01)

(def ^:private ms-per-day
  86400000)

(defn- finite-number
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (let [text (str/trim value)]
                              (when (seq text)
                                (js/Number text)))
            :else nil)]
    (when (and (number? n)
               (js/isFinite n))
      n)))

(defn- normalize-address
  [value]
  (some-> value str str/trim str/lower-case not-empty))

(defn- transfer-row
  [vault-address row]
  (let [delta (:delta row)
        time-ms (some-> (finite-number (:time row)) js/Math.floor)]
    (when (and (map? delta)
               (number? time-ms)
               (= vault-address (normalize-address (:vault delta))))
      (case (:type delta)
        "vaultDeposit"
        (when-let [amount (finite-number (:usdc delta))]
          {:kind :deposit
           :time-ms time-ms
           :hash (:hash row)
           :amount amount})

        "vaultWithdraw"
        (let [net (finite-number (:netWithdrawnUsd delta))
              requested (finite-number (:requestedUsd delta))
              basis (finite-number (:basis delta))]
          (when-let [amount (or net requested)]
            {:kind :withdraw
             :time-ms time-ms
             :hash (:hash row)
             :amount amount
             :requested requested
             :basis basis
             :commission (finite-number (:commission delta))
             :realized (when (and (number? net)
                                  (number? basis))
                         (- net basis))}))

        nil))))

(defn vault-transfers
  "The viewer's deposits into and withdrawals from `vault-address`, oldest
   first. `rows` are raw `userNonFundingLedgerUpdates` rows."
  [rows vault-address]
  (if-let [vault-address* (normalize-address vault-address)]
    (->> (if (sequential? rows) rows [])
         (keep #(transfer-row vault-address* %))
         (sort-by :time-ms)
         vec)
    []))

(defn- with-position-flags
  "Walks the running cost basis and marks which transfers belong to the
   current position: everything after the last time the basis returned to
   zero."
  [transfers]
  (let [{:keys [rows start-index]}
        (reduce (fn [{:keys [basis rows start-index]} transfer]
                  (let [index (count rows)]
                    (case (:kind transfer)
                      :deposit
                      {:basis (+ basis (:amount transfer))
                       :rows (conj rows transfer)
                       :start-index (if (<= basis basis-epsilon) index start-index)}

                      :withdraw
                      {:basis (max 0 (- basis (or (:basis transfer)
                                                  (:amount transfer))))
                       :rows (conj rows transfer)
                       :start-index start-index})))
                {:basis 0 :rows [] :start-index nil}
                transfers)]
    {:transfers (vec (map-indexed (fn [index transfer]
                                    (assoc transfer :current-position?
                                           (boolean (and (number? start-index)
                                                         (>= index start-index)))))
                                  rows))
     :start-ms (some-> start-index rows :time-ms)}))

(defn- cumulative-percent-at
  "Linear interpolation over `[[time-ms cumulative-percent] ...]`, sorted by
   time. Nil before the first point: the history cannot say what happened."
  [rows time-ms]
  (let [first-row (first rows)
        last-row (peek rows)]
    (cond
      (or (nil? first-row)
          (not (number? time-ms))
          (< time-ms (first first-row)))
      nil

      (>= time-ms (first last-row))
      (second last-row)

      :else
      (let [[[t0 v0] [t1 v1]] (->> (partition 2 1 rows)
                                   (some (fn [[[ta] [tb] :as pair]]
                                           (when (and (<= ta time-ms)
                                                      (<= time-ms tb))
                                             pair))))]
        (if (= t0 t1)
          v1
          (+ v0 (* (- v1 v0) (/ (- time-ms t0) (- t1 t0)))))))))

(defn- clean-rows
  [rows]
  (vec (sort-by first (filter (fn [[t v]]
                                (and (number? t) (number? v)))
                              rows))))

(defn vault-return-since
  "Vault return in percent from `time-ms` to the newest history point, or nil
   when the history does not reach back that far."
  [returns-rows time-ms]
  (let [rows (clean-rows returns-rows)
        start (cumulative-percent-at rows time-ms)
        end (some-> rows peek second)]
    (when (and (number? start)
               (number? end)
               (> (+ 1 (/ start 100)) 0))
      (* 100 (- (/ (+ 1 (/ end 100))
                   (+ 1 (/ start 100)))
                1)))))

(defn rows-covering
  "The first (finest) candidate row set whose history reaches back to
   `time-ms`. Candidates are ordered finest to coarsest (day, week, month,
   all-time), so recent deposits get the densest history available."
  [candidates time-ms]
  (when (number? time-ms)
    (some (fn [rows]
            (let [rows* (clean-rows rows)]
              (when (and (>= (count rows*) 2)
                         (<= (ffirst rows*) time-ms))
                rows*)))
          candidates)))

(defn- index-at
  [rows time-ms]
  (when-let [pct (cumulative-percent-at rows time-ms)]
    (let [index (+ 1 (/ pct 100))]
      (when (pos? index) index))))

(defn- apply-transfer
  [{:keys [units basis]} {:keys [kind amount requested time-ms] :as transfer} rows]
  (let [index (index-at rows time-ms)]
    (case kind
      :deposit {:units (+ units (/ amount index))
                :basis (+ basis amount)}
      :withdraw {:units (max 0 (- units (/ (or requested amount) index)))
                 :basis (max 0 (- basis (or (:basis transfer) amount)))})))

(defn position-series
  "Estimated value of the current position over time: units bought at the
   vault's share-price index (from cumulative returns) on each deposit, sold on
   each withdrawal, marked to the index in between, ending at the live value.
   Each transfer contributes a before and after point so the lines step.
   Nil when the vault history does not cover the position's first deposit."
  [{:keys [transfers returns-candidates now-ms value cost-basis]}]
  (let [transfers* (vec (sort-by :time-ms transfers))
        start-ms (:time-ms (first transfers*))
        rows (rows-covering returns-candidates start-ms)]
    (when (and rows (number? now-ms))
      (let [state-before (fn [t inclusive?]
                           (reduce (fn [acc transfer]
                                     (if (if inclusive?
                                           (<= (:time-ms transfer) t)
                                           (< (:time-ms transfer) t))
                                       (apply-transfer acc transfer rows)
                                       (reduced acc)))
                                   {:units 0 :basis 0}
                                   transfers*))
            point (fn [t inclusive?]
                    (let [{:keys [units basis]} (state-before t inclusive?)]
                      {:time-ms t
                       :value (* units (index-at rows t))
                       :basis basis}))
            transfer-times (set (map :time-ms transfers*))
            history-times (->> rows
                               (map first)
                               (filter #(and (> % start-ms) (< % now-ms))))
            times (sort (distinct (concat history-times transfer-times)))
            points (into []
                         (mapcat (fn [t]
                                   (if (contains? transfer-times t)
                                     (if (= t start-ms)
                                       [(point t true)]
                                       [(point t false) (point t true)])
                                     [(point t true)])))
                         times)
            last-point (peek points)
            ;; The index model drifts from the live balance (commission, stale
            ;; history). Spread that gap linearly over the window instead of
            ;; drawing a cliff at "now".
            model-now (* (:units (state-before now-ms true)) (index-at rows now-ms))
            ratio (if (and (number? value) (pos? value) (pos? model-now))
                    (/ value model-now)
                    1)
            span-ms (max 1 (- now-ms start-ms))
            calibrated (mapv (fn [{:keys [time-ms] :as p}]
                               (update p :value * (+ 1 (* (- ratio 1)
                                                          (/ (- time-ms start-ms) span-ms)))))
                             points)
            points* (if (and (number? value)
                             (number? cost-basis)
                             (> now-ms (:time-ms last-point)))
                      (conj calibrated {:time-ms now-ms :value value :basis cost-basis})
                      calibrated)]
        (when (>= (count points*) 2)
          {:points points*
           :markers (mapv #(select-keys % [:kind :time-ms :amount]) transfers*)
           :start-ms start-ms
           :end-ms (:time-ms (peek points*))})))))

(defn returns-rows-from-summary
  "Cumulative-percent rows for the vault's all-time portfolio summary."
  [summary]
  (if (map? summary)
    (->> (portfolio-metrics/returns-history-rows-from-summary summary)
         (keep (fn [row]
                 (let [t (portfolio-metrics/history-point-time-ms row)
                       v (portfolio-metrics/history-point-value row)]
                   (when (and (number? t) (number? v))
                     [t v]))))
         vec)
    []))

(defn- lockup-status
  [lockup-until-ms now-ms]
  (if (and (number? lockup-until-ms)
           (number? now-ms)
           (> lockup-until-ms now-ms))
    {:locked? true
     :lockup-until-ms lockup-until-ms
     :lockup-remaining-ms (- lockup-until-ms now-ms)}
    {:locked? false
     :lockup-until-ms lockup-until-ms
     :lockup-remaining-ms nil}))

(defn- composition
  "Shares for the value bar: basis and gain over value when in profit, value
   and loss over basis when under water."
  [value cost-basis unrealized]
  (cond
    (not (and (number? value) (number? cost-basis) (number? unrealized)))
    nil

    (and (>= unrealized 0) (pos? value))
    {:direction :gain
     :basis-share (/ cost-basis value)
     :pnl-share (/ unrealized value)}

    (pos? cost-basis)
    {:direction :loss
     :basis-share (/ value cost-basis)
     :pnl-share (/ (- unrealized) cost-basis)}

    :else nil))

(defn position-summary
  "Everything the vault detail page shows about the viewer's own money.

   Inputs:
   - `:follower`      normalized follower state (`:vault-equity`, `:pnl`,
                      `:all-time-pnl`, `:vault-entry-time-ms`, `:lockup-until-ms`)
   - `:equity`        optional fresher equity from `userVaultEquities`
   - `:ledger-rows`   raw ledger rows for the viewer, or nil when not loaded
   - `:ledger-status` one of `:idle` `:loading` `:ready` `:error`
   - `:returns-candidates` vault cumulative-percent row sets `[[time-ms pct] ...]`,
                      finest first (day, week, month, all-time); `:returns-rows`
                      is accepted as a single candidate."
  [{:keys [vault-address follower equity ledger-rows ledger-status returns-rows returns-candidates now-ms]}]
  (let [follower-equity (finite-number (:vault-equity follower))
        value (or (finite-number equity) follower-equity)
        pnl (finite-number (:pnl follower))
        all-time-pnl (finite-number (:all-time-pnl follower))
        cost-basis (when (and (number? follower-equity) (number? pnl))
                     (- follower-equity pnl))
        unrealized (when (and (number? value) (number? cost-basis))
                     (- value cost-basis))
        open? (and (number? value) (> value basis-epsilon))
        status (cond
                 open? :open
                 (number? all-time-pnl) :closed
                 :else :none)
        candidates (vec (remove empty? (or returns-candidates [returns-rows])))
        return-since (fn [time-ms]
                       ;; A vault return over less than a day is noise.
                       (when (and (number? time-ms)
                                  (number? now-ms)
                                  (>= (- now-ms time-ms) ms-per-day))
                         (vault-return-since (rows-covering candidates time-ms) time-ms)))
        ledger-ready? (= :ready ledger-status)
        {:keys [transfers start-ms]} (with-position-flags
                                       (if ledger-ready?
                                         (vault-transfers ledger-rows vault-address)
                                         []))
        entry-ms (some-> (finite-number (:vault-entry-time-ms follower)) js/Math.floor)
        position-start-ms (if open? (or start-ms entry-ms) start-ms)
        transfers* (->> transfers
                        (map (fn [{:keys [kind time-ms current-position?] :as transfer}]
                               (cond-> transfer
                                 (and (= :deposit kind) current-position? open?)
                                 (assoc :vault-return-since-pct (return-since time-ms)))))
                        reverse
                        vec)]
    (merge
     {:status status
      :value value
      :cost-basis cost-basis
      :unrealized unrealized
      :unrealized-pct (when (and (number? unrealized)
                                 (number? cost-basis)
                                 (pos? cost-basis))
                        (* 100 (/ unrealized cost-basis)))
      :realized (when (and (number? all-time-pnl) (number? pnl))
                  (- all-time-pnl pnl))
      :all-time-earned all-time-pnl
      :first-deposit-ms entry-ms
      :position-start-ms position-start-ms
      :position-start-exact? (number? start-ms)
      :days-held (when (and (number? position-start-ms) (number? now-ms))
                   (max 0 (js/Math.floor (/ (- now-ms position-start-ms) ms-per-day))))
      :vault-return-since-start-pct (when open? (return-since position-start-ms))
      :series (when (and open? (number? start-ms))
                (position-series {:transfers (filter :current-position? transfers)
                                  :returns-candidates candidates
                                  :now-ms now-ms
                                  :value value
                                  :cost-basis cost-basis}))
      :composition (when open? (composition value cost-basis unrealized))
      :ledger-status (or ledger-status :idle)
      :transfers transfers*
      :transfer-count (count transfers*)}
     (lockup-status (finite-number (:lockup-until-ms follower)) now-ms))))
