(ns hyperopen.views.vault-detail.format-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.vault-detail.format :as vf]))

(deftest finite-number-and-currency-fallback-test
  (is (true? (vf/finite-number? 42)))
  (is (false? (vf/finite-number? js/Infinity)))
  (is (false? (vf/finite-number? js/NaN)))
  (is (= "N/A" (vf/format-currency nil {:missing "N/A"})))
  (is (= "$0.00" (vf/format-currency -0))))

(deftest quantity-price-percent-and-rate-formatters-test
  (let [formatted-price (vf/format-price 1234.5)]
    (is (string? formatted-price))
    (is (not= "—" formatted-price)))
  (is (= "—" (vf/format-price nil)))
  (is (= "1.23" (vf/format-balance-quantity 1.23000000)))
  (is (= "0" (vf/format-balance-quantity 0.0)))
  (is (= "NA" (vf/format-balance-quantity js/NaN {:missing "NA"})))
  (is (= "2.5000" (vf/format-size 2.5)))
  (is (= "—" (vf/format-size js/NaN)))
  (is (= "+1.23%" (vf/format-percent 1.234 {:decimals 2})))
  (is (= "-0.10%" (vf/format-percent -0.1 {:decimals 2})))
  (is (= "1.2%" (vf/format-percent 1.2 {:signed? false :decimals 1})))
  (is (= "0.0100%" (vf/format-funding-rate 0.0001)))
  (is (= "—" (vf/format-funding-rate nil))))

(deftest time-hash-name-and-loading-skeleton-helpers-test
  (with-redefs [fmt/format-local-date-time (fn [_] "2025-01-02 03:04")]
    (is (= "2025-01-02 03:04" (vf/format-time 1735787040000))))
  (with-redefs [fmt/format-local-date-time (fn [_] nil)]
    (is (= "—" (vf/format-time 1735787040000))))
  (is (= "0x123456...abcdef"
         (vf/short-hash "0x1234567890abcdef1234567890abcdef")))
  (is (= "0x1234" (vf/short-hash "0x1234")))
  (is (= "—" (vf/short-hash nil)))
  (is (nil? (vf/resolved-vault-name " 0xABC " "0xabc")))
  (is (= "Vault One" (vf/resolved-vault-name " Vault One " "0xabc")))
  (let [skeleton (vf/loading-skeleton-block ["w-24"])
        classes (hiccup/node-class-set skeleton)]
    (is (= :span (first skeleton)))
    (is (contains? classes "animate-pulse"))
    (is (contains? classes "w-24"))))
