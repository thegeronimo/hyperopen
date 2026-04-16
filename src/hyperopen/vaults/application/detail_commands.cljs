(ns hyperopen.vaults.application.detail-commands
  (:require [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.vaults.domain.identity :as identity]
            [hyperopen.vaults.detail.activity :as activity-model]
            [hyperopen.vaults.detail.types :as detail-types]
            [hyperopen.vaults.application.ui-state :as ui-state]))

(def ^:private vault-detail-activity-sort-by-tab-path
  [:vaults-ui :detail-activity-sort-by-tab])

(def ^:private vault-detail-activity-direction-filter-path
  [:vaults-ui :detail-activity-direction-filter])

(def ^:private vault-detail-activity-filter-open-path
  [:vaults-ui :detail-activity-filter-open?])

(def ^:private replace-shareable-route-query-effect
  [:effects/replace-shareable-route-query])

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
    [[:effects/api-fetch-vault-index-with-cache]
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
  ([snapshot-range benchmark-coins]
   (vault-detail-returns-benchmark-fetch-effects snapshot-range benchmark-coins nil))
  ([snapshot-range benchmark-coins detail-route-vault-address]
   (let [{:keys [interval bars]} (portfolio-actions/returns-benchmark-candle-request
                                  (ui-state/normalize-vault-snapshot-range snapshot-range))
         detail-route-vault-address* (identity/normalize-vault-address detail-route-vault-address)]
     (->> (portfolio-actions/normalize-portfolio-returns-benchmark-coins benchmark-coins)
          (remove (fn [coin]
                    (some? (detail-types/vault-benchmark-address coin))))
          (mapv (fn [coin]
                  (cond-> [:effects/fetch-candle-snapshot
                           :coin coin
                           :interval interval
                           :bars bars]
                    detail-route-vault-address*
                    (conj :detail-route-vault-address detail-route-vault-address*))))))))

(defn- history-addresses
  [state vault-address]
  (let [vault-address* (identity/normalize-vault-address vault-address)]
    (->> (concat [vault-address*]
                 (identity/component-vault-addresses state vault-address*))
         (keep identity/normalize-vault-address)
         distinct
         vec)))

(defn vault-detail-activity-fetch-effects
  ([state vault-address]
   (vault-detail-activity-fetch-effects state
                                        vault-address
                                        (get-in state [:vaults-ui :detail-activity-tab])))
  ([state vault-address activity-tab]
   (let [vault-address* (identity/normalize-vault-address vault-address)
         activity-tab* (ui-state/normalize-vault-detail-activity-tab activity-tab)
         addresses (history-addresses state vault-address*)]
     (case activity-tab*
       :trade-history
       (mapv (fn [address]
               [:effects/api-fetch-vault-fills address])
             addresses)

       :funding-history
       (mapv (fn [address]
               [:effects/api-fetch-vault-funding-history address])
             addresses)

       :order-history
       (mapv (fn [address]
               [:effects/api-fetch-vault-order-history address])
             addresses)

       :deposits-withdrawals
       (if vault-address*
         [[:effects/api-fetch-vault-ledger-updates vault-address*]]
         [])

       []))))

(defn- current-route-vault-address
  [{:keys [parse-vault-route-fn]} state]
  (some-> (when (fn? parse-vault-route-fn)
            (parse-vault-route-fn (get-in state [:router :path])))
          :vault-address
          identity/normalize-vault-address))

(defn- normalize-vault-detail-returns-benchmark-search
  [value]
  (if (string? value)
    value
    (str (or value ""))))

(defn set-vault-detail-tab
  [_state tab]
  [[:effects/save [:vaults-ui :detail-tab]
    (ui-state/normalize-vault-detail-tab tab)]
   replace-shareable-route-query-effect])

(defn set-vault-detail-activity-tab
  [deps state tab]
  (let [tab* (ui-state/normalize-vault-detail-activity-tab tab)
        projection-effect [:effects/save-many [[[:vaults-ui :detail-activity-tab] tab*]
                                               [vault-detail-activity-filter-open-path false]]]
        fetch-effects (if-let [vault-address (current-route-vault-address deps state)]
                        (vault-detail-activity-fetch-effects state vault-address tab*)
                        [])]
    (into [projection-effect
           replace-shareable-route-query-effect]
          fetch-effects)))

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
                        [vault-detail-activity-filter-open-path false]]]
   replace-shareable-route-query-effect])

(defn set-vault-detail-chart-series
  [deps state series]
  (let [series* (ui-state/normalize-vault-detail-chart-series series)
        snapshot-range (ui-state/normalize-vault-snapshot-range
                        (get-in state [:vaults-ui :snapshot-range]))
        detail-route-vault-address (current-route-vault-address deps state)
        projection-effect [:effects/save-many
                           [[[:vaults-ui :detail-chart-series] series*]]]
        fetch-effects (if (= :returns series*)
                        (vault-detail-returns-benchmark-fetch-effects
                         snapshot-range
                         (selected-vault-detail-returns-benchmark-coins state)
                         detail-route-vault-address)
                        [])]
    (into [projection-effect
           replace-shareable-route-query-effect]
          fetch-effects)))

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
          detail-route-vault-address (current-route-vault-address deps state)
          selected-coins (selected-vault-detail-returns-benchmark-coins state)
          already-selected? (contains? (set selected-coins) coin)
          next-coins (if already-selected?
                       selected-coins
                       (conj selected-coins coin))
          projection-effect [:effects/save-many
                             [[[:vaults-ui :detail-returns-benchmark-coins] next-coins]
                              [[:vaults-ui :detail-returns-benchmark-coin] (first next-coins)]
                              [[:vaults-ui :detail-returns-benchmark-search] ""]
                              [[:vaults-ui :detail-returns-benchmark-suggestions-open?] false]]]
          candle-effects (if (and (not already-selected?)
                                  (vault-detail-benchmark-fetch-enabled? deps state))
                           (vault-detail-returns-benchmark-fetch-effects
                            snapshot-range
                            [coin]
                            detail-route-vault-address)
                           [])
          benchmark-detail-effects (if already-selected?
                                     []
                                     (if-let [vault-address (detail-types/vault-benchmark-address coin)]
                                       (vault-benchmark-details-fetch-effects state [vault-address])
                                       []))]
      (into [projection-effect]
            (concat [replace-shareable-route-query-effect]
                    candle-effects
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
         [[:vaults-ui :detail-returns-benchmark-coin] (first next-coins)]]]
       replace-shareable-route-query-effect])
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
     [[:vaults-ui :detail-returns-benchmark-suggestions-open?] false]]]
   replace-shareable-route-query-effect])
