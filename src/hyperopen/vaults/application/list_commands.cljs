(ns hyperopen.vaults.application.list-commands
  (:require [hyperopen.vaults.application.detail-commands :as detail-commands]
            [hyperopen.vaults.domain.identity :as identity]
            [hyperopen.vaults.application.ui-state :as ui-state]))

(def ^:private vault-detail-chart-timeframe-dropdown-open-path
  [:vaults-ui :detail-chart-timeframe-dropdown-open?])

(def ^:private vault-detail-performance-metrics-timeframe-dropdown-open-path
  [:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?])

(def ^:private vault-filter-paths
  {:leading [:vaults-ui :filter-leading?]
   :deposited [:vaults-ui :filter-deposited?]
   :others [:vaults-ui :filter-others?]
   :closed [:vaults-ui :filter-closed?]})

(defn- detail-timeframe-selector-visibility-path-values
  [open-dropdown]
  [[vault-detail-chart-timeframe-dropdown-open-path
    (= open-dropdown :chart)]
   [vault-detail-performance-metrics-timeframe-dropdown-open-path
    (= open-dropdown :performance-metrics)]])

(defn- detail-timeframe-selector-projection-effect
  ([open-dropdown]
   (detail-timeframe-selector-projection-effect open-dropdown []))
  ([open-dropdown extra-path-values]
   [:effects/save-many (into (vec extra-path-values)
                             (detail-timeframe-selector-visibility-path-values open-dropdown))]))

(defn- save-vault-ui-with-user-page-reset
  [path value]
  [[:effects/save-many [[path value]
                        [[:vaults-ui :user-vaults-page] ui-state/default-vault-user-page]]]])

(defn set-vaults-search-query
  [_state query]
  (save-vault-ui-with-user-page-reset [:vaults-ui :search-query] (str (or query ""))))

(defn toggle-vaults-filter
  [state filter-key]
  (if-let [path (get vault-filter-paths filter-key)]
    (let [next-value (not (true? (get-in state path)))]
      (save-vault-ui-with-user-page-reset path next-value))
    []))

(defn set-vaults-snapshot-range
  [{:keys [snapshot-range-save-effect-fn]
    :as deps}
  state
  snapshot-range]
  (let [snapshot-range* (ui-state/normalize-vault-snapshot-range snapshot-range)
        detail-route-vault-address (some-> (when-let [parse-vault-route-fn (:parse-vault-route-fn deps)]
                                             (parse-vault-route-fn (get-in state [:router :path])))
                                           :vault-address
                                           identity/normalize-vault-address)
        projection-effect (detail-timeframe-selector-projection-effect
                           nil
                           [[[:vaults-ui :snapshot-range] snapshot-range*]
                            [[:vaults-ui :user-vaults-page] ui-state/default-vault-user-page]])
        persist-effect (when (fn? snapshot-range-save-effect-fn)
                         (snapshot-range-save-effect-fn snapshot-range*))
        fetch-effects (if (detail-commands/vault-detail-benchmark-fetch-enabled? deps state)
                        (detail-commands/vault-detail-returns-benchmark-fetch-effects
                         snapshot-range*
                         (detail-commands/selected-vault-detail-returns-benchmark-coins state)
                         detail-route-vault-address)
                        [])]
    (into (cond-> [projection-effect]
            persist-effect (conj persist-effect))
          fetch-effects)))

(defn toggle-vault-detail-chart-timeframe-dropdown
  [state]
  (let [current-visible? (boolean (get-in state vault-detail-chart-timeframe-dropdown-open-path))
        open-dropdown (when-not current-visible? :chart)]
    [(detail-timeframe-selector-projection-effect open-dropdown)]))

(defn close-vault-detail-chart-timeframe-dropdown
  [_state]
  [(detail-timeframe-selector-projection-effect nil)])

(defn toggle-vault-detail-performance-metrics-timeframe-dropdown
  [state]
  (let [current-visible? (boolean (get-in state vault-detail-performance-metrics-timeframe-dropdown-open-path))
        open-dropdown (when-not current-visible? :performance-metrics)]
    [(detail-timeframe-selector-projection-effect open-dropdown)]))

(defn close-vault-detail-performance-metrics-timeframe-dropdown
  [_state]
  [(detail-timeframe-selector-projection-effect nil)])

(defn set-vaults-sort
  [state sort-column]
  (let [column* (ui-state/normalize-vault-sort-column sort-column)
        current (or (get-in state [:vaults-ui :sort])
                    {:column ui-state/default-vault-sort-column
                     :direction ui-state/default-vault-sort-direction})
        next-direction (if (= column* (:column current))
                         (if (= :asc (:direction current)) :desc :asc)
                         :desc)]
    (save-vault-ui-with-user-page-reset
     [:vaults-ui :sort]
     {:column column*
      :direction next-direction})))

(defn set-vaults-user-page-size
  [_state page-size]
  [[:effects/save-many [[[:vaults-ui :user-vaults-page-size]
                         (ui-state/normalize-vault-user-page-size page-size)]
                        [[:vaults-ui :user-vaults-page]
                         ui-state/default-vault-user-page]
                        [[:vaults-ui :user-vaults-page-size-dropdown-open?]
                         false]]]])

(defn toggle-vaults-user-page-size-dropdown
  [state]
  [[:effects/save [:vaults-ui :user-vaults-page-size-dropdown-open?]
    (not (true? (get-in state [:vaults-ui :user-vaults-page-size-dropdown-open?])))]])

(defn close-vaults-user-page-size-dropdown
  [_state]
  [[:effects/save [:vaults-ui :user-vaults-page-size-dropdown-open?] false]])

(defn set-vaults-user-page
  [_state page max-page]
  [[:effects/save [:vaults-ui :user-vaults-page]
    (ui-state/normalize-vault-user-page page max-page)]])

(defn next-vaults-user-page
  [state max-page]
  (let [current-page (ui-state/normalize-vault-user-page
                      (get-in state [:vaults-ui :user-vaults-page]))]
    (set-vaults-user-page state (inc current-page) max-page)))

(defn prev-vaults-user-page
  [state max-page]
  (let [current-page (ui-state/normalize-vault-user-page
                      (get-in state [:vaults-ui :user-vaults-page]))]
    (set-vaults-user-page state (dec current-page) max-page)))
