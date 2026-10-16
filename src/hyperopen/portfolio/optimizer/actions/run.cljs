(ns hyperopen.portfolio.optimizer.actions.run
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.actions.common :as action-common]
            [hyperopen.portfolio.optimizer.actions.default-assumptions :as default-assumptions-actions]
            [hyperopen.portfolio.optimizer.application.run-identity :as run-identity]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.application.tear-sheet.sources :as tear-sheet-sources]
            [hyperopen.portfolio.optimizer.black-litterman-actions.common :as bl-common]
            [hyperopen.portfolio.optimizer.black-litterman-actions.editor-model :as bl-editor-model]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.optimizer.query-state :as optimizer-query-state]
            [hyperopen.portfolio.routes :as portfolio-routes]))

(defn set-portfolio-optimizer-results-tab
  [_state tab]
  [[:effects/save
    contracts/ui-results-tab-path
    (optimizer-query-state/normalize-results-tab tab)]
   [:effects/replace-shareable-route-query]])

(defn set-portfolio-optimizer-selected-risk-instrument
  "Selects the instrument whose contribution breakdown the Equal Risk
  correlation view explains; Allocation rows dispatch it on click. A stale id
  (result re-solved with a different universe) needs no cleanup — the
  view-model falls back to its default selection."
  [_state instrument-id]
  [[:effects/save contracts/ui-selected-risk-instrument-path instrument-id]])

(defn load-portfolio-optimizer-history-from-draft
  [state]
  (if (seq (get-in state contracts/draft-universe-path))
    [[:effects/load-portfolio-optimizer-history]]
    []))

(defn- run-pipeline-effect
  []
  [:effects/run-portfolio-optimizer-pipeline])

(defn- black-litterman-run-effects
  ;; Zero authored views is a valid run: the posterior equals the baseline
  ;; expected returns, exactly the pre-views Maximum Sharpe behavior. Only a
  ;; half-entered structured-editor draft still blocks (with its field errors).
  [state]
  (let [pending (bl-editor-model/pending-editor-view-result state)]
    (case (:status pending)
      :valid
      (conj (bl-common/save-draft-path-values
             (bl-editor-model/materialized-view-path-values pending))
            (run-pipeline-effect))

      :invalid
      (bl-common/save-ui-path-values
       (bl-editor-model/error-path-values (:errors pending)))

      [(run-pipeline-effect)])))

(defn run-portfolio-optimizer-from-draft
  [state]
  (if (seq (get-in state contracts/draft-universe-path))
    (let [run-effects (if (bl-common/black-litterman-return-model? state)
                        (black-litterman-run-effects state)
                        [(run-pipeline-effect)])
          ;; A blocked draft's Run click otherwise dead-ends in the pipeline's
          ;; readiness throw; when the backend has applicable recommendations
          ;; for the pending workflow assets, the click applies them all first
          ;; (the same funnel as the Apply-all-recommended banner) and then
          ;; runs. A runnable draft is never silently reconfigured, and a
          ;; click the Black-Litterman editor rejects (no pipeline effect in
          ;; run-effects) must not mutate assumptions as a side effect.
          auto-apply (when (and (some #{(run-pipeline-effect)} run-effects)
                                (not (:runnable? (setup-readiness/build-readiness state))))
                       (default-assumptions-actions/pending-recommended-apply-effects
                        state))]
      (if (seq auto-apply)
        (into (vec auto-apply) run-effects)
        run-effects))
    []))

(defn run-portfolio-optimizer-from-ready-draft
  [state]
  (let [{:keys [request runnable?]} (setup-readiness/build-readiness state)]
    (if runnable?
      [[:effects/run-portfolio-optimizer
        request
        (action-common/build-request-signature request)]]
      [])))

(defn- optimizer-running?
  [state]
  (or (= :running (get-in state contracts/run-state-status-path))
      (= :running (get-in state contracts/optimization-progress-status-path))))

(defn- stale-solved-run?
  [state readiness]
  (let [last-successful-run (get-in state contracts/last-successful-run-path)]
    (and (run-identity/solved-run? last-successful-run)
         (run-identity/stale-run?
          {:draft (get-in state contracts/draft-path)
           :readiness readiness
           :last-successful-run last-successful-run
           :run-state (get-in state contracts/run-state-path)
           :running? (optimizer-running? state)}))))

(defn auto-recompute-stale-portfolio-optimizer-scenario
  [state]
  (let [readiness (setup-readiness/build-readiness state)
        request (:request readiness)
        request-signature (when request
                            (action-common/build-request-signature request))
        input-signature (when request
                          (contracts/optimizer-input-signature request))
        last-requested-input-signature
        (get-in state
                (conj contracts/ui-stale-auto-recompute-path
                      :input-signature))]
    (if (and request-signature
             input-signature
             (not (optimizer-running? state))
             (seq (get-in state contracts/draft-universe-path))
             (stale-solved-run? state readiness)
             (not= input-signature last-requested-input-signature))
      [[:effects/save
        contracts/ui-stale-auto-recompute-path
        {:request-signature request-signature
         :input-signature input-signature
         :scenario-id (:scenario-id request)}]
       (run-pipeline-effect)]
      [])))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- scenario-save-default-name
  [state]
  (or (non-blank-text (get-in state contracts/active-scenario-name-path))
      (non-blank-text (get-in state contracts/draft-name-path))
      "Untitled Optimization"))

