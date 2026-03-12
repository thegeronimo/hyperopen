(ns hyperopen.views.staking-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.staking-view :as staking-view]))

(defn- find-node
  [pred node]
  (cond
    (vector? node)
    (or (when (pred node) node)
        (some #(find-node pred %) (rest node)))

    (seq? node)
    (some #(find-node pred %) node)

    :else nil))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (rest node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(deftest staking-view-shows-establish-connection-when-wallet-is-disconnected-test
  (let [view (staking-view/staking-view {:wallet {:connected? false}
                                         :staking {:validator-summaries []}})
        connect-btn (find-node #(= "staking-establish-connection"
                                   (get-in % [1 :data-role]))
                               view)
        transfer-button (find-node #(= "staking-action-transfer-button"
                                       (get-in % [1 :data-role]))
                                   view)]
    (is (some? connect-btn))
    (is (nil? transfer-button))
    (is (= [[:actions/connect-wallet]]
           (get-in connect-btn [1 :on :click])))))

(deftest staking-view-renders-validator-table-and-top-action-buttons-test
  (let [validator "0x1234567890abcdef1234567890abcdef12345678"
        view (staking-view/staking-view
              {:wallet {:connected? true
                        :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
               :staking {:validator-summaries [{:validator validator
                                               :name "Alpha"
                                               :description "Validator alpha"
                                               :stake 100
                                               :is-active? true
                                               :is-jailed? false
                                               :commission 0.1
                                               :stats {:week {:uptime-fraction 0.98
                                                              :predicted-apr 0.12
                                                              :sample-count 7}}}]
                         :delegations [{:validator validator
                                        :amount 10}]
                         :delegator-summary {:delegated 10
                                             :undelegated 5
                                             :total-pending-withdrawal 0}}})
        table (find-node #(= "staking-validator-table"
                             (get-in % [1 :data-role]))
                         view)
        transfer-button (find-node #(= "staking-action-transfer-button"
                                       (get-in % [1 :data-role]))
                                   view)
        stake-sort-header (find-node #(= "staking-sort-header-stake"
                                         (get-in % [1 :data-role]))
                                     view)
        row (find-node #(and (= :tr (first %))
                             (= "staking-validator-row"
                                (get-in % [1 :data-role])))
                              view)]
    (is (some? table))
    (is (some? transfer-button))
    (is (= [[:actions/open-staking-action-popover
             :transfer
             :event.currentTarget/bounds]]
           (get-in transfer-button [1 :on :click])))
    (is (some? stake-sort-header))
    (is (= [[:actions/set-staking-validator-sort :stake]]
           (get-in stake-sort-header [1 :on :click])))
    (is (some? row))
    (is (= [[:actions/select-staking-validator validator]]
           (get-in row [1 :on :click])))))

(deftest staking-view-jailed-validator-renders-inactive-with-tooltip-test
  (let [validator "0x1111111111111111111111111111111111111111"
        view (staking-view/staking-view
              {:wallet {:connected? true
                        :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
               :staking {:validator-summaries [{:validator validator
                                               :name "Jailed Validator"
                                               :description "Validator with low stake"
                                               :stake 10
                                               :is-active? false
                                               :is-jailed? true
                                               :commission 0.03
                                               :stats {:week {:uptime-fraction 0.2
                                                              :predicted-apr 0
                                                              :sample-count 7}}}]}})
        inactive-label (find-node #(= "staking-validator-status-inactive"
                                      (get-in % [1 :data-role]))
                                  view)
        tooltip-panel (find-node #(= "staking-validator-status-tooltip"
                                     (get-in % [1 :data-role]))
                                 view)
        strings (set (collect-strings view))]
    (is (some? inactive-label))
    (is (contains? strings "Inactive"))
    (is (some? tooltip-panel))
    (is (contains? strings
                   "The validator does not have enough stake to participate in the active validator set."))))
