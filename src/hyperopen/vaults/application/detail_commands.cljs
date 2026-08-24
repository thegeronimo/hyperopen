(ns hyperopen.vaults.application.detail-commands
  (:require [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.custom-range :as custom-range]
            [hyperopen.portfolio.montecarlo.actions :as mc-actions]
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
   ;; A custom range must NOT go through `normalize-vault-snapshot-range`: that
   ;; normalizer only recognizes presets and silently collapses anything else to
   ;; the default, which would fetch a 30-day candle window for a two-year custom
   ;; span and leave the benchmark line stopping short of the chart.
   (let [range* (or (custom-range/normalize snapshot-range)
                    (ui-state/normalize-vault-snapshot-range snapshot-range))
         {:keys [interval bars]} (portfolio-actions/returns-benchmark-candle-request range*)
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

(defn set-vault-monte-carlo-control
  "Set one vault Monte Carlo control (`:sims`, `:horizon`, `:bust`, `:goal`,
  `:seed`)."
  [_state control value]
  (mc-actions/set-control-at ui-state/vault-monte-carlo-state-path control value))

(defn rerun-vault-monte-carlo
  "Bump the vault Monte Carlo run nonce so the chart replays its reveal
  animation. The simulation is deterministic, so the numbers are unchanged
  unless a control differs."
  [state]
  (mc-actions/rerun-at state ui-state/vault-monte-carlo-state-path))

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

;; --- custom chart range (design 1c: drag the context strip) --------------------------------
;;
;; The custom window lives in its OWN key rather than in [:vaults-ui :snapshot-range].
;; That preset key is shared with the vault LIST page and is persisted with
;; `(name snapshot-range)`, so writing a map into it would both leak a chart-only
;; window onto the list and throw on the next persist.

(def vault-detail-custom-range-path
  [:vaults-ui :detail-custom-range])

(def vault-detail-range-strip-path
  "Which panel currently shows the range strip: `:chart`, `:metrics`, or nil.
  See the portfolio twin for why this is a target and not a boolean."
  [:vaults-ui :detail-range-strip])

(def ^:private vault-detail-range-strip-targets
  #{:chart :metrics})

(defn normalize-vault-detail-range-strip-target
  [value]
  (when (contains? vault-detail-range-strip-targets value)
    value))

(def vault-detail-range-drag-path
  [:vaults-ui :detail-range-drag])

(defn vault-detail-custom-range
  [state]
  (custom-range/normalize (get-in state vault-detail-custom-range-path)))

(defn vault-detail-range-drag-mode
  [state]
  (get-in state vault-detail-range-drag-path))

(defn vault-detail-range-strip-target
  [state]
  (normalize-vault-detail-range-strip-target (get-in state vault-detail-range-strip-path)))

(defn effective-vault-detail-range
  "Range value the vault detail data pipeline should use: the custom window when
  one is applied, otherwise the shared preset."
  [state]
  (or (vault-detail-custom-range state)
      (ui-state/normalize-vault-snapshot-range (get-in state [:vaults-ui :snapshot-range]))))

(defn- custom-range-projection
  "Range writes always close BOTH timeframe menus. The chart and the tearsheet
  each own one, and either can open the strip — closing only the chart's leaves
  the tearsheet menu hanging open over its own strip."
  [path-values]
  [:effects/save-many
   (into (vec path-values)
         [[[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
          [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]])])

(defn open-vault-detail-custom-range
  "Custom... — reveal the strip seeded with the window on screen.

  Publishes the window and refetches: the seed is day-snapped, and a custom range
  reads its benchmark candles at the all-time interval rather than the preset's,
  so opening the editor genuinely changes what has to be fetched."
  [deps state target seed-from seed-to]
  (let [seed (custom-range/normalize {:from seed-from :to seed-to})
        target* (or (normalize-vault-detail-range-strip-target target) :chart)
        projection (custom-range-projection
                    (cond-> [[vault-detail-range-strip-path target*]
                             [vault-detail-range-drag-path nil]]
                      seed (conj [vault-detail-custom-range-path seed])))]
    (if (and seed (vault-detail-benchmark-fetch-enabled? deps state))
      (into [projection replace-shareable-route-query-effect]
            (vault-detail-returns-benchmark-fetch-effects
             seed
             (selected-vault-detail-returns-benchmark-coins state)
             (current-route-vault-address deps state)))
      (if seed
        [projection replace-shareable-route-query-effect]
        [projection]))))

(defn close-vault-detail-custom-range
  [_state]
  [(custom-range-projection [[vault-detail-range-strip-path nil]
                             [vault-detail-range-drag-path nil]])])

(defn start-vault-detail-custom-range-drag
  [state client-x bounds domain-from domain-to]
  (if-let [{:keys [mode range]}
           (custom-range/drag-begin {:client-x client-x
                                     :bounds bounds
                                     :domain (custom-range/normalize {:from domain-from
                                                                      :to domain-to})
                                     :range (vault-detail-custom-range state)})]
    [(custom-range-projection [[vault-detail-range-strip-path
                                (or (vault-detail-range-strip-target state) :chart)]
                               [vault-detail-range-drag-path mode]
                               [vault-detail-custom-range-path range]])]
    []))

(defn update-vault-detail-custom-range-drag
  "One pointer sample. Projection-only: a fetch or a URL rewrite here would fire
  dozens of times per gesture."
  [state client-x bounds buttons domain-from domain-to]
  (if-let [{:keys [mode range]}
           (custom-range/drag-move {:mode (vault-detail-range-drag-mode state)
                                    :client-x client-x
                                    :bounds bounds
                                    :buttons buttons
                                    :domain (custom-range/normalize {:from domain-from
                                                                     :to domain-to})
                                    :range (vault-detail-custom-range state)})]
    [(custom-range-projection [[vault-detail-range-drag-path mode]
                               [vault-detail-custom-range-path range]])]
    []))

(defn end-vault-detail-custom-range-drag
  "Pointer-up is the only point that pays for the gesture: the URL gains the
  shareable bounds and benchmarks refetch at a resolution covering the new span."
  [deps state]
  (if (vault-detail-range-drag-mode state)
    (let [range (vault-detail-custom-range state)
          fetch-effects (if (and range
                                 (vault-detail-benchmark-fetch-enabled? deps state))
                          (vault-detail-returns-benchmark-fetch-effects
                           range
                           (selected-vault-detail-returns-benchmark-coins state)
                           (current-route-vault-address deps state))
                          [])]
      (into [(custom-range-projection [[vault-detail-range-drag-path nil]])
             replace-shareable-route-query-effect]
            fetch-effects))
    []))

(defn set-vault-detail-chart-series
  [deps state series]
  (let [series* (ui-state/normalize-vault-detail-chart-series series)
        ;; Effective, not preset: the chart section derives the candle interval
        ;; from the window on screen, so fetching at the preset's interval while
        ;; a custom window is applied stores bars nobody reads.
        snapshot-range (effective-vault-detail-range state)
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
    ;; Effective, not preset — see set-vault-detail-chart-series.
    (let [snapshot-range (effective-vault-detail-range state)
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
