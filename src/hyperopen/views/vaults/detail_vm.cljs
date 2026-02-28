(ns hyperopen.views.vaults.detail-vm
  (:require [clojure.string :as str]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.vaults.actions :as vault-actions]
            [hyperopen.views.account-info.sort-kernel :as sort-kernel]))

(def ^:private chart-y-tick-count
  4)

(def ^:private performance-periods-per-year
  365)

(def ^:private default-strategy-series-stroke
  "#e7ecef")

(def ^:private account-value-series-stroke
  "#f7931a")

(def ^:private account-value-area-fill
  "rgba(247, 147, 26, 0.24)")

(def ^:private pnl-area-positive-fill
  "rgba(22, 214, 161, 0.24)")

(def ^:private pnl-area-negative-fill
  "rgba(237, 112, 136, 0.24)")

(def ^:private benchmark-series-strokes
  ["#f2cf66"
   "#7cc2ff"
   "#ff9d7c"
   "#8be28b"
   "#d8a8ff"
   "#ffdf8a"])

(def ^:private chart-empty-y-ticks
  [{:value 3 :y-ratio 0}
   {:value 2 :y-ratio (/ 1 3)}
   {:value 1 :y-ratio (/ 2 3)}
   {:value 0 :y-ratio 1}])

(def ^:private activity-tabs
  [{:value :performance-metrics
    :label "Performance Metrics"}
   {:value :balances
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

(def ^:private chart-timeframe-options
  [{:value :day
    :label "24H"}
   {:value :week
    :label "7D"}
   {:value :month
    :label "30D"}
   {:value :three-month
    :label "3M"}
   {:value :six-month
    :label "6M"}
   {:value :one-year
    :label "1Y"}
   {:value :two-year
    :label "2Y"}
   {:value :all-time
    :label "All-time"}])

(def ^:private activity-filter-options
  [{:value :all
    :label "All"}
   {:value :long
    :label "Long"}
   {:value :short
    :label "Short"}])

(def ^:private direction-filter-tabs
  #{:positions
    :open-orders
    :twap
    :trade-history
    :funding-history
    :order-history})

(def ^:private activity-sort-defaults
  {:balances {:column "USDC Value"
              :direction :desc}
   :positions {:column "Position Value"
               :direction :desc}
   :open-orders {:column "Time"
                 :direction :desc}
   :twap {:column "Creation Time"
          :direction :desc}
   :trade-history {:column "Time"
                   :direction :desc}
   :funding-history {:column "Time"
                     :direction :desc}
   :order-history {:column "Time"
                   :direction :desc}
   :deposits-withdrawals {:column "Time"
                          :direction :desc}
   :depositors {:column "Vault Amount"
                :direction :desc}})

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

(defn- parse-cache-order
  [value]
  (let [parsed (cond
                 (number? value) value
                 (string? value) (js/parseInt value 10)
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed)))
      (js/Math.floor parsed))))

(defn- market-type-token
  [value]
  (cond
    (keyword? value) value
    (string? value) (some-> value str/trim str/lower-case keyword)
    :else nil))

(defn- benchmark-open-interest
  [market]
  (let [open-interest (optional-number (:openInterest market))]
    (if (number? open-interest)
      open-interest
      0)))

(defn- benchmark-option-label
  [market]
  (let [symbol (some-> (:symbol market) str str/trim)
        coin (some-> (:coin market) str str/trim)
        dex (some-> (:dex market) str str/trim str/upper-case)
        market-type (market-type-token (:market-type market))
        type-label (case market-type
                     :spot "SPOT"
                     :perp "PERP"
                     nil)
        primary-label (or symbol coin "")]
    (cond
      (and (seq dex) (seq type-label)) (str primary-label " (" dex " " type-label ")")
      (seq type-label) (str primary-label " (" type-label ")")
      :else primary-label)))

(defn- benchmark-option-rank
  [market]
  [(- (benchmark-open-interest market))
   (or (parse-cache-order (:cache-order market))
       js/Number.MAX_SAFE_INTEGER)
   (str/lower-case (or (some-> (:symbol market) str str/trim) ""))
   (str/lower-case (or (some-> (:coin market) str str/trim) ""))
   (str/lower-case (or (some-> (:key market) str str/trim) ""))])

(def ^:private vault-benchmark-prefix
  "vault:")

(def ^:private max-vault-benchmark-options
  100)

(defonce ^:private eligible-vault-benchmark-rows-cache
  (atom nil))

(defn- normalize-vault-address
  [value]
  (some-> value str str/trim str/lower-case))

(defn- vault-benchmark-value
  [vault-address]
  (str vault-benchmark-prefix vault-address))

(defn- vault-benchmark-address
  [benchmark]
  (let [benchmark* (some-> benchmark str str/trim)
        benchmark-lower (some-> benchmark* str/lower-case)]
    (when (and (seq benchmark-lower)
               (str/starts-with? benchmark-lower vault-benchmark-prefix))
      (normalize-vault-address (subs benchmark* (count vault-benchmark-prefix))))))

(defn- benchmark-vault-tvl
  [row]
  (or (optional-number (:tvl row))
      0))

(defn- benchmark-vault-option-rank
  [row]
  [(- (benchmark-vault-tvl row))
   (str/lower-case (or (non-blank-text (:name row)) ""))
   (str/lower-case (or (normalize-vault-address (:vault-address row)) ""))])

(defn- benchmark-market-selector-options
  [state]
  (let [ordered-markets (->> (or (get-in state [:asset-selector :markets]) [])
                             (filter map?)
                             (sort-by benchmark-option-rank))]
    (->> ordered-markets
         (reduce (fn [{:keys [seen options]} market]
                   (if-let [coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin
                                  (:coin market))]
                     (if (contains? seen coin)
                       {:seen seen
                        :options options}
                       {:seen (conj seen coin)
                        :options (conj options
                                       {:value coin
                                        :label (benchmark-option-label market)
                                        :open-interest (benchmark-open-interest market)})})
                     {:seen seen
                      :options options}))
                 {:seen #{}
                  :options []})
         :options
         vec)))

(defn- benchmark-vault-row?
  [row]
  (and (map? row)
       (seq (normalize-vault-address (:vault-address row)))
       (not= :child (get-in row [:relationship :type]))))

(defn- eligible-vault-benchmark-rows
  [rows]
  (->> (or rows [])
       (filter benchmark-vault-row?)
       (sort-by benchmark-vault-option-rank)
       (take max-vault-benchmark-options)
       vec))

(defn- memoized-eligible-vault-benchmark-rows
  [rows]
  (let [cache @eligible-vault-benchmark-rows-cache]
    (if (and (map? cache)
             (identical? rows (:rows cache)))
      (:eligible-rows cache)
      (let [eligible-rows (eligible-vault-benchmark-rows rows)]
        (reset! eligible-vault-benchmark-rows-cache {:rows rows
                                                     :eligible-rows eligible-rows})
        eligible-rows))))

