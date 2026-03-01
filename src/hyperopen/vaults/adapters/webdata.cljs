(ns hyperopen.vaults.adapters.webdata
  (:require [clojure.string :as str]))

(defn- optional-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [trimmed (str/trim value)]
      (when (seq trimmed)
        (let [parsed (js/Number trimmed)]
          (when (js/isFinite parsed)
            parsed))))

    :else nil))

(defn- optional-int
  [value]
  (when-let [n (optional-number value)]
    (js/Math.floor n)))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn normalize-address
  [value]
  (some-> value non-blank-text str/lower-case))

(defn- first-sequential
  [values]
  (some (fn [value]
          (when (sequential? value)
            value))
        values))

(defn rows-from-source
  [source keys]
  (cond
    (sequential? source)
    source

    (map? source)
    (let [source* source
          data* (if (map? (:data source*))
                  (:data source*)
                  {})
          direct-values (map #(get source* %) keys)
          direct-nested-values (mapcat (fn [value]
                                         (if (map? value)
                                           (map #(get value %) keys)
                                           []))
                                       direct-values)
          data-values (map #(get data* %) keys)
          data-nested-values (mapcat (fn [value]
                                       (if (map? value)
                                         (map #(get value %) keys)
                                         []))
                                     data-values)]
      (or (first-sequential direct-values)
          (first-sequential direct-nested-values)
          (first-sequential data-values)
          (first-sequential data-nested-values)
          []))

    :else
    []))

(defn- nested-clearinghouse-position-rows
  [webdata]
  (let [rows (rows-from-source webdata [:clearinghouseStates])]
    (->> rows
         (keep (fn [entry]
                 (when (and (sequential? entry)
                            (>= (count entry) 2))
                   (second entry))))
         (mapcat (fn [state]
                   (if (map? state)
                     (or (:assetPositions state) [])
                     [])))
         vec)))

(defn- normalize-percent-value
  [value]
  (when-let [n (optional-number value)]
    (if (<= (js/Math.abs n) 1)
      (* 100 n)
      n)))

(defn- normalize-side
  [side]
  (let [token (some-> side str str/trim str/upper-case)]
    (case token
      "B" "Long"
      "A" "Short"
      "S" "Short"
      "BUY" "Long"
      "SELL" "Short"
      token)))

(defn- normalize-position-entry
  [row]
  (cond
    (and (map? row) (map? (:position row)))
    (:position row)

    (map? row)
    row

    :else
    nil))

(defn positions
  [webdata]
  (let [rows (or (first-sequential
                  [(get-in webdata [:clearinghouseState :assetPositions])
                   (:assetPositions webdata)
                   (get-in webdata [:data :clearinghouseState :assetPositions])
                   (get-in webdata [:data :assetPositions])])
                 (nested-clearinghouse-position-rows webdata)
                 [])]
    (->> (if (sequential? rows) rows [])
         (keep (fn [row]
                 (when-let [pos (normalize-position-entry row)]
                   {:coin (non-blank-text (:coin pos))
                    :size (optional-number (:szi pos))
                    :leverage (optional-number (get-in pos [:leverage :value]))
                    :position-value (optional-number (:positionValue pos))
                    :entry-price (optional-number (:entryPx pos))
                    :mark-price (or (optional-number (:markPx pos))
                                    (optional-number (:markPrice pos))
                                    (optional-number (:entryPx pos)))
                    :pnl (optional-number (:unrealizedPnl pos))
                    :roe (normalize-percent-value (:returnOnEquity pos))
                    :liq-price (optional-number (:liquidationPx pos))
                    :margin (optional-number (:marginUsed pos))
                    :funding (or (optional-number (get-in pos [:cumFunding :sinceOpen]))
                                 (optional-number (get-in pos [:cumFunding :allTime])))})))
         (sort-by (fn [{:keys [position-value]}]
                    (js/Math.abs (or position-value 0)))
                  >)
         vec)))

(defn- normalize-open-order-row
  [row]
  (let [root (if (map? (:order row))
               (:order row)
               row)
        root* (if (map? root) root {})
        trigger (or (:triggerPx root*)
                    (:triggerPx row)
                    (get-in root* [:t :trigger :triggerPx])
                    (get-in row [:t :trigger :triggerPx]))]
    (when (map? root*)
      {:time-ms (or (optional-number (:timestamp root*))
                    (optional-number (:time root*))
                    (optional-number (:timestamp row))
                    (optional-number (:time row)))
       :coin (non-blank-text (:coin root*))
       :side (normalize-side (:side root*))
       :size (or (optional-number (:sz root*))
                 (optional-number (:origSz root*)))
       :price (or (optional-number (:limitPx root*))
                  (optional-number (:px root*)))
       :trigger-price (optional-number trigger)
       :type (non-blank-text (or (:orderType root*)
                                 (:type root*)
                                 (:tif root*)))})))

(defn open-orders
  [webdata]
  (let [rows (rows-from-source webdata [:openOrders :orders])]
    (->> (if (sequential? rows) rows [])
         (keep normalize-open-order-row)
         (sort-by (fn [{:keys [time-ms]}]
                    (or time-ms 0))
                  >)
         vec)))

(defn- as-boolean
  [value]
  (cond
    (true? value) true
    (false? value) false
    (string? value)
    (case (some-> value str/lower-case str/trim)
      "true" true
      "false" false
      nil)
    :else nil))

(defn- format-duration-ms
  [value]
  (when-let [ms (optional-number value)]
    (let [seconds (max 0 (js/Math.floor (/ ms 1000)))
          hours (js/Math.floor (/ seconds 3600))
          minutes (js/Math.floor (/ (mod seconds 3600) 60))]
      (str hours "h " minutes "m"))))

(defn- twap-running-times
  [row now-ms]
  (let [start-ms (or (optional-number (:startTime row))
                     (optional-number (:startTimeMs row))
                     (optional-number (:start row)))
        now-ms* (or (optional-number now-ms)
                    (.now js/Date))
        elapsed-ms (when (number? start-ms)
                     (max 0 (- now-ms* start-ms)))
        total-ms (or (optional-number (:durationMs row))
                     (optional-number (:totalDurationMs row))
                     (optional-number (:totalMs row))
                     (optional-number (:duration row)))]
    {:elapsed-ms elapsed-ms
     :total-ms total-ms}))

(defn- twap-running-label
  [{:keys [elapsed-ms total-ms]}]
  (let [elapsed-label (format-duration-ms elapsed-ms)
        total-label (format-duration-ms total-ms)]
    (cond
      (and elapsed-label total-label) (str elapsed-label " / " total-label)
      elapsed-label elapsed-label
      total-label total-label
      :else "—")))

(defn- normalize-twap-row
  [row now-ms]
  (when (map? row)
    (let [state (if (map? (:state row))
                  (:state row)
                  row)
          order (if (map? (:order state))
                  (:order state)
                  state)
          coin (or (non-blank-text (:coin state))
                   (non-blank-text (:coin order)))
          size (or (optional-number (:sz state))
                   (optional-number (:totalSz state))
                   (optional-number (:size state))
                   (optional-number (:origSz order))
                   (optional-number (:sz order)))
          executed-size (or (optional-number (:executedSz state))
                            (optional-number (:filledSz state))
                            (optional-number (:executedSize state))
                            (optional-number (:filled state))
                            0)
          average-price (or (optional-number (:avgPx state))
                            (optional-number (:averagePx state))
                            (optional-number (:avgPrice state))
                            (optional-number (:averagePrice state)))
          reduce-only? (or (as-boolean (:reduceOnly state))
                           (as-boolean (:reduceOnly order)))
          running-times (twap-running-times state now-ms)
          creation-time-ms (or (optional-int (:creationTime state))
                               (optional-int (:createdAt state))
                               (optional-int (:timestamp state))
                               (optional-int (:time state))
                               (optional-int (:timestamp row))
                               (optional-int (:time row)))]
      {:coin coin
       :size size
       :executed-size executed-size
       :average-price average-price
       :running-label (twap-running-label running-times)
       :running-ms (:elapsed-ms running-times)
       :total-ms (:total-ms running-times)
       :reduce-only? reduce-only?
       :creation-time-ms creation-time-ms})))

(defn twaps
  [webdata now-ms]
  (let [rows (rows-from-source webdata [:twapStates :states :twaps])]
    (->> (if (sequential? rows) rows [])
         (keep #(normalize-twap-row % now-ms))
         (sort-by (fn [{:keys [creation-time-ms]}]
                    (or creation-time-ms 0))
                  >)
         vec)))

(defn- fill-row
  [row]
  (when (map? row)
    (let [size (optional-number (or (:sz row)
                                    (:size row)
                                    (:closedSize row)))
          price (optional-number (or (:px row)
                                     (:price row)))]
      {:time-ms (or (optional-number (:time row))
                    (optional-number (:timestamp row))
                    (optional-number (:timeMs row)))
       :coin (non-blank-text (or (:coin row)
                                 (:symbol row)
                                 (:asset row)))
       :side (normalize-side (or (:side row)
                                 (:dir row)))
       :size size
       :price price
       :trade-value (when (and (number? size)
                               (number? price))
                      (* (js/Math.abs size) price))
       :fee (optional-number (:fee row))
       :closed-pnl (optional-number (or (:closedPnl row)
                                        (:closed-pnl row)
                                        (:pnl row)))})))

(defn fills
  [rows]
  (let [rows* (rows-from-source rows [:fills :userFills])]
    (->> (if (sequential? rows*) rows* [])
         (keep fill-row)
         (sort-by (fn [{:keys [time-ms]}]
                    (or time-ms 0))
                  >)
         vec)))

(defn- normalize-funding-row
  [row]
  (when (map? row)
    (let [delta (if (map? (:delta row))
                  (:delta row)
                  row)]
      {:time-ms (or (optional-int (:time-ms row))
                    (optional-int (:time row))
                    (optional-int (:timestamp row)))
       :coin (non-blank-text (or (:coin row)
                                 (:coin delta)))
       :funding-rate (optional-number (or (:fundingRate row)
                                          (:funding-rate row)
                                          (:fundingRate delta)))
       :position-size (optional-number (or (:positionSize row)
                                           (:position-size-raw row)
                                           (:szi row)
                                           (:szi delta)))
       :payment (optional-number (or (:payment row)
                                     (:payment-usdc-raw row)
                                     (:usdc row)
                                     (:usdc delta)))})))

(defn funding-history
  [rows]
  (let [rows* (rows-from-source rows [:fundings :userFundings :fundingHistory :funding-history])]
    (->> (if (sequential? rows*) rows* [])
         (keep normalize-funding-row)
         (sort-by (fn [{:keys [time-ms]}]
                    (or time-ms 0))
                  >)
         vec)))

(defn- normalize-order-history-row
  [row]
  (when (map? row)
    (let [order (if (map? (:order row))
                  (:order row)
                  row)]
      {:time-ms (or (optional-int (:statusTimestamp row))
                    (optional-int (:timestamp order))
                    (optional-int (:time row)))
       :coin (non-blank-text (:coin order))
       :side (normalize-side (:side order))
       :type (non-blank-text (or (:orderType order)
                                 (:type order)
                                 (:tif order)))
       :size (or (optional-number (:origSz order))
                 (optional-number (:sz order)))
       :price (or (optional-number (:limitPx order))
                  (optional-number (:px order)))
       :status (non-blank-text (:status row))})))

(defn order-history
  [rows]
  (let [rows* (rows-from-source rows [:order-history :orderHistory :historicalOrders])]
    (->> (if (sequential? rows*) rows* [])
         (keep normalize-order-history-row)
         (sort-by (fn [{:keys [time-ms]}]
                    (or time-ms 0))
                  >)
         vec)))

(defn- ledger-type-label
  [value]
  (case (some-> value str str/trim str/lower-case)
    "vaultdeposit" "Deposit"
    "vaultwithdraw" "Withdraw"
    nil))

(defn- normalize-ledger-row
  [row]
  (when (map? row)
    (let [delta (if (map? (:delta row))
                  (:delta row)
                  row)
          type-label (ledger-type-label (:type delta))]
      (when type-label
        {:time-ms (or (optional-int (:time row))
                      (optional-int (:timestamp row)))
         :type-label type-label
         :amount (optional-number (or (:usdc delta)
                                      (:amount delta)
                                      (:value delta)))
         :hash (non-blank-text (:hash row))
         :vault (normalize-address (:vault delta))}))))

(defn ledger-updates
  [rows vault-address]
  (let [rows* (rows-from-source rows [:depositsWithdrawals :nonFundingLedgerUpdates :ledger])]
    (->> (if (sequential? rows*) rows* [])
         (keep normalize-ledger-row)
         (filter (fn [{:keys [vault]}]
                   (or (nil? vault)
                       (= vault vault-address))))
         (sort-by (fn [{:keys [time-ms]}]
                    (or time-ms 0))
                  >)
         vec)))

(defn balances
  [webdata]
  (let [balances (or (first-sequential
                      [(get-in webdata [:spotState :balances])
                       (:balances webdata)
                       (get-in webdata [:data :spotState :balances])
                       (get-in webdata [:data :balances])])
                     [])
        spot-rows (->> (if (sequential? balances) balances [])
                       (keep (fn [row]
                               (when (map? row)
                                 {:coin (or (non-blank-text (:coin row))
                                            (non-blank-text (:token row)))
                                  :total (or (optional-number (:total row))
                                             (optional-number (:totalBalance row))
                                             (optional-number (:hold row)))
                                  :available (or (optional-number (:available row))
                                                 (optional-number (:availableBalance row))
                                                 (optional-number (:free row)))
                                  :usdc-value (or (optional-number (:usdcValue row))
                                                  (optional-number (:usdValue row)))})))
                       (sort-by (fn [{:keys [total]}]
                                  (js/Math.abs (or total 0)))
                                >)
                       vec)]
    (if (seq spot-rows)
      spot-rows
      (let [clearinghouse-state (or (:clearinghouseState webdata)
                                    (get-in webdata [:data :clearinghouseState])
                                    {})
            margin-summary (or (:marginSummary clearinghouse-state)
                               (:crossMarginSummary clearinghouse-state)
                               {})
            account-value (optional-number (:accountValue margin-summary))
            total-margin-used (optional-number (:totalMarginUsed margin-summary))
            withdrawable (optional-number (:withdrawable clearinghouse-state))
            available (or withdrawable
                          (when (and (number? account-value)
                                     (number? total-margin-used))
                            (- account-value total-margin-used)))]
        (if (number? account-value)
          [{:coin "USDC (Perps)"
            :total account-value
            :available available
            :usdc-value account-value}]
          [])))))
