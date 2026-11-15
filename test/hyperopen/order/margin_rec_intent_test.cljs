(ns hyperopen.order.margin-rec-intent-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.margin-rec.state :as margin-rec-state]
            [hyperopen.order.actions :as order-actions]))

(def xyz-position
  {:coin "xyz:TSM"
   :szi "0.36"
   :entryPx "446.441"
   :positionValue "157.5"
   :liquidationPx "424.20"
   :marginUsed "12.42"
   :maxLeverage 10
   :leverage {:type "isolated" :value 10}})

(defn- base-state
  [& [overrides]]
  (merge {:active-market {:coin "xyz:TSM" :symbol "TSM"}
          :trading-settings {:margin-rec-auto-topup? true}
          :perp-dex-clearinghouse {"xyz" {:assetPositions [{:position xyz-position}]}}
          :webdata2 {}
          :margin-rec (margin-rec-state/default-state)}
         overrides))

(def isolated-form {:margin-mode :isolated})

(defn- order-request
  [& [order-overrides]]
  {:action {:type "order"
            :orders [(merge {:a 100001 :b true :p "440" :s "0.2" :r false}
                            order-overrides)]}})

(deftest creates-intent-for-risk-increasing-isolated-order
  (let [[effect-id path intents] (order-actions/margin-rec-intent-save
                                  (base-state)
                                  isolated-form
                                  (order-request))
        intent (get intents "xyz:TSM|xyz")]
    (is (= :effects/save effect-id))
    (is (= margin-rec-state/intents-path path))
    (is (some? intent))
    (is (= :pending (:status intent)))
    (is (= "xyz:TSM" (:coin intent)))
    (is (= "xyz" (:dex intent)))
    (is (nil? (:target-equity intent)))
    (is (nil? (:expires-at intent)))
    (testing "expected size covers the existing position plus the order"
      (is (< (js/Math.abs (- 0.56 (:expected-size intent))) 1e-9)))
    (testing "top-up is capped by the order notional"
      (is (= 88 (:max-add intent))))))

(deftest intent-guards
  (testing "disabled setting"
    (is (nil? (order-actions/margin-rec-intent-save
               (base-state {:trading-settings {}})
               isolated-form
               (order-request)))))
  (testing "cross margin mode"
    (is (nil? (order-actions/margin-rec-intent-save
               (base-state)
               {:margin-mode :cross}
               (order-request)))))
  (testing "reduce-only orders never create intents"
    (is (nil? (order-actions/margin-rec-intent-save
               (base-state)
               isolated-form
               (order-request {:r true})))))
  (testing "orders that shrink the position never create intents"
    (is (nil? (order-actions/margin-rec-intent-save
               (base-state)
               isolated-form
               (order-request {:b false :s "0.2"})))))
  (testing "multi-order and non-order actions are skipped"
    (is (nil? (order-actions/margin-rec-intent-save
               (base-state)
               isolated-form
               {:action {:type "twapOrder"}})))
    (is (nil? (order-actions/margin-rec-intent-save
               (base-state)
               isolated-form
               (update-in (order-request) [:action :orders]
                          conj {:a 1 :b true :p "1" :s "1"}))))))

(deftest submit-flow-includes-intent-before-heavy-io
  (let [draft (margin-rec-state/make-intent-draft
               {:position-key "xyz:TSM|xyz"
                :coin "xyz:TSM"
                :dex "xyz"
                :expected-size 0.36
                :target-equity 18.64
                :source :trade})]
    (is (nil? (:expires-at draft)))
    (let [stamped (margin-rec-state/stamp-intent draft 1000)]
      (is (= 1000 (:created-at stamped)))
      (is (= (+ 1000 margin-rec-state/intent-ttl-ms) (:expires-at stamped)))
      (testing "stamping is idempotent"
        (is (= stamped (margin-rec-state/stamp-intent stamped 9999)))))))
