(ns hyperopen.portfolio.optimizer.application.scenario-workflow-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.scenario-operations :as operations]
            [hyperopen.portfolio.optimizer.application.scenario-workflow :as workflow]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]))

(def address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(defn- save-state
  []
  {:router {:path "/portfolio/optimize/scn_01"}
   :wallet {:address address}
   :portfolio {:optimizer
               {:draft {:id "scn_01"
                        :name "Core Hedge"
                        :objective {:kind :max-sharpe}
                        :return-model {:kind :historical-mean}
                        :risk-model {:kind :diagonal-shrink}
                        :metadata {:dirty? true}}
                :scenario-index {:ordered-ids []
                                 :by-id {}}
                :last-successful-run
                (fixtures/sample-last-successful-run
                 {:result {:expected-return 0.18
                           :volatility 0.42}
                  :computed-at-ms 2000})}}})

(deftest begin-save-marks-save-state-and-loads-index-test
  (let [result (workflow/begin-save {:state (save-state)
                                     :address address
                                     :scenario-id "scn_01"
                                     :started-at-ms 3000})]
    (is (= {:status :saving
            :scenario-id "scn_01"
            :started-at-ms 3000
            :completed-at-ms nil
            :error nil}
           (get-in result [:state :portfolio :optimizer :scenario-save-state])))
    (is (= [{:command/type :optimizer.workflow/load-scenario-index
             :address address
             :scenario-id "scn_01"
             :started-at-ms 3000}]
           (:commands result)))))

(deftest continue-save-after-index-builds-ordered-save-commands-test
  (let [loaded-index {:ordered-ids []
                      :by-id {}}
        result (workflow/continue-save-after-index
                {:state (save-state)
                 :address address
                 :scenario-id "scn_01"
                 :started-at-ms 3000
                 :loaded-index loaded-index})
        scenario-record (:scenario-record result)
        scenario-index (:scenario-index result)]
    (is (= "scn_01" (:id scenario-record)))
    (is (= :saved (:status scenario-record)))
    (is (= ["scn_01"] (:ordered-ids scenario-index)))
    (is (= [:optimizer.workflow/save-scenario
            :optimizer.workflow/save-scenario-index]
           (mapv :command/type (:commands result))))
    (is (= ["scn_01" address]
	                    [(get-in result [:commands 0 :scenario-id])
	                     (get-in result [:commands 1 :address])]))))

(deftest begin-save-allows-setup-only-save-without-solved-run-test
  (let [state (update-in (save-state)
                         [:portfolio :optimizer]
                         dissoc :last-successful-run)
        result (workflow/begin-save {:state state
                                     :address address
                                     :scenario-id "scn_01"
                                     :started-at-ms 3000})]
    (is (= :saving
           (get-in result [:state :portfolio :optimizer :scenario-save-state :status])))
    (is (= [:optimizer.workflow/load-scenario-index]
           (mapv :command/type (:commands result))))))

(deftest begin-save-still-requires-an-address-test
  (let [result (workflow/begin-save {:state (save-state)
                                     :address nil
                                     :scenario-id "scn_01"
                                     :started-at-ms 3000})]
    (is (= :failed
           (get-in result [:state :portfolio :optimizer :scenario-save-state :status])))
    (is (= [] (:commands result)))))

(deftest continue-save-after-index-omits-stale-run-snapshot-test
  (let [result (workflow/continue-save-after-index
                {:state (save-state)
                 :address address
                 :scenario-id "scn_01"
                 :started-at-ms 3000
                 :loaded-index {:ordered-ids [] :by-id {}}})]
    (is (nil? (:saved-run (:scenario-record result))))))

(deftest continue-save-after-index-attaches-current-run-snapshot-test
  (let [run (fixtures/sample-last-successful-run
             {:request-signature {:scenario-id "scn_01"
                                  :input-signature "sig-1"}})
        state (-> (save-state)
                  (assoc-in [:portfolio :optimizer :draft :metadata :dirty?] false)
                  (assoc-in [:portfolio :optimizer :last-successful-run] run)
                  (assoc-in [:portfolio :optimizer :run-state]
                            {:status :succeeded
                             :request-signature {:scenario-id "scn_01"
                                                 :input-signature "sig-1"}}))
        result (workflow/continue-save-after-index
                {:state state
                 :address address
                 :scenario-id "scn_01"
                 :started-at-ms 3000
                 :loaded-index {:ordered-ids [] :by-id {}}})]
    (is (= run (:saved-run (:scenario-record result))))))

(deftest command-result-advancement-removes-completed-command-test
  (let [result {:state {:portfolio {:optimizer {}}}
                :commands [{:command/type :optimizer.workflow/save-scenario
                            :scenario-id "scn_01"}
                           {:command/type :optimizer.workflow/save-scenario-index
                            :address address}]}
        advanced (workflow/advance-command-result result)]
    (is (= {:state {:portfolio {:optimizer {}}}
            :commands [{:command/type :optimizer.workflow/save-scenario-index
                        :address address}]}
           advanced))))

(def scenario-record
  {:schema-version 1
   :id "scn_01"
   :name "Core Hedge"
   :address address
   :status :saved
   :config {:id "scn_01"
            :name "Core Hedge"
            :status :saved
            :metadata {:dirty? false
                       :updated-at-ms 2000}}
   :saved-run (fixtures/sample-last-successful-run)
   :updated-at-ms 2000})

(def scenario-index
  {:ordered-ids ["scn_01"]
   :by-id {"scn_01" {:id "scn_01"
                     :name "Core Hedge"
                     :status :saved}}})

(deftest operation-registry-continues-archive-after-loaded-record-test
  (let [operation {:operation/type :archive
                   :address address
                   :scenario-id "scn_01"
                   :started-at-ms 4000}
        command {:command/type :optimizer.workflow/load-scenario
                 :scenario-id "scn_01"}
        result (operations/continue-after-scenario-record
                operation
                {:portfolio {:optimizer {}}}
                command
                scenario-record
                4100)]
    (is (= [{:command/type :optimizer.workflow/load-scenario-index
             :address address
             :scenario-id "scn_01"
             :started-at-ms 4000
             :source :archive}]
           (:commands result)))))

(deftest operation-registry-continues-save-after-loaded-index-test
  (let [operation {:operation/type :save
                   :address address
                   :scenario-id "scn_01"
                   :started-at-ms 3000}
        command {:command/type :optimizer.workflow/load-scenario-index
                 :address address}
        result (operations/continue-after-scenario-index
                operation
                (save-state)
                command
                scenario-index
                3100)]
    (is (= [:optimizer.workflow/save-scenario
            :optimizer.workflow/save-scenario-index]
           (mapv :command/type (:commands result))))
    (is (= "scn_01" (get-in result [:commands 0 :scenario-id])))
    (is (= :save (get-in result [:commands 1 :source])))))

(deftest operation-registry-fails-duplicate-operation-test
  (let [operation {:operation/type :duplicate
                   :scenario-id "scn_01"
                   :started-at-ms 5000}
        result (operations/fail operation
                                {:portfolio {:optimizer {}}}
                                {:message "boom"}
                                5100)]
    (is (= {:status :failed
            :source-scenario-id "scn_01"
            :duplicated-scenario-id nil
            :started-at-ms 5000
            :completed-at-ms 5100
            :error {:message "boom"}}
           (get-in result [:state :portfolio :optimizer :scenario-duplicate-state])))
    (is (= [] (:commands result)))))

(deftest begin-index-load-plans-address-scoped-index-load-test
  (let [result (workflow/begin-index-load {:state (save-state)
                                           :address address
                                           :started-at-ms 3000})]
    (is (= :loading
           (get-in result [:state :portfolio :optimizer :scenario-index-load-state :status])))
    (is (= [{:command/type :optimizer.workflow/load-scenario-index
             :address address
             :started-at-ms 3000}]
           (:commands result)))))

(deftest load-workflow-loads-tracking-before-applying-record-test
  (let [started (workflow/begin-load {:state {:portfolio {:optimizer {}}}
                                      :scenario-id "scn_01"
                                      :started-at-ms 3000})
        after-record (workflow/continue-load-after-record
                      {:state (:state started)
                       :scenario-id "scn_01"
                       :scenario-record scenario-record
                       :started-at-ms 3000
                       :completed-at-ms 3100})
        result (workflow/complete-load-after-tracking
                {:state (:state after-record)
                 :scenario-id "scn_01"
                 :scenario-record scenario-record
                 :tracking-record {:status :tracking}
                 :started-at-ms 3000
                 :completed-at-ms 3200})]
    (is (= [{:command/type :optimizer.workflow/load-scenario
             :scenario-id "scn_01"
             :started-at-ms 3000}]
           (:commands started)))
    (is (= [{:command/type :optimizer.workflow/load-tracking
             :scenario-id "scn_01"
             :started-at-ms 3000}]
           (:commands after-record)))
    (is (= :loaded
           (get-in result [:state :portfolio :optimizer :scenario-load-state :status])))
    (is (= {:status :tracking}
           (get-in result [:state :portfolio :optimizer :tracking])))))

(deftest archive-workflow-builds-ordered-persistence-commands-test
  (let [started (workflow/begin-archive {:state {:portfolio {:optimizer
                                                             {:active-scenario
                                                              {:loaded-id "scn_01"
                                                               :status :saved}
                                                              :draft (:config scenario-record)}}}
                                         :address address
                                         :scenario-id "scn_01"
                                         :started-at-ms 4000})
        after-record (workflow/continue-archive-after-record
                      {:state (:state started)
                       :address address
                       :scenario-id "scn_01"
                       :scenario-record scenario-record
                       :started-at-ms 4000
                       :completed-at-ms 4100})
        plan (workflow/continue-archive-after-index
              {:state (:state after-record)
               :address address
               :scenario-id "scn_01"
               :scenario-record scenario-record
               :started-at-ms 4000
               :loaded-index scenario-index})
        archived-record (:scenario-record plan)
        result (workflow/complete-archive
                {:state (:state plan)
                 :scenario-index (:scenario-index plan)
                 :scenario-record archived-record
                 :started-at-ms 4000
                 :completed-at-ms 4200})]
    (is (= [:optimizer.workflow/load-scenario]
           (mapv :command/type (:commands started))))
    (is (= [:optimizer.workflow/load-scenario-index]
           (mapv :command/type (:commands after-record))))
    (is (= [:optimizer.workflow/save-scenario
            :optimizer.workflow/save-scenario-index]
           (mapv :command/type (:commands plan))))
    (is (= :archived (:status archived-record)))
    (is (= :archived
           (get-in result [:state :portfolio :optimizer :active-scenario :status])))
    (is (= :archived
           (get-in result [:state :portfolio :optimizer :scenario-archive-state :status])))))

(deftest duplicate-workflow-builds-new-record-and-index-commands-test
  (let [started (workflow/begin-duplicate {:state {:portfolio {:optimizer
                                                               {:scenario-index scenario-index}}}
                                           :address address
                                           :scenario-id "scn_01"
                                           :duplicated-scenario-id "scn_copy"
                                           :started-at-ms 5000})
        after-record (workflow/continue-duplicate-after-record
                      {:state (:state started)
                       :address address
                       :scenario-id "scn_01"
                       :duplicated-scenario-id "scn_copy"
                       :scenario-record scenario-record
                       :started-at-ms 5000
                       :completed-at-ms 5100})
        plan (workflow/continue-duplicate-after-index
              {:state (:state after-record)
               :address address
               :scenario-id "scn_01"
               :duplicated-scenario-id "scn_copy"
               :scenario-record scenario-record
               :started-at-ms 5000
               :loaded-index scenario-index})
        duplicated-record (:scenario-record plan)]
    (is (= [:optimizer.workflow/load-scenario]
           (mapv :command/type (:commands started))))
    (is (= [:optimizer.workflow/load-scenario-index]
           (mapv :command/type (:commands after-record))))
    (is (= [:optimizer.workflow/save-scenario
            :optimizer.workflow/save-scenario-index]
           (mapv :command/type (:commands plan))))
    (is (= "scn_copy" (:id duplicated-record)))
    (is (= ["scn_copy" "scn_01"] (get-in plan [:scenario-index :ordered-ids])))))

(deftest manual-tracking-workflow-plans-load-index-and-save-commands-test
  (let [started (workflow/begin-manual-tracking {:state {:portfolio {:optimizer {}}}
                                                 :address address
                                                 :scenario-id "scn_01"
                                                 :started-at-ms 6000})
        after-record (workflow/continue-manual-tracking-after-record
                      {:state (:state started)
                       :address address
                       :scenario-id "scn_01"
                       :scenario-record scenario-record
                       :updated-at-ms 6100})
        plan (workflow/continue-manual-tracking-after-index
              {:state (:state after-record)
               :address address
               :scenario-id "scn_01"
               :scenario-record scenario-record
               :loaded-index scenario-index
               :updated-at-ms 6100})
        updated-record (:scenario-record plan)
        result (workflow/complete-manual-tracking
                {:state (:state plan)
                 :scenario-index (:scenario-index plan)
                 :scenario-record updated-record})]
    (is (= [:optimizer.workflow/load-scenario]
           (mapv :command/type (:commands started))))
    (is (= [:optimizer.workflow/load-scenario-index]
           (mapv :command/type (:commands after-record))))
    (is (= [:optimizer.workflow/save-scenario
            :optimizer.workflow/save-scenario-index]
           (mapv :command/type (:commands plan))))
    (is (= :tracking (:status updated-record)))
    (is (= :tracking
           (get-in result [:state :portfolio :optimizer :active-scenario :status])))
    (is (= :idle
           (get-in result [:state :portfolio :optimizer :tracking :status])))))
