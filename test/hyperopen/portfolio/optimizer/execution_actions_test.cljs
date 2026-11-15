(ns hyperopen.portfolio.optimizer.execution-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.actions.execution :as exec-actions]
            [hyperopen.portfolio.optimizer.application.rebalance-preview :as rebalance-preview]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.schema.contracts :as contracts]))

(defn- emitted-effects-valid?
  "Runs the emitted effects through the real action-emission contract — every emitted item
  must be a registered `effects/*` effect with conforming args. This guards against an action
  returning a raw `[:actions/...]` chain (which throws at runtime dispatch, not in a plain
  return-value assertion)."
  [action-id effects]
  (= effects (contracts/assert-emitted-effects!
              effects {:phase :action-emission :action-id action-id})))

(def ^:private current-run-signature
  {:scenario-id "draft-1" :input-signature "sig-current"})

(defn- current-run-state
  []
  {:status :succeeded :request-signature current-run-signature})

(deftest open-execution-stages-plan-and-switches-to-execution-tab-test
  (let [state {:portfolio {:optimizer
                           {:draft {:id "draft-1"
                                    :execution-assumptions {:default-order-type :market}}
                            :run-state (current-run-state)
                            :last-successful-run
                            (fixtures/sample-last-successful-run
                             {:request-signature current-run-signature
                              :result {:rebalance-preview
                                       {:summary {:estimated-fees-usd nil
                                                  :estimated-slippage-usd nil}
                                        :rows [{:instrument-id "perp:BTC"
                                                :instrument-type :perp
                                                :status :ready
                                                :side :buy
                                                :quantity 0.25
                                                :delta-notional-usd 1000}]}}})}}}
        effects (actions/open-portfolio-optimizer-execution state)]
    (is (= [:effects/save [:portfolio :optimizer :execution-modal]
            {:open? true
             :submitting? false
             :error nil
             :phase :staged
             :default-order-type :recommended
             :overrides {}
             :params {}
             :open-row nil
             :order-filter :all
             :overlap-cancels {}
             :exit-instrument-ids #{}
             :plan {:scenario-id "draft-1"
                    :status :ready
                    :execution-disabled? false
                    :disabled-reason nil
                    :disabled-message nil
                    :summary {:ready-count 1
                              :blocked-count 0
                              :skipped-count 0
                              :gross-ready-notional-usd 1000
                              :estimated-fees-usd nil
                              :estimated-slippage-usd nil
                              :margin nil}
                    :rows [{:row-id "perp:BTC"
                            :instrument-id "perp:BTC"
                            :instrument-type :perp
                            :status :ready
                            :side :buy
                            :quantity 0.25
                            :order-type :market
                            :delta-notional-usd 1000
                            :cost nil
                            :intent {:kind :perp-order
                                     :instrument-id "perp:BTC"
                                     :side :buy
                                     :quantity 0.25
                                     :order-type :market
                                     :reduce-only? false}}]}}]
           (first effects)))
    (is (= [:effects/save [:portfolio :optimizer :execution]
            {:status :idle :attempt nil :history [] :error nil}]
           (nth effects 1)))
    (is (= [:effects/refresh-portfolio-optimizer-open-orders] (nth effects 2)))
    (is (= [:effects/refresh-portfolio-optimizer-rebalance-slippage-snapshots]
           (nth effects 3)))
    (is (= [:effects/save [:portfolio-ui :optimizer :results-tab] :execution]
           (nth effects 4)))
    (is (= [:effects/replace-shareable-route-query] (nth effects 5)))))

