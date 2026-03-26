(ns hyperopen.formal.order-request-advanced-vectors)

(def btc-perp-context
  {:active-asset "BTC"
   :asset-idx 5
   :market {:market-type :perp
            :szDecimals 4}})

(def order-request-advanced-vectors
  [{:id :scale-order-request
    :contract :submit-ready
    :context btc-perp-context
    :form {:type :scale
           :side :sell
           :size "9"
           :reduce-only true
           :post-only true
           :scale {:start "100"
                   :end "90"
                   :count 3
                   :skew "1.00"}}
    :expected (let [orders [(array-map :a 5
                                       :b false
                                       :p "100"
                                       :s "3"
                                       :r true
                                       :t (array-map :limit (array-map :tif "Alo")))
                            (array-map :a 5
                                       :b false
                                       :p "95"
                                       :s "3"
                                       :r true
                                       :t (array-map :limit (array-map :tif "Alo")))
                            (array-map :a 5
                                       :b false
                                       :p "90"
                                       :s "3"
                                       :r true
                                       :t (array-map :limit (array-map :tif "Alo")))]]
                (array-map :action (array-map :type "order"
                                              :orders orders
                                              :grouping "na")
                           :asset-idx 5
                           :orders orders))}
   {:id :twap-order-request
    :contract :submit-ready
    :context btc-perp-context
    :form {:type :twap
           :side :sell
           :size "3"
           :reduce-only true
           :twap {:hours 1
                  :minutes 30
                  :randomize false}}
    :expected (array-map :action (array-map :type "twapOrder"
                                            :twap (array-map :a 5
                                                              :b false
                                                              :s "3"
                                                              :r true
                                                              :m 90
                                                              :t false))
                         :asset-idx 5)}
   {:id :legacy-twap-minutes-request
    :contract :submit-ready
    :context btc-perp-context
    :form {:type :twap
           :side :buy
           :size "2"
           :reduce-only false
           :twap {:minutes "15"
                  :randomize true}}
    :expected (array-map :action (array-map :type "twapOrder"
                                            :twap (array-map :a 5
                                                              :b true
                                                              :s "2"
                                                              :r false
                                                              :m 15
                                                              :t true))
                         :asset-idx 5)}
   {:id :scale-invalid-fails-closed
    :contract :raw-builder
    :context btc-perp-context
    :form {:type :scale
           :side :buy
           :size "0"
           :scale {:start "100"
                   :end "90"
                   :count 3
                   :skew "1.00"}}
    :expected nil}
   {:id :twap-invalid-runtime-fails-closed
    :contract :raw-builder
    :context btc-perp-context
    :form {:type :twap
           :side :buy
           :size "1"
           :twap {:hours 0
                  :minutes 4
                  :randomize true}}
    :expected nil}
   {:id :twap-missing-active-asset-fails-closed
    :contract :raw-builder
    :context {:active-asset nil
              :asset-idx 5
              :market {:market-type :perp
                       :szDecimals 4}}
    :form {:type :twap
           :side :buy
           :size "1"
           :twap {:hours 0
                  :minutes 15
                  :randomize true}}
    :expected nil}])
