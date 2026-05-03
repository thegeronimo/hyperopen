(ns hyperopen.state.trading.order-request-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.formal.order-request-standard-vectors :as standard-vectors]
            [hyperopen.schema.order-request-contracts :as contracts]
            [hyperopen.state.trading :as trading]
            [hyperopen.state.trading.test-support :as support]))

(def base-state support/base-state)

(defn- js-object-keys
  [value]
  (support/js-object-keys value))

(defn- state-from-command-context
  [{:keys [active-asset asset-idx market]}]
  {:active-asset active-asset
   :active-market (merge {:coin active-asset}
                         (or market {}))
   :asset-contexts (cond-> {}
                     (and (string? active-asset)
                          (number? asset-idx))
                     (assoc active-asset {:idx asset-idx}))})

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
    (is (= request
           (contracts/assert-order-request! request {:test :canonical-key-order})))
    (is (= ["type" "orders" "grouping"] (js-object-keys action-js)))
    (is (= ["a" "b" "p" "s" "r" "t"] (js-object-keys order-js)))
    (is (= ["limit"] (js-object-keys (aget order-js "t"))))
    (is (= ["tif"] (js-object-keys (aget (aget order-js "t") "limit"))))))

(deftest build-order-request-state-wrapper-conforms-to-committed-formal-vectors-test
  (doseq [{:keys [id context form expected]} (filter #(= :submit-ready (:contract %))
                                                     standard-vectors/standard-order-request-vectors)]
    (when (some? expected)
      (testing (name id)
        (let [state (state-from-command-context context)
              request (trading/build-order-request state form)]
          (is (= expected request))
          (is (= expected
                 (contracts/assert-order-request! request {:vector id}))))))))

(deftest build-order-request-preserves-pre-action-key-order-for-signing-test
  (let [state (state-from-command-context standard-vectors/btc-perp-context)
        form {:type :limit
              :side :buy
              :size "1"
              :price "100"
              :ui-leverage 17
              :margin-mode :cross}
        request (trading/build-order-request state form)
        pre-action-js (clj->js (first (:pre-actions request)))]
    (is (= ["type" "asset" "isCross" "leverage"]
           (js-object-keys pre-action-js)))))

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
        (is (= request
               (contracts/assert-order-request! request {:test :hip3-canonical-asset-id})))
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

(deftest build-order-request-resolves-selected-outcome-side-asset-id-test
  (let [base-form (-> (trading/default-order-form)
                      (assoc :type :limit
                             :side :buy
                             :size "1"
                             :price "0.58"))
        state {:active-asset "outcome:0"
               :active-market {:coin "outcome:0"
                               :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                               :title "BTC above 78213 on May 3 at 2:00 AM?"
                               :quote "USDC"
                               :market-type :outcome
                               :mark 0.58
                               :szDecimals 0
                               :outcome-sides [{:side-index 0
                                                :side-label "Yes"
                                                :coin "#0"
                                                :asset-id 100000000}
                                               {:side-index 1
                                                :side-label "No"
                                                :coin "#1"
                                                :asset-id 100000001}]}
               :orderbooks {"#0" {:bids [{:px "0.57"}]
                                  :asks [{:px "0.59"}]}
                            "#1" {:bids [{:px "0.41"}]
                                  :asks [{:px "0.43"}]}}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                               :totalMarginUsed "250"}}}
               :asset-contexts {}}
        default-request (trading/build-order-request state (assoc base-form
                                                                  :ui-leverage 10
                                                                  :margin-mode :cross))
        no-request (trading/build-order-request state (assoc base-form :outcome-side 1))
        sell-no-request (trading/build-order-request state (assoc base-form
                                                                  :side :sell
                                                                  :outcome-side 1))]
    (is (= 100000000 (get-in default-request [:action :orders 0 :a])))
    (is (= 100000001 (get-in no-request [:action :orders 0 :a])))
    (is (= 100000001 (get-in sell-no-request [:action :orders 0 :a])))
    (is (false? (get-in sell-no-request [:action :orders 0 :b])))
    (is (nil? (:pre-actions default-request)))))
