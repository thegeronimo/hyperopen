(ns hyperopen.views.agent-trading-recovery-modal-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.agent-trading-recovery-modal :as modal]
            [hyperopen.views.trade.order-form.test-support :refer [collect-strings
                                                                   find-first-node]]))

(defn- node-by-role
  [view-node role]
  (find-first-node view-node #(= role (get-in % [1 :data-role]))))

(deftest recovery-modal-hides-when-closed-test
  (is (nil? (modal/agent-trading-recovery-modal-view {:wallet {:agent {}}}))))

(deftest recovery-modal-renders-message-and-actions-when-open-test
  (let [view-node (modal/agent-trading-recovery-modal-view
                   {:wallet {:agent {:recovery-modal-open? true
                                     :status :error
                                     :error "Agent wallet not recognized by Hyperliquid. Enable Trading again."}}})
        strings (set (collect-strings view-node))
        dialog (node-by-role view-node "agent-trading-recovery-modal")
        close-button (node-by-role view-node "agent-trading-recovery-modal-close")
        dismiss-button (node-by-role view-node "agent-trading-recovery-modal-dismiss")
        confirm-button (node-by-role view-node "agent-trading-recovery-modal-confirm")
        message (node-by-role view-node "agent-trading-recovery-modal-message")]
    (is (some? dialog))
    (is (contains? strings "Enable Trading Again"))
    (is (contains? strings "Hyperliquid no longer recognizes this trading setup. Re-enable trading to continue placing orders."))
    (is (contains? (set (collect-strings message))
                   "Agent wallet not recognized by Hyperliquid. Enable Trading again."))
    (is (= [[:actions/close-agent-recovery-modal]]
           (get-in close-button [1 :on :click])))
    (is (= [[:actions/close-agent-recovery-modal]]
           (get-in dismiss-button [1 :on :click])))
    (is (= [[:actions/enable-agent-trading]]
           (get-in confirm-button [1 :on :click])))))

(deftest recovery-modal-disables-primary-action-while-approval-is-pending-test
  (let [view-node (modal/agent-trading-recovery-modal-view
                   {:wallet {:agent {:recovery-modal-open? true
                                     :status :approving}}})
        confirm-button (node-by-role view-node "agent-trading-recovery-modal-confirm")]
    (is (true? (get-in confirm-button [1 :disabled])))
    (is (contains? (set (collect-strings confirm-button))
                   "Awaiting signature..."))))
