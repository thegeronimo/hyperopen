(ns hyperopen.views.vaults.detail-vm
  (:require [clojure.string :as str]
            [hyperopen.vaults.actions :as vault-actions]))

(def ^:private chart-width
  760)

(def ^:private chart-height
  240)

(def ^:private activity-tabs
  [{:value :balances
    :label "Balances"}
   {:value :positions
    :label "Positions"}
   {:value :open-orders
    :label "Open Orders"}
   {:value :twap
    :label "TWAP"}
   {:value :trade-history
    :label "Trade History"}
   {:value :funding-history
    :label "Funding History"}
   {:value :order-history
    :label "Order History"}
   {:value :deposits-withdrawals
    :label "Deposits and Withdrawals"}
   {:value :depositors
    :label "Depositors"}])

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

(defn- normalize-address
  [value]
  (some-> value non-blank-text str/lower-case))

(defn- normalize-percent-value
  [value]
  (when-let [n (optional-number value)]
    (if (<= (js/Math.abs n) 1)
      (* 100 n)
      n)))

(defn- snapshot-point-value
  [entry]
  (cond
    (number? entry) entry

    (and (sequential? entry)
         (>= (count entry) 2))
    (optional-number (second entry))

    (map? entry)
    (or (optional-number (:value entry))
        (optional-number (:pnl entry))
        (optional-number (:account-value entry))
        (optional-number (:accountValue entry)))

    :else
    nil))

(defn- last-snapshot-value
  [snapshot-values]
  (when (sequential? snapshot-values)
    (some->> snapshot-values
             (keep snapshot-point-value)
             seq
             last)))

(defn- row-by-address
  [state vault-address]
  (some (fn [row]
          (when (= vault-address (normalize-address (:vault-address row)))
            row))
        (or (get-in state [:vaults :merged-index-rows]) [])))

(defn- portfolio-summary
  [details snapshot-range]
  (let [portfolio (or (:portfolio details) {})]
    (or (get portfolio snapshot-range)
        (get portfolio :month)
        (get portfolio :week)
        (get portfolio :day)
        (get portfolio :all-time)
        {})))

(defn- history-point
  [row]
  (cond
    (and (sequential? row)
         (>= (count row) 2))
    {:time-ms (optional-number (first row))
     :value (optional-number (second row))}

    (map? row)
    {:time-ms (or (optional-number (:time row))
                  (optional-number (:timestamp row))
                  (optional-number (:time-ms row))
                  (optional-number (:timeMs row))
                  (optional-number (:ts row))
                  (optional-number (:t row)))
     :value (or (optional-number (:value row))
                (optional-number (:account-value row))
                (optional-number (:accountValue row))
                (optional-number (:pnl row)))}

    :else
    nil))

(defn- history-points
  [rows]
  (->> (if (sequential? rows) rows [])
       (keep history-point)
       (filter (fn [{:keys [time-ms value]}]
                 (and (number? time-ms)
                      (number? value))))
       (sort-by :time-ms)
       vec))

(defn- chart-render-points
  [points]
  (if (seq points)
    (let [min-value (apply min (map :value points))
          max-value (apply max (map :value points))
          range-value (max 1e-9 (- max-value min-value))
          max-index (max 1 (dec (count points)))]
      (mapv (fn [[idx {:keys [time-ms value]}]]
              {:time-ms time-ms
               :value value
               :x (* (/ idx max-index) chart-width)
               :y (- chart-height (* (/ (- value min-value) range-value) chart-height))})
            (map-indexed vector points)))
    []))

(defn- line-path
  [points]
  (when (seq points)
    (let [segments (map-indexed
                    (fn [idx {:keys [x y]}]
                      (str (if (zero? idx) "M" "L")
                           (.toFixed x 2)
                           " "
                           (.toFixed y 2)))
                    points)]
      (str/join " " segments))))

(defn- snapshot-value-by-range
  [row snapshot-range tvl]
  (let [raw (some-> (get-in row [:snapshot-by-key snapshot-range])
                    last-snapshot-value
                    optional-number)]
    (cond
      (nil? raw) nil
      (and (number? tvl)
           (pos? tvl)
           (> (js/Math.abs raw) 1000))
      (* 100 (/ raw tvl))
      :else
      (normalize-percent-value raw))))

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

