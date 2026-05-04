(ns hyperopen.portfolio.optimizer.actions.universe
  (:require [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.history-prefetch :as history-prefetch]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.portfolio.optimizer.black-litterman-actions.views :as black-litterman-views]
            [hyperopen.portfolio.optimizer.universe-keyboard :as universe-keyboard]))

(defn set-portfolio-optimizer-universe-search-query
  [_state query]
  [[:effects/save-many
    [[[:portfolio-ui :optimizer :universe-search-query]
      (or (some-> query str) "")]
     [[:portfolio-ui :optimizer :universe-search-active-index]
      0]]]])

(declare add-portfolio-optimizer-universe-instrument)

(defn handle-portfolio-optimizer-universe-search-keydown
  [state key market-keys]
  (universe-keyboard/handle-keydown add-portfolio-optimizer-universe-instrument state key market-keys))

(defn- with-prefetch-effect
  [effects prefetch-plan]
  (cond-> effects
    (:start? prefetch-plan)
    (conj history-prefetch/selection-prefetch-effect)))

(defn- with-prefetch-path-value
  [path-values prefetch-plan]
  (cond-> path-values
    (:changed? prefetch-plan)
    (conj [[:portfolio :optimizer :history-prefetch]
           (:state prefetch-plan)])))

(defn add-portfolio-optimizer-universe-instrument
  [state market-key]
  (let [market-key* (common/non-blank-text market-key)
        universe (common/draft-universe state)
        market (or (get-in state [:asset-selector :market-by-key market-key*])
                   (when-let [vault-address (universe-candidates/vault-address-from-instrument-id
                                             market-key*)]
                     (some (fn [row]
                             (when (= vault-address
                                      (universe-candidates/normalize-vault-address
                                       (:vault-address row)))
                               (universe-candidates/vault-row->candidate row)))
                           (get-in state [:vaults :merged-index-rows]))))
        instrument (common/market->universe-instrument market)
        instrument-id (:instrument-id instrument)]
    (if (and instrument
             (not (common/instrument-present? universe instrument-id)))
      (let [prefetch-plan (history-prefetch/enqueue-missing-instruments
                           state
                           [instrument])
            path-values (with-prefetch-path-value
                          [[[:portfolio :optimizer :draft :universe] (conj universe instrument)]
                           [[:portfolio-ui :optimizer :universe-search-query] ""]
                           [[:portfolio-ui :optimizer :universe-search-active-index] 0]]
                          prefetch-plan)]
        (with-prefetch-effect
          (common/save-draft-path-values path-values)
          prefetch-plan))
      [])))

(defn- black-litterman-universe-path-values
  [state universe*]
  (let [ids (set (keep :instrument-id universe*))
        return-model (get-in state [:portfolio :optimizer :draft :return-model])
        views (vec (:views return-model))
        views* (vec (filter (fn [view]
                              (every? ids
                                      (black-litterman-views/view-instrument-ids view)))
                            views))
        draft-path (fn [kind field]
                     [:portfolio-ui :optimizer :black-litterman-editor :drafts kind field])
        clear-if-missing (fn [kind field]
                           (let [value (get-in state (draft-path kind field))]
                             (when (and value (not (contains? ids value)))
                               [(draft-path kind field) nil])))]
    (cond-> []
      (= :black-litterman (:kind return-model))
      (conj [[:portfolio :optimizer :draft :return-model :views] views*])

      :always
      (into (keep identity
                  [(clear-if-missing :absolute :instrument-id)
                   (clear-if-missing :relative :instrument-id)
                   (clear-if-missing :relative :comparator-instrument-id)])))))

(defn remove-portfolio-optimizer-universe-instrument
  [state instrument-id]
  (let [instrument-id* (common/non-blank-text instrument-id)
        universe (common/draft-universe state)
        universe* (vec (remove #(= instrument-id* (:instrument-id %)) universe))
        constraints (get-in state [:portfolio :optimizer :draft :constraints])]
    (if (and instrument-id*
             (not= universe universe*))
      (let [prefetch-state (history-prefetch/remove-instrument state instrument-id*)
            prefetch-changed? (not= (history-prefetch/prefetch-state state)
                                    prefetch-state)
            path-values (cond-> (into [[[:portfolio :optimizer :draft :universe] universe*]
                                        [[:portfolio :optimizer :draft :constraints :allowlist]
                                         (common/set-membership (vec (:allowlist constraints)) instrument-id* false)]
                                        [[:portfolio :optimizer :draft :constraints :blocklist]
                                         (common/set-membership (vec (:blocklist constraints)) instrument-id* false)]
                                        [[:portfolio :optimizer :draft :constraints :held-locks]
                                         (common/set-membership (vec (:held-locks constraints)) instrument-id* false)]
                                        [[:portfolio :optimizer :draft :constraints :asset-overrides]
                                         (dissoc (or (:asset-overrides constraints) {}) instrument-id*)]
                                        [[:portfolio :optimizer :draft :constraints :perp-leverage]
                                         (dissoc (or (:perp-leverage constraints) {}) instrument-id*)]]
                                       (black-litterman-universe-path-values state universe*))
                          prefetch-changed?
                          (conj [[:portfolio :optimizer :history-prefetch] prefetch-state]))]
        (common/save-draft-path-values path-values))
      [])))

(defn set-portfolio-optimizer-universe-from-current
  [state]
  (let [snapshot (current-portfolio/current-portfolio-snapshot state)
        universe (->> (:exposures snapshot)
                      (keep common/exposure->universe-instrument)
                      common/dedupe-instruments)]
    (if (seq universe)
      (let [prefetch-state (history-prefetch/cleanup-to-instrument-ids
                            (history-prefetch/prefetch-state state)
                            (keep :instrument-id universe))
            prefetch-base-state (assoc-in state
                                          [:portfolio :optimizer :history-prefetch]
                                          prefetch-state)
            prefetch-plan (history-prefetch/enqueue-missing-instruments
                           prefetch-base-state
                           universe)
            prefetch-changed? (or (not= (history-prefetch/prefetch-state state)
                                        prefetch-state)
                                  (:changed? prefetch-plan))
            path-values (cond-> (into [[[:portfolio :optimizer :draft :universe] universe]]
                                      (black-litterman-universe-path-values state universe))
                          prefetch-changed?
                          (conj [[:portfolio :optimizer :history-prefetch]
                                 (:state prefetch-plan)]))]
        (with-prefetch-effect
          (common/save-draft-path-values path-values)
          prefetch-plan))
      [])))
