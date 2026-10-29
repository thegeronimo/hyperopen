(ns hyperopen.borrow-lend.normalize
  "Anti-corruption layer for the HyperCore borrow/lend info payloads.

  Hyperliquid returns every decimal as a string and keys pools by spot token
  index. These functions turn the raw `allBorrowLendReserveStates`,
  `borrowLendUserState` and `userBorrowLendInterest` responses into plain
  maps with finite numbers, so nothing past this boundary parses strings.

  Captured shapes (mainnet, 2026-10-20):

    allBorrowLendReserveStates ->
      [[0 {\"borrowYearlyRate\" \"0.0501\" \"supplyYearlyRate\" \"0.0360\"
           \"balance\" \"108264162.2\" \"utilization\" \"0.8000\"
           \"oraclePx\" \"1.0\" \"ltv\" \"0.0\"
           \"totalSupplied\" \"541380906.1\" \"totalBorrowed\" \"433116822.6\"}] ...]
    borrowLendUserState ->
      {\"tokenToState\" [[0 {\"borrow\" {\"basis\" \"0\" \"value\" \"0\"}
                            \"supply\" {\"basis\" \"5000\" \"value\" \"5012.3\"}}]]
       \"health\" \"healthy\" \"healthFactor\" nil}
    userBorrowLendInterest ->
      [{\"time\" 1710000000000 \"token\" \"USDC\" \"borrow\" \"0.0\" \"supply\" \"1.23\"}]")

(defn- num
  [value]
  (let [n (cond (number? value) value
                (string? value) (js/parseFloat value)
                :else js/NaN)]
    (when (js/isFinite n) n)))

(defn- field
  [m k]
  (when (map? m)
    (or (get m k) (get m (keyword k)))))

(defn- token-index
  [value]
  (let [n (num value)]
    (when (and n (>= n 0) (== n (js/Math.floor n)))
      (int n))))

(defn- pair-entries
  "Accepts `[[idx payload] ...]` (the wire shape) or a map keyed by index."
  [raw]
  (cond (map? raw) (seq raw)
        (sequential? raw) (keep (fn [entry]
                                  (when (and (sequential? entry) (= 2 (count entry)))
                                    [(first entry) (second entry)]))
                                raw)
        :else nil))

(defn normalize-reserve
  [token raw]
  (when-let [idx (token-index token)]
    (when (map? raw)
      {:token idx
       :borrow-apy (or (num (field raw "borrowYearlyRate")) 0)
       :supply-apy (or (num (field raw "supplyYearlyRate")) 0)
       :available (or (num (field raw "balance")) 0)
       :utilization (or (num (field raw "utilization")) 0)
       :oracle-px (num (field raw "oraclePx"))
       :ltv (or (num (field raw "ltv")) 0)
       :total-supplied (or (num (field raw "totalSupplied")) 0)
       :total-borrowed (or (num (field raw "totalBorrowed")) 0)})))

(defn normalize-reserve-states
  "Vector of normalized reserves, ordered by token index."
  [raw]
  (->> (pair-entries raw)
       (keep (fn [[token payload]] (normalize-reserve token payload)))
       (sort-by :token)
       vec))

(defn- leg
  [m]
  {:basis (or (num (field m "basis")) 0)
   :value (or (num (field m "value")) 0)})

(defn normalize-position
  [token raw]
  (when-let [idx (token-index token)]
    (when (map? raw)
      (let [supply (leg (field raw "supply"))
            borrow (leg (field raw "borrow"))]
        {:token idx
         :supplied (:value supply)
         :supplied-basis (:basis supply)
         :borrowed (:value borrow)
         :borrowed-basis (:basis borrow)}))))

(defn normalize-user-state
  [raw]
  (let [positions (->> (pair-entries (field raw "tokenToState"))
                       (keep (fn [[token payload]] (normalize-position token payload)))
                       (filter (fn [{:keys [supplied borrowed]}]
                                 (or (pos? supplied) (pos? borrowed))))
                       (sort-by :token)
                       vec)
        health (field raw "health")]
    {:positions positions
     :health (when (string? health) health)
     :health-factor (num (field raw "healthFactor"))}))

(defn normalize-interest-rows
  [raw]
  (->> (if (sequential? raw) raw [])
       (keep (fn [row]
               (let [time-ms (num (field row "time"))
                     token (field row "token")]
                 (when (and time-ms (or (string? token) (number? token)))
                   {:time-ms time-ms
                    :token token
                    :borrow (or (num (field row "borrow")) 0)
                    :supply (or (num (field row "supply")) 0)}))))
       (sort-by :time-ms)
       vec))

(defn interest-totals
  "Sum of supply interest earned and borrow interest paid, per token."
  [rows]
  (reduce (fn [acc {:keys [token borrow supply]}]
            (-> acc
                (update-in [token :supply] (fnil + 0) supply)
                (update-in [token :borrow] (fnil + 0) borrow)))
          {}
          rows))
