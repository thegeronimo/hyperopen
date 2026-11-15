(ns hyperopen.portfolio.optimizer.execution-amend-actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.actions.execution :as exec-actions]
            [hyperopen.schema.contracts :as contracts]))

(defn- emitted-effects-valid?
  "Runs the emitted effects through the real action-emission contract — every emitted
  item must be a registered `effects/*` effect with conforming args (a raw
  `[:actions/...]` return throws at runtime dispatch, not in a plain assertion)."
  [action-id effects]
  (= effects (contracts/assert-emitted-effects!
              effects {:phase :action-emission :action-id action-id})))

(def ^:private resting-ledger-row
  {:row-id "perp:ZETA"
   :instrument-id "perp:ZETA"
   :instrument-type :perp
   :coin "ZETA"
   :side :buy
   :quantity 681.7
   :price 0.046
   :order-type :passive
   :status :resting
   :delta-notional-usd 31.36
   :request {:action {:type "order" :orders [{:a 42 :b true :s "681.7"}]}}
   :response {:status "ok"
              :response {:data {:statuses [{:resting {:oid 777}}]}}}})

(def ^:private ledger
  {:attempt-id "exec_1"
   :status :resting
   :rows [resting-ledger-row]})

(defn- amendable-state
  ([] (amendable-state {}))
  ([{:keys [agent-status modal-extra optimizer-extra open-orders]
     :or {agent-status :ready
          open-orders [{:oid 777 :coin "ZETA" :sz "300.5" :limitPx "0.045"}]}}]
   {:wallet {:agent {:status agent-status}}
    :asset-selector {:market-by-key
                     {"perp:ZETA" {:szDecimals 1 :markRaw "0.0465" :asset-id 42}}}
    :orders {:open-orders-hydrated? true
             :open-orders open-orders}
    :portfolio {:optimizer
                (merge {:execution-modal
                        (merge {:plan {:scenario-id "s1"
                                       :summary {:ready-count 0}
                                       :rows []}}
                               modal-extra)
                        :execution {:history [ledger]}}
                       optimizer-extra)}}))

(deftest amend-submits-cancel-and-replacement-test
  (let [effects (exec-actions/amend-portfolio-optimizer-execution-order
                 (amendable-state) "perp:ZETA")
        [save-submitting save-error [effect-id plan]] effects]
    (is (= 3 (count effects)))
    (is (= [:effects/save [:portfolio :optimizer :execution-modal :submitting?] true]
           save-submitting))
    (is (= [:effects/save [:portfolio :optimizer :execution-modal :error] nil]
           save-error))
    (is (= :effects/execute-portfolio-optimizer-plan effect-id))
    (is (= :amend (:kind plan)))
    (is (= ["777"] (mapv :oid (:cancel-orders plan)))
        "cancels exactly the order being replaced")
    (let [row (first (filter #(= "perp:ZETA" (:row-id %)) (:rows plan)))]
      (is (= :ready (:status row)))
      (is (= 300.5 (:quantity row)) "replacement sized to the live remaining size"))
    (is (emitted-effects-valid? :actions/amend-portfolio-optimizer-execution-order
                                effects))))

(deftest amend-never-attaches-the-session-carryover-test
  (let [state (amendable-state
               {:open-orders [{:oid 777 :coin "ZETA" :sz "300.5" :limitPx "0.045"}
                              {:oid 888 :coin "WLD" :sz "10" :limitPx "0.44"}]
                :optimizer-extra
                {:execution-resting-carryover
                 [{:oid 888 :coin "WLD" :asset-id 31}]}})
        [_ _ [_ plan]] (exec-actions/amend-portfolio-optimizer-execution-order
                        state "perp:ZETA")]
    (is (= ["777"] (mapv :oid (:cancel-orders plan))))))

(deftest amend-uses-the-rows-modal-selections-test
  (let [state (amendable-state
               {:modal-extra {:overrides {"perp:ZETA" :market}
                              :params {"perp:ZETA" {:limit-bps -5}}}})
        [_ _ [_ plan]] (exec-actions/amend-portfolio-optimizer-execution-order
                        state "perp:ZETA")
        row (first (filter #(= "perp:ZETA" (:row-id %)) (:rows plan)))]
    (is (= :market (get-in row [:intent :order-type])))))

(deftest amend-noops-without-plan-ledger-or-mid-run-test
  (testing "no staged plan"
    (is (= [] (exec-actions/amend-portfolio-optimizer-execution-order
               (assoc-in (amendable-state)
                         [:portfolio :optimizer :execution-modal :plan] nil)
               "perp:ZETA"))))
  (testing "no execution history"
    (is (= [] (exec-actions/amend-portfolio-optimizer-execution-order
               (assoc-in (amendable-state)
                         [:portfolio :optimizer :execution :history] [])
               "perp:ZETA"))))
  (testing "a run already in flight"
    (is (= [] (exec-actions/amend-portfolio-optimizer-execution-order
               (assoc-in (amendable-state)
                         [:portfolio :optimizer :execution-modal :submitting?] true)
               "perp:ZETA")))))

(deftest amend-refuses-disabled-and-gone-orders-test
  (testing "read-only (spectate) plan"
    (let [state (assoc-in (amendable-state)
                          [:portfolio :optimizer :execution-modal :plan
                           :execution-disabled?] true)
          effects (exec-actions/amend-portfolio-optimizer-execution-order
                   state "perp:ZETA")]
      (is (= 1 (count effects)))
      (is (= [:portfolio :optimizer :execution-modal :error] (second (first effects))))))
  (testing "the order is no longer on the book"
    (let [state (amendable-state {:open-orders [{:oid 999 :coin "ZEN"}]})
          effects (exec-actions/amend-portfolio-optimizer-execution-order
                   state "perp:ZETA")]
      (is (= [[:effects/save
               [:portfolio :optimizer :execution-modal :error]
               "This order is no longer on the book — it filled or was cancelled."]]
             effects)))))

(deftest amend-agent-gate-test
  (testing "locked agent prompts the passkey unlock and replays THIS amend (row-id kept)"
    (let [effects (exec-actions/amend-portfolio-optimizer-execution-order
                   (amendable-state {:agent-status :locked}) "perp:ZETA")]
      (is (= [:effects/save :effects/save-many :effects/unlock-agent-trading]
             (mapv first effects)))
      (is (= {:after-success-actions
              [[:actions/amend-portfolio-optimizer-execution-order "perp:ZETA"]]}
             (last (last effects))))
      (is (emitted-effects-valid? :actions/amend-portfolio-optimizer-execution-order
                                  effects))))
  (testing "unlock already in flight holds without submitting"
    (let [effects (exec-actions/amend-portfolio-optimizer-execution-order
                   (amendable-state {:agent-status :unlocking}) "perp:ZETA")]
      (is (= [[:effects/save
               [:portfolio :optimizer :execution-modal :error]
               "Awaiting passkey before updating the order."]]
             effects))))
  (testing "any other non-ready status opens the enable-trading recovery modal"
    (let [effects (exec-actions/amend-portfolio-optimizer-execution-order
                   (amendable-state {:agent-status :not-ready}) "perp:ZETA")]
      (is (= [:effects/save :effects/save] (mapv first effects)))
      (is (= [:wallet :agent :recovery-modal-open?] (second (first effects)))))))
