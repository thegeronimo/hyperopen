(ns hyperopen.state.trading.order-request-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.state.trading :as trading]
            [hyperopen.state.trading.test-support :as support]))

(def base-state support/base-state)

(defn- js-object-keys
  [value]
  (support/js-object-keys value))

(deftest build-order-request-uses-canonical-key-order-for-l1-signing-test
  (let [state (assoc base-state
                     :asset-contexts {:BTC {:idx 5}})
        form (-> (trading/default-order-form)
                 (assoc :type :limit
                        :side :buy
                        :size "1"
                        :price "50"))
        request (trading/build-order-request state form)
        action-js (clj->js (:action request))
        order-js (aget (aget action-js "orders") 0)]
    (is (= ["type" "orders" "grouping"] (js-object-keys action-js)))
    (is (= ["a" "b" "p" "s" "r" "t"] (js-object-keys order-js)))
    (is (= ["limit"] (js-object-keys (aget order-js "t"))))
    (is (= ["tif"] (js-object-keys (aget (aget order-js "t") "limit"))))))

(deftest build-order-request-fails-closed-when-required-numerics-missing-test
  (let [state (assoc base-state
                     :asset-contexts {:BTC {:idx 5}})
        base-form (assoc (trading/default-order-form)
                         :side :buy
                         :size "1")]
    (is (nil? (trading/build-order-request state (assoc base-form :type :limit :price ""))))
    (is (nil? (trading/build-order-request state (assoc base-form :type :market :price ""))))
    (is (nil? (trading/build-order-request state (assoc base-form
                                                        :type :stop-market
                                                        :price ""
                                                        :trigger-px ""))))
    (is (nil? (trading/build-order-request state (-> base-form
                                                     (assoc :type :limit :price "100")
                                                     (assoc-in [:tp :enabled?] true)
                                                     (assoc-in [:tp :trigger] "")))))
    (is (nil? (trading/build-order-request state (assoc base-form
                                                        :type :twap
                                                        :twap {:minutes 0
                                                               :randomize true}))))))

(deftest build-order-request-resolves-canonical-asset-id-for-hip3-test
  (let [form (-> (trading/default-order-form)
                 (assoc :type :limit
                        :side :buy
                        :size "1"
                        :price "100"))]
    (testing "named-dex perp uses active-market canonical asset-id"
      (let [state {:active-asset "hyna:GOLD"
                   :active-market {:coin "hyna:GOLD"
                                   :dex "hyna"
                                   :market-type :perp
                                   :asset-id 110005
                                   :mark 100
                                   :szDecimals 2}
                   :orderbooks {"hyna:GOLD" {:bids [{:px "99"}]
                                             :asks [{:px "101"}]}}
                   :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                                   :totalMarginUsed "250"}}}
                   :asset-contexts {}}
            request (trading/build-order-request state form)]
        (is (= 110005 (get-in request [:action :orders 0 :a])))))

    (testing "named-dex perp fails closed when canonical asset-id is unavailable"
      (let [state {:active-asset "hyna:GOLD"
                   :active-market {:coin "hyna:GOLD"
                                   :dex "hyna"
                                   :market-type :perp
                                   :idx 5
                                   :mark 100
                                   :szDecimals 2}
                   :orderbooks {"hyna:GOLD" {:bids [{:px "99"}]
                                             :asks [{:px "101"}]}}
                   :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                                   :totalMarginUsed "250"}}}
                   :asset-contexts {:hyna:GOLD {:idx 5}}}
            request (trading/build-order-request state form)]
        (is (nil? request))))))
