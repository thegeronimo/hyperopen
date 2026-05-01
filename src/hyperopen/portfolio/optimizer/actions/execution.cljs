(ns hyperopen.portfolio.optimizer.actions.execution
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.application.execution :as execution]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]))

(defn open-portfolio-optimizer-execution-modal
  [state]
  (let [result (get-in state [:portfolio :optimizer :last-successful-run :result])
        preview (:rebalance-preview result)]
    (if (and (= :solved (:status result))
             (map? preview))
      [[:effects/save
        [:portfolio :optimizer :execution-modal]
        {:open? true
         :plan (execution/build-execution-plan
                {:scenario-id (common/current-scenario-id state)
                 :rebalance-preview preview
                 :execution-assumptions (get-in state
                                                [:portfolio :optimizer :draft :execution-assumptions])
                 :mutations-blocked-message
                 (account-context/mutations-blocked-message state)})}]]
      [])))

(defn close-portfolio-optimizer-execution-modal
  [_state]
  [[:effects/save
    [:portfolio :optimizer :execution-modal]
    (optimizer-defaults/default-execution-modal-state)]])

(defn confirm-portfolio-optimizer-execution
  [state]
  (let [modal (get-in state [:portfolio :optimizer :execution-modal])
        plan (:plan modal)
        ready-count (get-in plan [:summary :ready-count])]
    (cond
      (not (map? plan))
      []

      (:submitting? modal)
      []

      (:execution-disabled? plan)
      [[:effects/save
        [:portfolio :optimizer :execution-modal :error]
        (or (:disabled-message plan)
            "Execution is disabled for this scenario.")]]

      (not (pos? (or ready-count 0)))
      [[:effects/save
        [:portfolio :optimizer :execution-modal :error]
        "No executable rows are ready."]]

      :else
      [[:effects/save [:portfolio :optimizer :execution-modal :submitting?] true]
       [:effects/save [:portfolio :optimizer :execution-modal :error] nil]
       [:effects/execute-portfolio-optimizer-plan plan]])))
