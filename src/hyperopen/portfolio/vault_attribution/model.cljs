(ns hyperopen.portfolio.vault-attribution.model
  "Pure building blocks for vault attribution: ledger transfers per vault,
   holding periods, a stitched share-price index per vault, and an IRR solver.
   Nothing here throws on malformed input; unusable rows are dropped."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.metrics.parsing :as parsing]
            [hyperopen.vaults.detail.position :as position]))

(def epsilon
  0.01)

(def day-ms 86400000)

(def ms-per-year
  (* 365.2425 day-ms))

(def ^:private dense-gap-ms
  (* 1.5 day-ms))

(def min-annualised-hold-ms
  (* 30 day-ms))

(def vault-delta-types
  #{"vaultDeposit" "vaultWithdraw" "vaultCreate" "vaultDistribution"})

(defn finite
  "`value` when it is a finite number, else nil."
  [value]
  (when (parsing/finite-number? value) value))

(defn number-in
  "Finite number from a number or numeric string, else nil."
  [value]
  (parsing/optional-number value))

(defn normalize-address
  [value]
  (some-> value str str/trim str/lower-case not-empty))

(defn normalize-keys
  "Lower-cases address keys of a map; anything that is not a map becomes {}."
  [m]
  (if (map? m)
    (reduce-kv (fn [acc k v]
                 (if-let [k* (normalize-address (if (keyword? k) (name k) k))]
                   (assoc acc k* v)
                   acc))
               {}
               m)
    {}))

(defn- ledger-rows*
  [rows]
  (if (sequential? rows) rows []))

(defn account-rows
  "Ledger rows that are the account's OWN vault transfers. When the account is
   itself a vault, its ledger also carries its followers' deposits into it and
   their withdrawals from it; those are dropped, as is any `vaultWithdraw`
   naming a different `:user`. Without an account address nothing is dropped."
  [rows account-address]
  (let [rows* (ledger-rows* rows)]
    (if-let [account (normalize-address account-address)]
      (filterv (fn [row]
                 (let [delta (when (map? row) (:delta row))
                       user (when (map? delta) (normalize-address (:user delta)))]
                   (not (and (map? delta)
                             (or (= account (normalize-address (:vault delta)))
                                 (and (= "vaultWithdraw" (:type delta))
                                      (some? user)
                                      (not= account user)))))))
               rows*)
      rows*)))

(defn ledger-vault-addresses
  "Every vault address the ledger mentions in a deposit, withdrawal, create or
   distribution."
  [rows]
  (into #{}
        (keep (fn [row]
                (let [delta (when (map? row) (:delta row))]
                  (when (and (map? delta)
                             (contains? vault-delta-types (:type delta)))
                    (normalize-address (:vault delta))))))
        (ledger-rows* rows)))

(defn vault-transfer-times
  "Times (ms) of every vault deposit, withdrawal or create row."
  [rows]
  (keep (fn [row]
          (let [delta (when (map? row) (:delta row))]
            (when (and (map? delta) (contains? vault-delta-types (:type delta)))
              (some-> (number-in (:time row)) js/Math.floor))))
        (ledger-rows* rows)))

(defn- create-transfer
  "`vaultCreate` carries the leader's seed capital as `:usdc` (the creation
   `:fee` is not part of the vault equity). Treated as a deposit."
  [vault-address row]
  (let [delta (when (map? row) (:delta row))
        time-ms (some-> (number-in (:time row)) js/Math.floor)
        amount (when (map? delta) (number-in (:usdc delta)))]
    (when (and (map? delta)
               (= "vaultCreate" (:type delta))
               (number? time-ms)
               (= vault-address (normalize-address (:vault delta)))
               (number? amount)
               (pos? amount))
      {:kind :deposit
       :time-ms time-ms
       :hash (:hash row)
       :amount amount
       :source :vault-create})))

(defn- distribution-transfer
  "`vaultDistribution` pays `:usdc` out to the depositor. It carries no basis,
   so `holding-periods` decides how much cost basis it returns."
  [vault-address row]
  (let [delta (when (map? row) (:delta row))
        time-ms (some-> (number-in (:time row)) js/Math.floor)
        amount (when (map? delta) (number-in (:usdc delta)))]
    (when (and (map? delta)
               (= "vaultDistribution" (:type delta))
               (number? time-ms)
               (= vault-address (normalize-address (:vault delta)))
               (number? amount))
      {:kind :withdraw
       :time-ms time-ms
       :hash (:hash row)
       :amount (js/Math.abs amount)
       :source :distribution})))

