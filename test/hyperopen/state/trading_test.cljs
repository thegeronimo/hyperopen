(ns hyperopen.state.trading-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.state.trading :as trading]
            [hyperopen.state.trading.test-support :as support]))

(def base-state support/base-state)

(deftest state-trading-facade-builds-submit-ready-policy-when-prereqs-present-test
  (let [state (assoc base-state
                     :asset-contexts {:BTC {:idx 5}}
                     :order-form (trading/default-order-form)
                     :order-form-ui (trading/default-order-form-ui))
        form (assoc (trading/default-order-form)
                    :type :limit
                    :side :buy
                    :size "1"
                    :price "100")
        policy (trading/submit-policy state form {:mode :submit
                                                  :agent-ready? true})]
    (is (nil? (:reason policy)))
    (is (false? (:disabled? policy)))
    (is (= "order" (get-in policy [:request :action :type])))))

(deftest state-trading-facade-composes-order-form-draft-and-ui-state-test
  (let [state (assoc base-state
                     :order-form {:entry-mode :market
                                  :type :limit
                                  :size "1"
                                  :price "100"}
                     :order-form-ui nil)
        draft (trading/order-form-draft state)
        ui-state (trading/order-form-ui-state state)]
    (is (= :market (:entry-mode draft)))
    (is (= :market (:type draft)))
    (is (= :market (:entry-mode ui-state)))
    (is (false? (:price-input-focused? ui-state)))))
