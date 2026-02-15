(ns hyperopen.state.trading-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.state.trading :as trading]))

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

(defn- approx= [a b]
  (<= (js/Math.abs (- a b)) 0.000001))

(defn- js-object-keys
  [value]
  (->> (js/Object.keys value)
       array-seq
       vec))

(defn- validation-codes
  [errors]
  (->> (or errors [])
       (keep :code)
       set))

(deftest validate-order-form-test
  (testing "size is required"
    (is (seq (trading/validate-order-form (trading/default-order-form)))))

  (testing "limit order requires price"
    (let [form (assoc (trading/default-order-form)
                      :size "1"
                      :type :limit
                      :price "")]
      (is (seq (trading/validate-order-form form)))))

  (testing "market order does not require price"
    (let [form (assoc (trading/default-order-form)
                      :size "1"
                      :type :market
                      :price "")]
      (is (empty? (trading/validate-order-form form)))))

  (testing "twap requires minutes"
    (let [form (assoc (trading/default-order-form)
                      :size "1"
                      :type :twap
                      :twap {:minutes 0 :randomize true})]
      (is (seq (trading/validate-order-form form))))))

(deftest scale-skew-validation-range-test
  (let [base-scale-form (assoc (trading/default-order-form)
                               :type :scale
                               :size "300"
                               :scale {:start "100"
                                       :end "110"
                                       :count 3
                                       :skew "1.00"})]
    (is (empty? (trading/validate-order-form (assoc-in base-scale-form [:scale :skew] "1"))))
    (is (empty? (trading/validate-order-form (assoc-in base-scale-form [:scale :skew] "100"))))
    (is (contains? (validation-codes (trading/validate-order-form (assoc-in base-scale-form [:scale :skew] "0")))
                   :scale/skew-invalid))
    (is (contains? (validation-codes (trading/validate-order-form (assoc-in base-scale-form [:scale :skew] "-1")))
                   :scale/skew-invalid))
    (is (contains? (validation-codes (trading/validate-order-form (assoc-in base-scale-form [:scale :skew] "101")))
                   :scale/skew-invalid))
    (is (contains? (validation-codes (trading/validate-order-form (assoc-in base-scale-form [:scale :skew] "abc")))
                   :scale/skew-invalid))
    (is (contains? (validation-codes (trading/validate-order-form (assoc-in base-scale-form [:scale :skew] nil)))
                   :scale/skew-invalid))))

(deftest scale-count-validation-upper-bound-test
  (let [form (assoc (trading/default-order-form)
                    :type :scale
                    :size "300"
                    :scale {:start "100"
                            :end "110"
                            :count 101
                            :skew "1.00"})]
    (is (contains? (validation-codes (trading/validate-order-form form))
                   :scale/inputs-invalid))
    (is (empty? (trading/validate-order-form (assoc-in form [:scale :count] 100))))))

(deftest scale-endpoint-min-notional-validation-test
  (let [form (assoc (trading/default-order-form)
                    :type :scale
                    :size "1"
                    :scale {:start "8"
                            :end "7"
                            :count 20
                            :skew "1.00"})]
    (is (contains? (validation-codes (trading/validate-order-form form))
                   :scale/endpoint-notional-too-small))))

(deftest normalize-order-form-disables-tpsl-for-scale-test
  (let [form (-> (trading/default-order-form)
                 (assoc :entry-mode :pro
                        :type :scale)
                 (assoc-in [:tp :enabled?] true)
                 (assoc-in [:sl :enabled?] true))
        state (assoc base-state
                     :order-form form
                     :order-form-ui (assoc (trading/default-order-form-ui)
                                           :tpsl-panel-open? true))
        normalized (trading/normalize-order-form state form)
        ui-state (trading/order-form-ui-state state)]
    (is (= :scale (:type normalized)))
    (is (false? (:tpsl-panel-open? ui-state)))
    (is (false? (get-in normalized [:tp :enabled?])))
    (is (false? (get-in normalized [:sl :enabled?])))))