(defn- benchmark-vault-selector-options
  [state]
  (let [top-rows (memoized-eligible-vault-benchmark-rows (get-in state [:vaults :merged-index-rows]))]
    (->> top-rows
         (reduce (fn [{:keys [seen options]} row]
                   (if-let [vault-address (normalize-vault-address (:vault-address row))]
                     (if (contains? seen vault-address)
                       {:seen seen
                        :options options}
                       (let [name (or (non-blank-text (:name row))
                                      vault-address)]
                         {:seen (conj seen vault-address)
                          :options (conj options
                                         {:value (vault-benchmark-value vault-address)
                                          :label (str name " (VAULT)")
                                          :tvl (benchmark-vault-tvl row)})}))
                     {:seen seen
                      :options options}))
                 {:seen #{}
                  :options []})
         :options
         vec)))

(defn- benchmark-selector-options
  [state]
  (into (benchmark-market-selector-options state)
        (benchmark-vault-selector-options state)))

(defn- normalize-benchmark-search-query
  [value]
  (-> (or value "")
      str
      str/trim
      str/lower-case))

(defn- benchmark-option-matches-search?
  [option search-query]
  (or (str/blank? search-query)
      (str/includes? (str/lower-case (or (:label option) "")) search-query)
      (str/includes? (str/lower-case (or (:value option) "")) search-query)))

(defn- selected-vault-detail-returns-benchmark-coins
  [state]
  (let [coins (portfolio-actions/normalize-portfolio-returns-benchmark-coins
               (get-in state [:vaults-ui :detail-returns-benchmark-coins]))]
    (if (seq coins)
      coins
      (if-let [legacy-coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin
                            (get-in state [:vaults-ui :detail-returns-benchmark-coin]))]
        [legacy-coin]
        []))))

(defn- selected-benchmark-options
  [options selected-coins]
  (let [options-by-coin (into {} (map (juxt :value identity)) options)]
    (mapv (fn [coin]
            (or (get options-by-coin coin)
                {:value coin
                 :label coin
                 :open-interest 0}))
          selected-coins)))

