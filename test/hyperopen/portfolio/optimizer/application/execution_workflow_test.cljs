(ns hyperopen.portfolio.optimizer.application.execution-workflow-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.execution-workflow :as workflow]))

(def address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(deftest order-error-message-extracts-the-exchange-error-not-the-raw-map-test
  (is (= "Order must have minimum value of $10. asset=110023"
         (workflow/order-error-message
          {:status "ok"
           :response {:type "order"
                      :data {:statuses [{:error "Order must have minimum value of $10. asset=110023"}]}}}))
      "the per-order statuses error wins over the surrounding map"))

(deftest order-error-message-handles-other-shapes-test
  (is (= "Insufficient margin" (workflow/order-error-message {:error "Insufficient margin"})))
  (is (= "boom" (workflow/order-error-message {:message "boom"})))
  (is (= "network down" (workflow/order-error-message (js/Error. "network down"))))
  (is (= "rejected" (workflow/order-error-message "rejected")))
  (is (= "The exchange rejected this order."
         (workflow/order-error-message {:foo {:bar 1}})))
  (is (= "The exchange rejected this order." (workflow/order-error-message nil))))

(def ledger
  {:attempt-id "exec_1000"
   :scenario-id "scn_submit"
   :status :executed
   :started-at-ms 1000
   :completed-at-ms 1100
   :rows [{:row-id "perp:BTC"
           :status :submitted}]})

(def scenario-record
  {:schema-version 1
   :id "scn_submit"
   :name "Submit Scenario"
   :address address
   :status :saved
   :config {:id "scn_submit"
            :name "Submit Scenario"
            :status :saved
            :metadata {:dirty? false
                       :updated-at-ms 900}}
   :execution-ledger []
   :updated-at-ms 900})

(def scenario-index
  {:ordered-ids ["scn_submit"]
   :by-id {"scn_submit" {:id "scn_submit"
                         :name "Submit Scenario"
                         :status :saved
                         :updated-at-ms 900}}})

(deftest begin-execution-state-seeds-live-run-attempt-test
  (let [attempt {:scenario-id "scn_submit"
                 :rows [{:row-id "perp:BTC" :status :ready}
                        {:row-id "spot:PURR" :status :blocked}]}
        state (workflow/begin-execution-state attempt 1000)]
    (is (= :submitting (:status state)))
    (is (= attempt (:run-attempt state)))
    (is (= [:ready :blocked] (mapv :status (get-in state [:run-attempt :rows]))))))

(deftest set-run-attempt-row-status-updates-matching-row-test
  (let [state {:portfolio
               {:optimizer
                {:execution
                 {:run-attempt {:rows [{:row-id "perp:BTC" :status :ready}
                                       {:row-id "perp:ETH" :status :ready}]}}}}}
        working (workflow/set-run-attempt-row-status state "perp:BTC" {:status :working})
        settled (workflow/set-run-attempt-row-status working "perp:BTC"
                                                     {:status :submitted})]
    (is (= [:working :ready]
           (mapv :status (get-in working [:portfolio :optimizer :execution
                                          :run-attempt :rows]))))
    (is (= [:submitted :ready]
           (mapv :status (get-in settled [:portfolio :optimizer :execution
                                          :run-attempt :rows]))))
    (is (= {:portfolio {:optimizer {:execution {:run-attempt {:rows []}}}}}
           (workflow/set-run-attempt-row-status
            {:portfolio {:optimizer {:execution {}}}} "perp:BTC" {:status :working})))))

(deftest begin-ledger-persistence-plans-scenario-load-test
  (let [result (workflow/begin-ledger-persistence {:state {:portfolio {:optimizer {}}}
                                                  :address address
                                                  :ledger ledger})]
    (is (= {:state {:portfolio {:optimizer {}}}
            :commands [{:command/type :optimizer.workflow/load-scenario
                        :source :execution-ledger
                        :scenario-id "scn_submit"}]}
           result))))

(deftest ledger-persistence-builds-ordered-save-commands-test
  (let [after-record (workflow/continue-ledger-persistence-after-record
                      {:state {:portfolio {:optimizer {:scenario-index scenario-index}}}
                       :address address
                       :ledger ledger
                       :scenario-record scenario-record})
        plan (workflow/continue-ledger-persistence-after-index
              {:state (:state after-record)
               :address address
               :ledger ledger
               :scenario-record scenario-record
               :loaded-index scenario-index})
        updated-record (:scenario-record plan)]
    (is (= [{:command/type :optimizer.workflow/load-scenario-index
             :source :execution-ledger
             :address address
             :scenario-id "scn_submit"}]
           (:commands after-record)))
    (is (= [:optimizer.workflow/save-scenario
            :optimizer.workflow/save-scenario-index]
           (mapv :command/type (:commands plan))))
    (is (= :executed (:status updated-record)))
    (is (= :executed (get-in updated-record [:config :status])))
    (is (= [ledger] (:execution-ledger updated-record)))
    (is (= :executed
           (get-in plan [:scenario-index :by-id "scn_submit" :status])))))

(deftest complete-ledger-persistence-updates-state-test
  (let [updated-record (assoc scenario-record
                              :status :executed
                              :config (assoc (:config scenario-record)
                                             :status :executed)
                              :execution-ledger [ledger])
        updated-index (assoc-in scenario-index
                                [:by-id "scn_submit" :status]
                                :executed)
        result (workflow/complete-ledger-persistence
                {:state {:portfolio {:optimizer {:scenario-index scenario-index
                                                 :draft (:config scenario-record)
                                                 :active-scenario {:loaded-id "scn_submit"
                                                                   :status :saved}}}}
                 :scenario-index updated-index
                 :scenario-record updated-record})]
    (is (= updated-index
           (get-in result [:state :portfolio :optimizer :scenario-index])))
    (is (= (:config updated-record)
           (get-in result [:state :portfolio :optimizer :draft])))
    (is (= :executed
           (get-in result [:state :portfolio :optimizer :active-scenario :status])))
    (is (= [] (:commands result)))))

(deftest apply-execution-ledger-maintains-resting-carryover-test
  (let [resting-row {:row-id "perp:ZETA"
                     :instrument-id "perp:ZETA"
                     :instrument-type :perp
                     :coin "ZETA"
                     :side :buy
                     :quantity 681.7
                     :status :resting
                     :request {:action {:type "order" :orders [{:a 42 :b true}]}}
                     :response {:status "ok"
                                :response {:data {:statuses [{:resting {:oid 333}}]}}}}
        state {:portfolio {:optimizer
                           {:execution-resting-carryover [{:oid 111 :asset-id 7}]
                            :execution {:history []}}}}
        ledger {:attempt-id "exec_2"
                :scenario-id "scn_submit"
                :status :resting
                :cancellations {:status :ok :oids [111]}
                :rows [resting-row]}
        next-state (workflow/apply-execution-ledger state ledger)
        carryover (get-in next-state
                          [:portfolio :optimizer :execution-resting-carryover])]
    (is (= [333] (mapv :oid carryover))
        "cancelled 111 pruned; this run's resting 333 recorded")
    (is (= 42 (:asset-id (first carryover)))
        "asset index captured from the frozen request for later cancellation")))
