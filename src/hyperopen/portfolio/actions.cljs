(ns hyperopen.portfolio.actions
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]))

(def ^:private portfolio-summary-time-range-storage-key
  "portfolio-summary-time-range")

(def default-summary-scope
  :all)

(def default-summary-time-range
  :one-year)

(def default-chart-tab
  :returns)

(def ^:private summary-scope-options
  #{:all :perps})

(def ^:private summary-time-range-options
  #{:day :week :month :three-month :six-month :one-year :two-year :all-time})

(def ^:private summary-time-range-aliases
  {:alltime :all-time
   :3m :three-month
   :3-m :three-month
   :3month :three-month
   :3-month :three-month
   :threemonth :three-month
   :three-month :three-month
   :quarter :three-month
   :6m :six-month
   :6-m :six-month
   :6month :six-month
   :6-month :six-month
   :sixmonth :six-month
   :six-month :six-month
   :halfyear :six-month
   :half-year :six-month
   :1y :one-year
   :1-y :one-year
   :1year :one-year
   :1-year :one-year
   :oneyear :one-year
   :one-year :one-year
   :year :one-year
   :2y :two-year
   :2-y :two-year
   :2year :two-year
   :2-year :two-year
   :twoyear :two-year
   :two-year :two-year})

(def ^:private returns-benchmark-candle-request-by-summary-time-range
  {:day {:interval :5m
         :bars 400}
   :week {:interval :15m
          :bars 800}
   :month {:interval :1h
           :bars 800}
   :three-month {:interval :4h
                 :bars 720}
   :six-month {:interval :8h
               :bars 720}
   :one-year {:interval :12h
              :bars 900}
   :two-year {:interval :1d
              :bars 900}
   :all-time {:interval :1d
              :bars 5000}})

(def ^:private chart-tab-options
  #{:account-value :pnl :returns})

(def default-account-info-tab
  :performance-metrics)

(def ^:private account-info-tab-options
  #{:performance-metrics
    :deposits-withdrawals
    :balances
    :positions
    :open-orders
    :twap
    :trade-history
    :funding-history
    :order-history})

(def ^:private account-info-tab-aliases
  {:performancemetrics :performance-metrics
   :performancemetric :performance-metrics
   :performance :performance-metrics
   :depositswithdrawals :deposits-withdrawals
   :openorders :open-orders
   :tradehistory :trade-history
   :fundinghistory :funding-history
   :orderhistory :order-history})

(defn- normalize-keyword-like
  [value]
  (let [text (cond
               (keyword? value) (name value)
               (string? value) (str/trim value)
               :else nil)]
    (when (seq text)
      (-> text
          (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
          str/lower-case
          (str/replace #"[_\s]+" "-")
          keyword))))

(defn normalize-summary-scope
  [value]
  (let [token (normalize-keyword-like value)
        normalized (case token
                     :perp :perps
                     token)]
    (if (contains? summary-scope-options normalized)
      normalized
      default-summary-scope)))

(defn normalize-summary-time-range
  ([value]
   (normalize-summary-time-range value default-summary-time-range))
  ([value fallback]
   (let [token (normalize-keyword-like value)
         normalized (get summary-time-range-aliases token token)]
     (if (contains? summary-time-range-options normalized)
       normalized
       fallback))))

(defn normalize-portfolio-chart-tab
  [value]
  (let [token (normalize-keyword-like value)
        normalized (case token
                     :accountvalue :account-value
                     :account :account-value
                     :return :returns
                     token)]
    (if (contains? chart-tab-options normalized)
      normalized
      default-chart-tab)))

(defn normalize-portfolio-account-info-tab
  [value]
  (let [token (normalize-keyword-like value)
        normalized (get account-info-tab-aliases token token)]
    (if (contains? account-info-tab-options normalized)
      normalized
      default-account-info-tab)))

(defn normalize-portfolio-returns-benchmark-coin
  [value]
  (let [coin (cond
               (map? value) (:coin value)
               (keyword? value) (name value)
               (string? value) value
               :else nil)
        coin* (some-> coin str str/trim)]
    (when (seq coin*)
      coin*)))

(defn normalize-portfolio-returns-benchmark-coins
  [value]
  (let [source (cond
                 (sequential? value) value
                 (set? value) (seq value)
                 :else (when-let [coin (normalize-portfolio-returns-benchmark-coin value)]
                         [coin]))]
    (->> source
         (keep normalize-portfolio-returns-benchmark-coin)
         distinct
         vec)))

(defn- selected-returns-benchmark-coins
  [state]
  (let [coins (normalize-portfolio-returns-benchmark-coins
               (get-in state [:portfolio-ui :returns-benchmark-coins]))]
    (if (seq coins)
      coins
      (if-let [legacy-coin (normalize-portfolio-returns-benchmark-coin
                            (get-in state [:portfolio-ui :returns-benchmark-coin]))]
        [legacy-coin]
        []))))

(defn- normalize-returns-benchmark-search
  [value]
  (if (string? value)
    value
    (str (or value ""))))

(def ^:private vault-benchmark-prefix
  "vault:")

(defn vault-benchmark-address
  [value]
  (let [coin (normalize-portfolio-returns-benchmark-coin value)
        coin-lower (some-> coin str/lower-case)]
    (when (and (seq coin-lower)
               (str/starts-with? coin-lower vault-benchmark-prefix))
      (some-> (subs coin (count vault-benchmark-prefix))
              str
              str/trim
              str/lower-case
              not-empty))))

(defn selected-portfolio-vault-benchmark-addresses
  [state]
  (->> (selected-returns-benchmark-coins state)
       (keep vault-benchmark-address)
       distinct
       vec))

(defn- vault-list-metadata-fetch-effects
  [state]
  (if (seq (get-in state [:vaults :merged-index-rows]))
    []
    [[:effects/api-fetch-vault-index]
     [:effects/api-fetch-vault-summaries]]))

(defn- vault-benchmark-details-fetch-effects
  [state addresses]
  (->> addresses
       (remove (fn [vault-address]
                 (or (get-in state [:vaults :benchmark-details-by-address vault-address])
                     (get-in state [:vaults :details-by-address vault-address])
                     (true? (get-in state [:vaults :loading :benchmark-details-by-address vault-address])))))
       (mapv (fn [vault-address]
               [:effects/api-fetch-vault-benchmark-details vault-address]))))

(defn ensure-portfolio-vault-benchmark-effects
  [state]
  (let [addresses (selected-portfolio-vault-benchmark-addresses state)
        metadata-needed? (or (true? (get-in state [:portfolio-ui :returns-benchmark-suggestions-open?]))
                             (seq addresses))]
    (into []
          (concat (when metadata-needed?
                    (vault-list-metadata-fetch-effects state))
                  (vault-benchmark-details-fetch-effects state addresses)))))

(defn- fetchable-benchmark-coin
  [value]
  (let [coin (normalize-portfolio-returns-benchmark-coin value)
        coin-lower (some-> coin str/lower-case)]
    (when (and (seq coin)
               (not (str/starts-with? coin-lower vault-benchmark-prefix)))
      coin)))

(defn returns-benchmark-candle-request
  [summary-time-range]
  (get returns-benchmark-candle-request-by-summary-time-range
       (normalize-summary-time-range summary-time-range)
       {:interval :1h
        :bars 800}))

(defn- returns-benchmark-fetch-effects
  [summary-time-range benchmark-coins]
  (let [{:keys [interval bars]} (returns-benchmark-candle-request summary-time-range)]
    (->> (normalize-portfolio-returns-benchmark-coins benchmark-coins)
         (keep fetchable-benchmark-coin)
         (mapv (fn [coin]
                 [:effects/fetch-candle-snapshot
                  :coin coin
                  :interval interval
                  :bars bars])))))

(defn- selector-visibility-path-values
  [open-dropdown]
  [[[:portfolio-ui :summary-scope-dropdown-open?] (= open-dropdown :scope)]
   [[:portfolio-ui :summary-time-range-dropdown-open?] (= open-dropdown :time-range)]
   [[:portfolio-ui :performance-metrics-time-range-dropdown-open?]
    (= open-dropdown :performance-metrics-time-range)]])

(defn- selector-projection-effect
  ([open-dropdown]
   (selector-projection-effect open-dropdown []))
  ([open-dropdown extra-path-values]
   [:effects/save-many (into (vec extra-path-values)
                             (selector-visibility-path-values open-dropdown))]))

(defn toggle-portfolio-summary-scope-dropdown
  [state]
  (let [current-visible? (boolean (get-in state [:portfolio-ui :summary-scope-dropdown-open?]))
        open-dropdown (when-not current-visible? :scope)]
    [(selector-projection-effect open-dropdown)]))

(defn toggle-portfolio-summary-time-range-dropdown
  [state]
  (let [current-visible? (boolean (get-in state [:portfolio-ui :summary-time-range-dropdown-open?]))
        open-dropdown (when-not current-visible? :time-range)]
    [(selector-projection-effect open-dropdown)]))

(defn toggle-portfolio-performance-metrics-time-range-dropdown
  [state]
  (let [current-visible? (boolean (get-in state [:portfolio-ui :performance-metrics-time-range-dropdown-open?]))
        open-dropdown (when-not current-visible? :performance-metrics-time-range)]
    [(selector-projection-effect open-dropdown)]))

(defn select-portfolio-summary-scope
  [_state scope]
  [(selector-projection-effect nil [[[:portfolio-ui :summary-scope]
                                     (normalize-summary-scope scope)]])])

(defn select-portfolio-summary-time-range
  [state time-range]
  (let [time-range* (normalize-summary-time-range time-range)
        benchmark-coins (selected-returns-benchmark-coins state)
        fetch-effects (returns-benchmark-fetch-effects time-range* benchmark-coins)]
    (into [(selector-projection-effect nil [[[:portfolio-ui :summary-time-range]
                                             time-range*]])
           [:effects/local-storage-set
            portfolio-summary-time-range-storage-key
            (name time-range*)]]
          fetch-effects)))

(defn restore-portfolio-summary-time-range!
  [store]
  (let [summary-time-range (normalize-summary-time-range
                            (platform/local-storage-get portfolio-summary-time-range-storage-key))]
    (swap! store assoc-in [:portfolio-ui :summary-time-range] summary-time-range)))

(defn select-portfolio-chart-tab
  [state chart-tab]
  (let [chart-tab* (normalize-portfolio-chart-tab chart-tab)
        summary-time-range (normalize-summary-time-range
                            (get-in state [:portfolio-ui :summary-time-range]
                                    default-summary-time-range))
        benchmark-coins (selected-returns-benchmark-coins state)
        fetch-effects (if (= chart-tab* :returns)
                        (returns-benchmark-fetch-effects summary-time-range benchmark-coins)
                        [])]
    (into [[:effects/save-many
            [[[:portfolio-ui :chart-tab] chart-tab*]]]]
          fetch-effects)))

(defn set-portfolio-account-info-tab
  [_state tab]
  [[:effects/save
    [:portfolio-ui :account-info-tab]
    (normalize-portfolio-account-info-tab tab)]])

(defn set-portfolio-returns-benchmark-search
  [_state search]
  [[:effects/save
    [:portfolio-ui :returns-benchmark-search]
    (normalize-returns-benchmark-search search)]])

(defn set-portfolio-returns-benchmark-suggestions-open
  [state open?]
  (let [open?* (boolean open?)
        projection-effect [:effects/save
                           [:portfolio-ui :returns-benchmark-suggestions-open?]
                           open?*]
        fetch-effects (if open?*
                        (vault-list-metadata-fetch-effects state)
                        [])]
    (into [projection-effect] fetch-effects)))

(declare clear-portfolio-returns-benchmark)

(defn select-portfolio-returns-benchmark
  [state benchmark]
  (if-let [coin (normalize-portfolio-returns-benchmark-coin benchmark)]
    (let [summary-time-range (normalize-summary-time-range
                              (get-in state [:portfolio-ui :summary-time-range]
                                      default-summary-time-range))
          selected-coins (selected-returns-benchmark-coins state)
          already-selected? (contains? (set selected-coins) coin)
          next-coins (if already-selected?
                       selected-coins
                       (conj selected-coins coin))
          projection-effect [:effects/save-many
                             [[[:portfolio-ui :returns-benchmark-coins] next-coins]
                              [[:portfolio-ui :returns-benchmark-coin] (first next-coins)]
                              [[:portfolio-ui :returns-benchmark-search] ""]
                              [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]
          candle-effects (if already-selected?
                           []
                           (returns-benchmark-fetch-effects summary-time-range [coin]))
          benchmark-detail-effects (if already-selected?
                                     []
                                     (if-let [vault-address (vault-benchmark-address coin)]
                                       (vault-benchmark-details-fetch-effects state [vault-address])
                                       []))]
      (into [projection-effect]
            (concat candle-effects
                    benchmark-detail-effects)))
    (clear-portfolio-returns-benchmark state)))

(defn remove-portfolio-returns-benchmark
  [state benchmark]
  (if-let [coin (normalize-portfolio-returns-benchmark-coin benchmark)]
    (let [next-coins (->> (selected-returns-benchmark-coins state)
                          (remove #(= % coin))
                          vec)]
      [[:effects/save-many
        [[[:portfolio-ui :returns-benchmark-coins] next-coins]
         [[:portfolio-ui :returns-benchmark-coin] (first next-coins)]]]])
    []))

(defn handle-portfolio-returns-benchmark-search-keydown
  [state key top-coin]
  (cond
    (= key "Enter")
    (if-let [coin (normalize-portfolio-returns-benchmark-coin top-coin)]
      (select-portfolio-returns-benchmark state coin)
      [])

    (= key "Escape")
    [[:effects/save [:portfolio-ui :returns-benchmark-suggestions-open?] false]]

    :else
    []))

(defn clear-portfolio-returns-benchmark
  [_state]
  [[:effects/save-many
    [[[:portfolio-ui :returns-benchmark-coins] []]
     [[:portfolio-ui :returns-benchmark-coin] nil]
     [[:portfolio-ui :returns-benchmark-search] ""]
     [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]])

(defn set-portfolio-metrics-result
  [_state payload]
  [[:effects/save [:portfolio-ui :metrics-result] payload]])
