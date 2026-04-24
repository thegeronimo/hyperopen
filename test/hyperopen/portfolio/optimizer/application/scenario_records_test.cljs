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
               :return-model {:kind :historical-mean}
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
    (is (= solved-run (:saved-run record)))
    (is (= {:id "scn_01"
            :name "Core Hedge"
            :status :saved
            :objective-kind :max-sharpe
            :return-model-kind :historical-mean
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
