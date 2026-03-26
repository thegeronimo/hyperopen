(ns hyperopen.formal.order-request-standard-vectors)

(def btc-perp-context
  {:active-asset "BTC"
   :asset-idx 5
   :market {:market-type :perp
            :szDecimals 4}})

(def mon-perp-context
  {:active-asset "MON"
   :asset-idx 215
   :market {:market-type :perp
            :szDecimals 0}})

(def named-dex-context
  {:active-asset "hyna:GOLD"
   :asset-idx 110005
   :market {:market-type :perp
            :dex "hyna"
            :asset-id 110005
            :szDecimals 2}})

(def named-dex-missing-asset-id-context
  {:active-asset "hyna:GOLD"
   :asset-idx nil
   :market {:market-type :perp
            :dex "hyna"
            :idx 5
            :szDecimals 2}})

(def spot-like-context
  {:active-asset "ETH/USDC"
   :asset-idx 12
   :market {:coin "ETH/USDC"
            :szDecimals 4}})

(def isolated-only-context
  {:active-asset "BTC"
   :asset-idx 5
   :market {:market-type :perp
            :szDecimals 4
            :marginMode "noCross"
            :onlyIsolated true}})

(def standard-order-request-vectors
  [{:id :limit-post-only
    :contract :submit-ready
    :context btc-perp-context
    :form {:type :limit
           :side :buy
           :size "1"
           :price "100"
           :post-only true
           :tif :ioc}
    :expected (let [order (array-map :a 5
                                     :b true
                                     :p "100"
                                     :s "1"
                                     :r nil
                                     :t (array-map :limit (array-map :tif "Alo")))]
                (array-map :action (array-map :type "order"
                                              :orders [order]
                                              :grouping "na")
                           :asset-idx 5
                           :orders [order]))}
   {:id :limit-with-tpsl
    :contract :submit-ready
    :context btc-perp-context
    :form {:type :limit
           :side :buy
           :size "2"
           :price "100"
           :tp {:enabled? true
                :trigger "110"
                :is-market true}
           :sl {:enabled? true
                :trigger "90"
                :is-market true}}
    :expected (let [main (array-map :a 5
                                    :b true
                                    :p "100"
                                    :s "2"
                                    :r nil
                                    :t (array-map :limit (array-map :tif "Gtc")))
                    tp (array-map :a 5
                                  :b false
                                  :p "110"
                                  :s "2"
                                  :r true
                                  :t (array-map :trigger (array-map :isMarket true
                                                                    :triggerPx "110"
                                                                    :tpsl "tp")))
                    sl (array-map :a 5
                                  :b false
                                  :p "90"
                                  :s "2"
                                  :r true
                                  :t (array-map :trigger (array-map :isMarket true
                                                                    :triggerPx "90"
                                                                    :tpsl "sl")))
                    orders [main tp sl]]
                (array-map :action (array-map :type "order"
                                              :orders orders
                                              :grouping "normalTpsl")
                           :asset-idx 5
                           :orders orders))}
   {:id :market-order
    :contract :submit-ready
    :context btc-perp-context
    :form {:type :market
           :side :buy
           :size "1"
           :price "100"}
    :expected (let [order (array-map :a 5
                                     :b true
                                     :p "100"
                                     :s "1"
                                     :r nil
                                     :t (array-map :limit (array-map :tif "Ioc")))]
                (array-map :action (array-map :type "order"
                                              :orders [order]
                                              :grouping "na")
                           :asset-idx 5
                           :orders [order]))}
   {:id :stop-market-canonicalized
    :contract :submit-ready
    :context mon-perp-context
    :form {:type :stop-market
           :side :buy
           :size "1"
           :price ""
           :trigger-px "0.01969873"}
    :expected (let [order (array-map :a 215
                                     :b true
                                     :p "0.019698"
                                     :s "1"
                                     :r nil
                                     :t (array-map :trigger (array-map :isMarket true
                                                                       :triggerPx "0.019698"
                                                                       :tpsl "sl")))]
                (array-map :action (array-map :type "order"
                                              :orders [order]
                                              :grouping "na")
                           :asset-idx 215
                           :orders [order]))}
   {:id :take-limit-canonicalized
    :contract :submit-ready
    :context mon-perp-context
    :form {:type :take-limit
           :side :buy
           :size "1"
           :price "0.01969873"
           :trigger-px "0.01969873"}
    :expected (let [order (array-map :a 215
                                     :b true
                                     :p "0.019698"
                                     :s "1"
                                     :r nil
                                     :t (array-map :trigger (array-map :isMarket false
                                                                       :triggerPx "0.019698"
                                                                       :tpsl "tp")))]
                (array-map :action (array-map :type "order"
                                              :orders [order]
                                              :grouping "na")
                           :asset-idx 215
                           :orders [order]))}
   {:id :cross-leverage-pre-action
    :contract :submit-ready
    :context btc-perp-context
    :form {:type :limit
           :side :buy
           :size "1"
           :price "100"
           :ui-leverage 17
           :margin-mode :cross}
    :expected (let [order (array-map :a 5
                                     :b true
                                     :p "100"
                                     :s "1"
                                     :r nil
                                     :t (array-map :limit (array-map :tif "Gtc")))
                    request (array-map :action (array-map :type "order"
                                                          :orders [order]
                                                          :grouping "na")
                                       :asset-idx 5
                                       :orders [order])]
                (assoc request
                       :pre-actions [(array-map :type "updateLeverage"
                                                :asset 5
                                                :isCross true
                                                :leverage 17)]))}
   {:id :cross-disallowed-forces-isolated
    :contract :submit-ready
    :context isolated-only-context
    :form {:type :limit
           :side :buy
           :size "1"
           :price "100"
           :ui-leverage 11
           :margin-mode :cross}
    :expected (let [order (array-map :a 5
                                     :b true
                                     :p "100"
                                     :s "1"
                                     :r nil
                                     :t (array-map :limit (array-map :tif "Gtc")))
                    request (array-map :action (array-map :type "order"
                                                          :orders [order]
                                                          :grouping "na")
                                       :asset-idx 5
                                       :orders [order])]
                (assoc request
                       :pre-actions [(array-map :type "updateLeverage"
                                                :asset 5
                                                :isCross false
                                                :leverage 11)]))}
   {:id :spot-like-omits-leverage-pre-action
    :contract :submit-ready
    :context spot-like-context
    :form {:type :limit
           :side :buy
           :size "1"
           :price "100"
           :ui-leverage 7
           :margin-mode :cross}
    :expected (let [order (array-map :a 12
                                     :b true
                                     :p "100"
                                     :s "1"
                                     :r nil
                                     :t (array-map :limit (array-map :tif "Gtc")))]
                (array-map :action (array-map :type "order"
                                              :orders [order]
                                              :grouping "na")
                           :asset-idx 12
                           :orders [order]))}
   {:id :named-dex-uses-canonical-asset-id
    :contract :submit-ready
    :context named-dex-context
    :form {:type :limit
           :side :buy
           :size "1"
           :price "100"}
    :expected (let [order (array-map :a 110005
                                     :b true
                                     :p "100"
                                     :s "1"
                                     :r nil
                                     :t (array-map :limit (array-map :tif "Gtc")))]
                (array-map :action (array-map :type "order"
                                              :orders [order]
                                              :grouping "na")
                           :asset-idx 110005
                           :orders [order]))}
   {:id :named-dex-missing-canonical-asset-id-fails-closed
    :contract :raw-builder
    :context named-dex-missing-asset-id-context
    :form {:type :limit
           :side :buy
           :size "1"
           :price "100"}
    :expected nil}
   {:id :invalid-enabled-tpsl-leg-fails-closed
    :contract :raw-builder
    :context btc-perp-context
    :form {:type :limit
           :side :buy
           :size "1"
           :price "100"
           :tp {:enabled? true
                :trigger ""}}
    :expected nil}
   {:id :limit-missing-price-fails-closed
    :contract :raw-builder
    :context btc-perp-context
    :form {:type :limit
           :side :buy
           :size "1"
           :price ""}
    :expected nil}
   {:id :stop-market-missing-trigger-fails-closed
    :contract :raw-builder
    :context btc-perp-context
    :form {:type :stop-market
           :side :buy
           :size "1"
           :price ""
           :trigger-px ""}
    :expected nil}])
