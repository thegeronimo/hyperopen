(ns hyperopen.portfolio.actions
  (:require [clojure.string :as str]))

(def default-summary-scope
  :all)

(def default-summary-time-range
  :month)

(def default-chart-tab
  :pnl)

(def ^:private summary-scope-options
  #{:all :perps})

(def ^:private summary-time-range-options
  #{:day :week :month :all-time})

(def ^:private chart-tab-options
  #{:account-value :pnl :returns})

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
  [value]
  (let [token (normalize-keyword-like value)
        normalized (case token
                     :alltime :all-time
                     token)]
    (if (contains? summary-time-range-options normalized)
      normalized
      default-summary-time-range)))

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

(defn returns-benchmark-candle-request
  [summary-time-range]
  (case (normalize-summary-time-range summary-time-range)
    :day {:interval :5m
          :bars 400}
    :week {:interval :15m
           :bars 800}
    :month {:interval :1h
            :bars 800}
    :all-time {:interval :1d
               :bars 5000}
    {:interval :1h
     :bars 800}))

(defn- returns-benchmark-fetch-effects
  [summary-time-range benchmark-coins]
  (let [{:keys [interval bars]} (returns-benchmark-candle-request summary-time-range)]
    (->> (normalize-portfolio-returns-benchmark-coins benchmark-coins)
         (mapv (fn [coin]
                 [:effects/fetch-candle-snapshot
                  :coin coin
                  :interval interval
                  :bars bars])))))

(defn- selector-visibility-path-values
  [open-dropdown]
  [[[:portfolio-ui :summary-scope-dropdown-open?] (= open-dropdown :scope)]
   [[:portfolio-ui :summary-time-range-dropdown-open?] (= open-dropdown :time-range)]])

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
                                             time-range*]])]
          fetch-effects)))

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
    (into [[:effects/save
            [:portfolio-ui :chart-tab]
            chart-tab*]]
          fetch-effects)))

(defn set-portfolio-returns-benchmark-search
  [_state search]
  [[:effects/save
    [:portfolio-ui :returns-benchmark-search]
    (normalize-returns-benchmark-search search)]])

(defn set-portfolio-returns-benchmark-suggestions-open
  [_state open?]
  [[:effects/save
    [:portfolio-ui :returns-benchmark-suggestions-open?]
    (boolean open?)]])

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
                              [[:portfolio-ui :returns-benchmark-suggestions-open?] true]]]
          fetch-effects (if already-selected?
                          []
                          (returns-benchmark-fetch-effects summary-time-range [coin]))]
      (into [projection-effect] fetch-effects))
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
