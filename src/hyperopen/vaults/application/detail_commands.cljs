(ns hyperopen.vaults.application.detail-commands
  (:require [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.vaults.detail.activity :as activity-model]
            [hyperopen.vaults.detail.types :as detail-types]
            [hyperopen.vaults.domain.ui-state :as ui-state]))

(def ^:private vault-detail-chart-hover-index-path
  [:vaults-ui :detail-chart-hover-index])

(def ^:private vault-detail-activity-sort-by-tab-path
  [:vaults-ui :detail-activity-sort-by-tab])

(def ^:private vault-detail-activity-direction-filter-path
  [:vaults-ui :detail-activity-direction-filter])

(def ^:private vault-detail-activity-filter-open-path
  [:vaults-ui :detail-activity-filter-open?])

(defn selected-vault-detail-returns-benchmark-coins
  [state]
  (let [coins (portfolio-actions/normalize-portfolio-returns-benchmark-coins
               (get-in state [:vaults-ui :detail-returns-benchmark-coins]))]
    (if (seq coins)
      coins
      (if-let [legacy-coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin
                            (get-in state [:vaults-ui :detail-returns-benchmark-coin]))]
        [legacy-coin]
        []))))

(defn selected-vault-detail-vault-benchmark-addresses
  [state]
  (->> (selected-vault-detail-returns-benchmark-coins state)
       (keep detail-types/vault-benchmark-address)
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

(defn ensure-vault-detail-vault-benchmark-effects
  [state]
  (let [addresses (selected-vault-detail-vault-benchmark-addresses state)
        metadata-needed? (or (true? (get-in state [:vaults-ui :detail-returns-benchmark-suggestions-open?]))
                             (seq addresses))]
    (into []
          (concat (when metadata-needed?
                    (vault-list-metadata-fetch-effects state))
                  (vault-benchmark-details-fetch-effects state addresses)))))

(defn- vault-detail-returns-chart-selected?
  [state]
  (= :returns
     (ui-state/normalize-vault-detail-chart-series
      (get-in state [:vaults-ui :detail-chart-series]))))

(defn- vault-detail-performance-metrics-selected?
  [state]
  (= :performance-metrics
     (ui-state/normalize-vault-detail-activity-tab
      (get-in state [:vaults-ui :detail-activity-tab]))))

(defn vault-detail-benchmark-fetch-enabled?
  [{:keys [parse-vault-route-fn]} state]
  (let [{:keys [kind]} (when (fn? parse-vault-route-fn)
                         (parse-vault-route-fn (get-in state [:router :path])))]
    (and (= :detail kind)
         (or (vault-detail-returns-chart-selected? state)
             (vault-detail-performance-metrics-selected? state)))))

(defn vault-detail-returns-benchmark-fetch-effects
  [snapshot-range benchmark-coins]
  (let [{:keys [interval bars]} (portfolio-actions/returns-benchmark-candle-request
                                 (ui-state/normalize-vault-snapshot-range snapshot-range))]
    (->> (portfolio-actions/normalize-portfolio-returns-benchmark-coins benchmark-coins)
         (remove (fn [coin]
                   (some? (detail-types/vault-benchmark-address coin))))
         (mapv (fn [coin]
                 [:effects/fetch-candle-snapshot
                  :coin coin
                  :interval interval
                  :bars bars])))))

(defn- normalize-vault-detail-returns-benchmark-search
  [value]
  (if (string? value)
    value
    (str (or value ""))))

(defn set-vault-detail-tab
  [_state tab]
  [[:effects/save [:vaults-ui :detail-tab]
    (ui-state/normalize-vault-detail-tab tab)]])

(defn set-vault-detail-activity-tab
  [_state tab]
  [[:effects/save-many [[[:vaults-ui :detail-activity-tab]
                         (ui-state/normalize-vault-detail-activity-tab tab)]
                        [vault-detail-activity-filter-open-path false]]]])

(defn sort-vault-detail-activity
  [state tab column]
  (let [tab* (ui-state/normalize-vault-detail-activity-tab tab)
        column* (activity-model/normalize-sort-column tab* column)
        current-sort (or (get-in state (conj vault-detail-activity-sort-by-tab-path tab*))
                         {})
        current-column (activity-model/normalize-sort-column tab* (:column current-sort))
        current-direction (ui-state/normalize-sort-direction (:direction current-sort))
        next-direction (if (= column* current-column)
                         (if (= :asc current-direction) :desc :asc)
                         ui-state/default-vault-detail-activity-sort-direction)]
    (if (nil? column*)
      []
      [[:effects/save-many [[(conj vault-detail-activity-sort-by-tab-path tab*)
                             {:column column*
                              :direction next-direction}]
                            [vault-detail-activity-filter-open-path false]]]])))

(defn toggle-vault-detail-activity-filter-open
  [state]
  [[:effects/save vault-detail-activity-filter-open-path
    (not (true? (get-in state vault-detail-activity-filter-open-path)))]] )

(defn close-vault-detail-activity-filter
  [_state]
  [[:effects/save vault-detail-activity-filter-open-path false]])

(defn set-vault-detail-activity-direction-filter
  [_state direction-filter]
  [[:effects/save-many [[vault-detail-activity-direction-filter-path
                         (ui-state/normalize-vault-detail-activity-direction-filter direction-filter)]
                        [vault-detail-activity-filter-open-path false]]]])

(defn set-vault-detail-chart-series
  [_deps state series]
  (let [series* (ui-state/normalize-vault-detail-chart-series series)
        snapshot-range (ui-state/normalize-vault-snapshot-range
                        (get-in state [:vaults-ui :snapshot-range]))
        projection-effect [:effects/save-many
                           [[[:vaults-ui :detail-chart-series] series*]
                            [vault-detail-chart-hover-index-path nil]]]
        fetch-effects (if (= :returns series*)
                        (vault-detail-returns-benchmark-fetch-effects
                         snapshot-range
                         (selected-vault-detail-returns-benchmark-coins state))
                        [])]
    (into [projection-effect] fetch-effects)))

(defn set-vault-detail-returns-benchmark-search
  [_state search]
  [[:effects/save
    [:vaults-ui :detail-returns-benchmark-search]
    (normalize-vault-detail-returns-benchmark-search search)]])

(defn set-vault-detail-returns-benchmark-suggestions-open
  [state open?]
  (let [open?* (boolean open?)
        projection-effect [:effects/save
                           [:vaults-ui :detail-returns-benchmark-suggestions-open?]
                           open?*]
        fetch-effects (if open?*
                        (vault-list-metadata-fetch-effects state)
                        [])]
    (into [projection-effect] fetch-effects)))

(declare clear-vault-detail-returns-benchmark)

(defn select-vault-detail-returns-benchmark
  [deps state benchmark]
  (if-let [coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin benchmark)]
    (let [snapshot-range (ui-state/normalize-vault-snapshot-range
                          (get-in state [:vaults-ui :snapshot-range]))
          selected-coins (selected-vault-detail-returns-benchmark-coins state)
          already-selected? (contains? (set selected-coins) coin)
          next-coins (if already-selected?
                       selected-coins
                       (conj selected-coins coin))
          projection-effect [:effects/save-many
                             [[[:vaults-ui :detail-returns-benchmark-coins] next-coins]
                              [[:vaults-ui :detail-returns-benchmark-coin] (first next-coins)]
                              [[:vaults-ui :detail-returns-benchmark-search] ""]
                              [[:vaults-ui :detail-returns-benchmark-suggestions-open?] true]]]
          candle-effects (if (and (not already-selected?)
                                  (vault-detail-benchmark-fetch-enabled? deps state))
                           (vault-detail-returns-benchmark-fetch-effects snapshot-range [coin])
                           [])
          benchmark-detail-effects (if already-selected?
                                     []
                                     (if-let [vault-address (detail-types/vault-benchmark-address coin)]
                                       (vault-benchmark-details-fetch-effects state [vault-address])
                                       []))]
      (into [projection-effect]
            (concat candle-effects
                    benchmark-detail-effects)))
    (clear-vault-detail-returns-benchmark state)))

(defn remove-vault-detail-returns-benchmark
  [state benchmark]
  (if-let [coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin benchmark)]
    (let [next-coins (->> (selected-vault-detail-returns-benchmark-coins state)
                          (remove #(= % coin))
                          vec)]
      [[:effects/save-many
        [[[:vaults-ui :detail-returns-benchmark-coins] next-coins]
         [[:vaults-ui :detail-returns-benchmark-coin] (first next-coins)]]]])
    []))

(defn handle-vault-detail-returns-benchmark-search-keydown
  [deps state key top-coin]
  (cond
    (= key "Enter")
    (if-let [coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin top-coin)]
      (select-vault-detail-returns-benchmark deps state coin)
      [])

    (= key "Escape")
    [[:effects/save [:vaults-ui :detail-returns-benchmark-suggestions-open?] false]]

    :else
    []))

