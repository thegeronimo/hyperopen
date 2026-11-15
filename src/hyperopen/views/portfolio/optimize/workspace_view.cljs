(ns hyperopen.views.portfolio.optimize.workspace-view
  (:require [hyperopen.portfolio.optimizer.application.view-model :as optimizer-view-model]
            [hyperopen.portfolio.optimizer.contracts :as optimizer-contracts]
            [hyperopen.views.portfolio.optimize.infeasible-panel :as infeasible-panel]
            [hyperopen.views.portfolio.optimize.run-status-banner :as run-status-banner]
            [hyperopen.views.portfolio.optimize.setup-context :as setup-context]
            [hyperopen.views.portfolio.optimize.setup-header :as setup-header]
            [hyperopen.views.portfolio.optimize.setup-sections :as setup]))

(defn workspace-view
  [state route]
  (let [{:keys [snapshot
                draft
                readiness
                preview-snapshot
                run-state
                optimization-progress
                running?
                run-triggerable?
                last-successful-run
                current-result?
                saving-scenario?
                history-load-state
                scenario-id
                result-path
                editor-state]} (optimizer-view-model/workspace-model state route)
        infeasible-result (infeasible-panel/infeasible-result run-state)
        highlighted-controls (infeasible-panel/highlighted-control-keys infeasible-result)]
    [:section {:class ["portfolio-optimizer" "optimizer-setup-route-surface"
                       "space-y-3" "pb-16" "leading-4" "text-trading-text"]
               :data-role "portfolio-optimizer-setup-route-surface"
               :data-scenario-id scenario-id}
     (setup-header/setup-header {:state state
                                 :draft draft
                                 :route route
                                 :draft-persist (get-in state
                                                        optimizer-contracts/draft-persist-path)
                                 :saving-scenario? saving-scenario?})
     (run-status-banner/run-status-banner {:optimization-progress optimization-progress
                                           :run-state run-state
                                           :draft draft})
     (infeasible-panel/infeasible-banner infeasible-result highlighted-controls)
     [:section {:class ["optimizer-setup-surface"
                        "grid"
                        "grid-cols-1"
                        "items-start"
                        "gap-5"
                        "xl:gap-6"
                        "xl:grid-cols-[minmax(300px,5fr)_minmax(0,12fr)_minmax(360px,7fr)]"]
                :data-role "portfolio-optimizer-setup-surface"}
      (setup/control-rail {:state state
                           :draft draft
                           :readiness readiness
                           :history-load-state history-load-state})
      (setup/policy-pane {:state state
                          :draft draft
                          :highlighted-controls highlighted-controls
                          :readiness readiness
                          :history-load-state history-load-state
                          :running? running?
                          :run-triggerable? run-triggerable?
                          :saving-scenario? saving-scenario?
                          :solved-run? current-result?
                          :result-path result-path})
      (setup-context/context-rail {:draft draft
                                   :state state
                                   :editor-state editor-state
                                   :readiness readiness
                                   :snapshot snapshot
                                   :preview-snapshot preview-snapshot
                                   :run-state run-state
                                   :optimization-progress optimization-progress
                                   :history-load-state history-load-state
                                   :last-successful-run last-successful-run
                                   :current-result? current-result?
                                   :result-path result-path})]]))