(deftest scale-weights-linear-ramp-determinism-test
  (let [first-weights (vec (trading/scale-weights 5 2.5))
        second-weights (vec (trading/scale-weights 5 2.5))]
    (is (= first-weights second-weights))
    (is (= 5 (count first-weights)))
    (is (approx= 1 (reduce + first-weights)))))

(deftest scale-weights-endpoint-ratio-equals-skew-test
  (doseq [skew [0.25 0.5 1.25 2 5 15 50 100]]
    (let [weights (vec (trading/scale-weights 20 skew))
          start (first weights)
          end (last weights)]
      (is (approx= skew (/ end start))))))

(deftest scale-weights-uniform-when-skew-is-one-test
  (let [weights (vec (trading/scale-weights 7 1.0))]
    (is (every? #(approx= (first weights) %) weights))))

(deftest scale-weights-skew-direction-test
  (let [skew-to-end (vec (trading/scale-weights 4 2.0))
        skew-to-start (vec (trading/scale-weights 4 0.5))]
    (is (< (first skew-to-end) (last skew-to-end)))
    (is (> (first skew-to-start) (last skew-to-start)))))

(deftest legacy-skew-keyword-compatibility-test
  (is (= (vec (trading/scale-weights 4 :front))
         (vec (trading/scale-weights 4 0.5))))
  (is (= (vec (trading/scale-weights 4 :even))
         (vec (trading/scale-weights 4 1.0))))
  (is (= (vec (trading/scale-weights 4 :back))
         (vec (trading/scale-weights 4 2.0)))))

(deftest scale-preview-boundaries-determinism-test
  (let [form (assoc (trading/default-order-form)
                    :type :scale
                    :size "13.8"
                    :scale {:start "80"
                            :end "60"
                            :count 20
                            :skew "1.00"})
        first-preview (trading/scale-preview-boundaries form)
        second-preview (trading/scale-preview-boundaries form)]
    (is (= first-preview second-preview))))

(deftest scale-preview-boundaries-even-skew-edge-values-test
  (let [form (assoc (trading/default-order-form)
                    :type :scale
                    :size "13.8"
                    :scale {:start "80"
                            :end "60"
                            :count 20
                            :skew "1.00"})
        preview (trading/scale-preview-boundaries form)]
    (is (approx= 80 (get-in preview [:start :price])))
    (is (approx= 60 (get-in preview [:end :price])))
    (is (approx= 0.69 (get-in preview [:start :size])))
    (is (approx= 0.69 (get-in preview [:end :size])))))

(deftest scale-preview-boundaries-skew-direction-test
  (let [base-form (assoc (trading/default-order-form)
                         :type :scale
                         :size "10"
                         :scale {:start "100"
                                 :end "90"
                                 :count 5
                                 :skew "1.00"})
        skew-to-start (trading/scale-preview-boundaries (assoc-in base-form [:scale :skew] "0.5"))
        skew-to-end (trading/scale-preview-boundaries (assoc-in base-form [:scale :skew] "2.0"))]
    (is (> (get-in skew-to-start [:start :size]) (get-in skew-to-start [:end :size])))
    (is (< (get-in skew-to-end [:start :size]) (get-in skew-to-end [:end :size])))))

(deftest scale-preview-boundaries-start-plus-end-nearly-constant-test
  (let [base-form (assoc (trading/default-order-form)
                         :type :scale
                         :size "9.45"
                         :scale {:start "80"
                                 :end "70"
                                 :count 20
                                 :skew "1.00"})
        baseline-preview (trading/scale-preview-boundaries base-form)
        baseline-sum (+ (get-in baseline-preview [:start :size])
                        (get-in baseline-preview [:end :size]))]
    (doseq [skew ["0.25" "0.5" "1.25" "2" "5" "15" "50" "100"]]
      (let [preview (trading/scale-preview-boundaries (assoc-in base-form [:scale :skew] skew))
            edge-sum (+ (get-in preview [:start :size]) (get-in preview [:end :size]))]
        (is (<= (js/Math.abs (- baseline-sum edge-sum)) 0.000001))))))

(deftest scale-preview-boundaries-incomplete-inputs-return-nil-test
  (let [base-form (assoc (trading/default-order-form)
                         :type :scale
                         :size "10"
                         :scale {:start "100"
                                 :end "90"
                                 :count 5
                                 :skew "1.00"})]
    (is (nil? (trading/scale-preview-boundaries (assoc base-form :size ""))))
    (is (nil? (trading/scale-preview-boundaries (assoc-in base-form [:scale :start] ""))))
    (is (nil? (trading/scale-preview-boundaries (assoc-in base-form [:scale :count] 1))))
    (is (nil? (trading/scale-preview-boundaries (assoc-in base-form [:scale :skew] "abc"))))))

(deftest scale-preview-boundaries-align-with-build-scale-orders-test
  (let [form (assoc (trading/default-order-form)
                    :type :scale
                    :side :buy
                    :size "13.8"
                    :scale {:start "80"
                            :end "60"
                            :count 20
                            :skew "1.00"})
        preview (trading/scale-preview-boundaries form {:sz-decimals 4})
        orders (trading/build-scale-orders 9
                                           :buy
                                           {:size (trading/parse-num (:size form))
                                            :count (get-in form [:scale :count])
                                            :skew (get-in form [:scale :skew])
                                            :sz-decimals 4}
                                           (get-in form [:scale :start])
                                           (get-in form [:scale :end])
                                           false
                                           false)
        first-order (first orders)
        last-order (last orders)]
    (is (approx= (get-in preview [:start :price]) (trading/parse-num (:p first-order))))
    (is (approx= (get-in preview [:start :size]) (trading/parse-num (:s first-order))))
    (is (approx= (get-in preview [:end :price]) (trading/parse-num (:p last-order))))
    (is (approx= (get-in preview [:end :size]) (trading/parse-num (:s last-order))))))

(deftest order-entry-mode-and-pro-type-normalization-test
  (is (= :market (trading/entry-mode-for-type :market)))
  (is (= :limit (trading/entry-mode-for-type :limit)))
  (is (= :pro (trading/entry-mode-for-type :stop-market)))
  (is (= :pro (trading/entry-mode-for-type :scale)))
  (is (= :stop-market (trading/normalize-pro-order-type :market)))
  (is (= :take-limit (trading/normalize-pro-order-type :take-limit))))

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

(deftest default-order-form-uses-limit-entry-mode-test
  (is (= :limit (:entry-mode (trading/default-order-form)))))

(deftest order-form-ui-state-defaults-and-legacy-fallback-test
  (let [no-ui-state (assoc base-state
                           :order-form (trading/default-order-form)
                           :order-form-ui nil)
        legacy-flag-state (assoc base-state
                                 :order-form (assoc (trading/default-order-form)
                                                    :pro-order-type-dropdown-open? true)
                                 :order-form-ui nil)
        normalized-no-ui (trading/order-form-ui-state no-ui-state)
        normalized-legacy (trading/order-form-ui-state legacy-flag-state)]
    (is (false? (:pro-order-type-dropdown-open? normalized-no-ui)))
    (is (false? (:price-input-focused? normalized-no-ui)))
    (is (false? (:tpsl-panel-open? normalized-no-ui)))
    (is (true? (:pro-order-type-dropdown-open? normalized-legacy)))))

(deftest normalize-order-form-keeps-entry-mode-and-type-consistent-test
  (let [market-form (trading/normalize-order-form base-state {:entry-mode :market
                                                               :type :limit
                                                               :size-percent 0
                                                               :ui-leverage 20})
        limit-form (trading/normalize-order-form base-state {:entry-mode :limit
                                                              :type :market
                                                              :size-percent 0
                                                              :ui-leverage 20})
        pro-form (trading/normalize-order-form base-state {:entry-mode :pro
                                                            :type :limit
                                                            :size-percent 0
                                                            :ui-leverage 20})]
    (is (= :market (:entry-mode market-form)))
    (is (= :market (:type market-form)))
    (is (= :limit (:entry-mode limit-form)))
    (is (= :limit (:type limit-form)))
    (is (= :pro (:entry-mode pro-form)))
    (is (= :stop-market (:type pro-form)))))

(deftest normalize-order-form-derives-entry-mode-from-type-when-entry-mode-is-missing-test
  (let [form-without-entry-mode (-> (trading/default-order-form)
                                    (dissoc :entry-mode)
                                    (assoc :type :take-limit))
        normalized (trading/normalize-order-form base-state form-without-entry-mode)]
    (is (= :pro (:entry-mode normalized)))
    (is (= :take-limit (:type normalized)))
    (is (= normalized
           (trading/normalize-order-form base-state form-without-entry-mode)))))

(deftest size-percent-conversion-roundtrip-test
  (let [form (assoc (trading/default-order-form)
                    :type :limit
                    :price "100"
                    :ui-leverage 20)
        from-percent (trading/apply-size-percent base-state form 50)
        derived-size (trading/parse-num (:size from-percent))
        roundtrip (trading/sync-size-percent-from-size base-state from-percent)]
    (is (= 50 (:size-percent from-percent)))
    (is (= 75 derived-size))
    (is (<= (js/Math.abs (- 50 (:size-percent roundtrip))) 0.01))))

(deftest size-percent-zero-clears-size-test
  (let [form (assoc (trading/default-order-form)
                    :type :limit
                    :price "100"
                    :size "5"
                    :size-percent 30)
        reset-form (trading/apply-size-percent base-state form 0)]
    (is (= 0 (:size-percent reset-form)))
    (is (= "" (:size reset-form)))))

(deftest order-summary-and-fallbacks-test
  (testing "summary uses available account data"
    (let [form (assoc (trading/default-order-form)
                      :type :limit
                      :size "2"
                      :price "100"
                      :ui-leverage 20)
          summary (trading/order-summary base-state form)]
      (is (= 750 (:available-to-trade summary)))
      (is (= 200 (:order-value summary)))
      (is (= 10 (:margin-required summary)))
      (is (= 80 (:liquidation-price summary)))
      (is (= trading/default-fees (:fees summary)))))

  (testing "summary falls back deterministically when data is missing"
    (let [state {:active-asset "BTC"
                 :active-market {}
                 :orderbooks {}
                 :webdata2 {}}
          summary (trading/order-summary state (trading/default-order-form))]
      (is (= 0 (:available-to-trade summary)))
      (is (nil? (:order-value summary)))
      (is (nil? (:margin-required summary)))
      (is (nil? (:liquidation-price summary)))
      (is (= trading/default-max-slippage-pct (:slippage-max summary))))))

(deftest order-summary-projects-liquidation-price-for-flat-position-test
  (let [state {:active-asset "SOL"
               :active-market {:coin "SOL"
                               :mark 100
                               :maxLeverage 50
                               :szDecimals 4}
               :orderbooks {}
               :webdata2 {:clearinghouseState {:marginSummary {:accountValue "100"
                                                               :totalMarginUsed "0"}
                                               :assetPositions []}}}
        long-form (assoc (trading/default-order-form)
                         :type :limit
                         :side :buy
                         :size "2"
                         :price "100")
        short-form (assoc long-form :side :sell)
        long-summary (trading/order-summary state long-form)
        short-summary (trading/order-summary state short-form)]
    (is (approx= 52 (:liquidation-price long-summary)))
    (is (approx= 148 (:liquidation-price short-summary)))))

(deftest order-summary-prefers-position-liquidation-over-projected-liquidation-test
  (let [form (assoc (trading/default-order-form)
                    :type :limit
                    :side :buy
                    :size "2"
                    :price "100")
        summary (trading/order-summary base-state form)]
    (is (= 80 (:liquidation-price summary)))))

(deftest market-slippage-estimate-uses-orderbook-depth-for-buy-side-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC"}
               :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}
                                          {:px "100" :sz "2"}]
                                   :asks [{:px "102" :sz "1"}
                                          {:price "101" :size "2"}
                                          {:p "103" :s "5"}]}}
               :webdata2 {}}
        form (assoc (trading/default-order-form)
                    :type :market
                    :side :buy
                    :size "2.5")
        summary (trading/order-summary state form)]
    (is (approx= 0.6965174129353312 (:slippage-est summary)))))

