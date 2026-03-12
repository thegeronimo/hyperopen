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

(deftest staking-view-shows-establish-connection-when-wallet-is-disconnected-test
  (let [view (staking-view/staking-view {:wallet {:connected? false}
                                         :staking {:validator-summaries []}})
        connect-btn (find-node #(= "staking-establish-connection"
                                   (get-in % [1 :data-role]))
                               view)
        deposit-input (find-node #(and (= :input (first %))
                                       (= "staking-deposit-amount"
                                          (get-in % [1 :id])))
                                 view)]
    (is (some? connect-btn))
    (is (nil? deposit-input))
    (is (= [[:actions/connect-wallet]]
           (get-in connect-btn [1 :on :click])))))

(deftest staking-view-renders-validator-table-and-select-action-test
  (let [validator "0x1234567890abcdef1234567890abcdef12345678"
        view (staking-view/staking-view
              {:wallet {:connected? true
                        :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
               :staking {:validator-summaries [{:validator validator
                                               :name "Alpha"
                                               :description "Validator alpha"
                                               :stake 100
                                               :isActive true
                                               :isJailed false
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
        row (find-node #(and (= :tr (first %))
                             (= "staking-validator-row"
                                (get-in % [1 :data-role])))
                              view)]
    (is (some? table))
    (is (some? row))
    (is (= [[:actions/select-staking-validator validator]]
           (get-in row [1 :on :click])))))
