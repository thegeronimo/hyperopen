(ns hyperopen.portfolio.optimizer.application.view-model.workspace
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.rebalance-preview :as rebalance-preview]
            [hyperopen.portfolio.optimizer.application.run-identity :as run-identity]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.routes :as portfolio-routes]))

(defn holdings-loading?
  "True while the /optimize/new default path is still waiting for account data to
  seed the universe: an untouched draft (no restored, cleared, or hand-edited
  input — a deliberate clear records a custom universe source, which makes the
  draft touched), an account whose holdings are coming, and no perp
  clearinghouse snapshot yet. Keyed off source ARRIVAL rather than \"seed
  happened\" because the seed writes nothing for an empty book — an empty
  account must resolve to the normal empty-universe copy, not load forever."
  [state route draft]
  (boolean
   (and (= :optimize-new (:kind route))
        (optimizer-defaults/untouched-draft? draft)
        (some? (account-context/effective-account-address state))
        (not (:perp? (current-portfolio/holdings-sources-signature state))))))

(defn- with-holdings-loading-reason
  "While holdings are being waited on, the honest blocker is the wait itself, not
  \"select a universe\". Only the :missing-universe reason is rewritten so every
  readiness consumer (status pill, Run CTA, readiness panel, universe panel,
  contract card) tells the same story without new plumbing."
  [readiness loading?]
  (if (and loading? (= :missing-universe (:reason readiness)))
    (assoc readiness :reason :holdings-loading)
    readiness))

(defn optimizer-draft
  [state]
  (or (get-in state contracts/draft-path)
      (optimizer-defaults/default-draft)))

(defn optimizer-running?
  [state]
  (or (= :running (get-in state contracts/run-state-status-path))
      (= :running (get-in state contracts/optimization-progress-status-path))))

(defn result
  [state]
  (get-in state contracts/last-successful-run-result-path))

(defn solved-result?
  [state]
  (= :solved (:status (result state))))

(defn scenario-stale?
  [state readiness]
  (run-identity/stale-run?
   {:draft (optimizer-draft state)
    :readiness readiness
    :run-state (get-in state contracts/run-state-path)
    :running? (optimizer-running? state)
    :last-successful-run (get-in state contracts/last-successful-run-path)}))

(defn current-result?
  [state readiness]
  (run-identity/current-solved-run?
   {:draft (optimizer-draft state)
    :readiness readiness
    :run-state (get-in state contracts/run-state-path)
    :running? (optimizer-running? state)
    :last-successful-run (get-in state contracts/last-successful-run-path)}))

(defn- retained-result-path
  [_state]
  (portfolio-routes/portfolio-optimize-scenario-path "draft"))

(defn workspace-model
  [state route]
  (let [snapshot (current-portfolio/current-portfolio-snapshot state)
        draft (optimizer-draft state)
        holdings-loading?* (holdings-loading? state route draft)
        readiness (with-holdings-loading-reason
                   (setup-readiness/build-readiness state)
                   holdings-loading?*)
        preview-snapshot (or (get-in readiness [:request :current-portfolio])
                             snapshot)
        run-state (or (get-in state contracts/run-state-path)
                      (optimizer-defaults/default-run-state))
        optimization-progress (or (get-in state contracts/optimization-progress-path)
                                  (optimizer-defaults/default-optimization-progress-state))
        progress-running? (= :running (:status optimization-progress))
        running? (or (= :running (:status run-state))
                     progress-running?)
        run-triggerable? (and (seq (:universe draft))
                              (not running?))
        last-successful-run (rebalance-preview/last-successful-run-with-rebalance-preview
                             (:request readiness)
                             (get-in state contracts/last-successful-run-path))
        current-result?* (run-identity/current-solved-run?
                          {:draft draft
                           :readiness readiness
                           :run-state run-state
                           :running? running?
                           :last-successful-run last-successful-run})
        scenario-save-state (or (get-in state contracts/scenario-save-state-path)
                                (optimizer-defaults/default-scenario-save-state))
        history-load-state (or (get-in state contracts/history-load-state-path)
                               (optimizer-defaults/default-history-load-state))]
    {:state state
     :route route
     :scenario-id (:scenario-id route)
     :snapshot snapshot
     :draft draft
     :readiness readiness
     :preview-snapshot preview-snapshot
     :run-state run-state
     :optimization-progress optimization-progress
     :progress-running? progress-running?
     :running? running?
     :run-triggerable? (boolean run-triggerable?)
     :last-successful-run last-successful-run
     :current-result? (boolean current-result?*)
     :solved-run? (boolean current-result?*)
     :scenario-save-state scenario-save-state
     :saving-scenario? (= :saving (:status scenario-save-state))
     :history-load-state history-load-state
     :editor-state (get-in state contracts/ui-black-litterman-editor-path)
     :result-path (retained-result-path state)}))