(deftest market-slippage-estimate-uses-orderbook-depth-for-sell-side-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC"}
               :orderbooks {"BTC" {:bids [{:px "99" :sz "1"}
                                          {:price "100" :size "1.5"}
                                          {:p "98.5" :s "4"}]
                                   :asks [{:px "101" :sz "3"}]}}
               :webdata2 {}}
        form (assoc (trading/default-order-form)
                    :type :market
                    :side :sell
                    :size "1.8")
        summary (trading/order-summary state form)]
    (is (approx= 0.6633499170812619 (:slippage-est summary)))))

(deftest market-slippage-estimate-increases-with-larger-size-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC"}
               :orderbooks {"BTC" {:bids [{:px "99" :sz "10"}]
                                   :asks [{:px "101" :sz "1"}
                                          {:px "103" :sz "10"}]}}
               :webdata2 {}}
        small-form (assoc (trading/default-order-form)
                          :type :market
                          :side :buy
                          :size "0.5")
        large-form (assoc (trading/default-order-form)
                          :type :market
                          :side :buy
                          :size "2")
        small-est (:slippage-est (trading/order-summary state small-form))
        large-est (:slippage-est (trading/order-summary state large-form))]
    (is (number? small-est))
    (is (number? large-est))
    (is (< small-est large-est))))

