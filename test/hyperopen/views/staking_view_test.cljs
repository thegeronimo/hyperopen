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

(defn- validator-address
  [idx]
  (let [suffix (str idx)
        zeros (apply str (repeat (- 40 (count suffix)) "0"))]
    (str "0x" zeros suffix)))

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
        timeframe-trigger (find-node #(= "staking-timeframe-menu-trigger"
                                         (get-in % [1 :data-role]))
                                     view)
        timeframe-option-day (find-node #(= "staking-timeframe-option-day"
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

(deftest staking-view-validator-description-renders-hover-tooltip-test
  (let [validator "0x2222222222222222222222222222222222222222"
        description "Trusted infrastructure for institutions. This node combines FalconX's leading trading and prime platform with Chorus One's institutional-grade staking."
        view (staking-view/staking-view
              {:wallet {:connected? true
                        :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
               :staking {:validator-summaries [{:validator validator
                                               :name "FalconX <> Chorus One"
                                               :description description
                                               :stake 3763524
                                               :is-active? true
                                               :is-jailed? false
                                               :commission 0.03
                                               :stats {:week {:uptime-fraction 1
                                                              :predicted-apr 0.0218
                                                              :sample-count 7}}}]}})
        description-tooltip (find-node #(= "staking-validator-description-tooltip"
                                           (get-in % [1 :data-role]))
                                       view)
        strings (set (collect-strings view))]
    (is (some? description-tooltip))
    (is (contains? strings description))))

(deftest staking-view-renders-validator-pagination-and-total-count-test
  (let [validator-rows (mapv (fn [idx]
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
                             (range 30))
        view (staking-view/staking-view
              {:wallet {:connected? true
                        :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
               :staking {:validator-summaries validator-rows}})
        next-page-button (find-node #(= "staking-validator-page-next"
                                        (get-in % [1 :data-role]))
                                    view)
        view-all-toggle (find-node #(= "staking-validator-toggle-view-all"
                                       (get-in % [1 :data-role]))
                                   view)
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

  (let [validator-rows (mapv (fn [idx]
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
                             (range 30))
        view (staking-view/staking-view
              {:wallet {:connected? true
                        :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
               :staking-ui {:validator-show-all? true}
               :staking {:validator-summaries validator-rows}})
        view-all-toggle (find-node #(= "staking-validator-toggle-view-all"
                                       (get-in % [1 :data-role]))
                                   view)
        strings (set (collect-strings view))]
    (is (= [[:actions/set-staking-validator-show-all false]]
           (get-in view-all-toggle [1 :on :click])))
    (is (contains? strings "1-30 of 30"))
    (is (contains? strings "Validator 26"))))
