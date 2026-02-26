(ns hyperopen.views.portfolio.vm
  (:require [clojure.string :as str]
            [hyperopen.domain.trading :as trading]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.account-info.projections :as projections]))

(def ^:private fourteen-days-ms
  (* 14 24 60 60 1000))

(def ^:private summary-scope-options
  [{:value :all
    :label "Perps + Spot + Vaults"}
   {:value :perps
    :label "Perps"}])

(def ^:private summary-time-range-options
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

(def ^:private chart-tab-options
  [{:value :account-value
    :label "Account Value"}
   {:value :pnl
    :label "PNL"}
   {:value :returns
    :label "Returns"}])

(def ^:private chart-y-tick-count
  4)

(def ^:private chart-empty-y-ticks
  [{:value 3 :y-ratio 0}
   {:value 2 :y-ratio (/ 1 3)}
   {:value 1 :y-ratio (/ 2 3)}
   {:value 0 :y-ratio 1}])

(def ^:private strategy-series-stroke
  "#f5f7f8")

(def ^:private benchmark-series-strokes
  ["#f2cf66"
   "#7cc2ff"
   "#ff9d7c"
   "#8be28b"
   "#d8a8ff"
   "#ffdf8a"])

(defn- optional-number [value]
  (projections/parse-optional-num value))

(defn- number-or-zero [value]
  (if-let [n (optional-number value)]
    n
    0))

(defn- finite-number? [value]
  (and (number? value)
       (js/isFinite value)))

(defn- fills-source [state]
  (or (get-in state [:orders :fills])
      (get-in state [:webdata2 :fills])
      []))

(defn- trade-values [rows]
  (keep (fn [row]
          (let [value (projections/trade-history-value-number row)
                time-ms (projections/trade-history-time-ms row)]
            (when (number? value)
              {:value value
               :time-ms time-ms})))
        rows))

(defn volume-14d-usd [state]
  (let [values (trade-values (fills-source state))
        cutoff (- (.now js/Date) fourteen-days-ms)
        in-window (filter (fn [{:keys [time-ms]}]
                            (and (number? time-ms)
                                 (>= time-ms cutoff)))
                          values)
        selected (if (seq in-window) in-window values)]
    (reduce (fn [acc {:keys [value]}]
              (+ acc value))
            0
            selected)))

(defn- selector-option-label [options selected-value]
  (or (some (fn [{:keys [value label]}]
              (when (= value selected-value)
                label))
            options)
      (some-> options first :label)
      ""))

(defn- parse-cache-order [value]
  (let [parsed (cond
                 (number? value) value
                 (string? value) (js/parseInt value 10)
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed)))
      (js/Math.floor parsed))))

(defn- market-type-token [value]
  (cond
    (keyword? value) value
    (string? value) (some-> value str/trim str/lower-case keyword)
    :else nil))

(defn- benchmark-open-interest [market]
  (let [open-interest (optional-number (:openInterest market))]
    (if (finite-number? open-interest)
      open-interest
      0)))

