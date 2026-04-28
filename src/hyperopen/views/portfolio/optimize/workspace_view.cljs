(ns hyperopen.views.portfolio.optimize.workspace-view
  (:require [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.portfolio.optimize.execution-modal :as execution-modal]
            [hyperopen.views.portfolio.optimize.infeasible-panel :as infeasible-panel]
            [hyperopen.views.portfolio.optimize.setup-v4-context :as setup-v4-context]
            [hyperopen.views.portfolio.optimize.setup-v4-header :as setup-v4-header]
            [hyperopen.views.portfolio.optimize.setup-v4-sections :as setup-v4]))

(defn- retained-result-path
  []
  (portfolio-routes/portfolio-optimize-scenario-path "draft"))

(defn- optimizer-draft
  [state]
  (or (get-in state [:portfolio :optimizer :draft])
      (optimizer-defaults/default-draft)))

(defn workspace-view
  [state route]
  (let [snapshot (current-portfolio/current-portfolio-snapshot state)
        draft (optimizer-draft state)
        readiness (setup-readiness/build-readiness state)
        preview-snapshot (or (get-in readiness [:request :current-portfolio])
                             snapshot)
        run-state (or (get-in state [:portfolio :optimizer :run-state])
                      (optimizer-defaults/default-run-state))
        optimization-progress (or (get-in state
                                          [:portfolio :optimizer :optimization-progress])
                                  (optimizer-defaults/default-optimization-progress-state))
        progress-running? (= :running (:status optimization-progress))
        running? (or (= :running (:status run-state))
                     progress-running?)
        run-triggerable? (and (seq (:universe draft))
                              (not running?))
        last-successful-run (get-in state [:portfolio :optimizer :last-successful-run])
        solved-run? (= :solved (get-in last-successful-run [:result :status]))
        scenario-save-state (or (get-in state [:portfolio :optimizer :scenario-save-state])
                                (optimizer-defaults/default-scenario-save-state))
        saving-scenario? (= :saving (:status scenario-save-state))
        history-load-state (or (get-in state [:portfolio :optimizer :history-load-state])
                               (optimizer-defaults/default-history-load-state))
        scenario-id (:scenario-id route)
        result-path (retained-result-path)
        infeasible-result (infeasible-panel/infeasible-result run-state)
        highlighted-controls (infeasible-panel/highlighted-control-keys infeasible-result)]
    [:section {:class ["portfolio-optimizer-v4" "space-y-3" "pb-16" "leading-4" "text-trading-text"]
               :data-role "portfolio-optimizer-setup-route-surface"
               :data-scenario-id scenario-id}
     (setup-v4-header/setup-header {:draft draft
                                    :route route
                                    :running? running?
                                    :run-triggerable? run-triggerable?
                                    :saving-scenario? saving-scenario?
                                    :solved-run? solved-run?
                                    :result-path result-path})
     (setup-v4-header/preset-row draft)
     (infeasible-panel/infeasible-banner infeasible-result highlighted-controls)
     [:section {:class ["grid"
                        "grid-cols-1"
                        "gap-5"
                        "xl:gap-6"
                        "xl:grid-cols-[minmax(420px,7fr)_minmax(0,11fr)_minmax(360px,6fr)]"]
                :data-role "portfolio-optimizer-setup-surface"}
      (setup-v4/control-rail {:state state
                              :draft draft
                              :highlighted-controls highlighted-controls})
      (setup-v4/summary-pane {:draft draft
                              :running? running?
                              :run-triggerable? run-triggerable?
                              :saving-scenario? saving-scenario?
                              :solved-run? solved-run?
                              :result-path result-path})
      (setup-v4-context/context-rail {:draft draft
                                      :readiness readiness
                                      :snapshot snapshot
                                      :preview-snapshot preview-snapshot
                                      :run-state run-state
                                      :optimization-progress optimization-progress
                                      :history-load-state history-load-state
                                      :last-successful-run last-successful-run
                                      :result-path result-path})]
     (execution-modal/execution-modal state)]))
