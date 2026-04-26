(ns hyperopen.portfolio.optimizer.application.scenario-records-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.scenario-records :as scenario-records]))

(def solved-run
  {:request-signature {:scenario-id "draft-1"}
   :computed-at-ms 2000
   :result {:status :solved
            :expected-return 0.18
            :volatility 0.42
            :diagnostics {:turnover 0.12}
            :rebalance-preview {:status :partially-blocked
                                :summary {:ready-count 1
                                          :blocked-count 1}}}})

(deftest build-saved-scenario-record-preserves-config-run-and-summary-test
  (let [draft {:name "Core Hedge"
               :objective {:kind :max-sharpe}
               :return-model {:kind :black-litterman
                              :views [{:id "view-1"
                                       :kind :absolute
                                       :instrument-id "perp:BTC"
                                       :return 0.12
                                       :confidence 0.7
                                       :confidence-variance 0.3
                                       :weights {"perp:BTC" 1}}]}
               :risk-model {:kind :ledoit-wolf}
               :metadata {:dirty? true}}
        record (scenario-records/build-saved-scenario-record
                {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                 :scenario-id "scn_01"
                 :draft draft
                 :last-successful-run solved-run
                 :saved-at-ms 3000})]
    (is (= :saved (:status record)))
    (is (= "scn_01" (:id record)))
    (is (= "Core Hedge" (:name record)))
    (is (= false (get-in record [:config :metadata :dirty?])))
    (is (= [{:id "view-1"
             :kind :absolute
             :instrument-id "perp:BTC"
             :return 0.12
             :confidence 0.7
             :confidence-variance 0.3
             :weights {"perp:BTC" 1}}]
           (get-in record [:config :return-model :views])))
    (is (= solved-run (:saved-run record)))
    (is (= {:id "scn_01"
            :name "Core Hedge"
            :status :saved
            :objective-kind :max-sharpe
            :return-model-kind :black-litterman
            :risk-model-kind :ledoit-wolf
            :expected-return 0.18
            :volatility 0.42
            :rebalance-status :partially-blocked
            :updated-at-ms 3000}
           (scenario-records/scenario-summary record)))))

(deftest upsert-scenario-index-keeps-most-recent-id-first-test
  (let [summary {:id "scn_02"
                 :name "Newer"
                 :updated-at-ms 2000}
        index (scenario-records/upsert-scenario-index
               {:ordered-ids ["scn_01" "scn_02"]
                :by-id {"scn_01" {:id "scn_01"}
                        "scn_02" {:id "scn_02"}}}
               summary)]
    (is (= ["scn_02" "scn_01"] (:ordered-ids index)))
    (is (= summary (get-in index [:by-id "scn_02"])))))

(deftest refresh-scenario-index-summary-preserves-existing-board-order-test
  (let [summary {:id "scn_01"
                 :name "Updated Existing"
                 :updated-at-ms 3000}
        index (scenario-records/refresh-scenario-index-summary
               {:ordered-ids ["scn_02" "scn_01"]
                :by-id {"scn_01" {:id "scn_01"
                                  :name "Old Existing"}
                        "scn_02" {:id "scn_02"}}}
               summary)
        missing-index (scenario-records/refresh-scenario-index-summary
                       {:ordered-ids ["scn_02"]
                        :by-id {"scn_02" {:id "scn_02"}}}
                       summary)]
    (is (= ["scn_02" "scn_01"] (:ordered-ids index)))
    (is (= summary (get-in index [:by-id "scn_01"])))
    (is (= ["scn_01" "scn_02"] (:ordered-ids missing-index)))))

(deftest archive-scenario-record-marks-record-and-config-archived-test
  (let [record {:id "scn_01"
                :name "Core Hedge"
                :status :saved
                :config {:id "scn_01"
                         :name "Core Hedge"
                         :status :saved
                         :metadata {:dirty? false
                                    :updated-at-ms 3000}}
                :saved-run solved-run
                :updated-at-ms 3000}
        archived (scenario-records/archive-scenario-record record 5000)]
    (is (= :archived (:status archived)))
    (is (= :archived (get-in archived [:config :status])))
    (is (= 5000 (:updated-at-ms archived)))
    (is (= 5000 (get-in archived [:config :metadata :updated-at-ms])))
    (is (= false (get-in archived [:config :metadata :dirty?])))))

(deftest duplicate-scenario-record-copies-config-and-run-under-new-id-test
  (let [record {:id "scn_01"
                :name "Core Hedge"
                :address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                :status :partially-executed
                :config {:id "scn_01"
                         :name "Core Hedge"
                         :status :partially-executed
                         :metadata {:dirty? false
                                    :created-at-ms 1000
                                    :updated-at-ms 3000}}
                :saved-run solved-run
                :execution-ledger [{:row-id "row-1"}]
                :updated-at-ms 3000}
        duplicated (scenario-records/duplicate-scenario-record
                    {:source-record record
                     :scenario-id "scn_02"
                     :duplicated-at-ms 5000})]
    (is (= "scn_02" (:id duplicated)))
    (is (= "Copy of Core Hedge" (:name duplicated)))
    (is (= :saved (:status duplicated)))
    (is (= "scn_01" (:source-scenario-id duplicated)))
    (is (= "scn_02" (get-in duplicated [:config :id])))
    (is (= :saved (get-in duplicated [:config :status])))
    (is (= "Copy of Core Hedge" (get-in duplicated [:config :name])))
    (is (= 5000 (:created-at-ms duplicated)))
    (is (= 5000 (:updated-at-ms duplicated)))
    (is (= 5000 (get-in duplicated [:config :metadata :created-at-ms])))
    (is (= 5000 (get-in duplicated [:config :metadata :updated-at-ms])))
    (is (= false (get-in duplicated [:config :metadata :dirty?])))
    (is (= solved-run (:saved-run duplicated)))
    (is (= [] (:execution-ledger duplicated)))))

(deftest append-execution-ledger-updates-scenario-lifecycle-test
  (let [record {:id "scn_01"
                :name "Core Hedge"
                :status :saved
                :config {:id "scn_01"
                         :name "Core Hedge"
                         :status :saved
                         :metadata {:dirty? false
                                    :updated-at-ms 3000}}
                :saved-run solved-run
                :execution-ledger [{:attempt-id "exec_old"
                                    :status :failed}]
                :updated-at-ms 3000}
        ledger {:attempt-id "exec_5000"
                :status :partially-executed
                :completed-at-ms 5100
                :rows [{:row-id "perp:BTC"
                        :status :submitted}
                       {:row-id "spot:PURR"
                        :status :blocked}]}
        updated (scenario-records/append-execution-ledger record ledger)]
    (is (= :partially-executed (:status updated)))
    (is (= :partially-executed (get-in updated [:config :status])))
    (is (= false (get-in updated [:config :metadata :dirty?])))
    (is (= 5100 (:updated-at-ms updated)))
    (is (= 5100 (get-in updated [:config :metadata :updated-at-ms])))
    (is (= [{:attempt-id "exec_old"
             :status :failed}
            ledger]
           (:execution-ledger updated)))
    (is (= :partially-executed
           (:status (scenario-records/scenario-summary updated))))))
