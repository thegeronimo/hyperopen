(ns hyperopen.api.trading.cancel-request-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api.trading :as trading]))

(deftest resolve-cancel-order-oid-normalizes-supported-wire-keys-test
  (is (= 42
         (trading/resolve-cancel-order-oid {:oid "42"})))
  (is (= 42
         (trading/resolve-cancel-order-oid {:o "42"})))
  (is (= 42
         (trading/resolve-cancel-order-oid {:order {:oid "42"}})))
  (is (= 42
         (trading/resolve-cancel-order-oid {:order {:o "42"}}))))

(deftest resolve-cancel-order-oid-preserves-safe-integer-boundary-test
  (is (= 9007199254740991
         (trading/resolve-cancel-order-oid {:oid "9007199254740991"}))))

(deftest build-cancel-order-request-normalizes-order-shape-and-integer-fields-test
  (let [state {:asset-contexts {}
               :asset-selector {:market-by-key {}}}
        request (trading/build-cancel-order-request state {:order {:assetIdx "12"
                                                                   :oid "307891000622"}})]
    (is (= {:action {:type "cancel"
                     :cancels [{:a 12
                                :o 307891000622}]}}
           request))))

(deftest build-cancel-order-request-preserves-safe-integer-boundary-values-test
  (let [state {:asset-contexts {}
               :asset-selector {:market-by-key {}}}
        request (trading/build-cancel-order-request state {:order {:assetIdx "9007199254740991"
                                                                   :oid "9007199254740991"}})]
    (is (= {:action {:type "cancel"
                     :cancels [{:a 9007199254740991
                                :o 9007199254740991}]}}
           request))))

(deftest build-cancel-order-request-falls-back-to-market-and-context-index-test
  (let [market-fallback-state {:asset-contexts {}
                               :asset-selector {:market-by-key {"perp:SOL" {:coin "SOL"
                                                                            :idx 7}}}}
        context-fallback-state {:asset-contexts {:BTC {:idx "5"}}
                                :asset-selector {:market-by-key {}}}]
    (is (= {:action {:type "cancel"
                     :cancels [{:a 7 :o 99}]}}
           (trading/build-cancel-order-request market-fallback-state {:coin "SOL"
                                                                      :oid "99"})))
    (is (= {:action {:type "cancel"
                     :cancels [{:a 5 :o 88}]}}
           (trading/build-cancel-order-request context-fallback-state {:coin "BTC"
                                                                       :oid "88"})))))

(deftest build-cancel-order-request-uses-canonical-hip3-asset-id-test
  (let [state-with-asset-id {:asset-contexts {:hyna:GOLD {:idx 5}}
                             :asset-selector {:market-by-key {"perp:hyna:GOLD" {:coin "hyna:GOLD"
                                                                                 :dex "hyna"
                                                                                 :market-type :perp
                                                                                 :idx 5
                                                                                 :asset-id 110005}}}}
        state-missing-asset-id {:asset-contexts {:hyna:GOLD {:idx 5}}
                                :asset-selector {:market-by-key {"perp:hyna:GOLD" {:coin "hyna:GOLD"
                                                                                    :dex "hyna"
                                                                                    :market-type :perp
                                                                                    :idx 5}}}}]
    (is (= {:action {:type "cancel"
                     :cancels [{:a 110005 :o 88}]}}
           (trading/build-cancel-order-request state-with-asset-id {:coin "hyna:GOLD"
                                                                    :oid "88"})))
    (is (nil? (trading/build-cancel-order-request state-missing-asset-id {:coin "hyna:GOLD"
                                                                           :oid "88"})))))

(deftest build-cancel-order-request-returns-nil-when-required-fields-are-missing-test
  (is (nil? (trading/build-cancel-order-request {:asset-contexts {}
                                                 :asset-selector {:market-by-key {}}}
                                                {:coin "BTC"})))
  (is (nil? (trading/build-cancel-order-request {:asset-contexts {}
                                                 :asset-selector {:market-by-key {}}}
                                                {:oid 101}))))

(deftest build-cancel-orders-request-builds-a-batched-cancel-action-test
  (let [state {:asset-contexts {:BTC {:idx 0}
                                :ETH {:idx 1}}
               :asset-selector {:market-by-key {}}}
        request (trading/build-cancel-orders-request state [{:coin "BTC"
                                                             :oid "101"}
                                                            {:coin "ETH"
                                                             :oid "202"}])]
    (is (= {:action {:type "cancel"
                     :cancels [{:a 0 :o 101}
                               {:a 1 :o 202}]}}
           request))))

(deftest build-cancel-orders-request-deduplicates-identical-visible-orders-test
  (let [state {:asset-contexts {:BTC {:idx 0}}
               :asset-selector {:market-by-key {}}}
        request (trading/build-cancel-orders-request state [{:coin "BTC"
                                                             :oid "101"}
                                                            {:coin "BTC"
                                                             :oid "101"}])]
    (is (= {:action {:type "cancel"
                     :cancels [{:a 0 :o 101}]}}
           request))))

(deftest build-cancel-orders-request-returns-nil-when-any-order-is-invalid-test
  (let [state {:asset-contexts {:BTC {:idx 0}}
               :asset-selector {:market-by-key {}}}]
    (is (nil? (trading/build-cancel-orders-request state [{:coin "BTC"
                                                           :oid "101"}
                                                          {:coin "ETH"}])))
    (is (nil? (trading/build-cancel-orders-request state [])))))
