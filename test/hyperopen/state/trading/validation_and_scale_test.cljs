(ns hyperopen.state.trading.validation-and-scale-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.formal.order-request-advanced-vectors :as advanced-vectors]
            [hyperopen.state.trading :as trading]
            [hyperopen.state.trading.test-support :as support]))

(defn- approx= [a b]
  (support/approx= a b))

(defn- validation-codes
  [errors]
  (support/validation-codes errors))

(deftest validate-order-form-test
  (testing "size is required"
    (let [errors (trading/validate-order-form (trading/default-order-form))]
      (is (contains? (validation-codes errors)
                     :order/size-invalid))))

  (testing "limit order requires price"
    (let [form (assoc (trading/default-order-form)
                      :size "1"
                      :type :limit
                      :price "")
          errors (trading/validate-order-form form)]
      (is (= #{:order/price-required}
             (validation-codes errors)))))

  (testing "market order does not require price"
    (let [form (assoc (trading/default-order-form)
                      :size "1"
                      :type :market
                      :price "")]
      (is (empty? (trading/validate-order-form form)))))

  (testing "twap runtime must be between 5 minutes and 24 hours"
    (let [too-short (assoc (trading/default-order-form)
                           :size "1"
                           :type :twap
                           :twap {:hours 0 :minutes 4 :randomize false})
          too-long (assoc (trading/default-order-form)
                          :size "1"
                          :type :twap
                          :twap {:hours 24 :minutes 1 :randomize false})]
      (is (= #{:twap/runtime-invalid}
             (validation-codes (trading/validate-order-form too-short))))
      (is (= #{:twap/runtime-invalid}
             (validation-codes (trading/validate-order-form too-long))))))

  (testing "twap suborders must satisfy the venue minimum order value"
    (let [too-small (assoc (trading/default-order-form)
                           :side :buy
                           :size "1"
                           :type :twap
                           :twap {:hours 0 :minutes 30 :randomize false})
          valid (assoc too-small :size "10")]
      (is (= #{:twap/suborder-notional-too-small}
             (validation-codes (trading/validate-order-form support/base-state too-small))))
      (is (empty? (trading/validate-order-form support/base-state valid))))))

(deftest positive-required-fields-reject-zero-boundary-test
  (testing "size zero remains invalid"
    (let [form (assoc (trading/default-order-form)
                      :type :market
                      :size "0")]
      (is (= #{:order/size-invalid}
             (validation-codes (trading/validate-order-form form))))))

  (testing "limit price zero remains invalid"
    (let [form (assoc (trading/default-order-form)
                      :size "1"
                      :type :limit
                      :price "0")]
      (is (= #{:order/price-required}
             (validation-codes (trading/validate-order-form form))))))

  (testing "trigger zero remains invalid for stop orders"
    (let [form (assoc (trading/default-order-form)
                      :size "1"
                      :type :stop-market
                      :trigger-px "0")]
      (is (= #{:order/trigger-required}
             (validation-codes (trading/validate-order-form form)))))))

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

(deftest scale-endpoint-min-notional-boundary-test
  (testing "start endpoint can equal the venue minimum"
    (let [form (assoc (trading/default-order-form)
                      :type :scale
                      :size "2"
                      :scale {:start "10"
                              :end "20"
                              :count 2
                              :skew "1.00"})]
      (is (empty? (trading/validate-order-form form)))))

  (testing "end endpoint can equal the venue minimum"
    (let [form (assoc (trading/default-order-form)
                      :type :scale
                      :size "2"
                      :scale {:start "20"
                              :end "10"
                              :count 2
                              :skew "1.00"})]
      (is (empty? (trading/validate-order-form form))))))

(deftest twap-suborder-min-notional-boundary-test
  (let [state (assoc support/base-state :orderbooks {})
        form (assoc (trading/default-order-form)
                    :side :buy
                    :size "6.1"
                    :type :twap
                    :twap {:hours 0 :minutes 30 :randomize false})]
    (is (empty? (trading/validate-order-form state form)))))

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

(deftest formal-advanced-order-vectors-preserve-domain-arithmetic-invariants-test
  (let [scale-case (first advanced-vectors/order-request-advanced-vectors)
        scale-request (:expected scale-case)
        scale-orders (:orders scale-request)
        parsed-sizes (mapv #(trading/parse-num (:s %)) scale-orders)
        parsed-prices (mapv #(trading/parse-num (:p %)) scale-orders)
        twap-case (second advanced-vectors/order-request-advanced-vectors)
        twap-form (:form twap-case)
        total-minutes (trading/twap-total-minutes (:twap twap-form))]
    (is (= [100 95 90] parsed-prices))
    (is (every? pos? parsed-sizes))
    (is (<= (reduce + parsed-sizes)
            (trading/parse-num (:size (:form scale-case)))))
    (is (= 181 (trading/twap-suborder-count total-minutes)))
    (is (= 90 (get-in (:expected twap-case) [:action :twap :m])))))
