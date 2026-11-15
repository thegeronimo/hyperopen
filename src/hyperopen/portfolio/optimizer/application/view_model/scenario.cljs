(ns hyperopen.portfolio.optimizer.application.view-model.scenario
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.instrument-labels :as instrument-labels]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]
            [hyperopen.portfolio.optimizer.application.rebalance-preview :as rebalance-preview]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.application.view-model.refinement :as refinement-vm]
            [hyperopen.portfolio.optimizer.application.view-model.workspace :as workspace]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.optimizer.ids :as ids]
            [hyperopen.portfolio.optimizer.query-state :as optimizer-query-state]))

(def ^:private normalized-text coercion/non-blank-text)

(defn- active-tab
  [state]
  (optimizer-query-state/normalize-results-tab
   (get-in state contracts/ui-results-tab-path)))

(defn- loaded-scenario-matches-route?
  [state scenario-id]
  (= scenario-id
     (get-in state contracts/active-scenario-loaded-id-path)))

(defn- pending-route-load?
  [state scenario-id]
  (let [load-state (get-in state contracts/scenario-load-state-path)]
    (and (= scenario-id (:scenario-id load-state))
         (= :loading (:status load-state)))))

(defn- retained-workspace-run?
  [state]
  (let [loaded-id (get-in state contracts/active-scenario-loaded-id-path)
        draft-id (get-in state contracts/draft-id-path)]
    (and (some? (get-in state contracts/last-successful-run-path))
         (or (nil? loaded-id)
             (= loaded-id draft-id)))))

(defn- draft-alias?
  [scenario-id]
  (= "draft" scenario-id))

(defn- retained-workspace-route?
  [state scenario-id]
  (and (retained-workspace-run? state)
       (draft-alias? scenario-id)))

(defn route-mismatched?
  [state scenario-id]
  (let [loaded-id (get-in state contracts/active-scenario-loaded-id-path)]
    (and (not (retained-workspace-route? state scenario-id))
         (or (and (some? loaded-id)
                  (not= loaded-id scenario-id))
             (and (nil? loaded-id)
                  (or (pending-route-load? state scenario-id)
                      (retained-workspace-run? state)))))))

(defn route-loading?
  "True when the routed scenario is masked AND a load can actually resolve it.
  The \"draft\" alias has no loader — with no retained workspace run to show it
  is an idle state, never a loading one."
  [state scenario-id]
  (and (route-mismatched? state scenario-id)
       (not (draft-alias? scenario-id))))

(defn scenario-scoped-state
  [state scenario-id]
  (if (route-mismatched? state scenario-id)
    (-> state
        (assoc-in contracts/draft-path (optimizer-defaults/default-draft))
        (assoc-in contracts/last-successful-run-path nil)
        (assoc-in contracts/tracking-path (optimizer-defaults/default-tracking-state))
        (assoc-in contracts/active-scenario-path
                  (if (draft-alias? scenario-id)
                    {:loaded-id nil
                     :status :idle
                     :read-only? false}
                    {:loaded-id nil
                     :status :loading
                     :read-only? true})))
    state))

(defn scenario-name
  [state scenario-id]
  (or (when (loaded-scenario-matches-route? state scenario-id)
        (or (get-in state contracts/active-scenario-name-path)
            (get-in state contracts/draft-name-path)))
      (when (retained-workspace-route? state scenario-id)
        (or (get-in state contracts/draft-name-path)
            "Unsaved Optimization"))
      (when (= scenario-id (get-in state contracts/draft-id-path))
        (get-in state contracts/draft-name-path))
      (str "Scenario " scenario-id)))

(defn scenario-detail-model
  [state route]
  (let [scenario-id (:scenario-id route)
        loading? (route-loading? state scenario-id)
        state* (scenario-scoped-state state scenario-id)
        selected-tab (active-tab state)
        readiness (setup-readiness/build-readiness state*)
        last-successful-run (rebalance-preview/last-successful-run-with-rebalance-preview
                             (:request readiness)
                             (get-in state* contracts/last-successful-run-path))
        current-result?* (workspace/current-result? state* readiness)
        stale? (workspace/scenario-stale? state* readiness)
        optimization-progress (get-in state* contracts/optimization-progress-path)
        progress-running? (= :running (:status optimization-progress))
        run-state (get-in state* contracts/run-state-path)
        running? (or (= :running (:status run-state))
                     progress-running?)
        rerun-blocked-reason (when-not (seq (get-in state
                                             (conj contracts/draft-path :universe)))
                               "Add assets to the universe before rerunning.")]
    {:state state*
     :route route
     :scenario-id scenario-id
     :loading? loading?
     :rerun-blocked-reason rerun-blocked-reason
     :selected-tab selected-tab
     :readiness readiness
     :current-result? (boolean current-result?*)
     :stale? (boolean stale?)
     :scenario-name (scenario-name state* scenario-id)
     :draft (workspace/optimizer-draft state*)
     :result (:result last-successful-run)
     :last-successful-run last-successful-run
     :active-scenario (get-in state* contracts/active-scenario-path)
     :run-state run-state
     :optimization-progress optimization-progress
     :progress-running? progress-running?
     :running? running?
     :scenario-save-state (get-in state* contracts/scenario-save-state-path)
     :frontier-overlay-mode (get-in state* contracts/ui-frontier-overlay-mode-path)
     :constrain-frontier? (get-in state* contracts/ui-constrain-frontier-path)
     :refinement (refinement-vm/refinement-model
                  {:state state*
                   :result (:result last-successful-run)
                   :run-state run-state
                   :running? running?
                   :progress optimization-progress})}))

