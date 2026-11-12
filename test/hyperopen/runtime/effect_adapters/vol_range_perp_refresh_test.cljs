(ns hyperopen.runtime.effect-adapters.vol-range-perp-refresh-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.order.effects :as order-effects]
            [hyperopen.order.effects.spot-refresh :as spot-refresh]
            [hyperopen.runtime.effect-adapters.vol-range-execution :as vre]))

(deftest a-perp-ladder-send-refreshes-every-dex-open-orders-test
  ;; The `openOrders` stream carries the first perp dex and spot only, so a
  ;; HIP-3 ladder's orders appeared in Open Orders only after a page reload.
  (let [surfaces (atom [])
        spot (atom [])
        store (atom {})
        perp-grid {:scope {:market-type :perp :dex "xyz" :coin "xyz:NVDA" :account-address "0xabc"}}
        spot-grid {:scope {:market-type :spot :coin "@107" :account-address "0xabc"}}]
    (with-redefs [order-effects/refresh-account-surfaces-after-order-mutation!
                  (fn [_store address & _] (swap! surfaces conj address))
                  spot-refresh/refresh-spot-clearinghouse-snapshot!
                  (fn [_store address _opts] (swap! spot conj address))]
      (vre/refresh-surfaces-after-send! store :place {:grid perp-grid :status :live})
      (is (= ["0xabc"] @surfaces))
      (is (empty? @spot))
      (vre/refresh-surfaces-after-send! store :cancel {:grid perp-grid :status :closed})
      (is (= ["0xabc" "0xabc"] @surfaces))
      (vre/refresh-surfaces-after-send! store :place {:grid perp-grid :status :refused})
      (vre/refresh-surfaces-after-send! store :reconcile-open {:grid perp-grid :status :live})
      (is (= 2 (count @surfaces)) "nothing sent, nothing refetched")
      (vre/refresh-surfaces-after-send! store :place {:grid spot-grid :status :live})
      (is (= 2 (count @surfaces)) "a spot ladder keeps its spot-only refresh")
      (is (= ["0xabc"] @spot)))))
