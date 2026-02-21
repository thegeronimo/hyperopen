(ns hyperopen.state.trading.order-form-state-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.state.trading :as trading]
            [hyperopen.state.trading.test-support :as support]))

(def base-state support/base-state)

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

(deftest order-entry-mode-and-pro-type-normalization-test
  (is (= :market (trading/entry-mode-for-type :market)))
  (is (= :limit (trading/entry-mode-for-type :limit)))
  (is (= :pro (trading/entry-mode-for-type :stop-market)))
  (is (= :pro (trading/entry-mode-for-type :scale)))
  (is (= :stop-market (trading/normalize-pro-order-type :market)))
  (is (= :take-limit (trading/normalize-pro-order-type :take-limit))))

(deftest default-order-form-and-ui-field-ownership-test
  (is (nil? (:entry-mode (trading/default-order-form))))
  (is (nil? (:ui-leverage (trading/default-order-form))))
  (is (nil? (:size-input-mode (trading/default-order-form))))
  (is (nil? (:size-input-source (trading/default-order-form))))
  (is (nil? (:size-display (trading/default-order-form))))
  (is (= :limit (:entry-mode (trading/default-order-form-ui))))
  (is (number? (:ui-leverage (trading/default-order-form-ui))))
  (is (= :quote (:size-input-mode (trading/default-order-form-ui))))
  (is (= :manual (:size-input-source (trading/default-order-form-ui))))
  (is (false? (:size-unit-dropdown-open? (trading/default-order-form-ui))))
  (is (= "" (:size-display (trading/default-order-form-ui)))))

(deftest order-form-ui-state-defaults-without-legacy-fallback-test
  (let [no-ui-state (assoc base-state
                           :order-form (trading/default-order-form)
                           :order-form-ui nil)
        legacy-flag-state (assoc base-state
                                 :order-form (assoc (trading/default-order-form)
                                                    :pro-order-type-dropdown-open? true)
                                 :order-form-ui nil)
        explicit-ui-state (assoc base-state
                                 :order-form (trading/default-order-form)
                                 :order-form-ui {:pro-order-type-dropdown-open? true})
        normalized-no-ui (trading/order-form-ui-state no-ui-state)
        normalized-legacy (trading/order-form-ui-state legacy-flag-state)
        normalized-explicit (trading/order-form-ui-state explicit-ui-state)]
    (is (false? (:pro-order-type-dropdown-open? normalized-no-ui)))
    (is (false? (:size-unit-dropdown-open? normalized-no-ui)))
    (is (false? (:price-input-focused? normalized-no-ui)))
    (is (false? (:tpsl-panel-open? normalized-no-ui)))
    (is (= :limit (:entry-mode normalized-no-ui)))
    (is (number? (:ui-leverage normalized-no-ui)))
    (is (= :quote (:size-input-mode normalized-no-ui)))
    (is (= :manual (:size-input-source normalized-no-ui)))
    (is (= "" (:size-display normalized-no-ui)))
    (is (false? (:pro-order-type-dropdown-open? normalized-legacy)))
    (is (true? (:pro-order-type-dropdown-open? normalized-explicit)))))

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

(deftest percent-and-size-sync-projections-follow-size-input-mode-test
  (let [quote-form (assoc (trading/default-order-form)
                          :type :limit
                          :price "100"
                          :ui-leverage 20
                          :size-input-mode :quote)
        base-form (assoc quote-form :size-input-mode :base)
        quote-from-percent (trading/apply-size-percent base-state quote-form 50)
        base-from-percent (trading/apply-size-percent base-state base-form 50)
        quote-from-size (trading/sync-size-percent-from-size base-state
                                                             (assoc quote-form :size "2"))
        base-from-size (trading/sync-size-percent-from-size base-state
                                                            (assoc base-form :size "2"))]
    (is (= "7500" (:size-display quote-from-percent)))
    (is (= "75" (:size-display base-from-percent)))
    (is (= "200" (:size-display quote-from-size)))
    (is (= "2" (:size-display base-from-size)))))
