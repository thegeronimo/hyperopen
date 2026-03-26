(ns hyperopen.api.gateway.orders.commands-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.formal.order-request-advanced-vectors :as advanced-vectors]
            [hyperopen.formal.order-request-standard-vectors :as standard-vectors]
            [hyperopen.schema.order-request-contracts :as contracts]
            [hyperopen.api.gateway.orders.commands :as commands]))

(def command-context
  {:active-asset "BTC"
   :asset-idx 5
   :market {:market-type :perp
            :szDecimals 4}})

(def mon-command-context
  {:active-asset "MON"
   :asset-idx 215
   :market {:market-type :perp
            :szDecimals 0}})

(deftest build-scale-orders-respects-post-only-and-side-test
  (let [base-size {:size 9
                   :count 3
                   :skew 1.0
                   :sz-decimals 4}
        post-only-orders (commands/build-scale-orders 5 :sell base-size "100" "90" true true)
        regular-orders (commands/build-scale-orders 5 :buy base-size "100" "90" false false)]
    (is (= 3 (count post-only-orders)))
    (is (= "Alo" (get-in post-only-orders [0 :t :limit :tif])))
    (is (= false (get-in post-only-orders [0 :b])))
    (is (= true (get-in post-only-orders [0 :r])))
    (is (= "Gtc" (get-in regular-orders [0 :t :limit :tif])))
    (is (= true (get-in regular-orders [0 :b])))))

(deftest build-tpsl-orders-builds-enabled-legs-test
  (let [orders (commands/build-tpsl-orders
                5
                :buy
                {:size "2.5"
                 :tp {:enabled? true
                      :trigger "110"
                      :limit "112.5"
                      :is-market false}
                 :sl {:enabled? true
                      :trigger "90"
                      :is-market true}})]
    (is (= 2 (count orders)))
    (is (= false (get-in orders [0 :b])))
    (is (= "112.5" (get-in orders [0 :p])))
    (is (= "2.5" (get-in orders [0 :s])))
    (is (= true (get-in orders [0 :r])))
    (is (= false (get-in orders [0 :t :trigger :isMarket])))
    (is (= "110" (get-in orders [0 :t :trigger :triggerPx])))
    (is (= "tp" (get-in orders [0 :t :trigger :tpsl])))
    (is (= "90" (get-in orders [1 :p])))
    (is (= true (get-in orders [1 :t :trigger :isMarket])))
    (is (= "90" (get-in orders [1 :t :trigger :triggerPx])))
    (is (= "sl" (get-in orders [1 :t :trigger :tpsl])))))

(deftest build-tpsl-orders-fails-closed-for-invalid-enabled-leg-test
  (is (= [] (commands/build-tpsl-orders 5 :buy {:size "1"})))
  (is (nil? (commands/build-tpsl-orders 5 :buy {:size "1"
                                                :tp {:enabled? true
                                                     :trigger ""}}))))

(deftest build-tpsl-orders-canonicalizes-trigger-price-to-exchange-precision-test
  (let [orders (commands/build-tpsl-orders
                215
                :sell
                {:size "965"
                 :tp {:enabled? true
                      :trigger "0.01969873"
                      :is-market true}}
                mon-command-context)]
    (is (= 1 (count orders)))
    (is (= "0.019698" (get-in orders [0 :p])))
    (is (= "0.019698" (get-in orders [0 :t :trigger :triggerPx])))))

(deftest build-order-action-normalizes-type-and-tif-test
  (let [action (commands/build-order-action command-context {:type :unsupported
                                                             :side :buy
                                                             :size "1"
                                                             :price "100"
                                                             :tif :ioc})]
    (is (= "order" (get-in action [:action :type])))
    (is (= "Ioc" (get-in action [:orders 0 :t :limit :tif])))))

