(ns hyperopen.vaults.application.user-equity-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vaults.application.user-equity :as user-equity]))

(def ^:private account-a
  "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private account-b
  "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(defn- loaded-state
  [rows]
  {:wallet {:address account-a}
   :vaults {:user-equities rows
            :user-equities-for-address account-a
            :user-equities-error-for-address nil}})

(deftest vault-equity-status-sums-the-effective-accounts-rows-test
  (is (= {:status :ready :usd 1500.5}
         (user-equity/vault-equity-status
          (loaded-state [{:vault-address "0xv1" :equity-raw "1000.25"}
                         {:vault-address "0xv2" :equity-raw "500.25"}]))))
  (testing "an account with no vault deposits is a known zero"
    (is (= {:status :ready :usd 0}
           (user-equity/vault-equity-status (loaded-state []))))
    (is (= 0 (user-equity/current-vault-equity (loaded-state [])))))
  (testing "a background refresh keeps the last good rows"
    (is (= 42 (user-equity/current-vault-equity
               (assoc-in (loaded-state [{:equity-raw "42"}])
                         [:vaults :loading]
                         {:user-equities? true
                          :user-equities-for-address account-a}))))))

(deftest vault-equity-status-never-reports-unknown-as-zero-test
  (doseq [[label state expected]
          [["no account shown" {:wallet {:address nil}} :none]
           ["the retired webData2 total alone"
            {:wallet {:address account-a}
             :webdata2 {:totalVaultEquity "500"}}
            :loading]
           ["rows for another account"
            (assoc-in (loaded-state [{:equity-raw "9"}])
                      [:vaults :user-equities-for-address] account-b)
            :loading]
           ["failed read"
            (assoc-in (loaded-state [{:equity-raw "9"}])
                      [:vaults :user-equities-error-for-address] account-a)
            :unavailable]
           ["malformed payload" (loaded-state nil) :unavailable]
           ["a row that does not parse"
            (loaded-state [{:equity-raw "9"} {:equity-raw "NaN"}])
            :unavailable]]]
    (is (= expected (:status (user-equity/vault-equity-status state))) label)
    (is (nil? (user-equity/current-vault-equity state)) label)))