(deftest open-execution-derives-preview-when-solved-run-lacks-preview-test
  (let [state {:asset-selector
               {:market-by-key
                {"perp:BTC" {:key "perp:BTC"
                             :market-type :perp
                             :coin "BTC"
                             :markPx "100"}
                 "perp:ETH" {:key "perp:ETH"
                             :market-type :perp
                             :coin "ETH"
                             :markPx "50"}}}
               :webdata2
               {:clearinghouseState
                {:marginSummary {:accountValue "10000"
                                 :totalMarginUsed "500"}
                 :assetPositions [{:position {:coin "BTC"
                                              :szi "25"
                                              :positionValue "2500"
                                              :markPx "100"
                                              :leverage {:value 5
                                                         :type "cross"}}}]}}
               :portfolio {:optimizer
                           {:active-scenario {:loaded-id nil
                                              :status :computed}
                            :draft {:id "draft-1"
                                    :universe [{:instrument-id "perp:BTC"
                                                :market-type :perp
                                                :coin "BTC"}
                                               {:instrument-id "perp:ETH"
                                                :market-type :perp
                                                :coin "ETH"}]
                                    :objective {:kind :minimum-variance}
                                    :return-model {:kind :historical-mean}
                                    :risk-model {:kind :diagonal-shrink}
                                    :constraints {:long-only? true
                                                  :max-asset-weight 0.75
                                                  :rebalance-tolerance 0.001}
                                    :execution-assumptions
                                    {:default-order-type :market
                                     :fallback-slippage-bps 20
                                     :prices-by-id {"perp:BTC" 100
                                                    "perp:ETH" 50}
                                     :fee-bps-by-id {"perp:BTC" 4
                                                    "perp:ETH" 5}}}
                            :history-data {:candle-history-by-coin
                                           {"BTC" [{:time-ms 1000 :close "100"}
                                                   {:time-ms 2000 :close "101"}
                                                   {:time-ms 3000 :close "102"}]
                                            "ETH" [{:time-ms 1000 :close "50"}
                                                   {:time-ms 2000 :close "51"}
                                                   {:time-ms 3000 :close "52"}]}
                                           :funding-history-by-coin
                                           {"BTC" [{:time-ms 1000
                                                    :funding-rate-raw 0}]
                                            "ETH" [{:time-ms 1000
                                                    :funding-rate-raw 0}]}}
                            :runtime {:as-of-ms 3000
                                      :stale-after-ms 60000}
                            :last-successful-run
                            (fixtures/sample-minimal-last-successful-run
                             {:result {:status :solved
                                       :scenario-id "draft-1"
                                       :instrument-ids ["perp:BTC" "perp:ETH"]
                                       :current-weights [0.25 0.0]
                                       :target-weights [0.5 0.5]
                                       :target-weights-by-instrument {"perp:BTC" 0.5
                                                                      "perp:ETH" 0.5}
                                       :current-weights-by-instrument {"perp:BTC" 0.25
                                                                       "perp:ETH" 0.0}
                                       :diagnostics {:turnover 0.75}}})}}}
        effects (actions/open-portfolio-optimizer-execution state)
        plan (get-in effects [0 2 :plan])]
    (is (= :effects/save (get-in effects [0 0])))
    (is (= [:portfolio :optimizer :execution-modal] (get-in effects [0 1])))
    (is (= true (get-in effects [0 2 :open?])))
    (is (= :ready (:status plan)))
    (is (= 2 (get-in plan [:summary :ready-count])))
    (is (= #{"perp:BTC" "perp:ETH"} (set (map :instrument-id (:rows plan)))))
    (is (= #{:perp-order} (set (map #(get-in % [:intent :kind]) (:rows plan)))))))

(deftest open-execution-without-solved-run-still-switches-tab-with-nil-plan-test
  (let [effects (actions/open-portfolio-optimizer-execution
                 {:portfolio {:optimizer {:last-successful-run
                                          (fixtures/sample-last-successful-run
                                           {:result {:status :infeasible}})}}})]
    (is (nil? (get-in effects [0 2 :plan])))
    (is (= [:effects/save [:portfolio-ui :optimizer :results-tab] :execution]
           (nth effects 4)))))

(deftest open-execution-stages-disabled-plan-when-recommendation-stale-test
  (let [state {:portfolio {:optimizer
                           {:draft {:id "draft-1"
                                    :metadata {:dirty? true}
                                    :execution-assumptions {:default-order-type :market}}
                            :last-successful-run
                            (fixtures/sample-last-successful-run
                             {:result {:rebalance-preview
                                       {:summary {:estimated-fees-usd nil
                                                  :estimated-slippage-usd nil}
                                        :rows [{:instrument-id "perp:BTC"
                                                :instrument-type :perp
                                                :status :ready
                                                :side :buy
                                                :quantity 0.25
                                                :delta-notional-usd 1000}]}}})}}}
        plan (get-in (actions/open-portfolio-optimizer-execution state) [0 2 :plan])]
    (is (true? (:execution-disabled? plan)))
    (is (= :stale-recommendation (:disabled-reason plan)))
    (is (= exec-actions/stale-recommendation-message (:disabled-message plan)))
    (is (= 1 (count (:rows plan))))
    (is (= "perp:BTC" (:instrument-id (first (:rows plan)))))))

(def ^:private exit-request
  {:current-portfolio
   {:capital {:nav-usdc 1000}
    :by-instrument {"perp:BTC" {:instrument-id "perp:BTC"
                                :market-type :perp
                                :coin "BTC"
                                :weight 0.5
                                :close 100}
                    "perp:ENS" {:instrument-id "perp:ENS"
                                :market-type :perp
                                :coin "ENS"
                                :weight 0.2
                                :close 20}}}
   :universe [{:instrument-id "perp:BTC" :instrument-type :perp :coin "BTC"}
              {:instrument-id "perp:ENS" :instrument-type :perp :coin "ENS"}]
   :execution-assumptions {:default-fee-bps 4.5
                           :fallback-slippage-bps 25}})

(defn- exit-toggle-state
  [modal-overrides]
  {:portfolio {:optimizer
               {:draft {:id "draft-1"
                        :execution-assumptions {:default-order-type :market}}
                :run-state (current-run-state)
                :execution-modal (merge {:open? true
                                         :phase :staged
                                         :submitting? false
                                         :exit-instrument-ids #{}
                                         :plan {:status :ready :rows []}}
                                        modal-overrides)
                :last-successful-run
                (fixtures/sample-minimal-last-successful-run
                 {:request-signature (assoc current-run-signature
                                            :request exit-request)
                  :result {:scenario-id "draft-1"
                           :instrument-ids ["perp:BTC"]
                           :target-weights [0.5]
                           :current-weights [0.5]}})}}})

(defn- plan-row
  [plan instrument-id]
  (first (filter #(= instrument-id (:instrument-id %)) (:rows plan))))

(deftest execution-exit-toggle-stages-sell-to-zero-and-restages-plan-test
  (let [effects (actions/set-portfolio-optimizer-execution-exit
                 (exit-toggle-state {:phase :armed})
                 ["perp:ENS"]
                 true)
        plan (get-in effects [1 2])
        ens-row (plan-row plan "perp:ENS")]
    (is (emitted-effects-valid? :actions/set-portfolio-optimizer-execution-exit effects))
    (is (= [:effects/save [:portfolio :optimizer :execution-modal :exit-instrument-ids]
            #{"perp:ENS"}]
           (first effects)))
    (is (= [:portfolio :optimizer :execution-modal :plan] (get-in effects [1 1])))
    (is (= :ready (:status ens-row)))
    (is (= :sell (:side ens-row)))
    (is (= -200 (:delta-notional-usd ens-row)))
    (is (= :perp-order (get-in ens-row [:intent :kind])))
    (is (= 1 (get-in plan [:summary :ready-count])))
    (is (= 1 (get-in plan [:summary :skipped-count])))
    (is (= [:effects/save [:portfolio :optimizer :execution-modal :phase] :staged]
           (nth effects 2)))
    (is (= [:effects/save [:portfolio :optimizer :execution-modal :error] nil]
           (nth effects 3)))))

(deftest execution-exit-untoggle-returns-row-to-skipped-hold-test
  (let [effects (actions/set-portfolio-optimizer-execution-exit
                 (exit-toggle-state {:exit-instrument-ids #{"perp:ENS"}})
                 ["perp:ENS"]
                 false)
        plan (get-in effects [1 2])
        ens-row (plan-row plan "perp:ENS")]
    (is (emitted-effects-valid? :actions/set-portfolio-optimizer-execution-exit effects))
    (is (= #{} (get-in effects [0 2])))
    (is (= :skipped (:status ens-row)))
    (is (= :excluded-from-optimization (:reason ens-row)))
    (is (= :none (:side ens-row)))
    (is (zero? (get-in plan [:summary :ready-count])))
    (is (= 2 (get-in plan [:summary :skipped-count])))))

(deftest restage-plan-rebuilds-the-staged-plan-and-keeps-the-exit-set-test
  (let [effects (actions/restage-portfolio-optimizer-execution-plan
                 (exit-toggle-state {:exit-instrument-ids #{"perp:ENS"}}))
        plan (get-in effects [0 2])]
    (is (emitted-effects-valid? :actions/restage-portfolio-optimizer-execution-plan effects))
    (is (= 1 (count effects)) "only the plan is rewritten — no phase or error churn")
    (is (= [:portfolio :optimizer :execution-modal :plan] (get-in effects [0 1])))
    (is (= :ready (:status (plan-row plan "perp:ENS"))))
    (is (= -200 (:delta-notional-usd (plan-row plan "perp:ENS"))))))

(deftest restage-plan-never-touches-a-committed-or-in-flight-surface-test
  (is (= [] (actions/restage-portfolio-optimizer-execution-plan
             (exit-toggle-state {:phase :armed}))))
  (is (= [] (actions/restage-portfolio-optimizer-execution-plan
             (exit-toggle-state {:submitting? true}))))
  (is (= [] (actions/restage-portfolio-optimizer-execution-plan
             (exit-toggle-state {:open? false}))))
  (is (= [] (actions/restage-portfolio-optimizer-execution-plan
             (exit-toggle-state {:plan nil}))))
  (is (= [] (actions/restage-portfolio-optimizer-execution-plan
             (assoc-in (exit-toggle-state nil)
                       [:portfolio :optimizer :execution :status]
                       :submitted)))))

(def ^:private auto-exit-request
  {:current-portfolio
   {:capital {:nav-usdc 1000}
    :by-instrument {"perp:BTC" {:instrument-id "perp:BTC"
                                :market-type :perp
                                :coin "BTC"
                                :weight 0.5
                                :close 100}
                    "perp:ENS" {:instrument-id "perp:ENS"
                                :market-type :perp
                                :coin "ENS"
                                :weight 0.2
                                :close 20}
                    "perp:DROPPED" {:instrument-id "perp:DROPPED"
                                    :market-type :perp
                                    :coin "DROPPED"
                                    :weight 0.05
                                    :close 10}
                    "spot:@1" {:instrument-id "spot:@1"
                               :market-type :spot
                               :coin "HYPE"
                               :weight 0.05
                               :close 40}}}
   :requested-universe [{:instrument-id "perp:BTC" :instrument-type :perp :coin "BTC"}
                        {:instrument-id "perp:DROPPED" :instrument-type :perp :coin "DROPPED"}]
   :universe [{:instrument-id "perp:BTC" :instrument-type :perp :coin "BTC"}
              {:instrument-id "perp:ENS" :instrument-type :perp :coin "ENS"}
              {:instrument-id "perp:DROPPED" :instrument-type :perp :coin "DROPPED"}]
   :execution-assumptions {:default-fee-bps 4.5
                           :fallback-slippage-bps 25}})

(defn- auto-exit-state
  [trading-settings]
  (let [signature (assoc current-run-signature :request auto-exit-request)
        result (rebalance-preview/result-with-refreshed-rebalance-preview
                auto-exit-request
                {:status :solved
                 :scenario-id "draft-1"
                 :instrument-ids ["perp:BTC"]
                 :target-weights [0.6]
                 :current-weights [0.5]})]
    (cond-> {:portfolio {:optimizer
                         {:draft {:id "draft-1"
                                  :execution-assumptions {:default-order-type :market}}
                          :run-state (current-run-state)
                          :last-successful-run
                          (fixtures/sample-minimal-last-successful-run
                           {:request-signature signature})}}}
      true (assoc-in [:portfolio :optimizer :last-successful-run :result] result)
      (some? trading-settings) (assoc :trading-settings trading-settings))))

(deftest open-execution-auto-stages-exits-for-removed-perps-by-default-test
  (let [effects (actions/open-portfolio-optimizer-execution (auto-exit-state nil))
        modal (get-in effects [0 2])
        plan (:plan modal)
        row (fn [id] (plan-row plan id))]
    (is (= #{"perp:ENS"} (:exit-instrument-ids modal)))
    (is (= :ready (:status (row "perp:ENS"))))
    (is (= :sell (:side (row "perp:ENS"))))
    (is (= -200 (:delta-notional-usd (row "perp:ENS"))))
    (is (= :skipped (:status (row "perp:DROPPED"))))
    (is (= :skipped (:status (row "spot:@1"))))))

(deftest open-execution-honors-auto-exit-opt-out-test
  (let [effects (actions/open-portfolio-optimizer-execution
                 (auto-exit-state {:optimizer-auto-exit-excluded? false}))
        modal (get-in effects [0 2])]
    (is (= #{} (:exit-instrument-ids modal)))
    (is (= :skipped (:status (plan-row (:plan modal) "perp:ENS"))))))

(deftest set-auto-exit-persists-and-restages-test
  (let [on-state (-> (auto-exit-state nil)
                     (assoc-in [:portfolio :optimizer :execution-modal]
                               {:open? true
                                :phase :staged
                                :submitting? false
                                :exit-instrument-ids #{"perp:ENS"}
                                :plan {:status :ready :rows []}}))
        effects (actions/set-portfolio-optimizer-execution-auto-exit on-state false)
        [save-settings persist-json save-exits save-plan] effects]
    (is (emitted-effects-valid? :actions/set-portfolio-optimizer-execution-auto-exit
                                effects))
    (is (= [:trading-settings] (nth save-settings 1)))
    (is (false? (:optimizer-auto-exit-excluded? (nth save-settings 2))))
    (is (= :effects/local-storage-set-json (first persist-json)))
    (is (false? (:optimizer-auto-exit-excluded? (nth persist-json 2))))
    (is (= #{} (nth save-exits 2)))
    (is (= :skipped (:status (plan-row (nth save-plan 2) "perp:ENS"))))
    (let [off-state (-> on-state
                        (assoc :trading-settings {:optimizer-auto-exit-excluded? false})
                        (assoc-in [:portfolio :optimizer :execution-modal
                                   :exit-instrument-ids] #{}))
          effects-on (actions/set-portfolio-optimizer-execution-auto-exit off-state true)]
      (is (= #{"perp:ENS"} (nth (nth effects-on 2) 2)))
      (is (= :ready (:status (plan-row (nth (nth effects-on 3) 2) "perp:ENS")))))))

(deftest set-auto-exit-only-persists-without-staged-plan-test
  (let [effects (actions/set-portfolio-optimizer-execution-auto-exit
                 (auto-exit-state nil)
                 false)]
    (is (= 2 (count effects)))
    (is (= #{:effects/save :effects/local-storage-set-json}
           (set (map first effects))))))

(deftest execution-exit-toggle-guards-test
  (is (= [] (actions/set-portfolio-optimizer-execution-exit
             (exit-toggle-state {:plan nil}) ["perp:ENS"] true)))
  (is (= [] (actions/set-portfolio-optimizer-execution-exit
             (exit-toggle-state {:submitting? true}) ["perp:ENS"] true)))
  (is (= [] (actions/set-portfolio-optimizer-execution-exit
             (assoc-in (exit-toggle-state {})
                       [:portfolio :optimizer :execution :status]
                       :executed)
             ["perp:ENS"] true)))
  (is (= [] (actions/set-portfolio-optimizer-execution-exit
             (exit-toggle-state {}) [] true))))

(deftest execution-staging-mutators-update-interaction-state-test
  (is (= [[:effects/save [:portfolio :optimizer :execution-modal :phase] :armed]
          [:effects/save [:portfolio :optimizer :execution-modal :error] nil]]
         (actions/set-portfolio-optimizer-execution-phase {} :armed)))
  (is (= [[:effects/save [:portfolio :optimizer :execution-modal :phase] :staged]
          [:effects/save [:portfolio :optimizer :execution-modal :error] nil]]
         (actions/set-portfolio-optimizer-execution-phase {} :staged)))
  (is (= [[:effects/save [:portfolio :optimizer :execution-modal :default-order-type] :twap]]
         (actions/set-portfolio-optimizer-execution-default-order-type {} :twap)))
  (is (= [[:effects/save [:portfolio :optimizer :execution-modal :overrides] {"perp:BTC" :limit}]]
         (actions/set-portfolio-optimizer-execution-row-order-type {} "perp:BTC" :limit)))
  (is (= [[:effects/save [:portfolio :optimizer :execution-modal :overrides] {}]]
         (actions/set-portfolio-optimizer-execution-row-order-type
          {:portfolio {:optimizer {:execution-modal {:overrides {"perp:BTC" :limit}}}}}
          "perp:BTC" :recommended)))
  (is (= [[:effects/save [:portfolio :optimizer :execution-modal :open-row] "perp:BTC"]]
         (actions/toggle-portfolio-optimizer-execution-row {} "perp:BTC")))
  (is (= [[:effects/save [:portfolio :optimizer :execution-modal :open-row] nil]]
         (actions/toggle-portfolio-optimizer-execution-row
          {:portfolio {:optimizer {:execution-modal {:open-row "perp:BTC"}}}}
          "perp:BTC")))
  (is (= [[:effects/save [:portfolio :optimizer :execution-modal :params] {"perp:BTC" {:limit-bps -5}}]]
         (actions/set-portfolio-optimizer-execution-row-param {} "perp:BTC" :limit-bps -5)))
  (is (= [[:effects/save [:portfolio :optimizer :execution-modal :order-filter] :working]]
         (actions/set-portfolio-optimizer-execution-order-filter {} :working))))

(deftest confirm-execution-applies-order-type-selections-and-dispatches-test
  (let [row {:row-id "perp:BTC"
             :status :ready
             :side :buy
             :instrument-type :perp
             :delta-notional-usd 30000
             :intent {:kind :perp-order :side :buy :quantity 0.25 :order-type :market}}
        plan {:scenario-id "draft-1"
              :status :ready
              :execution-disabled? false
              :summary {:ready-count 1}
              :rows [row]}
        state {:wallet {:agent {:status :ready}}
               :portfolio {:optimizer {:execution-modal
                                       {:open? true
                                        :plan plan
                                        :default-order-type :recommended
                                        :overrides {"perp:BTC" :limit}
                                        :params {"perp:BTC" {:limit-bps -5}}}}}}
        effects (actions/confirm-portfolio-optimizer-execution state)
        [effect-key dispatched] (nth effects 2)
        intent (get-in dispatched [:rows 0 :intent])]
    (is (= [:effects/save [:portfolio :optimizer :execution-modal :submitting?] true]
           (first effects)))
    (is (= [:effects/save [:portfolio :optimizer :execution-modal :error] nil]
           (second effects)))
    (is (= :effects/execute-portfolio-optimizer-plan effect-key))
    (is (= :limit (:order-type intent)))
    (is (= -5 (:limit-bps intent)))
    (is (= :limit (get-in dispatched [:rows 0 :order-type])))))

(deftest confirm-execution-recommended-default-routes-by-clip-size-test
  (let [row {:row-id "perp:BTC"
             :status :ready
             :side :buy
             :instrument-type :perp
             :delta-notional-usd 30000
             :intent {:kind :perp-order :side :buy :quantity 0.25 :order-type :market}}
        state {:wallet {:agent {:status :ready}}
               :portfolio {:optimizer {:execution-modal
                                       {:open? true
                                        :plan {:scenario-id "draft-1"
                                               :status :ready
                                               :execution-disabled? false
                                               :summary {:ready-count 1}
                                               :rows [row]}
                                        :default-order-type :recommended
                                        :overrides {}
                                        :params {}}}}}
        [_ dispatched] (nth (actions/confirm-portfolio-optimizer-execution state) 2)]
    (is (= :passive (get-in dispatched [:rows 0 :intent :order-type])))))

(defn- locked-state
  [agent-status]
  (let [row {:row-id "perp:BTC"
             :status :ready
             :side :buy
             :instrument-type :perp
             :delta-notional-usd 30000
             :intent {:kind :perp-order :side :buy :quantity 0.25 :order-type :market}}]
    {:wallet {:agent {:status agent-status}}
     :portfolio {:optimizer {:execution-modal
                             {:open? true
                              :plan {:scenario-id "draft-1"
                                     :status :ready
                                     :execution-disabled? false
                                     :summary {:ready-count 1}
                                     :rows [row]}
                              :default-order-type :market
                              :overrides {}
                              :params {}}}}}))

(defn- stale-execution-state
  "A solved run whose draft was edited since the solve (dirty), so the staged plan is stale.
  agent :ready proves the block is staleness, not trading-readiness."
  []
  (-> (locked-state :ready)
      (assoc-in [:portfolio :optimizer :draft] {:id "draft-1" :metadata {:dirty? true}})
      (assoc-in [:portfolio :optimizer :last-successful-run]
                (fixtures/sample-last-successful-run {}))))

(defn- current-execution-state
  "A just-completed solved run: clean draft + a run-state whose signature matches the retained
  run, so `stale-run?` is false and the gate must let it through."
  []
  (let [signature {:scenario-id "draft-1" :input-signature "sig-current"}]
    (-> (locked-state :ready)
        (assoc-in [:portfolio :optimizer :draft] {:id "draft-1" :metadata {:dirty? false}})
        (assoc-in [:portfolio :optimizer :run-state]
                  {:status :succeeded :request-signature signature})
        (assoc-in [:portfolio :optimizer :last-successful-run]
                  (fixtures/sample-last-successful-run {:request-signature signature})))))

(deftest confirm-execution-blocks-stale-recommendation-test
  (let [effects (actions/confirm-portfolio-optimizer-execution (stale-execution-state))]
    (is (emitted-effects-valid? :actions/confirm-portfolio-optimizer-execution effects))
    (is (= [[:effects/save [:portfolio :optimizer :execution-modal :error]
             exec-actions/stale-recommendation-message]]
           effects))
    (is (not (some #{:effects/execute-portfolio-optimizer-plan} (map first effects))))))

(deftest arm-execution-blocked-when-stale-test
  (is (= [[:effects/save [:portfolio :optimizer :execution-modal :phase] :staged]
          [:effects/save [:portfolio :optimizer :execution-modal :error]
           exec-actions/stale-recommendation-message]]
         (actions/set-portfolio-optimizer-execution-phase (stale-execution-state) :armed))))

(deftest confirm-execution-current-solved-run-still-submits-test
  (let [effects (actions/confirm-portfolio-optimizer-execution (current-execution-state))]
    (is (some #{:effects/execute-portfolio-optimizer-plan} (map first effects)))))

(deftest confirm-execution-locked-agent-prompts-unlock-and-replays-confirm-test
  (let [effects (actions/confirm-portfolio-optimizer-execution (locked-state :locked))
        effect-keys (map first effects)]
    (is (emitted-effects-valid? :actions/confirm-portfolio-optimizer-execution effects))
    (is (some #{:effects/unlock-agent-trading} effect-keys))
    (is (not (some #{:effects/execute-portfolio-optimizer-plan} effect-keys)))
    (is (some #{[:effects/save-many [[[:wallet :agent :status] :unlocking]
                                     [[:wallet :agent :error] nil]]]}
              effects))
    (let [unlock (first (filter #(= :effects/unlock-agent-trading (first %)) effects))]
      (is (= {:after-success-actions [[:actions/confirm-portfolio-optimizer-execution]]}
             (second unlock))))))

(deftest confirm-execution-unlocking-agent-waits-without-submitting-test
  (let [effects (actions/confirm-portfolio-optimizer-execution (locked-state :unlocking))
        effect-keys (map first effects)]
    (is (emitted-effects-valid? :actions/confirm-portfolio-optimizer-execution effects))
    (is (not (some #{:effects/execute-portfolio-optimizer-plan} effect-keys)))
    (is (some #{:effects/save} effect-keys))))

(deftest confirm-execution-not-ready-agent-opens-enable-recovery-test
  (let [effects (actions/confirm-portfolio-optimizer-execution (locked-state :not-ready))
        effect-keys (map first effects)]
    (is (emitted-effects-valid? :actions/confirm-portfolio-optimizer-execution effects))
    (is (not (some #{:effects/execute-portfolio-optimizer-plan} effect-keys)))
    (is (some #{[:effects/save [:wallet :agent :recovery-modal-open?] true]} effects))))

(deftest confirm-execution-ready-agent-still-submits-test
  (let [effects (actions/confirm-portfolio-optimizer-execution (locked-state :ready))]
    (is (emitted-effects-valid? :actions/confirm-portfolio-optimizer-execution effects))
    (is (some #{:effects/execute-portfolio-optimizer-plan} (map first effects)))))

(deftest confirm-execution-blocks-read-only-plan-test
  (let [state {:portfolio {:optimizer {:execution-modal
                                       {:open? true
                                        :plan {:scenario-id "draft-1"
                                               :status :ready
                                               :execution-disabled? true
                                               :disabled-message "Spectate Mode is read-only."}}}}}]
    (is (= [[:effects/save
             [:portfolio :optimizer :execution-modal :error]
             "Spectate Mode is read-only."]]
           (actions/confirm-portfolio-optimizer-execution state)))))

(deftest resume-execution-retries-only-failed-rows-test
  (let [plan {:scenario-id "draft-1"
              :status :partially-blocked
              :execution-disabled? false
              :summary {:ready-count 2 :blocked-count 0 :skipped-count 0
                        :gross-ready-notional-usd 3000
                        :estimated-fees-usd 1 :estimated-slippage-usd 2 :margin nil}
              :rows [{:row-id "perp:BTC" :status :ready :delta-notional-usd 1000}
                     {:row-id "perp:ETH" :status :ready :delta-notional-usd 2000}]}
        ledger {:rows [{:row-id "perp:BTC" :instrument-id "perp:BTC" :status :submitted
                        :delta-notional-usd 1000 :intent {:kind :perp-order}
                        :response {:status "ok"}}
                       {:row-id "perp:ETH" :instrument-id "perp:ETH" :status :failed
                        :delta-notional-usd 2000 :intent {:kind :perp-order}
                        :error {:message "rejected"}}]}
        state {:portfolio {:optimizer {:execution-modal {:open? true :plan plan}
                                       :execution {:status :partially-executed
                                                   :history [ledger]}}}}
        effects (actions/resume-portfolio-optimizer-execution state)
        [effect-key resume-plan] (nth effects 2)
        row-by-id (into {} (map (juxt :row-id identity) (:rows resume-plan)))]
    (is (= [:effects/save [:portfolio :optimizer :execution-modal :submitting?] true]
           (first effects)))
    (is (= [:effects/save [:portfolio :optimizer :execution-modal :error] nil]
           (second effects)))
    (is (= :effects/execute-portfolio-optimizer-plan effect-key))
    (is (= {:status :skipped :reason :already-filled}
           (select-keys (row-by-id "perp:BTC") [:status :reason])))
    (is (nil? (:intent (row-by-id "perp:BTC"))))
    (is (= :ready (:status (row-by-id "perp:ETH"))))
    (is (nil? (:error (row-by-id "perp:ETH"))))
    (is (= {:kind :perp-order} (:intent (row-by-id "perp:ETH"))))
    (is (= 1 (get-in resume-plan [:summary :ready-count])))
    (is (= 1 (get-in resume-plan [:summary :skipped-count])))
    (is (= 2000 (get-in resume-plan [:summary :gross-ready-notional-usd])))))

(deftest resume-execution-noops-when-nothing-recoverable-test
  (let [ledger {:rows [{:row-id "perp:BTC" :status :submitted :delta-notional-usd 1000}]}
        state {:portfolio {:optimizer {:execution-modal {:plan {:scenario-id "draft-1"
                                                                :execution-disabled? false
                                                                :summary {}
                                                                :rows []}}
                                       :execution {:history [ledger]}}}}]
    (is (= [[:effects/save
             [:portfolio :optimizer :execution-modal :error]
             "No orders are eligible to resume."]]
           (actions/resume-portfolio-optimizer-execution state)))))

(deftest revert-execution-builds-reversing-orders-from-filled-ledger-test
  (let [plan {:scenario-id "draft-1" :execution-disabled? false :summary {} :rows []}
        ledger {:rows [{:row-id "perp:BTC" :instrument-id "perp:BTC" :instrument-type :perp
                        :status :submitted :side :buy :quantity 0.25 :price 100
                        :delta-notional-usd 1000}
                       {:row-id "perp:ETH" :instrument-id "perp:ETH" :instrument-type :perp
                        :status :failed :side :buy :quantity 0.5 :delta-notional-usd 2000}]}
        state {:portfolio {:optimizer {:execution-modal {:plan plan}
                                       :execution {:history [ledger]}}}}
        effects (actions/revert-portfolio-optimizer-execution-filled state)
        [effect-key revert-plan] (nth effects 2)
        row (first (:rows revert-plan))]
    (is (= [:effects/save [:portfolio :optimizer :execution-modal :submitting?] true]
           (first effects)))
    (is (= :effects/execute-portfolio-optimizer-plan effect-key))
    (is (= :revert (:kind revert-plan)))
    (is (= 1 (count (:rows revert-plan))))
    (is (= "perp:BTC" (:row-id row)))
    (is (= :sell (:side row)))
    (is (= :ready (:status row)))
    (is (= true (get-in row [:intent :reduce-only?])))
    (is (= :market (get-in row [:intent :order-type])))))

(deftest revert-execution-noops-without-filled-rows-test
  (let [ledger {:rows [{:row-id "perp:ETH" :status :failed :delta-notional-usd 2000}]}
        state {:portfolio {:optimizer {:execution-modal {:plan {:execution-disabled? false
                                                                :summary {}
                                                                :rows []}}
                                       :execution {:history [ledger]}}}}]
    (is (= [[:effects/save
             [:portfolio :optimizer :execution-modal :error]
             "No filled orders to revert."]]
           (actions/revert-portfolio-optimizer-execution-filled state)))))

(deftest restage-execution-halves-unfilled-rows-and-returns-to-staged-test
  (let [plan {:scenario-id "draft-1" :execution-disabled? false
              :summary {:ready-count 2}
              :rows [{:row-id "perp:BTC" :status :ready :quantity 0.25 :delta-notional-usd 1000
                      :intent {:quantity 0.25}}
                     {:row-id "perp:ETH" :status :ready :quantity 1.0 :delta-notional-usd 2000
                      :intent {:quantity 1.0}}]}
        ledger {:rows [{:row-id "perp:BTC" :status :submitted}
                       {:row-id "perp:ETH" :status :failed}]}
        state {:portfolio {:optimizer {:execution-modal {:plan plan}
                                       :execution {:history [ledger]}}}}
        effects (actions/restage-portfolio-optimizer-execution-smaller state)
        [_ plan-path restaged] (first effects)
        eth (first (:rows restaged))]
    (is (= [:portfolio :optimizer :execution-modal :plan] plan-path))
    (is (= 1 (count (:rows restaged))))
    (is (= "perp:ETH" (:row-id eth)))
    (is (= 0.5 (:quantity eth)))
    (is (= 0.5 (get-in eth [:intent :quantity])))
    (is (= 1000 (:delta-notional-usd eth)))
    (is (= 1 (get-in restaged [:summary :ready-count])))
    (is (= [:effects/save [:portfolio :optimizer :execution-modal :phase] :staged]
           (second effects)))))

(deftest pause-execution-sets-abort-flag-test
  (is (= [[:effects/save [:portfolio :optimizer :execution :abort-requested?] true]]
         (actions/pause-portfolio-optimizer-execution {}))))

(deftest discard-execution-clears-plan-and-returns-to-recommendation-test
  (let [state {:portfolio {:optimizer {:execution-modal {:open? true :submitting? false
                                                         :plan {:rows []}}}}}
        effects (actions/discard-portfolio-optimizer-execution state)]
    (is (= [:portfolio :optimizer :execution-modal] (get-in effects [0 1])))
    (is (= [:portfolio :optimizer :execution] (get-in effects [1 1])))
    (is (= [:effects/save [:portfolio-ui :optimizer :results-tab] :recommendation]
           (nth effects 2)))
    (is (= [:effects/replace-shareable-route-query] (nth effects 3)))))

(deftest discard-execution-noops-while-submitting-test
  (is (= [] (actions/discard-portfolio-optimizer-execution
             {:portfolio {:optimizer {:execution-modal {:submitting? true}}}}))))

(deftest open-in-ticket-navigates-to-first-ready-market-test
  (let [state {:portfolio {:optimizer {:execution-modal
                                       {:plan {:rows [{:row-id "perp:ETH" :status :blocked :coin "ETH"}
                                                      {:row-id "perp:BTC" :status :ready :coin "BTC"}]}}}}}]
    (is (= [[:actions/navigate "/trade?market=BTC"]]
           (actions/open-portfolio-optimizer-execution-in-ticket state))))
  (is (= [] (actions/open-portfolio-optimizer-execution-in-ticket
             {:portfolio {:optimizer {:execution-modal {:plan {:rows []}}}}}))))