(defn- benchmark-option-label [market]
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

(defn- benchmark-option-rank [market]
  [(- (benchmark-open-interest market))
   (or (parse-cache-order (:cache-order market))
       js/Number.MAX_SAFE_INTEGER)
   (str/lower-case (or (some-> (:symbol market) str str/trim) ""))
   (str/lower-case (or (some-> (:coin market) str str/trim) ""))
   (str/lower-case (or (some-> (:key market) str str/trim) ""))])

(defn- benchmark-selector-options [state]
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

(defn- normalize-benchmark-search-query [value]
  (-> (or value "")
      str
      str/trim
      str/lower-case))

(defn- benchmark-option-matches-search? [option search-query]
  (or (str/blank? search-query)
      (str/includes? (str/lower-case (or (:label option) "")) search-query)
      (str/includes? (str/lower-case (or (:value option) "")) search-query)))

(defn- selected-returns-benchmark-coins [state]
  (let [coins (portfolio-actions/normalize-portfolio-returns-benchmark-coins
               (get-in state [:portfolio-ui :returns-benchmark-coins]))]
    (if (seq coins)
      coins
      (if-let [legacy-coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin
                            (get-in state [:portfolio-ui :returns-benchmark-coin]))]
        [legacy-coin]
        []))))

(defn- selected-benchmark-options [options selected-coins]
  (let [options-by-coin (into {} (map (juxt :value identity)) options)]
    (mapv (fn [coin]
            (or (get options-by-coin coin)
                {:value coin
                 :label coin
                 :open-interest 0}))
          selected-coins)))

(defn- returns-benchmark-selector-model [state]
  (let [selected-coins (selected-returns-benchmark-coins state)
        selected-coin-set (set selected-coins)
        options (benchmark-selector-options state)
        search (or (get-in state [:portfolio-ui :returns-benchmark-search]) "")
        search-query (normalize-benchmark-search-query search)
        suggestions-open? (boolean (get-in state [:portfolio-ui :returns-benchmark-suggestions-open?]))
        selected-options (selected-benchmark-options options selected-coins)
        candidates (->> options
                        (remove (fn [{:keys [value]}]
                                  (contains? selected-coin-set value)))
                        (filter #(benchmark-option-matches-search? % search-query))
                        vec)
        top-coin (some-> candidates first :value)
        empty-message (cond
                        (empty? options)
                        "No benchmark symbols available."

                        (seq candidates)
                        nil

                        (seq search-query)
                        "No matching symbols."

                        :else
                        "All symbols selected.")]
    {:selected-coins selected-coins
     :selected-options selected-options
     :coin-search search
     :suggestions-open? suggestions-open?
     :candidates candidates
     :top-coin top-coin
     :empty-message empty-message
     :label-by-coin (into {} (map (juxt :value :label)) options)}))

(defn- canonical-summary-key [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      (let [token (-> text
                      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                      str/lower-case
                      (str/replace #"[^a-z0-9]+" "-")
                      (str/replace #"(^-+)|(-+$)" ""))]
        (case token
          "day" :day
          "week" :week
          "month" :month
          "3m" :three-month
          "3-m" :three-month
          "3month" :three-month
          "3-month" :three-month
          "threemonth" :three-month
          "three-month" :three-month
          "three-months" :three-month
          "quarter" :three-month
          "6m" :six-month
          "6-m" :six-month
          "6month" :six-month
          "6-month" :six-month
          "sixmonth" :six-month
          "six-month" :six-month
          "six-months" :six-month
          "halfyear" :six-month
          "half-year" :six-month
          "1y" :one-year
          "1-y" :one-year
          "1year" :one-year
          "1-year" :one-year
          "oneyear" :one-year
          "one-year" :one-year
          "one-years" :one-year
          "year" :one-year
          "2y" :two-year
          "2-y" :two-year
          "2year" :two-year
          "2-year" :two-year
          "twoyear" :two-year
          "two-year" :two-year
          "two-years" :two-year
          "alltime" :all-time
          "all-time" :all-time
          "perpday" :perp-day
          "perp-day" :perp-day
          "perpweek" :perp-week
          "perp-week" :perp-week
          "perpmonth" :perp-month
          "perp-month" :perp-month
          "perp3m" :perp-three-month
          "perp3-m" :perp-three-month
          "perp3month" :perp-three-month
          "perp3-month" :perp-three-month
          "perpthreemonth" :perp-three-month
          "perp-three-month" :perp-three-month
          "perp-three-months" :perp-three-month
          "perpquarter" :perp-three-month
          "perp6m" :perp-six-month
          "perp6-m" :perp-six-month
          "perp6month" :perp-six-month
          "perp6-month" :perp-six-month
          "perpsixmonth" :perp-six-month
          "perp-six-month" :perp-six-month
          "perp-six-months" :perp-six-month
          "perphalfyear" :perp-six-month
          "perp-half-year" :perp-six-month
          "perp1y" :perp-one-year
          "perp1-y" :perp-one-year
          "perp1year" :perp-one-year
          "perp1-year" :perp-one-year
          "perponeyear" :perp-one-year
          "perp-one-year" :perp-one-year
          "perp-one-years" :perp-one-year
          "perpyear" :perp-one-year
          "perp2y" :perp-two-year
          "perp2-y" :perp-two-year
          "perp2year" :perp-two-year
          "perp2-year" :perp-two-year
          "perptwoyear" :perp-two-year
          "perp-two-year" :perp-two-year
          "perp-two-years" :perp-two-year
          "perpalltime" :perp-all-time
          "perp-all-time" :perp-all-time
          (keyword token))))))

(defn- normalize-summary-by-key [summary-by-key]
  (reduce-kv (fn [acc key value]
               (let [summary-key (canonical-summary-key key)]
                 (if (and summary-key
                          (map? value))
                   (assoc acc summary-key value)
                   acc)))
             {}
             (or summary-by-key {})))

(defn- selected-summary-key [scope time-range]
  (if (= scope :perps)
    (case time-range
      :day :perp-day
      :week :perp-week
      :month :perp-month
      :three-month :perp-three-month
      :six-month :perp-six-month
      :one-year :perp-one-year
      :two-year :perp-two-year
      :all-time :perp-all-time
      :perp-month)
    (case time-range
      :day :day
      :week :week
      :month :month
      :three-month :three-month
      :six-month :six-month
      :one-year :one-year
      :two-year :two-year
      :all-time :all-time
      :month)))

(defn- summary-key-candidates [scope time-range]
  (let [primary (selected-summary-key scope time-range)]
    (case primary
      :day [:day :week :month :three-month :six-month :one-year :two-year :all-time]
      :week [:week :month :three-month :six-month :one-year :two-year :all-time :day]
      :month [:month :three-month :six-month :one-year :two-year :all-time :week :day]
      :three-month [:three-month :six-month :one-year :two-year :all-time :month :week :day]
      :six-month [:six-month :one-year :two-year :all-time :three-month :month :week :day]
      :one-year [:one-year :two-year :all-time :six-month :three-month :month :week :day]
      :two-year [:two-year :all-time :one-year :six-month :three-month :month :week :day]
      :all-time [:all-time :two-year :one-year :six-month :three-month :month :week :day]
      :perp-day [:perp-day :perp-week :perp-month :perp-three-month :perp-six-month :perp-one-year :perp-two-year :perp-all-time]
      :perp-week [:perp-week :perp-month :perp-three-month :perp-six-month :perp-one-year :perp-two-year :perp-all-time :perp-day]
      :perp-month [:perp-month :perp-three-month :perp-six-month :perp-one-year :perp-two-year :perp-all-time :perp-week :perp-day]
      :perp-three-month [:perp-three-month :perp-six-month :perp-one-year :perp-two-year :perp-all-time :perp-month :perp-week :perp-day]
      :perp-six-month [:perp-six-month :perp-one-year :perp-two-year :perp-all-time :perp-three-month :perp-month :perp-week :perp-day]
      :perp-one-year [:perp-one-year :perp-two-year :perp-all-time :perp-six-month :perp-three-month :perp-month :perp-week :perp-day]
      :perp-two-year [:perp-two-year :perp-all-time :perp-one-year :perp-six-month :perp-three-month :perp-month :perp-week :perp-day]
      :perp-all-time [:perp-all-time :perp-two-year :perp-one-year :perp-six-month :perp-three-month :perp-month :perp-week :perp-day]
      [primary])))

(declare derived-summary-entry)

(defn- selected-summary-entry [summary-by-key scope time-range]
  (or (get summary-by-key (selected-summary-key scope time-range))
      (derived-summary-entry summary-by-key scope time-range)
      (some #(get summary-by-key %) (summary-key-candidates scope time-range))
      (some-> summary-by-key vals first)))

(defn- history-point-value [row]
  (cond
    (and (sequential? row)
         (>= (count row) 2))
    (optional-number (second row))

    (map? row)
    (or (optional-number (:value row))
        (optional-number (:pnl row))
        (optional-number (:account-value row))
        (optional-number (:accountValue row)))

    :else
    nil))

(defn- history-point-time-ms [row]
  (cond
    (and (sequential? row)
         (seq row))
    (optional-number (first row))

    (map? row)
    (or (optional-number (:time row))
        (optional-number (:timestamp row))
        (optional-number (:time-ms row))
        (optional-number (:timeMs row))
        (optional-number (:ts row))
        (optional-number (:t row)))

    :else
    nil))

(defn- account-value-history-rows [summary]
  (let [source (:accountValueHistory summary)]
    (if (sequential? source)
      source
      [])))

(defn- pnl-history-rows [summary]
  (let [source (:pnlHistory summary)]
    (if (sequential? source)
      source
      [])))

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
  [summary-time-range end-time-ms]
  (when (number? end-time-ms)
    (case summary-time-range
      :three-month (with-utc-months-offset end-time-ms -3)
      :six-month (with-utc-months-offset end-time-ms -6)
      :one-year (with-utc-years-offset end-time-ms -1)
      :two-year (with-utc-years-offset end-time-ms -2)
      nil)))

(defn- normalized-history-rows
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [time-ms (history-point-time-ms row)
                     value (history-point-value row)]
                 (when (and (finite-number? time-ms)
                            (finite-number? value))
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

(defn- range-all-time-key
  [scope]
  (if (= scope :perps)
    :perp-all-time
    :all-time))

(defn- derived-summary-entry
  [summary-by-key scope summary-time-range]
  (when-let [base-summary (get summary-by-key (range-all-time-key scope))]
    (let [account-rows (normalized-history-rows (account-value-history-rows base-summary))
          pnl-rows (normalized-history-rows (pnl-history-rows base-summary))
          end-time-ms (or (some-> account-rows last first)
                          (some-> pnl-rows last first))
          cutoff-ms (summary-window-cutoff-ms summary-time-range end-time-ms)]
      (when (number? cutoff-ms)
        (let [account-window (history-window-rows account-rows cutoff-ms)
              pnl-window (history-window-rows pnl-rows cutoff-ms)
              pnl-window* (rebase-history-rows pnl-window)]
          (when (or (seq account-window)
                    (seq pnl-window*))
            {:accountValueHistory account-window
             :pnlHistory pnl-window*}))))))

(defn- normalize-address [value]
  (some-> value str str/lower-case str/trim))

(defn- same-address?
  [left right]
  (let [left* (normalize-address left)
        right* (normalize-address right)]
    (and (seq left*)
         (seq right*)
         (= left* right*))))

(defn- canonical-ledger-delta-type
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      (let [token (-> text
                      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                      str/lower-case
                      (str/replace #"[^a-z0-9]+" "-")
                      (str/replace #"(^-+)|(-+$)" ""))]
        (keyword token)))))

(defn- ledger-row-id
  [row]
  (let [hash* (some-> (:hash row) str str/lower-case str/trim)
        time-ms (history-point-time-ms row)
        delta (:delta row)]
    (when (or (seq hash*)
              (number? time-ms)
              (some? delta))
      ;; Dedupe exact duplicates across bootstrap/websocket sources without
      ;; collapsing distinct deltas that share a transaction hash.
      (str (or hash* "")
           "|"
           (or time-ms "")
           "|"
           (pr-str delta)))))

(defn- combined-ledger-rows
  [state]
  (let [rows (concat (or (get-in state [:portfolio :ledger-updates]) [])
                     (or (get-in state [:orders :ledger]) []))
        normalized (->> rows
                        (keep (fn [row]
                                (let [time-ms (history-point-time-ms row)]
                                  (when (number? time-ms)
                                    {:id (ledger-row-id row)
                                     :time-ms time-ms
                                     :row row}))))
                        (sort-by :time-ms))]
    (->> normalized
         (reduce (fn [{:keys [seen rows]} {:keys [id row]}]
                   (let [id* (or id (str "row-" (count seen)))]
                     (if (contains? seen id*)
                       {:seen seen
                        :rows rows}
                       {:seen (conj seen id*)
                        :rows (conj rows row)})))
                 {:seen #{}
                  :rows []})
         :rows
         vec)))

(defn- transfer-flow
  [amount sender destination current-user-address]
  (let [sender? (same-address? sender current-user-address)
        destination? (same-address? destination current-user-address)]
    (cond
      (and sender? (not destination?)) (- amount)
      (and destination? (not sender?)) amount
      :else 0)))

(defn- usdc-fee
  [delta]
  (let [fee (number-or-zero (:fee delta))
        fee-token (some-> (:feeToken delta) str str/upper-case)]
    (if (or (nil? fee-token)
            (= fee-token "")
            (= fee-token "USDC"))
      fee
      0)))

(defn- ledger-row-flow-usd
  [row summary-scope current-user-address]
  (let [delta (:delta row)]
    (cond
      (number? (optional-number delta))
      (optional-number delta)

      (map? delta)
      (case (canonical-ledger-delta-type (:type delta))
        :deposit
        (number-or-zero (:usdc delta))

        :withdraw
        (- (+ (number-or-zero (:usdc delta))
              (number-or-zero (:fee delta))))

        :account-class-transfer
        (if (= summary-scope :perps)
          (let [amount (number-or-zero (:usdc delta))]
            (if (true? (:toPerp delta))
              amount
              (- amount)))
          0)

        :sub-account-transfer
        (transfer-flow (number-or-zero (:usdc delta))
                       (:user delta)
                       (:destination delta)
                       current-user-address)

        :internal-transfer
        (let [sender? (same-address? (:user delta) current-user-address)
              amount (+ (number-or-zero (:usdc delta))
                        (if sender?
                          (number-or-zero (:fee delta))
                          0))]
          (transfer-flow amount
                         (:user delta)
                         (:destination delta)
                         current-user-address))

        :spot-transfer
        (let [sender? (same-address? (:user delta) current-user-address)
              amount (+ (number-or-zero (or (:usdcValue delta)
                                            (:usdc delta)))
                        (if sender?
                          (usdc-fee delta)
                          0))]
          (transfer-flow amount
                         (:user delta)
                         (:destination delta)
                         current-user-address))

        :send
        (let [sender? (same-address? (:user delta) current-user-address)
              amount (+ (number-or-zero (or (:usdcValue delta)
                                            (:usdc delta)))
                        (if sender?
                          (usdc-fee delta)
                          0))]
          (transfer-flow amount
                         (:user delta)
                         (:destination delta)
                         current-user-address))

        0)

      :else
      nil)))

(defn- ledger-flow-events
  [state summary-scope]
  (let [current-user-address (normalize-address (get-in state [:wallet :address]))]
    (->> (combined-ledger-rows state)
         (keep (fn [row]
                 (let [time-ms (history-point-time-ms row)
                       flow (ledger-row-flow-usd row summary-scope current-user-address)]
                   (when (and (number? time-ms)
                              (finite-number? flow)
                              (not (zero? flow)))
                     {:time-ms time-ms
                      :flow flow}))))
         (sort-by :time-ms)
         vec)))

(defn- account-history-points
  [summary]
  (->> (account-value-history-rows summary)
       (map-indexed (fn [idx row]
                      (let [value (history-point-value row)
                            time-ms (history-point-time-ms row)]
                        (when (finite-number? value)
                          {:index idx
                           :time-ms (or time-ms idx)
                           :value value}))))
       (keep identity)
       (sort-by :time-ms)
       vec))

(defn- interval-flow-stats
  [flows start-time-ms end-time-ms]
  (let [duration-ms (- end-time-ms start-time-ms)
        interval-flows (filter (fn [{:keys [time-ms]}]
                                 (and (number? time-ms)
                                      (> time-ms start-time-ms)
                                      (<= time-ms end-time-ms)))
                               flows)
        net-flow (reduce (fn [acc {:keys [flow]}]
                           (+ acc flow))
                         0
                         interval-flows)
        weighted-flow (if (pos? duration-ms)
                        (reduce (fn [acc {:keys [time-ms flow]}]
                                  (let [weight (/ (- end-time-ms time-ms) duration-ms)
                                        weight* (cond
                                                  (< weight 0) 0
                                                  (> weight 1) 1
                                                  :else weight)]
                                    (+ acc (* flow weight*))))
                                0
                                interval-flows)
                        0)]
    {:net-flow net-flow
     :weighted-flow weighted-flow}))

(defn- returns-history-rows
  [state summary summary-scope]
  (let [points (account-history-points summary)
        anchor-index (first (keep-indexed (fn [idx {:keys [value]}]
                                            (when (pos? value)
                                              idx))
                                          points))]
    (if (number? anchor-index)
      (let [points* (subvec points anchor-index)
            flows (ledger-flow-events state summary-scope)]
        (if (seq points*)
          (loop [idx 1
                 previous (first points*)
                 cumulative-factor 1
                 output [[(:time-ms (first points*)) 0]]
                 point-count (count points*)]
            (if (>= idx point-count)
              output
              (let [current (nth points* idx)
                    start-time-ms (:time-ms previous)
                    end-time-ms (:time-ms current)
                    {:keys [net-flow weighted-flow]} (interval-flow-stats flows start-time-ms end-time-ms)
                    denominator (+ (:value previous) weighted-flow)
                    numerator (- (:value current) (:value previous) net-flow)
                    period-return (if (and (finite-number? denominator)
                                           (pos? denominator))
                                    (/ numerator denominator)
                                    0)
                    period-return* (if (finite-number? period-return)
                                     (max -0.999999 period-return)
                                     0)
                    cumulative-factor* (* cumulative-factor (+ 1 period-return*))
                    cumulative-percent (* 100 (- cumulative-factor* 1))
                    cumulative-percent* (if (finite-number? cumulative-percent)
                                          cumulative-percent
                                          (* 100 (- cumulative-factor 1)))]
                (recur (inc idx)
                       current
                       cumulative-factor*
                       (conj output [(:time-ms current) cumulative-percent*])
                       point-count))))
          []))
      [])))

(defn- chart-history-rows [state summary chart-tab summary-scope]
  (let [source (case chart-tab
                 :pnl (pnl-history-rows summary)
                 :returns (portfolio-metrics/returns-history-rows state summary summary-scope)
                 :account-value (account-value-history-rows summary)
                 (account-value-history-rows summary))]
    (if (sequential? source)
      source
      [])))

(defn- normalize-chart-point-value [chart-tab value]
  (when (finite-number? value)
    (if (= chart-tab :returns)
      (let [rounded (/ (js/Math.round (* value 100)) 100)]
        (if (== rounded -0)
          0
          rounded))
      ;; Hyperliquid chart rounds account-value and pnl points to integers before plotting.
      (if (zero? value)
        0
        (js/parseInt (.toFixed value 0) 10)))))

(defn- rows->chart-points [rows chart-tab]
  (->> rows
       (map-indexed (fn [idx row]
                      (let [value (history-point-value row)
                            value* (normalize-chart-point-value chart-tab value)]
                        (when (number? value*)
                          {:index idx
                           :time-ms (or (history-point-time-ms row) idx)
                           :value value*}))))
       (keep identity)
       vec))

(defn- chart-data-points [state summary chart-tab summary-scope]
  (rows->chart-points (chart-history-rows state summary chart-tab summary-scope)
                      chart-tab))

(defn- candle-point-close [row]
  (cond
    (map? row)
    (or (optional-number (:c row))
        (optional-number (:close row)))

    (and (sequential? row)
         (>= (count row) 5))
    (optional-number (nth row 4))

    :else
    nil))

(defn- benchmark-candle-points [rows]
  (if (sequential? rows)
    (->> rows
         (keep (fn [row]
                 (let [time-ms (history-point-time-ms row)
                       close (candle-point-close row)]
                   (when (and (finite-number? time-ms)
                              (finite-number? close)
                              (pos? close))
                     {:time-ms time-ms
                      :close close}))))
         (sort-by :time-ms)
         vec)
    []))

(defn- aligned-benchmark-return-rows [benchmark-points strategy-points]
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
              output* (if (and (finite-number? latest-close*)
                               (finite-number? anchor-close*)
                               (pos? anchor-close*))
                        (let [cumulative-return (* 100 (- (/ latest-close* anchor-close*) 1))]
                          (if (finite-number? cumulative-return)
                            (conj output [time-ms cumulative-return])
                            output))
                        output)]
          (recur (inc time-idx)
                 candle-idx*
                 latest-close*
                 anchor-close*
                 output*))))))

(defn- benchmark-returns-points [state summary-time-range benchmark-coin strategy-points]
  (if (and (seq benchmark-coin)
           (seq strategy-points))
    (let [{:keys [interval]} (portfolio-actions/returns-benchmark-candle-request summary-time-range)
          candles (benchmark-candle-points (get-in state [:candles benchmark-coin interval]))
          aligned-returns (aligned-benchmark-return-rows candles strategy-points)]
      (rows->chart-points aligned-returns :returns))
    []))

(defn- cumulative-return-time-points
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [time-ms (history-point-time-ms row)
                     value (history-point-value row)]
                 (when (and (number? time-ms)
                            (finite-number? value))
                   {:time-ms time-ms
                    :value value}))))
       vec))