(deftest market-slippage-estimate-is-nil-when-visible-depth-is-insufficient-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC"}
               :orderbooks {"BTC" {:bids [{:px "99" :sz "3"}]
                                   :asks [{:px "101" :sz "1"}
                                          {:px "102" :sz "1"}]}}
               :webdata2 {}}
        form (assoc (trading/default-order-form)
                    :type :market
                    :side :buy
                    :size "5")
        summary (trading/order-summary state form)]
    (is (nil? (:slippage-est summary)))))

(deftest market-slippage-estimate-is-nil-when-midpoint-is-unavailable-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC"}
               :orderbooks {"BTC" {:bids []
                                   :asks [{:px "101" :sz "5"}]}}
               :webdata2 {}}
        form (assoc (trading/default-order-form)
                    :type :market
                    :side :buy
                    :size "1")
        summary (trading/order-summary state form)]
    (is (nil? (:slippage-est summary)))))

(deftest market-slippage-estimate-is-deterministic-test
  (let [state {:active-asset "BTC"
               :active-market {:coin "BTC"}
               :orderbooks {"BTC" {:bids [{:px "99" :sz "5"}]
                                   :asks [{:px "101" :sz "5"}
                                          {:px "102" :sz "5"}]}}
               :webdata2 {}}
        form (assoc (trading/default-order-form)
                    :type :market
                    :side :buy
                    :size "3")
        first-summary (trading/order-summary state form)
        second-summary (trading/order-summary state form)]
    (is (= first-summary second-summary))))