(deftest build-order-request-builds-standard-order-type-shapes-test
  (testing "limit variants and normal TP/SL grouping"
    (let [limit-post-only (commands/build-order-request command-context {:type :limit
                                                                         :side :buy
                                                                         :size "1"
                                                                         :price "100"
                                                                         :post-only true
                                                                         :tif :ioc})
          limit-with-tpsl (commands/build-order-request command-context {:type :limit
                                                                         :side :buy
                                                                         :size "2"
                                                                         :price "100"
                                                                         :tp {:enabled? true
                                                                              :trigger "110"
                                                                              :is-market true}
                                                                         :sl {:enabled? true
                                                                              :trigger "90"
                                                                              :is-market true}})]
      (is (= "Alo" (get-in limit-post-only [:orders 0 :t :limit :tif])))
      (is (= "normalTpsl" (get-in limit-with-tpsl [:action :grouping])))
      (is (= 3 (count (:orders limit-with-tpsl))))
      (is (= "tp" (get-in limit-with-tpsl [:orders 1 :t :trigger :tpsl])))
      (is (= "sl" (get-in limit-with-tpsl [:orders 2 :t :trigger :tpsl])))))

  (testing "market/stop/take wire shapes"
    (let [market (commands/build-order-request command-context {:type :market
                                                                 :side :buy
                                                                 :size "1"
                                                                 :price "100"})
          stop-market (commands/build-order-request command-context {:type :stop-market
                                                                      :side :buy
                                                                      :size "1"
                                                                      :price ""
                                                                      :trigger-px "95"})
          stop-limit (commands/build-order-request command-context {:type :stop-limit
                                                                     :side :sell
                                                                     :size "1.5"
                                                                     :price "97"
                                                                     :trigger-px "98"})
          take-market (commands/build-order-request command-context {:type :take-market
                                                                      :side :sell
                                                                      :size "2"
                                                                      :price ""
                                                                      :trigger-px "105"})
          take-limit (commands/build-order-request command-context {:type :take-limit
                                                                     :side :buy
                                                                     :size "3"
                                                                     :price "106"
                                                                     :trigger-px "105"})]
      (is (= "Ioc" (get-in market [:orders 0 :t :limit :tif])))
      (is (= "95" (get-in stop-market [:orders 0 :p])))
      (is (= true (get-in stop-market [:orders 0 :t :trigger :isMarket])))
      (is (= "95" (get-in stop-market [:orders 0 :t :trigger :triggerPx])))
      (is (= "sl" (get-in stop-market [:orders 0 :t :trigger :tpsl])))
      (is (= false (get-in stop-limit [:orders 0 :t :trigger :isMarket])))
      (is (= "98" (get-in stop-limit [:orders 0 :t :trigger :triggerPx])))
      (is (= "sl" (get-in stop-limit [:orders 0 :t :trigger :tpsl])))
      (is (= "105" (get-in take-market [:orders 0 :p])))
      (is (= true (get-in take-market [:orders 0 :t :trigger :isMarket])))
      (is (= "105" (get-in take-market [:orders 0 :t :trigger :triggerPx])))
      (is (= "tp" (get-in take-market [:orders 0 :t :trigger :tpsl])))
      (is (= false (get-in take-limit [:orders 0 :t :trigger :isMarket])))
      (is (= "105" (get-in take-limit [:orders 0 :t :trigger :triggerPx])))
      (is (= "tp" (get-in take-limit [:orders 0 :t :trigger :tpsl])))))

  (testing "invalid TP/SL fails closed"
    (is (nil? (commands/build-order-request command-context {:type :limit
                                                              :side :buy
                                                              :size "1"
                                                              :price "100"
                                                              :tp {:enabled? true
                                                                   :trigger ""}}))))

  (testing "stop/take trigger prices are canonicalized to exchange precision"
    (let [stop-market (commands/build-order-request mon-command-context {:type :stop-market
                                                                         :side :buy
                                                                         :size "1"
                                                                         :price ""
                                                                         :trigger-px "0.01969873"})
          take-limit (commands/build-order-request mon-command-context {:type :take-limit
                                                                        :side :buy
                                                                        :size "1"
                                                                        :price "0.01969873"
                                                                        :trigger-px "0.01969873"})]
      (is (= "0.019698" (get-in stop-market [:orders 0 :p])))
      (is (= "0.019698" (get-in stop-market [:orders 0 :t :trigger :triggerPx])))
      (is (= "0.019698" (get-in take-limit [:orders 0 :p])))
      (is (= "0.019698" (get-in take-limit [:orders 0 :t :trigger :triggerPx]))))))

(deftest build-order-request-conforms-to-committed-formal-standard-vectors-test
  (doseq [{:keys [id contract context form expected]} standard-vectors/standard-order-request-vectors]
    (testing (name id)
      (let [actual (commands/build-order-request context form)]
        (is (= expected actual))
        (if (= :submit-ready contract)
          (is (= expected
                 (contracts/assert-standard-order-request! actual {:vector id})))
          (is (nil? actual)))))))

(deftest build-order-request-builds-scale-and-twap-requests-test
  (let [scale-request (commands/build-order-request command-context {:type :scale
                                                                     :side :sell
                                                                     :size "9"
                                                                     :reduce-only true
                                                                     :post-only true
                                                                     :scale {:start "100"
                                                                             :end "90"
                                                                             :count 3
                                                                             :skew "1.00"}})
        twap-request (commands/build-order-request command-context {:type :twap
                                                                    :side :sell
                                                                    :size "3"
                                                                    :reduce-only true
                                                                    :twap {:hours 1
                                                                           :minutes 30
                                                                           :randomize false}})
        legacy-twap-request (commands/build-order-request command-context {:type :twap
                                                                           :side :buy
                                                                           :size "2"
                                                                           :reduce-only false
                                                                           :twap {:minutes "15"
                                                                                  :randomize true}})]
    (is (= "order" (get-in scale-request [:action :type])))
    (is (= "na" (get-in scale-request [:action :grouping])))
    (is (= 3 (count (:orders scale-request))))
    (is (= "Alo" (get-in scale-request [:orders 0 :t :limit :tif])))
    (is (= false (get-in scale-request [:orders 0 :b])))
    (is (= true (get-in scale-request [:orders 0 :r])))

    (is (= "twapOrder" (get-in twap-request [:action :type])))
    (is (= 5 (get-in twap-request [:action :twap :a])))
    (is (= false (get-in twap-request [:action :twap :b])))
    (is (= "3" (get-in twap-request [:action :twap :s])))
    (is (= true (get-in twap-request [:action :twap :r])))
    (is (= 90 (get-in twap-request [:action :twap :m])))
    (is (= false (get-in twap-request [:action :twap :t])))

    (is (= true (get-in legacy-twap-request [:action :twap :b])))
    (is (= "2" (get-in legacy-twap-request [:action :twap :s])))
    (is (= 15 (get-in legacy-twap-request [:action :twap :m])))
    (is (= true (get-in legacy-twap-request [:action :twap :t])))))

