(ns hyperopen.state.trading.test-support)

(def base-state
  {:active-asset "BTC"
   :active-market {:coin "BTC"
                   :mark 100
                   :maxLeverage 40
                   :szDecimals 4}
   :orderbooks {"BTC" {:bids [{:px "99"}]
                       :asks [{:px "101"}]}}
   :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                   :totalMarginUsed "250"}
                                   :assetPositions [{:position {:coin "BTC"
                                                                :szi "0.5"
                                                                :liquidationPx "80"}}]}}})

(defn approx= [a b]
  (<= (js/Math.abs (- a b)) 0.000001))

(defn js-object-keys
  [value]
  (->> (js/Object.keys value)
       array-seq
       vec))

(defn validation-codes
  [errors]
  (->> (or errors [])
       (keep :code)
       set))