(defn- benchmark-cumulative-return-rows
  [state summary-time-range benchmark-coin strategy-time-points]
  (if (and (seq benchmark-coin)
           (seq strategy-time-points))
    (let [{:keys [interval]} (portfolio-actions/returns-benchmark-candle-request summary-time-range)
          candles (benchmark-candle-points (get-in state [:candles benchmark-coin interval]))]
      (aligned-benchmark-return-rows candles strategy-time-points))
    []))

(defn- with-performance-metric-columns
  [groups portfolio-values benchmark-values]
  (mapv (fn [{:keys [rows] :as group}]
          (assoc group
                 :rows (mapv (fn [{:keys [key] :as row}]
                               (assoc row
                                      :portfolio-value (get portfolio-values key)
                                      :benchmark-value (get benchmark-values key)))
                             (or rows []))))
        (or groups [])))

(defn- performance-metrics-model
  [state summary-entry summary-scope summary-time-range returns-benchmark-selector]
  (let [strategy-cumulative-rows (portfolio-metrics/returns-history-rows state
                                                                          summary-entry
                                                                          summary-scope)
        strategy-time-points (cumulative-return-time-points strategy-cumulative-rows)
        strategy-daily-rows (portfolio-metrics/daily-compounded-returns strategy-cumulative-rows)
        benchmark-coin (some-> returns-benchmark-selector :selected-coins first)
        benchmark-cumulative-rows (benchmark-cumulative-return-rows state
                                                                    summary-time-range
                                                                    benchmark-coin
                                                                    strategy-time-points)
        benchmark-daily-rows (portfolio-metrics/daily-compounded-returns benchmark-cumulative-rows)
        portfolio-values (portfolio-metrics/compute-performance-metrics {:strategy-daily-rows strategy-daily-rows
                                                                         :benchmark-daily-rows benchmark-daily-rows
                                                                         :rf 0
                                                                         :periods-per-year 252
                                                                         :compounded true})
        benchmark-values (if (seq benchmark-daily-rows)
                           (portfolio-metrics/compute-performance-metrics {:strategy-daily-rows benchmark-daily-rows
                                                                          :rf 0
                                                                          :periods-per-year 252
                                                                          :compounded true})
                           {})
        groups (with-performance-metric-columns (portfolio-metrics/metric-rows portfolio-values)
                 portfolio-values
                 benchmark-values)
        benchmark-label (when (seq benchmark-coin)
                          (or (get-in returns-benchmark-selector [:label-by-coin benchmark-coin])
                              benchmark-coin))]
    {:benchmark-selected? (boolean (seq benchmark-coin))
     :benchmark-coin benchmark-coin
     :benchmark-label benchmark-label
     :values portfolio-values
     :benchmark-values benchmark-values
     :groups groups}))

