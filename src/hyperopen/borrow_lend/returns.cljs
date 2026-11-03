(ns hyperopen.borrow-lend.returns
  "Realized supply returns, rebuilt from interest history.

  HyperCore has no balance or rate history for a lending position, only the
  interest paid each hour (or day) and the supply and withdraw events. Walking
  backward from today's supplied value, subtracting each interest payment and
  undoing each supply or withdraw, gives the balance every interest row
  accrued on. Realized APY over a period is then interest received divided by
  balance-hours, annualized: the balance-weighted average of the rate the pool
  actually paid.

  Pure functions over normalized rows (`hyperopen.borrow-lend.normalize`).")

(def ^:private hour-ms (* 60 60 1000))
(def ^:private day-ms (* 24 hour-ms))
(def ^:private hours-per-year (* 365 24))

(def periods
  "Fixed look-back windows, shortest first. A window longer than the
  position's history is left out; \"Since first supply\" covers it."
  [{:id :7d :label "7 days" :days 7}
   {:id :1m :label "1 month" :days 30}
   {:id :3m :label "3 months" :days 91}
   {:id :6m :label "6 months" :days 182}])

(defn- tolerance
  "Rounding slack for the rebuilt balance: a cent, or 0.01% of the position."
  [value]
  (max 0.01 (* 1e-4 (js/Math.abs value))))

(defn- interest-end-ms
  "When the interest in `row` had finished accruing. Hourly rows are stamped
  just after their hour; daily aggregates at the start of their day."
  [{:keys [time-ms daily?]}]
  (if daily? (+ time-ms day-ms) time-ms))

(defn- interest-span-ms
  [{:keys [daily?]}]
  (if daily? day-ms hour-ms))

(defn exposure-rows
  "Each interest row for one token with the balance it accrued on, oldest
  first: `{:end-ms :span-ms :interest :hours :balance}`, `:hours` 0 where
  nothing was supplied. `interest-rows` and `ledger-rows` must already be that token's.
  Returns nil when the rebuilt balance goes negative, which means a balance
  change the history does not show (for example portfolio margin's automatic
  supply)."
  [current-value interest-rows ledger-rows]
  (when (and (number? current-value) (js/isFinite current-value))
    (let [tol (tolerance current-value)
          events (sort-by :at
                          (concat
                           (map (fn [row] (assoc row :kind :interest :at (interest-end-ms row)))
                                interest-rows)
                           (map (fn [{:keys [time-ms operation amount]}]
                                  {:kind :ledger
                                   :at time-ms
                                   :delta (if (= :withdraw operation) (- amount) amount)})
                                ledger-rows)))]
      (loop [events (reverse events)
             balance current-value
             out ()]
        (if-let [{:keys [kind at] :as event} (first events)]
          (let [before (- balance (if (= :ledger kind) (:delta event) (:supply event)))]
            (cond
              (< before (- tol)) nil
              (= :ledger kind) (recur (rest events) before out)
              :else (let [supplied? (> before tol)]
                      (recur (rest events)
                             before
                             (conj out {:end-ms at
                                        :span-ms (interest-span-ms event)
                                        :interest (:supply event)
                                        :hours (if supplied? (:hours event) 0)
                                        :balance (if supplied? before 0)})))))
          (vec out))))))

(defn- in-window
  "The share of `row`'s span after `from-ms`: 1 inside the window, 0 before
  it, a fraction for a daily row that straddles the start."
  [from-ms {:keys [end-ms span-ms]}]
  (min 1 (max 0 (/ (- end-ms from-ms) (or span-ms hour-ms)))))

(defn- summarize
  "Totals over the exposure rows after `from-ms`, prorating a row that
  straddles it."
  [rows from-ms]
  (let [weighted (keep (fn [row]
                         (let [w (in-window from-ms row)]
                           (when (pos? w) [w row])))
                       rows)
        interest (reduce + 0 (map (fn [[w row]] (* w (:interest row))) weighted))
        hours (reduce + 0 (map (fn [[w row]] (* w (:hours row))) weighted))
        balance-hours (reduce + 0 (map (fn [[w row]] (* w (:balance row) (:hours row))) weighted))]
    (when (pos? hours)
      {:interest interest
       :hours hours
       :avg-balance (/ balance-hours hours)
       :realized-apy (when (pos? balance-hours)
                       (/ (* interest hours-per-year) balance-hours))})))

(defn realized-returns
  "Realized returns for one supplied token.

  `{:status :ok :since-ms <first supplied hour> :periods [...]}` where each
  period has `:id :label :interest :hours :avg-balance :realized-apy`, and
  fixed periods also `:days`. `{:status :inconsistent}` when the history does
  not add up to today's balance; `{:status :empty}` when nothing accrued yet."
  [{:keys [now-ms current-value interest-rows ledger-rows]}]
  (if-let [rows (exposure-rows current-value interest-rows ledger-rows)]
    (if-let [first-row (first (filter #(pos? (:hours %)) rows))]
      (let [since-ms (- (:end-ms first-row) (* hour-ms (:hours first-row)))
            age-ms (- now-ms since-ms)
            fixed (keep (fn [{:keys [days] :as period}]
                          (when (>= age-ms (* days day-ms))
                            (some->> (summarize rows (- now-ms (* days day-ms)))
                                     (merge period))))
                        periods)
            all (some-> (summarize rows ##-Inf)
                        (assoc :id :all :label "Since first supply"))]
        {:status :ok
         :since-ms since-ms
         :periods (cond-> (vec fixed) all (conj all))})
      {:status :empty})
    {:status :inconsistent}))
