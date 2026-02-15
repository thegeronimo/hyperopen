(ns hyperopen.views.trade.order-form-vm-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.state.trading :as trading]
            [hyperopen.views.trade.order-form-vm :as vm]))

(defn- base-state
  ([] (base-state {} {}))
  ([order-form-overrides order-form-ui-overrides]
   (let [base-form (merge (trading/default-order-form) order-form-overrides)
         order-form (if (and (contains? order-form-overrides :type)
                             (not (contains? order-form-overrides :entry-mode)))
                      (assoc base-form :entry-mode (trading/entry-mode-for-type (:type base-form)))
                      base-form)]
     {:active-asset "BTC"
      :active-market {:coin "BTC"
                      :quote "USDC"
                      :mark 100
                      :maxLeverage 40
                      :market-type :perp
                      :szDecimals 4}
      :orderbooks {"BTC" {:bids [{:px "99"}]
                          :asks [{:px "101"}]}}
      :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                      :totalMarginUsed "250"}}}
      :order-form order-form
      :order-form-ui (merge (trading/default-order-form-ui) order-form-ui-overrides)})))

(deftest order-form-vm-price-context-mid-vs-ref-test
  (testing "mid context is available when top-of-book midpoint exists"
    (let [state (base-state {:type :limit :price ""} {})
          view-model (vm/order-form-vm state)]
      (is (= "Mid" (get-in view-model [:price :context :label])))
      (is (true? (get-in view-model [:price :context :mid-available?])))))

  (testing "ref context is used when midpoint is unavailable"
    (let [state (assoc (base-state {:type :limit :price ""} {}) :orderbooks {})
          view-model (vm/order-form-vm state)]
      (is (= "Ref" (get-in view-model [:price :context :label])))
      (is (false? (get-in view-model [:price :context :mid-available?]))))))

(deftest order-form-vm-submit-tooltip-and-disable-reason-test
  (let [state (base-state {:type :limit :size "" :price ""} {})
        view-model (vm/order-form-vm state)]
    (is (true? (get-in view-model [:submit :disabled?])))
    (is (= :validation-errors (get-in view-model [:submit :reason])))
    (is (= "Fill required fields: Size."
           (get-in view-model [:submit :tooltip])))))

(deftest order-form-vm-uses-order-form-ui-flags-test
  (testing "price fallback display is suppressed while input is focused"
    (let [state (base-state {:type :limit :price ""}
                            {:price-input-focused? true})
          view-model (vm/order-form-vm state)]
      (is (true? (get-in view-model [:price :focused?])))
      (is (= "" (get-in view-model [:price :display])))))

  (testing "tpsl panel state is kept for non-scale order types"
    (let [state (base-state {:type :limit}
                            {:tpsl-panel-open? true})
          view-model (vm/order-form-vm state)]
      (is (true? (:tpsl-panel-open? view-model)))))

  (testing "tpsl panel state is forced closed for scale order type"
    (let [state (base-state {:type :scale}
                            {:tpsl-panel-open? true})
          view-model (vm/order-form-vm state)]
      (is (false? (:tpsl-panel-open? view-model))))))

(deftest order-form-vm-read-only-identity-test
  (let [state (assoc (base-state {:type :limit} {})
                     :active-asset "ETH/USDC")
        view-model (vm/order-form-vm state)]
    (is (true? (:spot? view-model)))
    (is (true? (:read-only? view-model)))
    (is (= "ETH" (:base-symbol view-model)))
    (is (= "USDC" (:quote-symbol view-model)))))
