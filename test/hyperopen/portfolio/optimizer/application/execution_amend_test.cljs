(ns hyperopen.portfolio.optimizer.application.execution-amend-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.execution-amend :as amend]))

(defn- resting-row
  ([] (resting-row 777 42))
  ([oid asset-idx]
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
    :request {:action {:type "order" :orders [{:a asset-idx :b true :s "681.7"}]}}
    :response {:status "ok"
               :response {:data {:statuses [{:resting {:oid oid}}]}}}}))

(def ^:private filled-sibling
  {:row-id "perp:WLD"
   :instrument-id "perp:WLD"
   :instrument-type :perp
   :coin "WLD"
   :side :sell
   :quantity 10
   :status :submitted
   :response {:status "ok"
              :response {:data {:statuses [{:filled {:avgPx "0.44" :totalSz "10"}}]}}}})

(def ^:private blocked-sibling
  {:row-id "perp:REZ"
   :instrument-id "perp:REZ"
   :instrument-type :perp
   :coin "REZ"
   :side :buy
   :quantity 0
   :status :blocked
   :reason :quantity-below-lot})

(def ^:private zeta-market
  {:szDecimals 1 :markRaw "0.0465" :mark "0.05" :asset-id 42})

(defn- live-state
  ([] (live-state [{:oid 777 :coin "ZETA" :sz "300.5" :limitPx "0.045"}]))
  ([open-orders]
   {:orders {:open-orders-hydrated? true
             :open-orders open-orders}}))

(def ^:private market-by-key {"perp:ZETA" zeta-market})

(deftest amend-selections-defaults-to-current-type-at-mark-test
  (is (= {:order-type :passive :limit-bps 0}
         (amend/amend-selections {} (resting-row)))))

(deftest amend-selections-honors-override-and-params-test
  (is (= {:order-type :market :limit-bps 0}
         (amend/amend-selections {:overrides {"perp:ZETA" :market}} (resting-row))))
  (is (= {:order-type :limit :limit-bps -5}
         (amend/amend-selections {:overrides {"perp:ZETA" :limit}
                                  :params {"perp:ZETA" {:limit-bps -5}}}
                                 (resting-row)))))

(deftest amend-selections-rejects-non-amendable-types-test
  (is (= :passive
         (:order-type (amend/amend-selections {:overrides {"perp:ZETA" :twap}}
                                              (resting-row)))))
  (is (= :limit
         (:order-type (amend/amend-selections {} (assoc (resting-row)
                                                        :order-type :twap))))))

(deftest live-native-mark-prefers-mark-raw-test
  (is (= 0.0465 (amend/live-native-mark zeta-market)))
  (is (= 0.05 (amend/live-native-mark {:mark "0.05"})))
  (is (nil? (amend/live-native-mark {:mark "not-a-number"})))
  (is (nil? (amend/live-native-mark nil))))

(deftest amend-target-resolves-live-context-test
  (let [target (amend/amend-target (live-state) market-by-key (resting-row))]
    (is (= "777" (:oid target)))
    (is (= 42 (:asset-id target))
        "wire asset index frozen from the row's own original request")
    (is (= 300.5 (:remaining-size target))
        "replacement size is the live REMAINING size, not the original quantity")
    (is (= 0.045 (:limit-px target)))
    (is (= 0.0465 (:live-mark target)))))

(deftest amend-target-nil-for-non-working-rows-test
  (testing "a filled row has nothing on the book to amend"
    (is (nil? (amend/amend-target (live-state) market-by-key filled-sibling))))
  (testing "hydrated book without the oid — the order filled or was cancelled elsewhere"
    (is (nil? (amend/amend-target (live-state [{:oid 999 :coin "ZEN"}])
                                  market-by-key (resting-row)))))
  (testing "book not hydrated — we cannot see the live remaining size, so refuse"
    (is (nil? (amend/amend-target {:orders {:open-orders-hydrated? false}}
                                  market-by-key (resting-row))))))

(def ^:private base-plan
  {:scenario-id "scenario-1"
   :summary {:ready-count 0 :blocked-count 1 :skipped-count 0}})

(defn- ledger-with-target
  []
  {:attempt-id "exec_1"
   :rows [(resting-row) filled-sibling blocked-sibling]})