(defn- vault-label
  [instrument]
  (or (normalized-text (:name instrument))
      (normalized-text (:symbol instrument))
      (ids/normalize-vault-address (:vault-address instrument))
      (ids/vault-address-from-value (:coin instrument))
      (ids/vault-address-from-value (:instrument-id instrument))
      (normalized-text (:instrument-id instrument))))

(defn- universe-audit-label
  [instrument]
  (if (ids/vault-instrument? instrument)
    (vault-label instrument)
    (:instrument-id instrument)))

(defn- draft-instrument-label
  [draft instrument-id]
  (or (some (fn [instrument]
              (when (= instrument-id (:instrument-id instrument))
                (or (when (ids/vault-instrument? instrument)
                      (vault-label instrument))
                    (normalized-text (:coin instrument))
                    (normalized-text (:symbol instrument))
                    (normalized-text (:name instrument)))))
            (:universe draft))
      (some-> instrument-id
              (str/split #":")
              last)
      instrument-id))

(defn- audit-view-primary-id
  [view]
  (or (:instrument-id view)
      (:long-instrument-id view)))

(defn- audit-view-comparator-id
  [view]
  (or (:comparator-instrument-id view)
      (:short-instrument-id view)))

(defn- audit-view-row
  [draft view]
  (assoc view
         :primary-label (draft-instrument-label draft
                                                (audit-view-primary-id view))
         :comparator-label (draft-instrument-label draft
                                                   (audit-view-comparator-id view))))

(defn- proxy-model-disclosure
  "The exposure story the last solved run actually used for one proxy
  assumption, straight from the run's synthesis diagnostics: source-labeled
  prior, regression estimate, confidence q, the final modeled basket that drove
  the covariance row, and the effective modeled volatility. Guarded per asset -
  the diagnostics must describe the entry's CURRENT proxy selection, so a
  post-run edit never presents a stale basket as if it were current."
  [run-diagnostics entry labels instrument-id]
  (let [diag (get run-diagnostics instrument-id)
        selected (set (history-assumptions/proxy-instrument-ids entry))
        diag-ids (vec (:proxy-instrument-ids diag))]
    (when (and (history-assumptions/proxy? entry)
               (= :proxy (:behavior diag))
               (seq diag-ids)
               (every? selected diag-ids))
      (let [weight-rows (fn [weights]
                          (->> diag-ids
                               (keep (fn [proxy-id]
                                       (when-let [weight (get weights proxy-id)]
                                         {:instrument-id proxy-id
                                          :label (get labels proxy-id proxy-id)
                                          :weight weight})))
                               vec))]
        {:prior-source (:prior-source diag)
         :prior-rows (weight-rows (:prior-weights diag))
         :regression-rows (when (= :estimated (:regression-status diag))
                            (weight-rows (:regression-beta diag)))
         :r2 (:r2 diag)
         :sample-count (:sample-count diag)
         :confidence-q (:confidence-q diag)
         :final-rows (weight-rows (:final-beta diag))
         :effective-modeled-volatility (:effective-modeled-volatility diag)}))))

(defn- history-assumption-audit-rows
  [draft run-diagnostics]
  (let [universe (vec (or (:universe draft) []))
        instruments (into universe (or (:proxy-reference-instruments draft) []))
        labels (instrument-labels/labels-by-instrument instruments
                                                       (keep :instrument-id instruments))]
    (->> (:history-assumptions draft)
         (map (fn [[instrument-id entry]]
                (let [proxy-ids (history-assumptions/proxy-instrument-ids entry)]
                  {:instrument-id instrument-id
                   :label (get labels instrument-id instrument-id)
                   :mode (:behavior entry)
                   :expected-return (:expected-return entry)
                   :volatility (:volatility entry)
                   :max-weight (:max-weight entry)
                   :proxy-instrument-ids proxy-ids
                   :proxy-labels (mapv #(get labels % %) proxy-ids)
                   :relationship-strength (when (history-assumptions/proxy? entry)
                                            (history-assumptions/relationship-strength entry))
                   :correlation-floor (:correlation-floor entry)
                   :model (proxy-model-disclosure run-diagnostics
                                                  entry
                                                  labels
                                                  instrument-id)})))
         (sort-by :label)
         vec)))

(defn inputs-audit-model
  [state]
  (let [draft (workspace/optimizer-draft state)
        views (vec (or (get-in draft [:return-model :views]) []))
        run-diagnostics (when (workspace/solved-result? state)
                          (get-in (workspace/result state)
                                  [:risk-estimation :history-assumptions]))]
    {:draft draft
     :history-assumption-rows (history-assumption-audit-rows draft run-diagnostics)
     :scenario-id (or (get-in state contracts/active-scenario-loaded-id-path)
                      (:id draft))
     :universe (vec (or (:universe draft) []))
     :universe-rows (mapv #(assoc % :audit-label (universe-audit-label %))
                          (or (:universe draft) []))
     :objective-kind (get-in draft [:objective :kind])
     :return-model-kind (get-in draft [:return-model :kind])
     :risk-model-kind (get-in draft [:risk-model :kind])
     :constraints (:constraints draft)
     :execution-assumptions (:execution-assumptions draft)
     :views views
     :view-rows (mapv (partial audit-view-row draft) views)}))