(deftest order-summary-uses-canonical-size-for-order-value-test
  (let [form (assoc (trading/default-order-form)
                    :type :limit
                    :size "2"
                    :size-display "1"
                    :price "100"
                    :ui-leverage 20)
        summary (trading/order-summary base-state form)]
    (is (= 200 (:order-value summary)))
    (is (= 10 (:margin-required summary)))))

(deftest base-size-string-truncates-to-market-step-test
  (let [state {:active-market {:szDecimals 5}}]
    (is (= "0.00002" (trading/base-size-string state 0.0000285)))
    (is (nil? (trading/base-size-string state 0.0000099)))))

(deftest available-to-trade-withdrawable-precedence-and-dex-isolation-test
  (testing "classic mode uses withdrawable as the canonical available balance when present"
    (let [state (assoc-in base-state [:webdata2 :clearinghouseState :withdrawable] "170.58")]
      (is (approx= 170.58 (trading/available-to-trade state)))
      (is (= (trading/available-to-trade state)
             (trading/available-to-trade state)))))

  (testing "falls back to account value minus margin used when withdrawable is missing"
    (is (approx= 750 (trading/available-to-trade base-state))))

  (testing "unified mode uses spot USDC available balance when present"
    (let [state (-> base-state
                    (assoc :account {:mode :unified})
                    (assoc-in [:spot :clearinghouse-state :balances]
                              [{:coin "USDC"
                                :total "204.41936500"
                                :hold "3.03000000"}])
                    (assoc-in [:webdata2 :clearinghouseState :withdrawable] "0.03"))]
      (is (approx= 201.389365 (trading/available-to-trade state)))))

  (testing "unified mode falls back when spot USDC balance is unavailable"
    (let [state (-> base-state
                    (assoc :account {:mode :unified})
                    (assoc-in [:webdata2 :clearinghouseState :withdrawable] "170.58"))]
      (is (approx= 170.58 (trading/available-to-trade state)))))

  (testing "dex-scoped market reads only dex-scoped clearinghouse data"
    (let [state {:active-market {:coin "BTC" :dex "dex-a"}
                 :perp-dex-clearinghouse {"dex-a" {:withdrawable "170.58"}}
                 :webdata2 {:clearinghouseState {:withdrawable "975.90"}}}]
      (is (approx= 170.58 (trading/available-to-trade state)))))

  (testing "missing dex-scoped clearinghouse does not fall back to default webdata2"
    (let [state {:active-market {:coin "BTC" :dex "dex-missing"}
                 :perp-dex-clearinghouse {}
                 :webdata2 {:clearinghouseState {:withdrawable "975.90"}}}]
      (is (= 0 (trading/available-to-trade state))))))

