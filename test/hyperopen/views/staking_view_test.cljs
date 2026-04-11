(ns hyperopen.views.staking-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.staking.vm :as staking-vm]
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

(defn- find-node-by-data-role
  [data-role node]
  (find-node #(= data-role (get-in % [1 :data-role])) node))

(defn- find-button-by-text
  [label node]
  (find-node #(and (= :button (first %))
                   (some #{label} (collect-strings %)))
             node))

(defn- find-input-by-id
  [input-id node]
  (find-node #(= input-id (get-in % [1 :id])) node))

(defn- find-input-by-placeholder
  [placeholder node]
  (find-node #(= placeholder (get-in % [1 :placeholder])) node))

(defn- validator-address
  [idx]
  (let [suffix (str idx)
        zeros (apply str (repeat (- 40 (count suffix)) "0"))]
    (str "0x" zeros suffix)))

(defn- validator-row
  [idx]
  {:validator (validator-address idx)
   :name (str "Validator " idx)
   :description (str "Description " idx)
   :stake (- 1000 idx)
   :is-active? true
   :is-jailed? false
   :commission 0.01
   :stats {:week {:uptime-fraction 1
                  :predicted-apr 0.02
                  :sample-count 7}}})

(defn- base-connected-state
  ([] (base-connected-state {}))
  ([overrides]
   (merge-with merge
               {:wallet {:connected? true
                         :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
                :staking {:validator-summaries []}}
               overrides)))

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
              (base-connected-state
               {:staking {:validator-summaries [{:validator validator
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
                                              :total-pending-withdrawal 0}}}))
        table (find-node-by-data-role "staking-validator-table" view)
        transfer-button (find-node-by-data-role "staking-action-transfer-button" view)
        stake-sort-header (find-node-by-data-role "staking-sort-header-stake" view)
        timeframe-trigger (find-node-by-data-role "staking-timeframe-menu-trigger" view)
        timeframe-option-day (find-node-by-data-role "staking-timeframe-option-day" view)
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
    (is (some? timeframe-trigger))
    (is (= [[:actions/toggle-staking-validator-timeframe-menu]]
           (get-in timeframe-trigger [1 :on :click])))
    (is (some? timeframe-option-day))
    (is (= [[:actions/set-staking-validator-timeframe :day]]
           (get-in timeframe-option-day [1 :on :click])))
    (is (some? row))
    (is (= [[:actions/select-staking-validator validator]]
           (get-in row [1 :on :click])))))

(deftest staking-view-jailed-validator-renders-inactive-with-tooltip-test
  (let [validator "0x1111111111111111111111111111111111111111"
        view (staking-view/staking-view
              (base-connected-state
               {:staking {:validator-summaries [{:validator validator
                                                :name "Jailed Validator"
                                                :description "Validator with low stake"
                                                :stake 10
                                                :is-active? false
                                                :is-jailed? true
                                                :commission 0.03
                                                :stats {:week {:uptime-fraction 0.2
                                                               :predicted-apr 0
                                                               :sample-count 7}}}]}}))
        inactive-label (find-node-by-data-role "staking-validator-status-inactive" view)
        tooltip-panel (find-node-by-data-role "staking-validator-status-tooltip" view)
        strings (set (collect-strings view))]
    (is (some? inactive-label))
    (is (contains? strings "Inactive"))
    (is (some? tooltip-panel))
    (is (contains? strings
                   "The validator does not have enough stake to participate in the active validator set."))))

(deftest staking-view-validator-description-renders-hover-tooltip-test
  (let [validator "0x2222222222222222222222222222222222222222"
        description "Trusted infrastructure for institutions. This node combines FalconX's leading trading and prime platform with Chorus One's institutional-grade staking."
        view (staking-view/staking-view
              (base-connected-state
               {:staking {:validator-summaries [{:validator validator
                                                :name "FalconX <> Chorus One"
                                                :description description
                                                :stake 3763524
                                                :is-active? true
                                                :is-jailed? false
                                                :commission 0.03
                                                :stats {:week {:uptime-fraction 1
                                                               :predicted-apr 0.0218
                                                               :sample-count 7}}}]}}))
        description-tooltip (find-node-by-data-role "staking-validator-description-tooltip" view)
        strings (set (collect-strings view))]
    (is (some? description-tooltip))
    (is (contains? strings description))))

(deftest staking-view-renders-validator-pagination-and-total-count-test
  (let [validator-rows (mapv validator-row (range 30))
        view (staking-view/staking-view
              (base-connected-state
               {:staking {:validator-summaries validator-rows}}))
        next-page-button (find-node-by-data-role "staking-validator-page-next" view)
        view-all-toggle (find-node-by-data-role "staking-validator-toggle-view-all" view)
        strings (set (collect-strings view))]
    (is (some? next-page-button))
    (is (some? view-all-toggle))
    (is (= [[:actions/set-staking-validator-show-all true]]
           (get-in view-all-toggle [1 :on :click])))
    (is (= [[:actions/set-staking-validator-page 1]]
           (get-in next-page-button [1 :on :click])))
    (is (contains? strings "1-25 of 30"))
    (is (contains? strings "Validator 0"))
    (is (not (contains? strings "Validator 26"))))

  (let [validator-rows (mapv validator-row (range 30))
        view (staking-view/staking-view
              (base-connected-state
               {:staking-ui {:validator-show-all? true}
                :staking {:validator-summaries validator-rows}}))
        view-all-toggle (find-node-by-data-role "staking-validator-toggle-view-all" view)
        strings (set (collect-strings view))]
    (is (= [[:actions/set-staking-validator-show-all false]]
           (get-in view-all-toggle [1 :on :click])))
    (is (contains? strings "1-30 of 30"))
    (is (contains? strings "Validator 26"))))

(deftest staking-vm-clamps-page-and-falls-back-selected-validator-and-transfer-balance-test
  (let [validator-rows (mapv validator-row (range 30))
        delegated-validator (validator-address 29)
        view-model (staking-vm/staking-vm
                    (base-connected-state
                     {:staking-ui {:validator-page 999}
                      :staking {:validator-summaries validator-rows
                                :delegations [{:validator delegated-validator
                                               :amount 1.25}]
                                :delegator-summary {:delegated 1.25
                                                    :undelegated 3
                                                    :total-pending-withdrawal 0}}
                      :spot {:clearinghouse-state {:balances [{:coin "hype"
                                                              :total "5"
                                                              :hold "3"}]}}}))]
    (is (= 1 (:validator-page view-model)))
    (is (= 2 (:validator-page-count view-model)))
    (is (= 26 (:validator-page-range-start view-model)))
    (is (= 30 (:validator-page-range-end view-model)))
    (is (= delegated-validator (:selected-validator view-model)))
    (is (= 2 (get-in view-model [:balances :available-transfer])))
    (is (= 5 (count (:validators view-model))))
    (is (= delegated-validator
           (get-in view-model [:validators 4 :validator])))))

(deftest staking-view-renders-open-transfer-popover-actions-test
  (let [view (staking-view/staking-view
              (base-connected-state
               {:staking-ui {:action-popover {:open? true
                                              :kind :transfer}
                             :transfer-direction :spot->staking
                             :deposit-amount "12.5"
                             :submitting {:deposit? true}}
                :staking {:delegator-summary {:delegated 3
                                              :undelegated 4
                                              :total-pending-withdrawal 1.25}}}))
        popover (find-node-by-data-role "staking-action-popover" view)
        direction-toggle (find-node-by-data-role "staking-transfer-direction-toggle" view)
        amount-input (find-input-by-id "staking-transfer-amount" view)
        max-button (find-button-by-text "MAX" view)
        strings (set (collect-strings view))]
    (is (some? popover))
    (is (some? direction-toggle))
    (is (= [[:actions/set-staking-transfer-direction :staking->spot]]
           (get-in direction-toggle [1 :on :click])))
    (is (= "12.5" (get-in amount-input [1 :value])))
    (is (= [:actions/set-staking-deposit-amount-to-max]
           (get-in max-button [1 :on :click])))
    (is (contains? strings "Transfer HYPE"))
    (is (contains? strings "Submitting..."))
    (is (contains? strings "Transfer HYPE between your staking and spot balances."))))

(deftest staking-view-renders-open-stake-popover-validator-selection-test
  (let [validator (validator-address 7)
        view (staking-view/staking-view
              (base-connected-state
               {:staking-ui {:action-popover {:open? true
                                              :kind :stake}
                             :delegate-amount "2.25"
                             :selected-validator validator
                             :validator-dropdown-open? true}
                :staking {:validator-summaries [(assoc (validator-row 7)
                                                       :validator validator
                                                       :name "Alpha Validator"
                                                       :stake 7500)]
                          :delegations [{:validator validator
                                         :amount 2.25}]
                          :delegator-summary {:delegated 2.25
                                              :undelegated 5}}}))
        popover (find-node-by-data-role "staking-action-popover" view)
        validator-input (find-input-by-placeholder "Alpha Validator" view)
        strings (set (collect-strings view))]
    (is (some? popover))
    (is (some? validator-input))
    (is (contains? strings "Stake"))
    (is (contains? strings "✓ Alpha Validator"))
    (is (contains? strings "The staking lockup period is 1 day."))))

(deftest staking-view-renders-open-unstake-popover-empty-validator-search-test
  (let [view (staking-view/staking-view
              (base-connected-state
               {:staking-ui {:action-popover {:open? true
                                              :kind :unstake}
                             :undelegate-amount "1.0"
                             :validator-dropdown-open? true
                             :validator-search-query "zzz"}
                :staking {:validator-summaries [(assoc (validator-row 1)
                                                       :name "Alpha Validator")]}}))
        max-button (find-button-by-text "MAX" view)
        strings (set (collect-strings view))]
    (is (= [:actions/set-staking-undelegate-amount-to-max]
           (get-in max-button [1 :on :click])))
    (is (contains? strings "Unstake"))
    (is (contains? strings "No validators found"))))

(deftest staking-view-renders-reward-history-states-test
  (let [loaded-view (staking-view/staking-view
                     (base-connected-state
                      {:staking-ui {:active-tab :staking-reward-history}
                       :staking {:rewards [{:time-ms 1700000000000
                                            :source :alpha
                                            :total-amount 1.5}]}}))
        loading-view (staking-view/staking-view
                      (base-connected-state
                       {:staking-ui {:active-tab :staking-reward-history}
                        :staking {:loading {:rewards true}}}))
        empty-view (staking-view/staking-view
                    (base-connected-state
                     {:staking-ui {:active-tab :staking-reward-history}}))]
    (is (contains? (set (collect-strings loaded-view)) "alpha"))
    (is (contains? (set (collect-strings loaded-view)) "1.50000000 HYPE"))
    (is (contains? (set (collect-strings loading-view)) "Loading staking rewards..."))
    (is (contains? (set (collect-strings empty-view)) "No staking rewards found."))))

(deftest staking-view-renders-action-history-states-test
  (let [hash "0x1234567890abcdef"
        loaded-view (staking-view/staking-view
                     (base-connected-state
                      {:staking-ui {:active-tab :staking-action-history}
                       :staking {:history [{:time-ms 1700000000000
                                            :hash hash
                                            :delta {:kind :withdraw
                                                    :amount 3
                                                    :phase :pending}}]}}))
        loading-view (staking-view/staking-view
                      (base-connected-state
                       {:staking-ui {:active-tab :staking-action-history}
                        :staking {:loading {:history true}}}))
        empty-view (staking-view/staking-view
                    (base-connected-state
                     {:staking-ui {:active-tab :staking-action-history}}))
        loaded-strings (set (collect-strings loaded-view))]
    (is (contains? loaded-strings "Transfer Out"))
    (is (contains? loaded-strings "3.00000000 HYPE"))
    (is (contains? loaded-strings "pending"))
    (is (contains? loaded-strings "0x12345678"))
    (is (contains? (set (collect-strings loading-view)) "Loading staking action history..."))
    (is (contains? (set (collect-strings empty-view)) "No staking actions found."))))

(deftest staking-view-renders-error-banner-and-disconnected-popover-gating-test
  (let [view (staking-view/staking-view
              {:wallet {:connected? false}
               :staking-ui {:form-error "Request failed"
                            :action-popover {:open? true
                                             :kind :transfer}}
               :staking {:validator-summaries []}})
        error-banner (find-node-by-data-role "staking-error" view)
        popover (find-node-by-data-role "staking-action-popover" view)
        strings (set (collect-strings view))]
    (is (some? error-banner))
    (is (contains? strings "Request failed"))
    (is (nil? popover))))