(defn- open-scenario-save-modal-effect
  [state]
  [:effects/save
   contracts/scenario-save-modal-path
   {:open? true
    :name (scenario-save-default-name state)
    :error nil}])

(defn open-portfolio-optimizer-scenario-save-modal
  [state]
  [(open-scenario-save-modal-effect state)])

(defn close-portfolio-optimizer-scenario-save-modal
  [_state]
  [[:effects/save
    contracts/scenario-save-modal-path
    (optimizer-defaults/default-scenario-save-modal-state)]])

(defn set-portfolio-optimizer-scenario-save-name
  [_state value]
  [[:effects/save
    (conj contracts/scenario-save-modal-path :name)
    value]])

(defn save-portfolio-optimizer-scenario-from-current
  [state]
  [(open-scenario-save-modal-effect state)])

(defn confirm-portfolio-optimizer-scenario-save
  ;; Saving needs a name, nothing else: a scenario without a current solved run
  ;; persists as a setup-only snapshot (the workflow attaches the results
  ;; snapshot only when it still matches the draft).
  [state]
  (let [scenario-name (non-blank-text
                       (get-in state
                               (conj contracts/scenario-save-modal-path :name)))]
    (if (nil? scenario-name)
      [[:effects/save
        contracts/scenario-save-modal-error-path
        "Enter a scenario name before saving."]]
      [[:effects/save contracts/scenario-save-modal-error-path nil]
       [:effects/save-portfolio-optimizer-scenario {:name scenario-name}]])))

(defn- asset-selector-market-fetch-effects
  [state]
  (if (= :full (get-in state [:asset-selector :phase]))
    []
    [[:effects/fetch-asset-selector-markets {:phase :full}]]))

(defn- history-discovery-effects
  [route]
  (if (contains? #{:optimize-new :optimize-scenario}
                 (:kind route))
    [[:effects/load-portfolio-optimizer-history-discovery]]
    []))

(defn load-portfolio-optimizer-route
  [state path]
  (let [route (portfolio-routes/parse-portfolio-route path)
        optimizer-route? (contains? #{:optimize-new :optimize-scenario}
                                    (:kind route))]
    (cond-> (into
             (case (:kind route)
               ;; The workspace hosts the Scenarios menu, so arriving there loads
               ;; the wallet's saved-scenario index alongside the draft machinery.
               :optimize-new [[:effects/load-portfolio-optimizer-scenario-index]]
               :optimize-scenario [[:effects/load-portfolio-optimizer-scenario
                                    (:scenario-id route)]]
               [])
             (if optimizer-route?
               (into (history-discovery-effects route)
                     (into (asset-selector-market-fetch-effects state)
                           (action-common/vault-list-metadata-fetch-effects state)))
               []))
      ;; Hydrate the wallet's remembered constraint profiles, return-view
      ;; library, and history-assumption library (and, if the draft is
      ;; pristine, seed the draft from the default for this universe), so the
      ;; trader stops re-entering them.
      optimizer-route?
      (conj [:effects/load-portfolio-optimizer-constraint-profiles]
            [:effects/load-portfolio-optimizer-view-library]
            [:effects/load-portfolio-optimizer-assumption-library])

      ;; The hypothetical tear sheet compares against HLP and BTC.
      (= :tear-sheet (:view route))
      (into (tear-sheet-sources/benchmark-fetch-effects state)))))

(defn archive-portfolio-optimizer-scenario
  [_state scenario-id]
  (action-common/scenario-id-effect
   :effects/archive-portfolio-optimizer-scenario
   scenario-id))

(defn duplicate-portfolio-optimizer-scenario
  [_state scenario-id]
  (action-common/scenario-id-effect
   :effects/duplicate-portfolio-optimizer-scenario
   scenario-id))

(defn run-portfolio-optimizer
  [_state request request-signature]
  [[:effects/run-portfolio-optimizer request request-signature]])