(deftest mid-price-summary-determinism-and-fallback-test
  (testing "mid source is used when bid/ask are present"
    (let [form (assoc (trading/default-order-form) :type :limit :price "100")
          summary (trading/mid-price-summary base-state form)]
      (is (= :mid (:source summary)))
      (is (approx= 100 (:mid-price summary)))
      (is (= summary (trading/mid-price-summary base-state form)))))

  (testing "reference source is used when mid cannot be computed"
    (let [state (assoc base-state :orderbooks {})
          form (assoc (trading/default-order-form) :type :limit :price "")
          summary (trading/mid-price-summary state form)]
      (is (= :reference (:source summary)))
      (is (approx= 100 (:mid-price summary)))))

  (testing "none source is used when no price data exists"
    (let [state {:active-asset "BTC"
                 :active-market {}
                 :orderbooks {}
                 :webdata2 {}}
          summary (trading/mid-price-summary state (trading/default-order-form))]
      (is (= :none (:source summary)))
      (is (nil? (:mid-price summary))))))

(deftest effective-limit-price-and-string-fallback-test
  (testing "effective limit price prefers mid when bid/ask are present"
    (let [form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (approx= 100 (trading/effective-limit-price base-state form)))))

  (testing "effective limit price falls back to reference when mid is unavailable"
    (let [state (assoc base-state :orderbooks {})
          form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (approx= 100 (trading/effective-limit-price state form)))))

  (testing "effective limit price returns nil when no usable sources exist"
    (let [state {:active-asset "BTC"
                 :active-market {}
                 :orderbooks {}
                 :webdata2 {}}
          form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (nil? (trading/effective-limit-price state form)))
      (is (nil? (trading/effective-limit-price-string state form)))))

  (testing "effective limit price string formatting is deterministic"
    (let [state (assoc-in base-state [:orderbooks "BTC" :asks 0 :px] "101.25")
          form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (= "100.125" (trading/effective-limit-price-string state form)))
      (is (= (trading/effective-limit-price-string state form)
             (trading/effective-limit-price-string state form))))))