(deftest build-order-request-conforms-to-committed-formal-advanced-vectors-test
  (doseq [{:keys [id contract context form expected]} advanced-vectors/order-request-advanced-vectors]
    (testing (name id)
      (let [actual (commands/build-order-request context form)]
        (is (= expected actual))
        (if (= :submit-ready contract)
          (case (:type form)
            :scale (is (= expected
                          (contracts/assert-scale-request! actual {:vector id})))
            :twap (is (= expected
                         (contracts/assert-twap-request! actual {:vector id}))))
          (is (nil? actual)))))))

(deftest build-order-request-includes-update-leverage-pre-action-test
  (let [cross-request (commands/build-order-request command-context {:type :limit
                                                                     :side :buy
                                                                     :size "1"
                                                                     :price "100"
                                                                     :ui-leverage 17
                                                                     :margin-mode :cross})
        isolated-request (commands/build-order-request command-context {:type :limit
                                                                        :side :buy
                                                                        :size "1"
                                                                        :price "100"
                                                                        :ui-leverage "21"
                                                                        :margin-mode "ISOLATED"})
        cross-action (first (:pre-actions cross-request))
        isolated-action (first (:pre-actions isolated-request))]
    (is (= "updateLeverage" (:type cross-action)))
    (is (= 5 (:asset cross-action)))
    (is (= true (:isCross cross-action)))
    (is (= 17 (:leverage cross-action)))
    (is (= false (:isCross isolated-action)))
    (is (= 21 (:leverage isolated-action)))))

(deftest build-order-request-forces-isolated-leverage-pre-action-when-market-disallows-cross-test
  (let [isolated-only-context (assoc command-context
                                     :market {:market-type :perp
                                              :szDecimals 4
                                              :marginMode "noCross"
                                              :onlyIsolated true})
        request (commands/build-order-request isolated-only-context {:type :limit
                                                                     :side :buy
                                                                     :size "1"
                                                                     :price "100"
                                                                     :ui-leverage 11
                                                                     :margin-mode :cross})
        pre-action (first (:pre-actions request))]
    (is (= "updateLeverage" (:type pre-action)))
    (is (= false (:isCross pre-action)))
    (is (= 11 (:leverage pre-action)))))

(deftest build-order-request-infers-perp-leverage-pre-action-when-market-type-metadata-is-missing-test
  (let [context-without-market-type {:active-asset "BTC"
                                     :asset-idx 5
                                     :market {:szDecimals 4}}
        request (commands/build-order-request context-without-market-type {:type :limit
                                                                           :side :buy
                                                                           :size "1"
                                                                           :price "100"
                                                                           :ui-leverage 9
                                                                           :margin-mode :cross})
        pre-action (first (:pre-actions request))]
    (is (= "updateLeverage" (:type pre-action)))
    (is (= 5 (:asset pre-action)))
    (is (= true (:isCross pre-action)))
    (is (= 9 (:leverage pre-action)))))

(deftest build-order-request-omits-leverage-pre-action-for-spot-like-instrument-with-missing-market-type-test
  (let [spot-like-context {:active-asset "ETH/USDC"
                           :asset-idx 12
                           :market {:coin "ETH/USDC"
                                    :szDecimals 4}}
        request (commands/build-order-request spot-like-context {:type :limit
                                                                 :side :buy
                                                                 :size "1"
                                                                 :price "100"
                                                                 :ui-leverage 7
                                                                 :margin-mode :cross})]
    (is (map? request))
    (is (nil? (:pre-actions request)))))

(deftest build-order-request-fails-closed-on-invalid-scale-and-twap-test
  (is (nil? (commands/build-order-request command-context {:type :scale
                                                            :side :buy
                                                            :size "0"
                                                            :scale {:start "100"
                                                                    :end "90"
                                                                    :count 3
                                                                    :skew "1.00"}})))
  (is (nil? (commands/build-order-request command-context {:type :twap
                                                            :side :buy
                                                            :size "1"
                                                            :twap {:hours 0
                                                                   :minutes 4
                                                                   :randomize true}})))
  (is (nil? (commands/build-twap-action (assoc command-context :active-asset nil)
                                        {:side :buy
                                         :size "1"
                                         :twap {:hours 0
                                                :minutes 15
                                                :randomize true}}))))