(defn- happy-plan
  ([] (happy-plan {}))
  ([selections]
   (amend/build-amend-plan
    {:plan base-plan
     :ledger (ledger-with-target)
     :row-id "perp:ZETA"
     :selections selections
     :target (amend/amend-target (live-state) market-by-key (resting-row))})))

(deftest build-amend-plan-re-arms-only-the-target-test
  (let [{:keys [plan error]} (happy-plan)
        rows (:rows plan)
        target (first (filter #(= "perp:ZETA" (:row-id %)) rows))]
    (is (nil? error))
    (is (= :amend (:kind plan)))
    (is (= :ready (:status target)))
    (is (= 300.5 (:quantity target)) "re-armed at the live remaining size")
    (is (= 0.0465 (:price target)) "reference price refreshed to the live native mark")
    (is (nil? (:request target)))
    (is (nil? (:response target)) "settled artifacts stripped so a fresh request builds")
    (is (= {:kind :perp-order
            :instrument-id "perp:ZETA"
            :side :buy
            :quantity 300.5
            :order-type :passive
            :limit-bps 0
            :reduce-only? false}
           (:intent target)))
    (is (= [filled-sibling blocked-sibling]
           (filterv #(not= "perp:ZETA" (:row-id %)) rows))
        "sibling rows pass through byte-identical — they are still live/settled")))

(deftest build-amend-plan-cancels-exactly-the-target-test
  (let [{:keys [plan]} (happy-plan)]
    (is (= 1 (count (:cancel-orders plan))))
    (is (= {:oid "777" :coin "ZETA" :instrument-id "perp:ZETA"
            :side :buy :quantity 681.7 :asset-id 42}
           (first (:cancel-orders plan))))))

(deftest build-amend-plan-recomputes-summary-test
  (let [{:keys [plan]} (happy-plan)]
    (is (= :partially-blocked (:status plan)) "one ready row + a blocked sibling")
    (is (= 1 (get-in plan [:summary :ready-count])))
    (is (= 1 (get-in plan [:summary :blocked-count])))
    (is (= 0 (get-in plan [:summary :skipped-count])))
    (is (< 13.9 (get-in plan [:summary :gross-ready-notional-usd]) 14.0)
        "gross notional = remaining 300.5 × live mark 0.0465")))

(deftest build-amend-plan-market-conversion-test
  (let [{:keys [plan]} (happy-plan {:overrides {"perp:ZETA" :market}})
        target (first (filter #(= "perp:ZETA" (:row-id %)) (:rows plan)))]
    (is (= :market (get-in target [:intent :order-type])))
    (is (nil? (get-in target [:intent :limit-bps]))
        "a crossing order carries no resting offset")))

(deftest build-amend-plan-refusals-test
  (testing "no live target — nothing safe to cancel-and-replace"
    (is (= :not-amendable
           (:error (amend/build-amend-plan {:plan base-plan
                                            :ledger (ledger-with-target)
                                            :row-id "perp:ZETA"
                                            :target nil})))))
  (testing "row absent from the ledger"
    (is (= :not-amendable
           (:error (amend/build-amend-plan
                    {:plan base-plan
                     :ledger {:rows [filled-sibling]}
                     :row-id "perp:ZETA"
                     :target (amend/amend-target (live-state) market-by-key
                                                 (resting-row))})))))
  (testing "remaining size floors below one lot — refusing beats cancel-then-block"
    (let [dust-state (live-state [{:oid 777 :coin "ZETA" :sz "0.4" :limitPx "0.045"}])
          dust-market {"perp:ZETA" (assoc zeta-market :szDecimals 0)}
          target (amend/amend-target dust-state dust-market (resting-row))]
      (is (= :remaining-below-lot
             (:error (amend/build-amend-plan {:plan base-plan
                                              :ledger (ledger-with-target)
                                              :row-id "perp:ZETA"
                                              :target target}))))))
  (testing "no catalog market — cannot build a replacement or size-check it"
    (let [target (-> (amend/amend-target (live-state) market-by-key (resting-row))
                     (assoc :market nil))]
      (is (= :market-unavailable
             (:error (amend/build-amend-plan {:plan base-plan
                                              :ledger (ledger-with-target)
                                              :row-id "perp:ZETA"
                                              :target target})))))))