(deftest mid-price-string-requires-true-midpoint-test
  (testing "returns midpoint string when bid and ask are present"
    (let [form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (= "100" (trading/mid-price-string base-state form)))))

  (testing "returns nil when midpoint is unavailable"
    (let [state (assoc base-state :orderbooks {})
          form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (nil? (trading/mid-price-string state form)))))

  (testing "midpoint uses max bid and min ask regardless of level ordering"
    (let [state {:active-asset "BTC"
                 :active-market {:coin "BTC" :mark 70000}
                 :orderbooks {"BTC" {:bids [{:px "70120"} {:px "70150"} {:px "70090"}]
                                     :asks [{:px "70240"} {:px "70160"} {:px "70210"}]}}
                 :webdata2 {}}
          form (assoc (trading/default-order-form) :type :limit :price "")]
      (is (= "70155" (trading/mid-price-string state form)))
      (is (= 70160 (trading/reference-price state (assoc form :side :buy))))
      (is (= 70150 (trading/reference-price state (assoc form :side :sell)))))))

(deftest market-identity-symbol-and-read-only-inference-test
  (testing "derives base and quote symbols from active-market and active-asset"
    (let [state {:active-asset "PURR"
                 :active-market {:symbol "PURR-USDT"
                                 :quote "USDT"
                                 :market-type :perp}}
          identity (trading/market-identity state)]
      (is (= "PURR" (:base-symbol identity)))
      (is (= "USDT" (:quote-symbol identity)))
      (is (false? (:spot? identity)))
      (is (false? (:hip3? identity)))
      (is (false? (:read-only? identity)))))

  (testing "infers spot and read-only when active-asset is spot-style"
    (let [state {:active-asset "ETH/USDC"
                 :active-market {:symbol "ETH-USDC"}}
          identity (trading/market-identity state)]
      (is (= "ETH" (:base-symbol identity)))
      (is (= "USDC" (:quote-symbol identity)))
      (is (true? (:spot? identity)))
      (is (false? (:hip3? identity)))
      (is (true? (:read-only? identity)))))

  (testing "infers hip3 and read-only from namespaced asset and dex market"
    (let [asset-only-state {:active-asset "hyna:GOLD"
                            :active-market {:symbol "hyna:GOLD"}}
          dex-state {:active-asset "BTC"
                     :active-market {:symbol "BTC-USD"
                                     :dex "dex-a"}}
          asset-identity (trading/market-identity asset-only-state)
          dex-identity (trading/market-identity dex-state)]
      (is (= "GOLD" (:base-symbol asset-identity)))
      (is (true? (:hip3? asset-identity)))
      (is (true? (:read-only? asset-identity)))
      (is (true? (:hip3? dex-identity)))
      (is (true? (:read-only? dex-identity))))))

(deftest submit-policy-reasons-test
  (testing "view mode reports validation reason and required fields"
    (let [form (assoc (trading/default-order-form) :type :limit :size "" :price "")
          policy (trading/submit-policy base-state form {:mode :view})]
      (is (= :validation-errors (:reason policy)))
      (is (true? (:disabled? policy)))
      (is (= ["Size"] (:required-fields policy)))))

  (testing "submit mode reports agent readiness after request validation"
    (let [state (assoc base-state
                       :asset-contexts {:BTC {:idx 0}})
          form (assoc (trading/default-order-form)
                      :type :limit
                      :side :buy
                      :size "1"
                      :price "100")
          policy (trading/submit-policy state form {:mode :submit
                                                    :agent-ready? false})]
      (is (= :agent-not-ready (:reason policy)))
      (is (= "Enable trading before submitting orders." (:error-message policy))))))