(defn- returns-benchmark-selector-model
  [state]
  (let [options (benchmark-selector-options state)
        option-values (into #{} (map :value) options)
        selected-coins (->> (selected-vault-detail-returns-benchmark-coins state)
                            (filter (fn [coin]
                                      (if (vault-benchmark-address coin)
                                        (contains? option-values coin)
                                        true)))
                            vec)
        selected-coin-set (set selected-coins)
        search (or (get-in state [:vaults-ui :detail-returns-benchmark-search]) "")
        search-query (normalize-benchmark-search-query search)
        suggestions-open? (boolean (get-in state [:vaults-ui :detail-returns-benchmark-suggestions-open?]))
        selected-options (selected-benchmark-options options selected-coins)
        candidates (->> options
                        (remove (fn [{:keys [value]}]
                                  (contains? selected-coin-set value)))
                        (filter #(benchmark-option-matches-search? % search-query))
                        vec)
        top-coin (some-> candidates first :value)
        empty-message (cond
                        (empty? options) "No benchmark symbols available."
                        (seq candidates) nil
                        (seq search-query) "No matching symbols."
                        :else "All symbols selected.")]
    {:selected-coins selected-coins
     :selected-options selected-options
     :coin-search search
     :suggestions-open? suggestions-open?
     :candidates candidates
     :top-coin top-coin
     :empty-message empty-message
     :label-by-coin (into {} (map (juxt :value :label)) options)}))

(defn- normalize-sort-direction
  [value]
  (if (#{:asc :desc} value)
    value
    :desc))

(defn- sortable-text
  [value]
  (some-> value non-blank-text str/lower-case))

(defn- sortable-number
  [value]
  (or (optional-number value) 0))

(defn- sortable-abs-number
  [value]
  (js/Math.abs (sortable-number value)))

(defn- normalize-side-key
  [value]
  (case (some-> value str str/trim str/lower-case)
    ("long" "buy" "b") :long
    ("short" "sell" "a" "s") :short
    nil))

(defn- direction-key-from-size
  [value]
  (when-let [n (optional-number value)]
    (if (neg? n) :short :long)))

(defn- row-direction-key
  [tab row]
  (case tab
    :positions (direction-key-from-size (:size row))
    :open-orders (normalize-side-key (:side row))
    :twap (direction-key-from-size (:size row))
    :trade-history (normalize-side-key (:side row))
    :funding-history (direction-key-from-size (:position-size row))
    :order-history (normalize-side-key (:side row))
    nil))

(defn- direction-match?
  [direction row-direction]
  (case direction
    :long (= :long row-direction)
    :short (= :short row-direction)
    true))

(defn- filter-activity-rows-by-direction
  [rows tab direction-filter]
  (let [rows* (vec (or rows []))
        filter* (vault-actions/normalize-vault-detail-activity-direction-filter direction-filter)]
    (if (or (= :all filter*)
            (not (contains? direction-filter-tabs tab)))
      rows*
      (->> rows*
           (filter (fn [row]
                     (direction-match? filter* (row-direction-key tab row))))
           vec))))

(def ^:private activity-sort-accessor-by-tab
  {:balances {"Coin" (fn [row]
                       (or (sortable-text (:coin row)) ""))
              "Total Balance" (fn [row]
                                (sortable-abs-number (:total row)))
              "Available Balance" (fn [row]
                                    (sortable-abs-number (:available row)))
              "USDC Value" (fn [row]
                             (sortable-abs-number (:usdc-value row)))}
   :positions {"Coin" (fn [row]
                        (or (sortable-text (:coin row)) ""))
               "Size" (fn [row]
                        (sortable-abs-number (:size row)))
               "Position Value" (fn [row]
                                  (sortable-abs-number (:position-value row)))
               "Entry Price" (fn [row]
                               (sortable-number (:entry-price row)))
               "Mark Price" (fn [row]
                              (sortable-number (:mark-price row)))
               "PNL (ROE %)" (fn [row]
                               (sortable-number (:pnl row)))
               "Liq. Price" (fn [row]
                              (sortable-number (:liq-price row)))
               "Margin" (fn [row]
                          (sortable-abs-number (:margin row)))
               "Funding" (fn [row]
                           (sortable-number (:funding row)))}
   :open-orders {"Time" (fn [row]
                          (sortable-number (:time-ms row)))
                 "Coin" (fn [row]
                          (or (sortable-text (:coin row)) ""))
                 "Side" (fn [row]
                          (or (sortable-text (:side row)) ""))
                 "Size" (fn [row]
                          (sortable-abs-number (:size row)))
                 "Price" (fn [row]
                           (sortable-number (:price row)))
                 "Trigger" (fn [row]
                             (sortable-number (:trigger-price row)))}
   :twap {"Coin" (fn [row]
                   (or (sortable-text (:coin row)) ""))
          "Size" (fn [row]
                   (sortable-abs-number (:size row)))
          "Executed Size" (fn [row]
                            (sortable-abs-number (:executed-size row)))
          "Average Price" (fn [row]
                            (sortable-number (:average-price row)))
          "Running Time / Total" (fn [row]
                                   (sortable-number (:running-ms row)))
          "Reduce Only" (fn [row]
                          (if (true? (:reduce-only? row)) 1 0))
          "Creation Time" (fn [row]
                            (sortable-number (:creation-time-ms row)))
          "Terminate" (fn [_row] 0)}
   :trade-history {"Time" (fn [row]
                            (sortable-number (:time-ms row)))
                   "Coin" (fn [row]
                            (or (sortable-text (:coin row)) ""))
                   "Side" (fn [row]
                            (or (sortable-text (:side row)) ""))
                   "Price" (fn [row]
                             (sortable-number (:price row)))
                   "Size" (fn [row]
                            (sortable-abs-number (:size row)))
                   "Trade Value" (fn [row]
                                   (sortable-abs-number (:trade-value row)))
                   "Fee" (fn [row]
                           (sortable-number (:fee row)))
                   "Closed PNL" (fn [row]
                                  (sortable-number (:closed-pnl row)))}
   :funding-history {"Time" (fn [row]
                              (sortable-number (:time-ms row)))
                     "Coin" (fn [row]
                              (or (sortable-text (:coin row)) ""))
                     "Funding Rate" (fn [row]
                                      (sortable-number (:funding-rate row)))
                     "Position Size" (fn [row]
                                       (sortable-abs-number (:position-size row)))
                     "Payment" (fn [row]
                                 (sortable-number (:payment row)))}
   :order-history {"Time" (fn [row]
                            (sortable-number (:time-ms row)))
                   "Coin" (fn [row]
                            (or (sortable-text (:coin row)) ""))
                   "Side" (fn [row]
                            (or (sortable-text (:side row)) ""))
                   "Type" (fn [row]
                            (or (sortable-text (:type row)) ""))
                   "Size" (fn [row]
                            (sortable-abs-number (:size row)))
                   "Price" (fn [row]
                             (sortable-number (:price row)))
                   "Status" (fn [row]
                              (or (sortable-text (:status row)) ""))}
   :deposits-withdrawals {"Time" (fn [row]
                                   (sortable-number (:time-ms row)))
                          "Type" (fn [row]
                                   (or (sortable-text (:type-label row)) ""))
                          "Amount" (fn [row]
                                     (sortable-number (:amount row)))
                          "Tx Hash" (fn [row]
                                      (or (sortable-text (:hash row)) ""))}
   :depositors {"Depositor" (fn [row]
                              (or (sortable-text (:address row)) ""))
                "Vault Amount" (fn [row]
                                 (sortable-abs-number (:vault-amount row)))
                "Unrealized PNL" (fn [row]
                                   (sortable-number (:unrealized-pnl row)))
                "All-time PNL" (fn [row]
                                 (sortable-number (:all-time-pnl row)))
                "Days Following" (fn [row]
                                   (sortable-number (:days-following row)))}})

(defn- activity-sort-state
  [state tab]
  (let [defaults (get activity-sort-defaults tab {:column nil
                                                   :direction :desc})
        columns (keys (get activity-sort-accessor-by-tab tab))
        fallback-column (or (:column defaults)
                            (first columns))
        saved (or (get-in state [:vaults-ui :detail-activity-sort-by-tab tab])
                  {})
        saved-column (non-blank-text (:column saved))
        column (if (contains? (set columns) saved-column)
                 saved-column
                 fallback-column)]
    {:column column
     :direction (normalize-sort-direction (or (:direction saved)
                                              (:direction defaults)))}))

(defn- row-sort-tie-breaker
  [row]
  (str (or (:coin row)
           (:address row)
           (:hash row)
           (:time-ms row)
           (:creation-time-ms row)
           (:type row)
           "")))

(defn- sort-activity-rows
  [rows tab sort-state]
  (let [rows* (vec (or rows []))
        accessor-by-column (get activity-sort-accessor-by-tab tab)
        column (:column sort-state)]
    (if (and (seq rows*)
             (map? accessor-by-column)
             (contains? accessor-by-column column))
      (->> (sort-kernel/sort-rows-by-column
            rows*
            {:column column
             :direction (normalize-sort-direction (:direction sort-state))
             :accessor-by-column accessor-by-column
             :tie-breaker row-sort-tie-breaker})
           vec)
      rows*)))

(defn- normalize-address
  [value]
  (some-> value non-blank-text str/lower-case))

(defn- first-sequential
  [values]
  (some (fn [value]
          (when (sequential? value)
            value))
        values))

(defn- rows-from-source
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

(defn- relationship-child-addresses
  [relationship]
  (->> (or (:child-addresses relationship) [])
       (keep normalize-address)
       distinct
       vec))

(defn- activity-addresses
  [vault-address relationship]
  (->> (concat [vault-address]
               (relationship-child-addresses relationship))
       (keep normalize-address)
       distinct
       vec))

(defn- concat-address-rows
  [state path addresses]
  (->> addresses
       (mapcat (fn [address]
                 (let [rows (get-in state (conj path address))]
                   (if (sequential? rows)
                     rows
                     []))))
       vec))

(defn- address-loading?
  [state loading-key addresses]
  (boolean
   (some true?
         (map #(get-in state [:vaults :loading loading-key %])
              addresses))))

(defn- first-address-error
  [state error-key addresses]
  (some (fn [address]
          (let [err (get-in state [:vaults :errors error-key address])]
            (when (seq (non-blank-text err))
              err)))
        addresses))

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

(defn- with-utc-months-offset
  [time-ms months]
  (let [date (js/Date. time-ms)]
    (.setUTCMonth date (+ (.getUTCMonth date) months))
    (.getTime date)))

(defn- with-utc-years-offset
  [time-ms years]
  (let [date (js/Date. time-ms)]
    (.setUTCFullYear date (+ (.getUTCFullYear date) years))
    (.getTime date)))

(defn- summary-window-cutoff-ms
  [snapshot-range end-time-ms]
  (when (number? end-time-ms)
    (case snapshot-range
      :three-month (with-utc-months-offset end-time-ms -3)
      :six-month (with-utc-months-offset end-time-ms -6)
      :one-year (with-utc-years-offset end-time-ms -1)
      :two-year (with-utc-years-offset end-time-ms -2)
      nil)))

(defn- normalized-history-rows
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [time-ms (portfolio-metrics/history-point-time-ms row)
                     value (portfolio-metrics/history-point-value row)]
                 (when (and (number? time-ms)
                            (number? value))
                   [time-ms value]))))
       (sort-by first)
       vec))

(defn- history-window-rows
  [rows cutoff-ms]
  (if (number? cutoff-ms)
    (->> rows
         (filter (fn [[time-ms _value]]
                   (>= time-ms cutoff-ms)))
         vec)
    []))

(defn- rebase-history-rows
  [rows]
  (if-let [baseline (some-> rows first second)]
    (mapv (fn [[time-ms value]]
            [time-ms (- value baseline)])
          rows)
    []))