(defn clear-vault-detail-returns-benchmark
  [_state]
  [[:effects/save-many
    [[[:vaults-ui :detail-returns-benchmark-coins] []]
     [[:vaults-ui :detail-returns-benchmark-coin] nil]
     [[:vaults-ui :detail-returns-benchmark-search] ""]
     [[:vaults-ui :detail-returns-benchmark-suggestions-open?] false]]]])

(defn- finite-number
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/Number value)
            :else js/NaN)]
    (when (and (number? n)
               (js/isFinite n))
      n)))

(defn- positive-point-count
  [value]
  (when-let [n (finite-number value)]
    (let [count* (js/Math.floor n)]
      (when (pos? count*)
        count*))))

(defn- clamp
  [value min-value max-value]
  (cond
    (< value min-value) min-value
    (> value max-value) max-value
    :else value))

(defn- hover-index-from-pointer
  [client-x bounds point-count]
  (let [point-count* (positive-point-count point-count)]
    (when point-count*
      (if (= point-count* 1)
        0
        (let [client-x* (finite-number client-x)
              left (finite-number (:left bounds))
              width (finite-number (:width bounds))]
          (when (and (number? client-x*)
                     (number? left)
                     (number? width)
                     (pos? width))
            (let [x-ratio (clamp (/ (- client-x* left) width) 0 1)
                  max-index (dec point-count*)
                  nearest-index (js/Math.round (* x-ratio max-index))]
              (clamp nearest-index 0 max-index))))))))

(defn- normalize-hover-index
  [value point-count]
  (let [point-count* (positive-point-count point-count)
        idx (finite-number value)]
    (when (and point-count*
               (number? idx))
      (let [max-index (dec point-count*)
            idx* (js/Math.floor idx)]
        (clamp idx* 0 max-index)))))

(defn set-vault-detail-chart-hover
  [state client-x bounds point-count]
  (let [current-index (normalize-hover-index (get-in state vault-detail-chart-hover-index-path)
                                             point-count)
        pointer-index (hover-index-from-pointer client-x bounds point-count)
        next-index (or pointer-index
                       current-index
                       (normalize-hover-index 0 point-count))]
    (if (= current-index next-index)
      []
      [[:effects/save vault-detail-chart-hover-index-path next-index]])))

(defn clear-vault-detail-chart-hover
  [state]
  (if (nil? (get-in state vault-detail-chart-hover-index-path))
    []
    [[:effects/save vault-detail-chart-hover-index-path nil]]))
