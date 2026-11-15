(ns hyperopen.views.account-equity-aggregate-test
  "The per-dex to USD aggregation, tested directly.

   `aggregate-clearinghouse-usd` is the seam that decides what \"the account's
   clearinghouse state\" means. Everything the classic panel prints is derived
   from it, so its two properties are worth pinning on their own rather than
   only through the panels: every dex is converted through its own collateral
   token before being summed, and a figure that cannot be converted contributes
   nothing rather than a zero."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.account-equity.pricing :as pricing]))

(defn- approx=
  [expected actual]
  (and (number? actual)
       (< (js/Math.abs (- expected actual)) 1e-9)))

(def ^:private balance-rows
  {"USDC" {:total-balance 1000.0 :usdc-value 1000.0}
   "HYPE" {:total-balance 10.0 :usdc-value 400.0}})

(defn- summary
  [account-value total-ntl-pos total-raw-usd total-margin-used]
  {:accountValue account-value
   :totalNtlPos total-ntl-pos
   :totalRawUsd total-raw-usd
   :totalMarginUsed total-margin-used})

(defn- record
  [dex quote-token margin cross maintenance]
  {:dex dex
   :quote-token quote-token
   :state {:marginSummary margin
           :crossMarginSummary cross
           :crossMaintenanceMarginUsed maintenance
           :assetPositions []}})

(def ^:private usdc-record
  (record nil "USDC"
          (summary "1000.0" "2000.0" "-1000.0" "200.0")
          (summary "900.0" "1800.0" "-900.0" "180.0")
          "50.0"))

(def ^:private hype-record
  (record "hna" "HYPE"
          (summary "50.0" "100.0" "-50.0" "10.0")
          (summary "40.0" "80.0" "-40.0" "8.0")
          "5.0"))

(deftest aggregate-converts-each-dex-through-its-own-collateral-token-test
  (let [aggregate (pricing/aggregate-clearinghouse-usd [usdc-record hype-record]
                                                       balance-rows
                                                       {})]
    (is (approx= (+ 1000.0 (* 50.0 40)) (:account-value aggregate)))
    (is (approx= (+ 2000.0 (* 100.0 40)) (:total-ntl-pos aggregate)))
    (is (approx= (+ -1000.0 (* -50.0 40)) (:total-raw-usd aggregate)))
    (is (approx= (+ 900.0 (* 40.0 40)) (:cross-account-value aggregate)))
    (is (approx= (+ 1800.0 (* 80.0 40)) (:cross-total-ntl-pos aggregate)))
    (is (approx= (+ 180.0 (* 8.0 40)) (:cross-total-margin-used aggregate)))
    (is (approx= (+ 50.0 (* 5.0 40)) (:maintenance-margin aggregate)))))

(deftest aggregate-of-a-single-base-dex-is-that-dex-test
  (testing "the common case stays exactly what the base dex reports"
    (let [aggregate (pricing/aggregate-clearinghouse-usd [usdc-record] balance-rows {})]
      (is (approx= 1000.0 (:account-value aggregate)))
      (is (approx= 900.0 (:cross-account-value aggregate)))
      (is (approx= 50.0 (:maintenance-margin aggregate))))))

(deftest aggregate-is-nil-throughout-without-records-test
  (let [aggregate (pricing/aggregate-clearinghouse-usd [] balance-rows {})]
    (is (every? nil? (vals aggregate)))))

(deftest aggregate-skips-a-dex-whose-collateral-has-no-price-test
  (let [unpriceable (record "wow" "WOW"
                            (summary "700.0" "1400.0" "-700.0" "140.0")
                            (summary "700.0" "1400.0" "-700.0" "140.0")
                            "35.0")
        aggregate (pricing/aggregate-clearinghouse-usd [usdc-record unpriceable]
                                                       balance-rows
                                                       {})]
    (is (approx= 1000.0 (:account-value aggregate)))
    (is (approx= 50.0 (:maintenance-margin aggregate)))))

(deftest aggregate-sums-only-the-records-carrying-each-field-test
  (let [partial-record {:dex "xyz"
                        :quote-token "USDC"
                        :state {:crossMarginSummary (summary "300.0" "600.0" "-300.0" "60.0")
                                :crossMaintenanceMarginUsed "15.0"}}
        aggregate (pricing/aggregate-clearinghouse-usd [usdc-record partial-record]
                                                       balance-rows
                                                       {})]
    (is (approx= 1000.0 (:account-value aggregate)))
    (is (approx= (+ 900.0 300.0) (:cross-account-value aggregate)))
    (is (approx= (+ 50.0 15.0) (:maintenance-margin aggregate)))))

(deftest aggregate-is-nil-per-field-when-no-record-carries-it-test
  (let [cross-only {:dex nil
                    :quote-token "USDC"
                    :state {:crossMarginSummary (summary "300.0" "600.0" "-300.0" "60.0")}}
        aggregate (pricing/aggregate-clearinghouse-usd [cross-only] balance-rows {})]
    (is (nil? (:account-value aggregate)))
    (is (nil? (:maintenance-margin aggregate)))
    (is (approx= 300.0 (:cross-account-value aggregate)))))