(defn vault-transfers
  "Deposits (including `vaultCreate`) and withdrawals (including
   `vaultDistribution`, tagged `:source :distribution`) for one vault, oldest
   first. Withdrawal `:amount` is the net USD received."
  [rows vault-address]
  (let [rows* (ledger-rows* rows)]
    (->> (concat (position/vault-transfers rows* vault-address)
                 (keep #(create-transfer vault-address %) rows*)
                 (keep #(distribution-transfer vault-address %) rows*))
         (filter (fn [{:keys [kind amount]}]
                   (if (= :deposit kind) (pos? amount) (>= amount 0))))
         (sort-by :time-ms)
         vec)))

(defn- reopen-last-period
  "The last closed period made current again: the position was not empty
   after all (money still came out of it, or equity is still held). Its
   closing row (always the last row: nothing is added while no period is
   open) no longer closes."
  [{:keys [rows periods] :as acc}]
  (assoc acc
         :current (dissoc (peek periods) :end-ms :closed?)
         :periods (pop periods)
         :rows (conj (pop rows) (assoc (peek rows) :closes? false))))

(defn holding-periods
  "Splits a vault's transfers into holding periods: from a deposit into an
   empty position until the cost basis returns to zero (or the final
   withdrawal of a vault whose equity is now zero). Returns
   `{:transfers [...] :periods [...] :leading-withdrawal? bool}`; each transfer
   gets `:period` (index) and withdrawals `:closes?`. An open last period ends
   at `end-ms`. `equity` is nil when unknown: then only the ledger basis can
   close a period.

   The basis reaching zero does not prove the position empty (profit can
   still be withdrawn after the basis is back, and a full withdrawal can be
   followed by dust). A withdrawal with no open period therefore reopens the
   previous period, and a closed last period is reopened when known equity is
   still held. Only a withdrawal before any deposit is `:leading-withdrawal?`.

   A distribution has no ledger basis. It closes its period only when it is
   the last transfer and the equity after it is known to be ~zero (the vault
   paid everything out); otherwise it returns at most the running basis. It
   gets the `:basis` it returned and its `:realized` P&L."
  [transfers equity end-ms]
  (let [n (count transfers)
        {:keys [rows periods current basis leading-withdrawal?] :as acc}
        (reduce
         (fn [{:keys [rows periods current basis] :as acc} [i transfer]]
           (if (= :deposit (:kind transfer))
             (let [current* (or current {:start-ms (:time-ms transfer)
                                         :index (count periods)})]
               (assoc acc
                      :current current*
                      :basis (+ basis (:amount transfer))
                      :rows (conj rows (assoc transfer :period (:index current*)))))

             (if (and (nil? current) (empty? periods))
               (assoc acc :leading-withdrawal? true)
               (let [{:keys [rows periods current basis] :as acc}
                     (if (nil? current) (reopen-last-period acc) acc)
                     amount (:amount transfer)
                     distribution? (= :distribution (:source transfer))
                     emptied? (and (= i (dec n)) (number? equity) (<= equity epsilon))
                     returned (cond
                                (not distribution?) (or (:basis transfer) amount)
                                emptied? basis
                                :else (min basis amount))
                     basis* (max 0 (- basis returned))
                     closes? (if distribution?
                               emptied?
                               (or (<= basis* epsilon) emptied?))
                     row (cond-> (assoc transfer :period (:index current) :closes? closes?)
                           distribution? (assoc :basis returned
                                                :realized (- amount returned)))]
                 (if closes?
                   (assoc acc
                          :current nil
                          :basis 0
                          :rows (conj rows row)
                          :periods (conj periods (assoc current
                                                        :end-ms (:time-ms transfer)
                                                        :closed? true)))
                   (assoc acc :basis basis* :rows (conj rows row)))))))
         {:rows [] :periods [] :current nil :basis 0 :leading-withdrawal? false}
         (map-indexed vector transfers))
        {:keys [rows periods current]}
        (if (and (nil? current) (seq periods) (number? equity) (> equity epsilon))
          (reopen-last-period acc)
          acc)]
    {:transfers rows
     :periods (cond-> periods
                current (conj (assoc current
                                     :end-ms (max end-ms (:start-ms current))
                                     :closed? false)))
     :open-at-end? (some? current)
     :ending-basis basis
     :leading-withdrawal? leading-withdrawal?}))

(defn- index-rows
  "`[[time-ms cumulative-pct] ...]` -> `[[time-ms index] ...]`, sorted,
   one row per time, index > 0."
  [rows]
  (->> (ledger-rows* rows)
       (keep (fn [row]
               (when (and (sequential? row) (>= (count row) 2))
                 (let [t (number-in (first row))
                       pct (number-in (second row))
                       index (when (number? pct) (+ 1 (/ pct 100)))]
                   (when (and (number? t) (finite index) (pos? index))
                     [t index])))))
       (sort-by first)
       (reduce (fn [acc row]
                 (if (= (first row) (first (peek acc)))
                   (conj (pop acc) row)
                   (conj acc row)))
               [])))

(defn index-at
  "Linear interpolation over sorted `[[time-ms index] ...]`, held flat beyond
   both ends. Nil only when `rows` is empty."
  [rows time-ms]
  (let [n (count rows)]
    (cond
      (or (zero? n) (not (number? time-ms))) nil
      (<= time-ms (ffirst rows)) (second (first rows))
      (>= time-ms (first (peek rows))) (second (peek rows))
      :else
      (loop [lo 0
             hi (dec n)]
        (if (> (- hi lo) 1)
          (let [mid (quot (+ lo hi) 2)]
            (if (<= (first (nth rows mid)) time-ms)
              (recur mid hi)
              (recur lo mid)))
          (let [[t0 v0] (nth rows lo)
                [t1 v1] (nth rows hi)]
            (if (= t0 t1)
              v1
              (+ v0 (* (- v1 v0) (/ (- time-ms t0) (- t1 t0)))))))))))

(defn merged-index
  "One index per vault from finest-first candidates: the finest set as is,
   then each coarser set's OLDER samples prepended, rescaled to meet the finer
   set at the join (every candidate is rebased to 0% at its own start)."
  [candidates]
  (reduce (fn [merged rows]
            (let [rows* (index-rows rows)]
              (cond
                (empty? rows*) merged
                (empty? merged) rows*
                :else
                (let [join-ms (ffirst merged)
                      older (filterv #(< (first %) join-ms) rows*)]
                  (if (seq older)
                    (let [scale (/ (second (first merged)) (index-at rows* join-ms))]
                      (into (mapv (fn [[t v]] [t (* v scale)]) older) merged))
                    merged)))))
          []
          (ledger-rows* candidates)))

(defn dense-start-ms
  "Earliest time from which the index is sampled at least ~daily through
   `now-ms`; nil when it never is."
  [index now-ms]
  (when (and (seq index)
             (<= (- now-ms (first (peek index))) dense-gap-ms))
    (loop [i (dec (count index))]
      (if (and (pos? i)
               (<= (- (first (nth index i)) (first (nth index (dec i)))) dense-gap-ms))
        (recur (dec i))
        (first (nth index i))))))

(defn- npv
  [flows t0 rate]
  (reduce (fn [acc [t cf]]
            (+ acc (* cf (js/Math.pow (+ 1 rate) (- (/ (- t t0) ms-per-year))))))
          0
          flows))

(def ^:private rate-grid
  [-0.999999 -0.99 -0.9 -0.5 -0.2 0 0.2 0.5 1 3 10 100 1000 1e4 1e5 1e6])

(defn- bisect
  [f lo hi]
  (loop [lo lo
         hi hi
         f-lo (f lo)
         i 0]
    (let [mid (/ (+ lo hi) 2)]
      (if (or (>= i 200) (< (- hi lo) 1e-13))
        mid
        (let [f-mid (f mid)]
          (cond
            (not (finite f-mid)) nil
            (zero? f-mid) mid
            (= (neg? f-mid) (neg? f-lo)) (recur mid hi f-mid (inc i))
            :else (recur lo mid f-lo (inc i))))))))

(defn irr
  "Annualised internal rate of return of dated flows `[[time-ms amount] ...]`
   (money in negative, money out positive), as a fraction. -1 for a total
   loss (money in, nothing back: -100% at any horizon). Nil when nothing went
   in or no root is bracketed."
  [flows]
  (let [flows* (filterv (fn [[t cf]] (and (finite t) (finite cf) (not (zero? cf))))
                        (ledger-rows* flows))
        money-in? (some #(neg? (second %)) flows*)
        money-out? (some #(pos? (second %)) flows*)]
    (if (and money-in? (not money-out?))
      -1
      (when (and money-in? money-out?)
      (let [t0 (apply min (map first flows*))
            f #(npv flows* t0 %)
            values (mapv (fn [r] [r (finite (f r))]) rate-grid)
            brackets (->> (partition 2 1 values)
                          (keep (fn [[[r0 v0] [r1 v1]]]
                                  (when (and (number? v0) (number? v1)
                                             (or (zero? v0)
                                                 (not= (neg? v0) (neg? v1))))
                                    [r0 r1]))))
            [lo hi] (first (sort-by (fn [[r0 r1]] (js/Math.abs (/ (+ r0 r1) 2)))
                                    brackets))]
        (when (number? lo)
          (finite (if (zero? (f lo)) lo (bisect f lo hi)))))))))

(defn money-weighted-return
  "`irr` of `flows`, but nil (not annualised) when the capital was held for
   less than `min-annualised-hold-ms` -- except a total loss, which is -100%
   at any horizon."
  [flows held-ms]
  (let [r (irr flows)]
    (when (or (= -1 r)
              (and (number? held-ms) (>= held-ms min-annualised-hold-ms)))
      r)))