(defn- non-zero-span
  [domain-min domain-max]
  (let [span (- domain-max domain-min)]
    (if (zero? span) 1 span)))

(defn- normalize-degenerate-domain [min-value max-value]
  (if (= min-value max-value)
    (let [pad (max 1 (* 0.05 (js/Math.abs min-value)))]
      [(- min-value pad) (+ min-value pad)])
    [min-value max-value]))

(defn- chart-domain [values]
  (if (seq values)
    (let [[min-value max-value] (normalize-degenerate-domain (apply min values)
                                                             (apply max values))
          step (/ (non-zero-span min-value max-value) (dec chart-y-tick-count))]
      {:min min-value
       :max max-value
       :step step})
    {:min 0
     :max 3
     :step 1}))

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

(defn- normalize-chart-points [points {:keys [min max]}]
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

(defn- format-svg-number [value]
  (let [rounded (/ (js/Math.round (* value 1000)) 1000)]
    (if (== rounded -0)
      0
      rounded)))

(defn- chart-step-path [points]
  (when (seq points)
    (let [{start-x-ratio :x-ratio
           start-y-ratio :y-ratio} (first points)
          start-x (format-svg-number (* 100 start-x-ratio))
          start-y (format-svg-number (* 100 start-y-ratio))]
      (if (= 1 (count points))
        (str "M " start-x " " start-y
             " L 100 " start-y)
        (let [commands (loop [parts [(str "M " start-x " " start-y)]
                              previous (first points)
                              remaining (rest points)]
                         (if-let [{x-ratio :x-ratio
                                   y-ratio :y-ratio} (first remaining)]
                           (let [x (format-svg-number (* 100 x-ratio))
                                 y (format-svg-number (* 100 y-ratio))
                                 prev-y (format-svg-number (* 100 (:y-ratio previous)))]
                             (recur (conj parts
                                          (str "L " x " " prev-y)
                                          (str "L " x " " y))
                                    (first remaining)
                                    (rest remaining)))
                           parts))]
          (str/join " " commands))))))

(defn- chart-axis-kind [tab]
  (if (= tab :returns)
    :percent
    :number))

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

(defn- benchmark-series-stroke
  [idx]
  (let [palette-size (count benchmark-series-strokes)]
    (if (pos? palette-size)
      (nth benchmark-series-strokes (mod idx palette-size))
      strategy-series-stroke)))

(defn- build-chart-model
  [state summary-entry summary-scope summary-time-range returns-benchmark-selector]
  (let [selected-tab (portfolio-actions/normalize-portfolio-chart-tab
                      (get-in state [:portfolio-ui :chart-tab]
                              portfolio-actions/default-chart-tab))
        axis-kind (chart-axis-kind selected-tab)
        strategy-points (chart-data-points state summary-entry selected-tab summary-scope)
        selected-benchmark-coins (if (= selected-tab :returns)
                                   (vec (:selected-coins returns-benchmark-selector))
                                   [])
        benchmark-label-by-coin (or (:label-by-coin returns-benchmark-selector) {})
        benchmark-series (if (= selected-tab :returns)
                           (mapv (fn [idx coin]
                                   (let [label (or (get benchmark-label-by-coin coin)
                                                   coin)]
                                     {:id (keyword (str "benchmark-" idx))
                                      :coin coin
                                      :label label
                                      :stroke (benchmark-series-stroke idx)
                                      :raw-points (benchmark-returns-points state
                                                                            summary-time-range
                                                                            coin
                                                                            strategy-points)}))
                                 (range)
                                 selected-benchmark-coins)
                           [])
        raw-series (cond-> [{:id :strategy
                             :label "Portfolio"
                             :stroke strategy-series-stroke
                             :raw-points strategy-points}]
                     (seq benchmark-series)
                     (into benchmark-series))
        domain-values (->> raw-series
                           (mapcat (fn [{:keys [raw-points]}]
                                     (map :value raw-points)))
                           vec)
        domain (when (seq domain-values)
                 (chart-domain domain-values))
        series (mapv (fn [{:keys [raw-points] :as entry}]
                       (let [points (if domain
                                      (normalize-chart-points raw-points domain)
                                      [])]
                         (assoc entry
                                :points points
                                :path (chart-step-path points)
                                :has-data? (seq points))))
                     raw-series)
        strategy-series (or (some (fn [series-entry]
                                    (when (= :strategy (:id series-entry))
                                      series-entry))
                                  series)
                            {:points []
                             :path nil
                             :has-data? false})
        strategy-points (:points strategy-series)
        hovered-index (normalize-hover-index (get-in state [:portfolio-ui :chart-hover-index])
                                             (count strategy-points))
        hovered-point (when (number? hovered-index)
                        (nth strategy-points hovered-index nil))]
    {:selected-tab selected-tab
     :axis-kind axis-kind
     :tabs chart-tab-options
     :points strategy-points
     :path (:path strategy-series)
     :series series
     :hover {:index hovered-index
             :point hovered-point
             :active? (some? hovered-point)}
     :benchmark-selected? (and (= selected-tab :returns)
                               (seq selected-benchmark-coins))
     :y-ticks (if domain
                (chart-y-ticks domain)
                chart-empty-y-ticks)
     :has-data? (boolean (:has-data? strategy-series))}))

(defn- pnl-delta [summary]
  (let [values (keep history-point-value (or (:pnlHistory summary) []))]
    (when (seq values)
      (- (last values) (first values)))))

(defn- max-drawdown-ratio [summary]
  (let [pnl-history (vec (or (:pnlHistory summary) []))
        account-history (vec (or (:accountValueHistory summary) []))]
    (when (and (seq pnl-history)
               (seq account-history))
      (loop [idx 0
             peak-pnl 0
             peak-account-value 0
             max-ratio 0]
        (if (>= idx (count pnl-history))
          max-ratio
          (let [pnl (history-point-value (nth pnl-history idx))
                max-ratio* (if (and (number? pnl)
                                    (number? peak-account-value)
                                    (pos? peak-account-value))
                             (max max-ratio (/ (- peak-pnl pnl) peak-account-value))
                             max-ratio)
                account-value-at-index (history-point-value (nth account-history idx nil))
                [peak-pnl* peak-account-value*]
                (if (and (number? pnl)
                         (>= pnl peak-pnl))
                  [pnl (if (number? account-value-at-index)
                         account-value-at-index
                         peak-account-value)]
                  [peak-pnl peak-account-value])]
            (recur (inc idx)
                   peak-pnl*
                   peak-account-value*
                   max-ratio*)))))))

(defn- daily-user-vlm-rows [state]
  (let [rows (or (get-in state [:portfolio :user-fees :dailyUserVlm])
                 (get-in state [:portfolio :user-fees :daily-user-vlm]))]
    (if (sequential? rows)
      rows
      [])))

(defn- daily-user-vlm-row-volume [row]
  (cond
    (map? row)
    (let [exchange (optional-number (:exchange row))
          user-cross (optional-number (:userCross row))
          user-add (optional-number (:userAdd row))]
      (if (or (number? user-cross)
              (number? user-add))
        (+ (or user-cross 0)
           (or user-add 0))
        (or exchange 0)))

    (and (sequential? row)
         (>= (count row) 2))
    (number-or-zero (second row))

    :else
    0))

(defn- volume-14d-usd-from-user-fees [state]
  (let [rows (daily-user-vlm-rows state)]
    (when (seq rows)
      (reduce (fn [acc row]
                (+ acc (daily-user-vlm-row-volume row)))
              0
              (butlast rows)))))

(defn- fees-from-user-fees [user-fees]
  (let [referral-discount (number-or-zero (:activeReferralDiscount user-fees))
        cross-rate (optional-number (:userCrossRate user-fees))
        add-rate (optional-number (:userAddRate user-fees))
        adjusted-cross-rate (when (number? cross-rate)
                              (* cross-rate (- 1 referral-discount)))
        adjusted-add-rate (when (number? add-rate)
                            (if (pos? add-rate)
                              (* add-rate (- 1 referral-discount))
                              add-rate))]
    (when (and (number? adjusted-cross-rate)
               (number? adjusted-add-rate))
      {:taker (* 100 adjusted-cross-rate)
       :maker (* 100 adjusted-add-rate)})))

(defn- top-up-abstraction-enabled? [state]
  (= :unified (get-in state [:account :mode])))

(defn- earn-balance [state]
  (number-or-zero (get-in state [:borrow-lend :total-supplied-usd])))

(defn- vault-equity [state summary]
  (or (optional-number (get-in state [:webdata2 :totalVaultEquity]))
      (optional-number (:totalVaultEquity summary))
      0))

(defn- perp-account-equity [state metrics]
  (or (optional-number (get-in state [:webdata2 :clearinghouseState :marginSummary :accountValue]))
      (optional-number (get-in state [:webdata2 :clearinghouseState :crossMarginSummary :accountValue]))
      (optional-number (:cross-account-value metrics))
      (optional-number (:perps-value metrics))
      0))

(defn- spot-account-equity [metrics]
  (number-or-zero (:spot-equity metrics)))

(defn- staking-account-hype [state]
  (or (optional-number (get-in state [:staking :total-hype]))
      (optional-number (get-in state [:staking :total]))
      0))

(defn- staking-value-usd [_state _staking-hype]
  0)

(defn- compute-total-equity
  [{:keys [top-up-enabled?
           vault-equity
           spot-equity
           staking-value-usd
           perp-equity
           earn-equity]}]
  (let [base-total (+ (number-or-zero vault-equity)
                      (number-or-zero spot-equity)
                      (number-or-zero staking-value-usd))]
    (if top-up-enabled?
      base-total
      (+ base-total
         (number-or-zero perp-equity)
         (number-or-zero earn-equity)))))

(defn portfolio-vm [state]
  (let [metrics (account-equity-view/account-equity-metrics state)
        summary-by-key (normalize-summary-by-key (get-in state [:portfolio :summary-by-key]))
        summary-scope (portfolio-actions/normalize-summary-scope
                       (get-in state [:portfolio-ui :summary-scope]
                               portfolio-actions/default-summary-scope))
        summary-time-range (portfolio-actions/normalize-summary-time-range
                            (get-in state [:portfolio-ui :summary-time-range]
                                    portfolio-actions/default-summary-time-range))
        summary-entry (selected-summary-entry summary-by-key summary-scope summary-time-range)
        selected-key (selected-summary-key summary-scope summary-time-range)
        top-up-enabled? (top-up-abstraction-enabled? state)
        pnl (or (pnl-delta summary-entry)
                (optional-number (:unrealized-pnl metrics))
                0)
        volume-from-summary (or (optional-number (:vlm summary-entry))
                                (optional-number (:volume summary-entry)))
        volume-from-user-fees (volume-14d-usd-from-user-fees state)
        volume-14d (if (some? volume-from-user-fees)
                     volume-from-user-fees
                     (volume-14d-usd state))
        volume (or volume-from-summary
                   volume-14d
                   0)
        max-drawdown-pct (max-drawdown-ratio summary-entry)
        perps-equity (perp-account-equity state metrics)
        spot-equity (spot-account-equity metrics)
        vault-equity-value (vault-equity state summary-entry)
        staking-hype (staking-account-hype state)
        staking-usd (staking-value-usd state staking-hype)
        earn-equity (earn-balance state)
        total-equity (compute-total-equity {:top-up-enabled? top-up-enabled?
                                            :vault-equity vault-equity-value
                                            :spot-equity spot-equity
                                            :staking-value-usd staking-usd
                                            :perp-equity perps-equity
                                            :earn-equity earn-equity})
        fees-default {:taker (number-or-zero (:taker trading/default-fees))
                      :maker (number-or-zero (:maker trading/default-fees))}
        fees (or (fees-from-user-fees (get-in state [:portfolio :user-fees]))
                 fees-default)
        returns-benchmark-selector (returns-benchmark-selector-model state)
        performance-metrics (performance-metrics-model state
                                                       summary-entry
                                                       summary-scope
                                                       summary-time-range
                                                       returns-benchmark-selector)
        chart (build-chart-model state
                                 summary-entry
                                 summary-scope
                                 summary-time-range
                                 returns-benchmark-selector)]
    {:volume-14d-usd volume-14d
     :fees fees
     :performance-metrics performance-metrics
     :chart chart
     :selectors {:summary-scope {:value summary-scope
                                 :label (selector-option-label summary-scope-options summary-scope)
                                 :open? (boolean (get-in state [:portfolio-ui :summary-scope-dropdown-open?]))
                                 :options summary-scope-options}
                 :summary-time-range {:value summary-time-range
                                      :label (selector-option-label summary-time-range-options summary-time-range)
                                      :open? (boolean (get-in state [:portfolio-ui :summary-time-range-dropdown-open?]))
                                      :options summary-time-range-options}
                 :returns-benchmark returns-benchmark-selector}
     :summary {:selected-key selected-key
               :pnl pnl
               :volume volume
               :max-drawdown-pct max-drawdown-pct
               :total-equity total-equity
               :show-perps-account-equity? (not top-up-enabled?)
               :perps-account-equity perps-equity
               :spot-equity-label (if top-up-enabled?
                                    "Trading Equity"
                                    "Spot Account Equity")
               :spot-account-equity spot-equity
               :show-earn-balance? (not top-up-enabled?)
               :earn-balance earn-equity
               :show-vault-equity? true
               :vault-equity vault-equity-value
               :show-staking-account? true
               :staking-account-hype staking-hype}}))