(defn- derived-portfolio-summary
  [all-time-summary snapshot-range]
  (let [account-rows (normalized-history-rows (:accountValueHistory all-time-summary))
        pnl-rows (normalized-history-rows (:pnlHistory all-time-summary))
        end-time-ms (or (some-> account-rows last first)
                        (some-> pnl-rows last first))
        cutoff-ms (summary-window-cutoff-ms snapshot-range end-time-ms)]
    (when (number? cutoff-ms)
      (let [account-window (history-window-rows account-rows cutoff-ms)
            pnl-window (history-window-rows pnl-rows cutoff-ms)
            pnl-window* (rebase-history-rows pnl-window)]
        (when (or (seq account-window)
                  (seq pnl-window*))
          (assoc all-time-summary
                 :accountValueHistory account-window
                 :pnlHistory pnl-window*))))))

(defn- portfolio-summary
  [details snapshot-range]
  (let [portfolio (or (:portfolio details) {})
        all-time-summary (get portfolio :all-time)]
    (or (get portfolio snapshot-range)
        (derived-portfolio-summary all-time-summary snapshot-range)
        (get portfolio :month)
        (get portfolio :week)
        (get portfolio :day)
        all-time-summary
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

(defn- normalize-chart-point-value
  [series value]
  (when (number? value)
    (if (= series :returns)
      (let [rounded (/ (js/Math.round (* value 100)) 100)]
        (if (== rounded -0)
          0
          rounded))
      value)))

(defn- rows->chart-points
  [rows series]
  (->> rows
       (map-indexed (fn [idx row]
                      (let [{:keys [time-ms value]} (history-point row)
                            value* (normalize-chart-point-value series value)]
                        (when (and (number? time-ms)
                                   (number? value*))
                          {:index idx
                           :time-ms time-ms
                           :value value*}))))
       (keep identity)
       vec))

(defn- returns-history-points
  [state summary]
  (rows->chart-points (portfolio-metrics/returns-history-rows state summary :all)
                      :returns))

(defn- candle-point-close
  [row]
  (cond
    (map? row)
    (or (optional-number (:c row))
        (optional-number (:close row)))

    (and (sequential? row)
         (>= (count row) 5))
    (optional-number (nth row 4))

    :else
    nil))

(defn- benchmark-candle-points
  [rows]
  (if (sequential? rows)
    (->> rows
         (keep (fn [row]
                 (let [time-ms (some-> row history-point :time-ms)
                       close (candle-point-close row)]
                   (when (and (number? time-ms)
                              (number? close)
                              (pos? close))
                     {:time-ms time-ms
                      :close close}))))
         (sort-by :time-ms)
         vec)
    []))

(defn- aligned-benchmark-return-rows
  [benchmark-points strategy-points]
  (let [benchmark-count (count benchmark-points)
        strategy-time-points (mapv :time-ms strategy-points)
        strategy-count (count strategy-time-points)]
    (loop [time-idx 0
           candle-idx 0
           latest-close nil
           anchor-close nil
           output []]
      (if (>= time-idx strategy-count)
        output
        (let [time-ms (nth strategy-time-points time-idx)
              [candle-idx* latest-close*]
              (loop [idx candle-idx
                     latest latest-close]
                (if (>= idx benchmark-count)
                  [idx latest]
                  (let [{candle-time-ms :time-ms
                         close :close} (nth benchmark-points idx)]
                    (if (<= candle-time-ms time-ms)
                      (recur (inc idx) close)
                      [idx latest]))))
              anchor-close* (or anchor-close latest-close*)
              output* (if (and (number? latest-close*)
                               (number? anchor-close*)
                               (pos? anchor-close*))
                        (let [cumulative-return (* 100 (- (/ latest-close* anchor-close*) 1))]
                          (if (number? cumulative-return)
                            (conj output [time-ms cumulative-return])
                            output))
                        output)]
          (recur (inc time-idx)
                 candle-idx*
                 latest-close*
                 anchor-close*
                 output*))))))

(defn- vault-benchmark-rows-by-address
  [state]
  (->> (memoized-eligible-vault-benchmark-rows (get-in state [:vaults :merged-index-rows]))
       (reduce (fn [rows-by-address row]
                 (if-let [vault-address (normalize-vault-address (:vault-address row))]
                   (assoc rows-by-address vault-address row)
                   rows-by-address))
               {})))

(defn- vault-snapshot-range-keys
  [snapshot-range]
  (case (vault-actions/normalize-vault-snapshot-range snapshot-range)
    :day [:day :week :month :all-time]
    :week [:week :month :all-time :day]
    :month [:month :week :all-time :day]
    :three-month [:all-time :month :week :day]
    :six-month [:all-time :month :week :day]
    :one-year [:all-time :month :week :day]
    :two-year [:all-time :month :week :day]
    :all-time [:all-time :month :week :day]
    [:month :week :all-time :day]))

(defn- vault-snapshot-point-value
  [entry]
  (cond
    (number? entry)
    entry

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

(defn- normalize-vault-snapshot-return
  [raw tvl]
  (cond
    (not (number? raw))
    nil

    (and (number? tvl)
         (pos? tvl)
         (> (js/Math.abs raw) 1000))
    (* 100 (/ raw tvl))

    (<= (js/Math.abs raw) 1)
    (* 100 raw)

    :else
    raw))

(defn- vault-benchmark-snapshot-values
  [row snapshot-range]
  (let [snapshot-by-key (or (:snapshot-by-key row) {})
        tvl (benchmark-vault-tvl row)]
    (or (some (fn [snapshot-key]
                (let [raw-values (get snapshot-by-key snapshot-key)]
                  (when (sequential? raw-values)
                    (let [normalized-values (->> raw-values
                                                 (keep vault-snapshot-point-value)
                                                 (keep #(normalize-vault-snapshot-return % tvl))
                                                 vec)]
                      (when (seq normalized-values)
                        normalized-values)))))
              (vault-snapshot-range-keys snapshot-range))
        [])))

(defn- aligned-vault-return-rows
  [snapshot-values strategy-return-points]
  (let [values (vec (or snapshot-values []))
        value-count (count values)
        strategy-time-points (mapv :time-ms strategy-return-points)
        strategy-count (count strategy-time-points)]
    (if (and (pos? value-count)
             (pos? strategy-count))
      (mapv (fn [idx time-ms]
              (let [ratio (if (> strategy-count 1)
                            (/ idx (dec strategy-count))
                            0)
                    value-idx (if (> value-count 1)
                                (js/Math.round (* ratio (dec value-count)))
                                0)
                    value-idx* (max 0 (min (dec value-count) value-idx))]
                [time-ms (nth values value-idx*)]))
            (range strategy-count)
            strategy-time-points)
      [])))

(defn- benchmark-cumulative-return-points-by-coin
  [state snapshot-range benchmark-coins strategy-return-points]
  (if (and (seq benchmark-coins)
           (seq strategy-return-points))
    (let [{:keys [interval]} (portfolio-actions/returns-benchmark-candle-request snapshot-range)
          any-vault-benchmark? (boolean (some vault-benchmark-address benchmark-coins))
          vault-rows-by-address (when any-vault-benchmark?
                                  (vault-benchmark-rows-by-address state))]
      (reduce (fn [rows-by-coin coin]
                (if (seq coin)
                  (let [aligned-rows (if-let [vault-address (vault-benchmark-address coin)]
                                       (aligned-vault-return-rows
                                        (vault-benchmark-snapshot-values
                                         (get vault-rows-by-address vault-address)
                                         snapshot-range)
                                        strategy-return-points)
                                       (let [candles (benchmark-candle-points (get-in state [:candles coin interval]))]
                                         (aligned-benchmark-return-rows candles strategy-return-points)))]
                    (assoc rows-by-coin
                           coin
                           (rows->chart-points aligned-rows :returns)))
                 rows-by-coin))
              {}
              benchmark-coins))
    {}))

(defn- benchmark-series-stroke
  [idx]
  (let [palette-size (count benchmark-series-strokes)]
    (if (pos? palette-size)
      (nth benchmark-series-strokes (mod idx palette-size))
      default-strategy-series-stroke)))

(defn- cumulative-rows
  [points]
  (mapv (fn [{:keys [time-ms value]}]
          [time-ms value])
        (or points [])))

(defn- benchmark-performance-column
  [benchmark-cumulative-rows label-by-coin coin]
  (let [benchmark-daily-rows (portfolio-metrics/daily-compounded-returns benchmark-cumulative-rows)
        values (if (seq benchmark-daily-rows)
                 (portfolio-metrics/compute-performance-metrics {:strategy-daily-rows benchmark-daily-rows
                                                                 :rf 0
                                                                 :periods-per-year performance-periods-per-year
                                                                 :compounded true})
                 {})]
    {:coin coin
     :label (or (get label-by-coin coin)
                coin)
     :daily-rows benchmark-daily-rows
     :values values}))

(defn- with-performance-metric-columns
  [groups portfolio-values benchmark-columns]
  (let [primary-benchmark-values (or (some-> benchmark-columns first :values)
                                     {})
        benchmark-values-by-coin (into {}
                                       (map (fn [{:keys [coin values]}]
                                              [coin values]))
                                       benchmark-columns)]
    (mapv (fn [{:keys [rows] :as group}]
            (assoc group
                   :rows (mapv (fn [{:keys [key] :as row}]
                                 (assoc row
                                        :portfolio-value (get portfolio-values key)
                                        :benchmark-value (get primary-benchmark-values key)
                                        :benchmark-values (into {}
                                                               (map (fn [{:keys [coin]}]
                                                                      [coin (get-in benchmark-values-by-coin [coin key])]))
                                                               benchmark-columns)))
                               (or rows []))))
          (or groups []))))

(defn- performance-metrics-model
  [returns-benchmark-selector strategy-cumulative-rows benchmark-cumulative-rows-by-coin]
  (let [strategy-daily-rows (portfolio-metrics/daily-compounded-returns strategy-cumulative-rows)
        selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector)
                                          []))
        benchmark-label-by-coin (or (:label-by-coin returns-benchmark-selector)
                                    {})
        benchmark-columns (mapv (fn [coin]
                                  (benchmark-performance-column (or (get benchmark-cumulative-rows-by-coin coin)
                                                                    [])
                                                                benchmark-label-by-coin
                                                                coin))
                                selected-benchmark-coins)
        primary-benchmark-column (first benchmark-columns)
        benchmark-coin (:coin primary-benchmark-column)
        benchmark-daily-rows (or (:daily-rows primary-benchmark-column)
                                 [])
        portfolio-values (portfolio-metrics/compute-performance-metrics {:strategy-daily-rows strategy-daily-rows
                                                                         :benchmark-daily-rows benchmark-daily-rows
                                                                         :rf 0
                                                                         :periods-per-year performance-periods-per-year
                                                                         :compounded true})
        benchmark-values (or (:values primary-benchmark-column)
                             {})
        groups (with-performance-metric-columns (portfolio-metrics/metric-rows portfolio-values)
                 portfolio-values
                 benchmark-columns)
        benchmark-label (:label primary-benchmark-column)]
    {:benchmark-selected? (boolean (seq benchmark-columns))
     :benchmark-coin benchmark-coin
     :benchmark-label benchmark-label
     :benchmark-coins (mapv :coin benchmark-columns)
     :benchmark-columns (mapv (fn [{:keys [coin label]}]
                                {:coin coin
                                 :label label})
                              benchmark-columns)
     :values portfolio-values
     :benchmark-values benchmark-values
     :groups groups}))

(defn- non-zero-span
  [domain-min domain-max]
  (let [span (- domain-max domain-min)]
    (if (zero? span) 1 span)))

(defn- normalize-degenerate-domain
  [min-value max-value]
  (if (= min-value max-value)
    (let [pad (max 1 (* 0.05 (js/Math.abs min-value)))]
      [(- min-value pad) (+ min-value pad)])
    [min-value max-value]))

(defn- chart-domain
  [values]
  (let [[min-value max-value] (normalize-degenerate-domain (apply min values)
                                                           (apply max values))
        step (/ (non-zero-span min-value max-value) (dec chart-y-tick-count))]
    {:min min-value
     :max max-value
     :step step}))

(defn- chart-y-ticks [{:keys [min max step]}]
  (let [step* (if (and (number? step)
                       (pos? step))
                step
                (/ (non-zero-span min max) (dec chart-y-tick-count)))
        span (non-zero-span min max)]
    (mapv (fn [idx]
            (let [value (if (= idx (dec chart-y-tick-count))
                          min
                          (- max (* step* idx)))]
              {:value value
               :y-ratio (/ (- max value) span)}))
          (range chart-y-tick-count))))

(defn- normalize-chart-points
  [points {:keys [min max]}]
  (let [point-count (count points)
        span (non-zero-span min max)]
    (mapv (fn [idx {:keys [value] :as point}]
            (let [x-ratio (if (> point-count 1)
                            (/ idx (dec point-count))
                            0)
                  y-ratio (/ (- max value) span)]
              (assoc point
                     :x-ratio x-ratio
                     :y-ratio y-ratio)))
          (range point-count)
          points)))

(defn- format-svg-number
  [value]
  (let [rounded (/ (js/Math.round (* value 1000)) 1000)]
    (if (== rounded -0)
      0
      rounded)))

(defn- strategy-series-stroke
  [selected-series]
  (case selected-series
    :account-value account-value-series-stroke
    default-strategy-series-stroke))

(defn- line-path
  [points]
  (when (seq points)
    (let [commands (map-indexed
                    (fn [idx {:keys [x-ratio y-ratio]}]
                      (let [x (format-svg-number (* 100 x-ratio))
                            y (format-svg-number (* 100 y-ratio))]
                        (str (if (zero? idx) "M " "L ")
                             x
                             " "
                             y)))
                    points)]
      (if (= 1 (count points))
        (let [first-point (first points)
              y (format-svg-number (* 100 (:y-ratio first-point)))]
          (str (first commands) " L 100 " y))
        (str/join " " commands)))))

(defn- value->y-ratio
  [{:keys [min max]} value]
  (let [span (non-zero-span min max)]
    (/ (- max value) span)))

(defn- area-path
  [points baseline-y-ratio]
  (when (seq points)
    (let [line-points (if (= 1 (count points))
                        (let [point (first points)]
                          [point
                           (assoc point :x-ratio 1)])
                        points)
          first-point (first line-points)
          last-point (last line-points)
          baseline-y (format-svg-number (* 100 baseline-y-ratio))
          line-commands (map-indexed
                         (fn [idx {:keys [x-ratio y-ratio]}]
                           (let [x (format-svg-number (* 100 x-ratio))
                                 y (format-svg-number (* 100 y-ratio))]
                             (str (if (zero? idx) "M " "L ")
                                  x
                                  " "
                                  y)))
                         line-points)
          last-x (format-svg-number (* 100 (:x-ratio last-point)))
          first-x (format-svg-number (* 100 (:x-ratio first-point)))]
      (str (str/join " " line-commands)
           " L "
           last-x
           " "
           baseline-y
           " L "
           first-x
           " "
           baseline-y
           " Z"))))

(defn- normalize-hover-index
  [value point-count]
  (let [point-count* (if (and (number? point-count)
                              (pos? point-count))
                       (js/Math.floor point-count)
                       0)
        idx (optional-number value)]
    (when (and (pos? point-count*)
               (number? idx))
      (let [idx* (js/Math.floor idx)]
        (max 0 (min idx* (dec point-count*)))))))

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

(defn- activity-open-orders
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
          running-times (twap-running-times state)
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

(defn- activity-twaps
  [webdata]
  (let [rows (rows-from-source webdata [:twapStates :states :twaps])]
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
  (let [rows* (rows-from-source rows [:fills :userFills])]
    (->> (if (sequential? rows*) rows* [])
       (keep activity-fill-row)
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

(defn- activity-funding-history
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

(defn- activity-order-history
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

(defn- activity-ledger-updates
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

(def ^:private ms-per-day
  (* 24 60 60 1000))

(defn- usdc-coin?
  [coin]
  (let [token (some-> coin str str/trim str/upper-case)]
    (and (seq token)
         (str/starts-with? token "USDC"))))

(defn- balance-row-available
  [row]
  (when (map? row)
    (let [available-direct (or (optional-number (:available row))
                               (optional-number (:availableBalance row))
                               (optional-number (:free row)))
          total (or (optional-number (:total row))
                    (optional-number (:totalBalance row)))
          hold (optional-number (:hold row))
          available-derived (cond
                              (number? total)
                              (if (number? hold)
                                (- total hold)
                                total)

                              :else nil)
          available (or available-direct available-derived)]
      (when (number? available)
        (max 0 available)))))

(defn- usdc-available-from-balance-rows
  [rows]
  (some (fn [row]
          (when (usdc-coin? (or (:coin row)
                                (:token row)))
            (balance-row-available row)))
        (if (sequential? rows) rows [])))

(defn- webdata-usdc-available
  [webdata]
  (let [balance-available (some (fn [row]
                                  (when (usdc-coin? (:coin row))
                                    (when-let [available (optional-number (:available row))]
                                      (max 0 available))))
                                (activity-balances webdata))
        clearinghouse-state (or (:clearinghouseState webdata)
                                (get-in webdata [:data :clearinghouseState])
                                {})
        withdrawable-direct (some optional-number
                                 [(:withdrawable clearinghouse-state)
                                  (:withdrawableUsd clearinghouse-state)
                                  (:withdrawableUSDC clearinghouse-state)
                                  (:availableToWithdraw clearinghouse-state)
                                  (:availableToWithdrawUsd clearinghouse-state)
                                  (:availableToWithdrawUSDC clearinghouse-state)])
        margin-summary (or (:marginSummary clearinghouse-state)
                           (:crossMarginSummary clearinghouse-state)
                           {})
        account-value (optional-number (:accountValue margin-summary))
        total-margin-used (optional-number (:totalMarginUsed margin-summary))
        withdrawable-derived (when (and (number? account-value)
                                        (number? total-margin-used))
                               (- account-value total-margin-used))
        withdrawable (or withdrawable-direct
                        withdrawable-derived)]
    (or balance-available
        (when (number? withdrawable)
          (max 0 withdrawable)))))

(defn- floor-to-decimals
  [value decimals]
  (let [n (or (optional-number value) 0)
        factor (js/Math.pow 10 decimals)]
    (/ (js/Math.floor (* (max 0 n) factor)) factor)))

(defn- format-usdc-display
  [value]
  (let [n (max 0 (or (optional-number value) 0))]
    (.toLocaleString (js/Number. n)
                     "en-US"
                     #js {:minimumFractionDigits 2
                          :maximumFractionDigits 2})))

(defn- format-usdc-input
  [value]
  (let [fixed (.toFixed (max 0 (or (optional-number value) 0)) 2)]
    (or (some-> fixed
                (str/replace #"(\.\d*?[1-9])0+$" "$1")
                (str/replace #"\.0+$" "")
                non-blank-text)
        "0")))

(defn- default-deposit-lockup-days
  [vault-name]
  (if (= "hyperliquidity provider (hlp)"
         (some-> vault-name str str/trim str/lower-case))
    4
    1))

(defn- follower-lockup-days
  [details]
  (let [entry-ms (optional-int (get-in details [:follower-state :vault-entry-time-ms]))
        lockup-ms (optional-int (get-in details [:follower-state :lockup-until-ms]))]
    (when (and (number? entry-ms)
               (number? lockup-ms)
               (> lockup-ms entry-ms))
      (let [days (js/Math.round (/ (- lockup-ms entry-ms) ms-per-day))]
        (when (pos? days)
          days)))))

(defn- vault-deposit-lockup-days
  [details vault-name]
  (or (follower-lockup-days details)
      (default-deposit-lockup-days vault-name)))

(defn- vault-transfer-deposit-max-usdc
  [state wallet-webdata vault-webdata]
  (let [spot-available (usdc-available-from-balance-rows
                        (get-in state [:spot :clearinghouse-state :balances]))
        wallet-webdata-available (webdata-usdc-available wallet-webdata)
        vault-webdata-available (webdata-usdc-available vault-webdata)
        available (or spot-available
                      wallet-webdata-available
                      vault-webdata-available
                      0)]
    (floor-to-decimals available 2)))

(defn- chart-series-data
  [state summary]
  {:account-value (history-points (:accountValueHistory summary))
   :pnl (history-points (:pnlHistory summary))
   :returns (returns-history-points state summary)})

(defn- resolve-chart-series
  [series-by-key selected-series]
  (let [selected* (vault-actions/normalize-vault-detail-chart-series selected-series)
        has-series? (fn [k]
                      (seq (get series-by-key k)))]
    (cond
      (= :returns selected*) :returns
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
        relationship (or (:relationship details)
                         (:relationship row)
                         {:type :normal})
        history-addresses (activity-addresses vault-address relationship)
        fills-source (let [rows (concat-address-rows state [:vaults :fills-by-vault] history-addresses)]
                       (if (seq rows)
                         rows
                         (or (:fills webdata)
                             (:userFills webdata)
                             (get-in webdata [:data :fills])
                             (get-in webdata [:data :userFills]))))
        funding-source (let [rows (concat-address-rows state [:vaults :funding-history-by-vault] history-addresses)]
                         (if (seq rows)
                           rows
                           (or (:fundings webdata)
                               (:userFundings webdata)
                               (:funding-history webdata)
                               (get-in webdata [:data :fundings])
                               (get-in webdata [:data :userFundings]))))
        order-history-source (let [rows (concat-address-rows state [:vaults :order-history-by-vault] history-addresses)]
                               (if (seq rows)
                                 rows
                                 (or (:order-history webdata)
                                     (:orderHistory webdata)
                                     (:historicalOrders webdata)
                                     (get-in webdata [:data :order-history])
                                     (get-in webdata [:data :orderHistory])
                                     (get-in webdata [:data :historicalOrders]))))
        ledger-source (or (get-in state [:vaults :ledger-updates-by-vault vault-address])
                          (:depositsWithdrawals webdata)
                          (:nonFundingLedgerUpdates webdata)
                          (get-in webdata [:data :depositsWithdrawals])
                          (get-in webdata [:data :nonFundingLedgerUpdates]))
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
        vault-name (or (non-blank-text (:name details))
                       (non-blank-text (:name row))
                       vault-address
                       "Vault")
        wallet-webdata (if (map? (:webdata2 state))
                         (:webdata2 state)
                         {})
        deposit-max-usdc (vault-transfer-deposit-max-usdc state wallet-webdata webdata)
        deposit-max-display (format-usdc-display deposit-max-usdc)
        deposit-max-input (format-usdc-input deposit-max-usdc)
        deposit-lockup-days (vault-deposit-lockup-days details vault-name)
        deposit-lockup-copy (str "Deposit funds to "
                                 vault-name
                                 ". The deposit lock-up period is "
                                 deposit-lockup-days
                                 " "
                                 (if (= 1 deposit-lockup-days)
                                   "day."
                                   "days."))
        wallet-address (vault-actions/normalize-vault-address (get-in state [:wallet :address]))
        agent-ready? (= :ready (get-in state [:wallet :agent :status]))
        deposit-allowed? (vault-actions/vault-transfer-deposit-allowed? state vault-address)
        raw-vault-transfer-modal (get-in state [:vaults-ui :vault-transfer-modal])
        vault-transfer-modal* (merge (vault-actions/default-vault-transfer-modal-state)
                                     (if (map? raw-vault-transfer-modal)
                                       raw-vault-transfer-modal
                                       {}))
        vault-transfer-vault-address (or (vault-actions/normalize-vault-address
                                          (:vault-address vault-transfer-modal*))
                                         vault-address)
        vault-transfer-mode (vault-actions/normalize-vault-transfer-mode
                             (:mode vault-transfer-modal*))
        vault-transfer-open? (and (true? (:open? vault-transfer-modal*))
                                  (= vault-transfer-vault-address vault-address))
        vault-transfer-preview (vault-actions/vault-transfer-preview
                                state
                                (assoc vault-transfer-modal*
                                       :vault-address vault-transfer-vault-address
                                       :mode vault-transfer-mode))
        vault-transfer-submitting? (true? (:submitting? vault-transfer-modal*))
        vault-transfer-submit-disabled? (or vault-transfer-submitting?
                                            (not (:ok? vault-transfer-preview)))
        vault-transfer-confirm-label (if vault-transfer-submitting?
                                      (if (= vault-transfer-mode :deposit)
                                        "Depositing..."
                                        "Withdrawing...")
                                      (if (= vault-transfer-mode :deposit)
                                        "Deposit"
                                        "Withdraw"))
        summary (portfolio-summary details snapshot-range)
        returns-benchmark-selector (returns-benchmark-selector-model state)
        series-by-key (chart-series-data state summary)
        selected-series (resolve-chart-series series-by-key chart-series)
        strategy-raw-points (vec (or (get series-by-key selected-series) []))
        strategy-return-points (vec (or (get series-by-key :returns) []))
        selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector) []))
        benchmark-label-by-coin (or (:label-by-coin returns-benchmark-selector) {})
        benchmark-points-by-coin (benchmark-cumulative-return-points-by-coin state
                                                                          snapshot-range
                                                                          selected-benchmark-coins
                                                                          strategy-return-points)
        benchmark-series (if (= selected-series :returns)
                           (mapv (fn [idx coin]
                                   {:id (keyword (str "benchmark-" idx))
                                    :coin coin
                                    :label (or (get benchmark-label-by-coin coin)
                                               coin)
                                    :stroke (benchmark-series-stroke idx)
                                    :raw-points (vec (or (get benchmark-points-by-coin coin) []))})
                                 (range)
                                 selected-benchmark-coins)
                           [])
        raw-series (cond-> [{:id :strategy
                             :label "Vault"
                             :stroke (strategy-series-stroke selected-series)
                             :raw-points strategy-raw-points}]
                     (seq benchmark-series)
                     (into benchmark-series))
        chart-domain-values (->> raw-series
                                 (mapcat (fn [{:keys [raw-points]}]
                                           (map :value raw-points)))
                                 vec)
        domain (when (seq chart-domain-values)
                 (chart-domain chart-domain-values))
        series (mapv (fn [{:keys [id raw-points] :as entry}]
                       (let [points (if domain
                                      (normalize-chart-points raw-points domain)
                                      [])
                             is-strategy? (= id :strategy)
                             area-baseline-y-ratio (case selected-series
                                                     :pnl (when domain
                                                            (value->y-ratio domain 0))
                                                     :account-value 1
                                                     nil)
                             area-path* (when (and is-strategy?
                                                   (not= selected-series :returns)
                                                   (number? area-baseline-y-ratio))
                                          (area-path points area-baseline-y-ratio))]
                         (cond-> (assoc entry
                                        :points points
                                        :path (line-path points)
                                        :has-data? (seq points))
                           (seq area-path*)
                           (assoc :area-path area-path*)

                           (and is-strategy?
                                (= selected-series :account-value)
                                (seq area-path*))
                           (assoc :area-fill account-value-area-fill)

                           (and is-strategy?
                                (= selected-series :pnl)
                                (seq area-path*))
                           (assoc :area-positive-fill pnl-area-positive-fill
                                  :area-negative-fill pnl-area-negative-fill
                                  :zero-y-ratio area-baseline-y-ratio))))
                     raw-series)
        strategy-series (or (some (fn [series-entry]
                                    (when (= :strategy (:id series-entry))
                                      series-entry))
                                  series)
                            {:points []
                             :path nil
                             :has-data? false})
        chart-points (vec (or (:points strategy-series) []))
        hovered-index (normalize-hover-index (get-in state [:vaults-ui :detail-chart-hover-index])
                                             (count chart-points))
        hovered-point (when (number? hovered-index)
                        (nth chart-points hovered-index nil))
        strategy-cumulative-rows (cumulative-rows strategy-return-points)
        benchmark-cumulative-rows-by-coin (into {}
                                                (map (fn [coin]
                                                       [coin (cumulative-rows (get benchmark-points-by-coin coin))]))
                                                selected-benchmark-coins)
        performance-metrics (performance-metrics-model returns-benchmark-selector
                                                       strategy-cumulative-rows
                                                       benchmark-cumulative-rows-by-coin)
        detail-error (get-in state [:vaults :errors :details-by-address vault-address])
        webdata-error (get-in state [:vaults :errors :webdata-by-vault vault-address])
        fills-error (first-address-error state :fills-by-vault history-addresses)
        funding-error (first-address-error state :funding-history-by-vault history-addresses)
        order-history-error (first-address-error state :order-history-by-vault history-addresses)
        ledger-error (get-in state [:vaults :errors :ledger-updates-by-vault vault-address])
        activity-direction-filter (vault-actions/normalize-vault-detail-activity-direction-filter
                                   (get-in state [:vaults-ui :detail-activity-direction-filter]))
        activity-filter-open? (true? (get-in state [:vaults-ui :detail-activity-filter-open?]))
        activity-sort-state-by-tab (into {}
                                         (map (fn [{:keys [value]}]
                                                [value (activity-sort-state state value)]))
                                         activity-tabs)
        positions-raw (activity-positions webdata)
        open-orders-raw (activity-open-orders webdata)
        balances-raw (activity-balances webdata)
        twaps-raw (activity-twaps webdata)
        fills-raw (activity-fills fills-source)
        funding-history-raw (activity-funding-history funding-source)
        order-history-raw (activity-order-history order-history-source)
        deposits-withdrawals-raw (activity-ledger-updates ledger-source vault-address)
        depositors-raw (activity-depositors details)
        project-rows (fn [tab rows]
                       (-> rows
                           (filter-activity-rows-by-direction tab activity-direction-filter)
                           (sort-activity-rows tab (get activity-sort-state-by-tab tab))))
        balances (project-rows :balances balances-raw)
        positions (project-rows :positions positions-raw)
        open-orders (project-rows :open-orders open-orders-raw)
        twaps (project-rows :twap twaps-raw)
        fills (project-rows :trade-history fills-raw)
        funding-history (project-rows :funding-history funding-history-raw)
        order-history (project-rows :order-history order-history-raw)
        deposits-withdrawals (project-rows :deposits-withdrawals deposits-withdrawals-raw)
        depositors (project-rows :depositors depositors-raw)
        activity-loading {:trade-history (address-loading? state :fills-by-vault history-addresses)
                          :funding-history (address-loading? state :funding-history-by-vault history-addresses)
                          :order-history (address-loading? state :order-history-by-vault history-addresses)
                          :deposits-withdrawals (true? (get-in state [:vaults :loading :ledger-updates-by-vault vault-address]))}
        activity-errors {:trade-history fills-error
                         :funding-history funding-error
                         :order-history order-history-error
                         :deposits-withdrawals ledger-error}
        activity-count-by-tab {:balances (count balances-raw)
                               :positions (count positions-raw)
                               :open-orders (count open-orders-raw)
                               :twap (count twaps-raw)
                               :trade-history (count fills-raw)
                               :funding-history (count funding-history-raw)
                               :order-history (count order-history-raw)
                               :deposits-withdrawals (count deposits-withdrawals-raw)
                               :depositors (max (count depositors-raw)
                                                (followers-count details))}]
    {:kind kind
     :vault-address vault-address
     :invalid-address? (and (= :detail kind)
                            (nil? vault-address))
     :loading? detail-loading?
     :error (or detail-error webdata-error)
     :name vault-name
     :leader (or (:leader details)
                 (:leader row))
     :description (or (:description details) "")
     :relationship relationship
     :allow-deposits? (true? (:allow-deposits? details))
     :always-close-on-withdraw? (true? (:always-close-on-withdraw? details))
     :wallet-connected? (string? wallet-address)
     :agent-ready? agent-ready?
     :followers (followers-count details)
     :leader-commission (normalize-percent-value (:leader-commission details))
     :leader-fraction (normalize-percent-value (:leader-fraction details))
     :metrics {:tvl tvl
               :past-month-return month-return
               :your-deposit your-deposit
               :all-time-earned all-time-earned
               :apr (normalize-percent-value apr)}
     :vault-transfer {:can-open-deposit? deposit-allowed?
                      :can-open-withdraw? true
                      :open? vault-transfer-open?
                      :mode vault-transfer-mode
                      :vault-address vault-transfer-vault-address
                      :deposit-max-usdc deposit-max-usdc
                      :deposit-max-display deposit-max-display
                      :deposit-max-input deposit-max-input
                      :deposit-lockup-days deposit-lockup-days
                      :deposit-lockup-copy deposit-lockup-copy
                      :amount-input (:amount-input vault-transfer-modal*)
                      :withdraw-all? (true? (:withdraw-all? vault-transfer-modal*))
                      :submitting? vault-transfer-submitting?
                      :error (:error vault-transfer-modal*)
                      :preview-ok? (:ok? vault-transfer-preview)
                      :preview-message (:display-message vault-transfer-preview)
                      :title (if (= vault-transfer-mode :deposit)
                               "Deposit"
                               "Withdraw")
                      :confirm-label vault-transfer-confirm-label
                      :submit-disabled? vault-transfer-submit-disabled?}
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
     :performance-metrics (assoc performance-metrics
                                 :timeframe-options chart-timeframe-options
                                 :selected-timeframe snapshot-range)
     :chart {:axis-kind (case selected-series
                          :pnl :pnl
                          :returns :returns
                          :account-value :account-value
                          :account-value)
             :series-tabs [{:value :returns
                            :label "Returns"}
                           {:value :account-value
                            :label "Account Value"}
                           {:value :pnl
                            :label "PNL"}]
             :timeframe-options chart-timeframe-options
             :selected-timeframe snapshot-range
             :selected-series selected-series
             :returns-benchmark returns-benchmark-selector
             :hover {:index hovered-index
                     :point hovered-point
                     :active? (some? hovered-point)}
             :y-ticks (if domain
                       (chart-y-ticks domain)
                       chart-empty-y-ticks)
             :points chart-points
             :path (:path strategy-series)
             :series series}
     :activity-tabs (mapv (fn [{:keys [value label]}]
                            {:value value
                             :label label
                             :count (get activity-count-by-tab value 0)})
                          activity-tabs)
     :selected-activity-tab activity-tab
     :activity-direction-filter activity-direction-filter
     :activity-filter-open? activity-filter-open?
     :activity-filter-options activity-filter-options
     :activity-sort-state-by-tab activity-sort-state-by-tab
     :selected-activity-sort-state (get activity-sort-state-by-tab activity-tab)
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