(defn- activity-positions
  [webdata]
  (let [rows (or (get-in webdata [:clearinghouseState :assetPositions])
                 (:assetPositions webdata)
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

(defn- activity-open-orders
  [webdata]
  (let [rows (:openOrders webdata)]
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

(defn- twap-running-label
  [row]
  (let [start-ms (or (optional-number (:startTime row))
                     (optional-number (:startTimeMs row))
                     (optional-number (:start row)))
        now-ms (.now js/Date)
        elapsed-ms (when (number? start-ms)
                     (max 0 (- now-ms start-ms)))
        total-ms (or (optional-number (:durationMs row))
                     (optional-number (:totalDurationMs row))
                     (optional-number (:totalMs row))
                     (optional-number (:duration row)))
        elapsed-label (format-duration-ms elapsed-ms)
        total-label (format-duration-ms total-ms)]
    (cond
      (and elapsed-label total-label) (str elapsed-label " / " total-label)
      elapsed-label elapsed-label
      total-label total-label
      :else "—")))

(defn- normalize-twap-row
  [row]
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
       :running-label (twap-running-label state)
       :reduce-only? reduce-only?
       :creation-time-ms creation-time-ms})))

(defn- activity-twaps
  [webdata]
  (let [rows (or (:twapStates webdata)
                 [])]
    (->> (if (sequential? rows) rows [])
         (keep normalize-twap-row)
         (sort-by (fn [{:keys [creation-time-ms]}]
                    (or creation-time-ms 0))
                  >)
         vec)))

(defn- activity-fill-row
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

(defn- activity-fills
  [rows]
  (->> (if (sequential? rows) rows [])
       (keep activity-fill-row)
       (sort-by (fn [{:keys [time-ms]}]
                  (or time-ms 0))
                >)
       vec))

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

(defn- activity-funding-history
  [rows]
  (->> (if (sequential? rows) rows [])
       (keep normalize-funding-row)
       (sort-by (fn [{:keys [time-ms]}]
                  (or time-ms 0))
                >)
       vec))

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

(defn- activity-order-history
  [rows]
  (->> (if (sequential? rows) rows [])
       (keep normalize-order-history-row)
       (sort-by (fn [{:keys [time-ms]}]
                  (or time-ms 0))
                >)
       vec))

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

(defn- activity-ledger-updates
  [rows vault-address]
  (->> (if (sequential? rows) rows [])
       (keep normalize-ledger-row)
       (filter (fn [{:keys [vault]}]
                 (or (nil? vault)
                     (= vault vault-address))))
       (sort-by (fn [{:keys [time-ms]}]
                  (or time-ms 0))
                >)
       vec))

(defn- normalize-depositor-row
  [row]
  (when (map? row)
    {:address (normalize-address (:user row))
     :vault-amount (optional-number (:vault-equity row))
     :unrealized-pnl (optional-number (:pnl row))
     :all-time-pnl (optional-number (:all-time-pnl row))
     :days-following (optional-int (:days-following row))}))

(defn- activity-depositors
  [details]
  (let [followers (or (:followers details) [])]
    (->> (if (sequential? followers) followers [])
         (keep normalize-depositor-row)
         (sort-by (fn [{:keys [vault-amount]}]
                    (js/Math.abs (or vault-amount 0)))
                  >)
         vec)))

(defn- activity-balances
  [webdata]
  (let [balances (or (get-in webdata [:spotState :balances])
                     (:balances webdata)
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
      (let [clearinghouse-state (or (:clearinghouseState webdata) {})
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

(defn- chart-series-data
  [summary]
  {:account-value (history-points (:accountValueHistory summary))
   :pnl (history-points (:pnlHistory summary))})

(defn- resolve-chart-series
  [series-by-key selected-series]
  (let [selected* (vault-actions/normalize-vault-detail-chart-series selected-series)
        has-series? (fn [k]
                      (seq (get series-by-key k)))]
    (cond
      (has-series? selected*) selected*
      (has-series? :pnl) :pnl
      (has-series? :account-value) :account-value
      :else selected*)))

(defn- followers-count
  [details]
  (or (optional-int (:followers-count details))
      (when (sequential? (:followers details))
        (count (:followers details)))
      0))

(defn vault-detail-vm
  [state]
  (let [route (get-in state [:router :path])
        {:keys [kind vault-address]} (vault-actions/parse-vault-route route)
        detail-tab (vault-actions/normalize-vault-detail-tab
                    (get-in state [:vaults-ui :detail-tab]))
        activity-tab (vault-actions/normalize-vault-detail-activity-tab
                      (get-in state [:vaults-ui :detail-activity-tab]))
        chart-series (vault-actions/normalize-vault-detail-chart-series
                      (get-in state [:vaults-ui :detail-chart-series]))
        snapshot-range (vault-actions/normalize-vault-snapshot-range
                        (get-in state [:vaults-ui :snapshot-range]))
        detail-loading? (true? (get-in state [:vaults-ui :detail-loading?]))
        details (get-in state [:vaults :details-by-address vault-address])
        row (row-by-address state vault-address)
        webdata (get-in state [:vaults :webdata-by-vault vault-address])
        user-equity (get-in state [:vaults :user-equity-by-address vault-address])
        fills-source (or (get-in state [:vaults :fills-by-vault vault-address])
                         (:fills webdata)
                         (get-in webdata [:data :fills]))
        funding-source (or (get-in state [:vaults :funding-history-by-vault vault-address])
                           (:fundings webdata))
        order-history-source (or (get-in state [:vaults :order-history-by-vault vault-address])
                                 (:order-history webdata))
        ledger-source (or (get-in state [:vaults :ledger-updates-by-vault vault-address])
                          (:depositsWithdrawals webdata))
        tvl (or (optional-number (:tvl details))
                (optional-number (:tvl row))
                0)
        apr (or (optional-number (:apr details))
                (optional-number (:apr row)))
        month-return (or (normalize-percent-value apr)
                         (snapshot-value-by-range row :month tvl))
        your-deposit (or (optional-number (:equity user-equity))
                         (optional-number (get-in details [:follower-state :vault-equity])))
        all-time-earned (optional-number (get-in details [:follower-state :all-time-pnl]))
        summary (portfolio-summary details snapshot-range)
        series-by-key (chart-series-data summary)
        selected-series (resolve-chart-series series-by-key chart-series)
        chart-points (chart-render-points (get series-by-key selected-series))
        relationship (or (:relationship details)
                         (:relationship row)
                         {:type :normal})
        detail-error (get-in state [:vaults :errors :details-by-address vault-address])
        webdata-error (get-in state [:vaults :errors :webdata-by-vault vault-address])
        fills-error (get-in state [:vaults :errors :fills-by-vault vault-address])
        funding-error (get-in state [:vaults :errors :funding-history-by-vault vault-address])
        order-history-error (get-in state [:vaults :errors :order-history-by-vault vault-address])
        ledger-error (get-in state [:vaults :errors :ledger-updates-by-vault vault-address])
        positions (activity-positions webdata)
        open-orders (activity-open-orders webdata)
        balances (activity-balances webdata)
        twaps (activity-twaps webdata)
        fills (activity-fills fills-source)
        funding-history (activity-funding-history funding-source)
        order-history (activity-order-history order-history-source)
        deposits-withdrawals (activity-ledger-updates ledger-source vault-address)
        depositors (activity-depositors details)
        activity-loading {:trade-history (true? (get-in state [:vaults :loading :fills-by-vault vault-address]))
                          :funding-history (true? (get-in state [:vaults :loading :funding-history-by-vault vault-address]))
                          :order-history (true? (get-in state [:vaults :loading :order-history-by-vault vault-address]))
                          :deposits-withdrawals (true? (get-in state [:vaults :loading :ledger-updates-by-vault vault-address]))}
        activity-errors {:trade-history fills-error
                         :funding-history funding-error
                         :order-history order-history-error
                         :deposits-withdrawals ledger-error}
        activity-count-by-tab {:balances (count balances)
                               :positions (count positions)
                               :open-orders (count open-orders)
                               :twap (count twaps)
                               :trade-history (count fills)
                               :funding-history (count funding-history)
                               :order-history (count order-history)
                               :deposits-withdrawals (count deposits-withdrawals)
                               :depositors (max (count depositors)
                                                (followers-count details))}]
    {:kind kind
     :vault-address vault-address
     :invalid-address? (and (= :detail kind)
                            (nil? vault-address))
     :loading? detail-loading?
     :error (or detail-error webdata-error)
     :name (or (:name details)
               (:name row)
               vault-address
               "Vault")
     :leader (or (:leader details)
                 (:leader row))
     :description (or (:description details) "")
     :relationship relationship
     :allow-deposits? (true? (:allow-deposits? details))
     :always-close-on-withdraw? (true? (:always-close-on-withdraw? details))
     :followers (followers-count details)
     :leader-commission (normalize-percent-value (:leader-commission details))
     :leader-fraction (normalize-percent-value (:leader-fraction details))
     :metrics {:tvl tvl
               :past-month-return month-return
               :your-deposit your-deposit
               :all-time-earned all-time-earned
               :apr (normalize-percent-value apr)}
     :tabs [{:value :about
             :label "About"}
            {:value :vault-performance
             :label "Vault Performance"}
            {:value :your-performance
             :label "Your Performance"}]
     :selected-tab detail-tab
     :snapshot-range snapshot-range
     :snapshot {:day (snapshot-value-by-range row :day tvl)
                :week (snapshot-value-by-range row :week tvl)
                :month (snapshot-value-by-range row :month tvl)
                :all-time (snapshot-value-by-range row :all-time tvl)}
     :chart {:width chart-width
             :height chart-height
             :series-tabs [{:value :account-value
                            :label "Account Value"}
                           {:value :pnl
                            :label "PNL"}]
             :selected-series selected-series
             :points chart-points
             :path (line-path chart-points)}
     :activity-tabs (mapv (fn [{:keys [value label]}]
                            {:value value
                             :label label
                             :count (get activity-count-by-tab value 0)})
                          activity-tabs)
     :selected-activity-tab activity-tab
     :activity-balances balances
     :activity-positions positions
     :activity-open-orders open-orders
     :activity-twaps twaps
     :activity-fills fills
     :activity-funding-history funding-history
     :activity-order-history order-history
     :activity-deposits-withdrawals deposits-withdrawals
     :activity-depositors depositors
     :activity-loading activity-loading
     :activity-errors activity-errors
     :activity-summary {:fill-count (count fills)
                        :open-order-count (count open-orders)
                        :position-count (count positions)}}))
