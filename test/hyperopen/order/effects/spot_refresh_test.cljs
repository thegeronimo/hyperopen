(ns hyperopen.order.effects.spot-refresh-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.order.effects.spot-refresh :as spot-refresh]))

(defn- order-request
  [& asset-ids]
  {:action {:type "order"
            :orders (mapv (fn [asset-id] {:a asset-id :b true}) asset-ids)}})

(defn- cancel-request
  [& asset-ids]
  {:action {:type "cancel"
            :cancels (mapv (fn [asset-id] {:a asset-id :o 1}) asset-ids)}})

(deftest spot-balance-order-mutation-covers-spot-pairs-test
  ;; PURR/USDC is 10000 + pair idx 0; HYPE/USDC @1035 is 11035.
  (is (true? (spot-refresh/spot-balance-order-mutation? (order-request 10000))))
  (is (true? (spot-refresh/spot-balance-order-mutation? (order-request 11035))))
  (is (true? (spot-refresh/spot-balance-order-mutation? (order-request 0 11035)))
      "a mixed perp + spot batch still refreshes spot"))

(deftest spot-balance-order-mutation-covers-outcome-markets-test
  (is (true? (spot-refresh/spot-balance-order-mutation? (order-request 100000010)))))

(deftest spot-balance-order-mutation-skips-perps-test
  (is (false? (spot-refresh/spot-balance-order-mutation? (order-request 0))))
  (is (false? (spot-refresh/spot-balance-order-mutation? (order-request 9999))))
  (is (false? (spot-refresh/spot-balance-order-mutation? (order-request 110003)))
      "builder-deployed perps (100000 + dex*10000 + idx) are not spot")
  (is (false? (spot-refresh/spot-balance-order-mutation? (order-request js/NaN))))
  (is (false? (spot-refresh/spot-balance-order-mutation? {:action {:type "order"}}))))

(deftest spot-balance-order-mutation-covers-cancels-test
  (is (true? (spot-refresh/spot-balance-order-mutation? (cancel-request 10107)))
      "cancelling a resting spot order releases its balance hold")
  (is (false? (spot-refresh/spot-balance-order-mutation? (cancel-request 3)))))
