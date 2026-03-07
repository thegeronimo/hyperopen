(ns hyperopen.vaults.application.list-commands
  (:require [hyperopen.vaults.application.detail-commands :as detail-commands]
            [hyperopen.vaults.domain.ui-state :as ui-state]))

(def ^:private vault-detail-chart-hover-index-path
  [:vaults-ui :detail-chart-hover-index])

(def ^:private vault-filter-paths
  {:leading [:vaults-ui :filter-leading?]
   :deposited [:vaults-ui :filter-deposited?]
   :others [:vaults-ui :filter-others?]
   :closed [:vaults-ui :filter-closed?]})

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
        projection-effect [:effects/save-many
                           [[[:vaults-ui :snapshot-range] snapshot-range*]
                            [[:vaults-ui :user-vaults-page] ui-state/default-vault-user-page]
                            [vault-detail-chart-hover-index-path nil]]]
        persist-effect (when (fn? snapshot-range-save-effect-fn)
                         (snapshot-range-save-effect-fn snapshot-range*))
        fetch-effects (if (detail-commands/vault-detail-benchmark-fetch-enabled? deps state)
                        (detail-commands/vault-detail-returns-benchmark-fetch-effects
                         snapshot-range*
                         (detail-commands/selected-vault-detail-returns-benchmark-coins state))
                        [])]
    (into (cond-> [projection-effect]
            persist-effect (conj persist-effect))
          fetch-effects)))

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
