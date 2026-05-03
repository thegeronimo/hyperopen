(ns hyperopen.schema.order-request-contracts-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.order-request-contracts :as contracts]))

(deftest order-request-contract-accepts-standard-order-shape-test
  (let [order (array-map :a 5
                         :b true
                         :p "100"
                         :s "1"
                         :r false
                         :t (array-map :limit (array-map :tif "Gtc")))
        request (array-map :action (array-map :type "order"
                                              :orders [order]
                                              :grouping "na")
                           :asset-idx 5
                           :orders [order])]
    (is (true? (contracts/order-request-valid? request)))
    (is (= request
           (contracts/assert-order-request! request {:boundary :test/contracts})))))

(deftest order-request-contract-accepts-spot-order-without-reduce-only-test
  (let [order (array-map :a 100000010
                         :b false
                         :p "0.59"
                         :s "8.5"
                         :t (array-map :limit (array-map :tif "Gtc")))
        request (array-map :action (array-map :type "order"
                                              :orders [order]
                                              :grouping "na")
                           :asset-idx 100000010
                           :orders [order])]
    (is (true? (contracts/order-request-valid? request)))))

(deftest order-request-contract-rejects-invalid-pre-action-shape-test
  (is (thrown-with-msg?
       js/Error
       #"order request contract validation failed"
       (contracts/assert-order-request!
        {:action {:type "order"
                  :orders [{:a 5
                            :b true
                            :p "100"
                            :s "1"
                            :r false
                            :t {:limit {:tif "Gtc"}}}]
                  :grouping "na"}
         :asset-idx 5
         :orders [{:a 5
                   :b true
                   :p "100"
                   :s "1"
                   :r false
                   :t {:limit {:tif "Gtc"}}}]
         :pre-actions [{:type "updateLeverage"
                        :asset 5
                        :isCross "true"
                        :leverage 20}]}
        {:boundary :test/contracts}))))
