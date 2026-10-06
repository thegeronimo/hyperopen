(ns hyperopen.views.account-equity-hyperevm-total-test
  "Classic Account Equity's grouped total includes account-owned, fully
   priced HyperEVM assets while leaving all trading and risk figures alone."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.funding.test-support.hyperevm-transfer :as evm-fixture]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.views.account-equity-fixtures :as equity-fixtures]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections.hyperevm-funds :as hyperevm-funds]))

(def ^:private account-a
  "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private account-b
  "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(defn- reset-caches!
  []
  (hyperevm-funds/reset-hyperevm-funds-cache!)
  (derived-cache/reset-derived-cache!)
  (account-equity-view/reset-account-equity-metrics-cache!))

(use-fixtures :each
  (fn [f]
    (reset-caches!)
    (f)
    (reset-caches!)))

(defn- classic-named-dex-state
  []
  (or (some (fn [{:keys [label state]}]
              (when (= "classic / empty base dex, whole book on a named dex" label)
                state))
            (equity-fixtures/classic))
      (throw (js/Error. "classic named-dex fixture missing"))))

(defn- with-confirmed-vaults
  ([state address]
   (with-confirmed-vaults state address 12500.0))
  ([state address equity]
   (assoc state :vaults {:user-equities [{:vault-address "0xvault-a"
                                           :equity equity
                                           :equity-raw (str equity)}]
                          :user-equities-for-address address
                          :loading {:user-equities? false}
                          :errors {:user-equities nil}})))

(defn- with-hyperevm-projection
  [state funds]
  (assoc state :account-equity/hyperevm-funds funds))

(defn- classic-projected-state
  [funds]
  (-> (classic-named-dex-state)
      (assoc :wallet {:address account-a})
      (with-confirmed-vaults account-a)
      (with-hyperevm-projection funds)))

(defn- screenshot-classic-state
  "The supplied Account Equity screenshot's four independently owned USD
   components: $224,892.08 Spot, $0.01 Perps, $23,028.52 Vaults and
   $56,538.39 HyperEVM."
  [funds]
  (-> (classic-named-dex-state)
      (assoc :wallet {:address account-a})
      (with-confirmed-vaults account-a 23028.52)
      (with-hyperevm-projection funds)
      (assoc-in [:spot :clearinghouse-state :balances]
                [{:coin "USDC" :token 0 :hold "0.0" :total "224892.08" :entryNtl "0"}])
      (assoc-in [:perp-dex-clearinghouse "xyz" :marginSummary]
                {:accountValue "0.01" :totalNtlPos "0.0" :totalRawUsd "0.01" :totalMarginUsed "0.0"})
      (assoc-in [:perp-dex-clearinghouse "xyz" :crossMarginSummary]
                {:accountValue "0.01" :totalNtlPos "0.0" :totalRawUsd "0.01" :totalMarginUsed "0.0"})
      (assoc-in [:perp-dex-clearinghouse "xyz" :crossMaintenanceMarginUsed] "0.0")
      (assoc-in [:perp-dex-clearinghouse "xyz" :assetPositions] [])))

(defn- metrics-for
  [state]
  (reset-caches!)
  (account-equity-view/account-equity-metrics state))

(defn- close?
  [actual expected]
  (< (js/Math.abs (- actual expected)) 1e-9))

(deftest classic-total-includes-trusted-account-owned-hyperevm-usd-test
  (let [confirmed-zero (metrics-for
                        (screenshot-classic-state {:status :ready
                                                   :address account-a
                                                   :usd 0
                                                   :unpriced-count 0}))
        metrics (metrics-for
                 (screenshot-classic-state {:status :ready
                                             :address account-a
                                             :usd 56538.39
                                             :unpriced-count 0}))]
    ;; $224,892.08 Spot + $0.01 Perps + $23,028.52 Vaults + $56,538.39
    ;; HyperEVM = the screenshot's user-visible $304,459.00 total.
    (is (= "$224,892.09" (account-equity-view/display-currency
                           (:account-value-display metrics))))
    (is (= 0 (:hyperevm-equity confirmed-zero)))
    (is (= "$247,920.61" (account-equity-view/display-currency
                           (:total-account-value-display confirmed-zero))))
    (is (= 56538.39 (:hyperevm-equity metrics)))
    (is (= "$304,459.00" (account-equity-view/display-currency
                            (:total-account-value-display metrics))))
    (is (= (+ (:account-value-display metrics)
              (:vault-equity metrics)
              (:hyperevm-equity metrics))
           (:total-account-value-display metrics)))
    ;; HyperEVM is worth part of the owner's account, but cannot margin a
    ;; position. Existing trading equity and cross-risk figures stay exact.
    (is (= (select-keys confirmed-zero [:account-value-display
                                        :base-balance
                                        :maintenance-margin
                                        :cross-margin-ratio
                                        :cross-account-leverage])
           (select-keys metrics [:account-value-display
                                 :base-balance
                                 :maintenance-margin
                                 :cross-margin-ratio
                                 :cross-account-leverage])))))

(deftest classic-total-requires-a-complete-current-priced-hyperevm-projection-test
  (let [untrusted-funds
        [["first read is still pending"
          {:status :loading :address account-a :usd nil :unpriced-count nil}]
         ["the account's HyperEVM reader failed before a successful read"
          {:status :unavailable :address account-a :usd nil :unpriced-count nil}]
         ["one token chunk was never answered"
          {:status :partial :address account-a :usd nil :unpriced-count nil}]
         ["the projection describes another effective account"
          {:status :ready :address account-b :usd 25 :unpriced-count 0}]
         ["one held token has no USD price"
          {:status :ready :address account-a :usd 25 :unpriced-count 1}]
         ["the projection did not prove every held token priced"
          {:status :ready :address account-a :usd 25 :unpriced-count nil}]
         ["the projected USD value is non-finite"
          {:status :ready :address account-a :usd js/Infinity :unpriced-count 0}]
         ["the ready projection has no USD value"
          {:status :ready :address account-a :usd nil :unpriced-count 0}]]]
    (doseq [[label funds] untrusted-funds]
      (testing label
        (let [metrics (metrics-for (classic-projected-state funds))]
          (is (nil? (:hyperevm-equity metrics)))
          (is (nil? (:total-account-value-display metrics))))))
    (testing "a forged ready projection cannot count without an effective account"
      (let [metrics (metrics-for
                     (-> (classic-projected-state {:status :ready
                                                    :address nil
                                                    :usd 1
                                                    :unpriced-count 0})
                         (assoc :wallet {})
                         (assoc :account-context {})))]
        (is (nil? (:hyperevm-equity metrics)))
        (is (nil? (:total-account-value-display metrics)))))))

(deftest direct-metrics-use-the-full-hyperevm-projection-and-keep-last-good-read-test
  (let [known (-> (evm-fixture/state)
                  (assoc :router {:path "/trade"})
                  (with-confirmed-vaults evm-fixture/owner))
        refreshing (hyperevm-balances/apply-loading known
                                                    {:addresses [evm-fixture/owner]
                                                     :requested-at-ms 101})
        failed-refresh (hyperevm-balances/apply-error refreshing
                                                      evm-fixture/owner
                                                      101
                                                      {:message "rate limited"
                                                       :kind :rate-limit})
        confirmed-empty (evm-fixture/with-evm-entry
                         known
                         (assoc evm-fixture/evm-entry :native-wei "0" :token-units {}))
        partial (assoc-in known
                          [:hyperevm :balances :by-address evm-fixture/owner :never-read-token-indexes]
                          #{evm-fixture/usdc-index})
        unpriced (-> known
                     (update-in [:asset-selector :market-by-key] dissoc "spot:PURR")
                     (evm-fixture/with-evm-entry
                      (assoc evm-fixture/evm-entry
                             :native-wei "0"
                             :token-units {evm-fixture/purr-index "50000000000000000000"})))
        pending (assoc known :hyperevm (hyperevm-balances/default-state))
        failed-first-read (evm-fixture/with-evm-entry
                           known
                           {:status :error
                            :error "rpc unavailable"
                            :error-kind :network})
        other-account (assoc known
                             :account-context {:spectate-mode {:active? true
                                                               :address account-b}})
        known-metrics (metrics-for known)
        refreshing-metrics (metrics-for refreshing)
        failed-refresh-metrics (metrics-for failed-refresh)]
    (is (close? (:hyperevm-equity known-metrics) 1808.5))
    (is (= (+ (:account-value-display known-metrics)
              (:vault-equity known-metrics)
              (:hyperevm-equity known-metrics))
           (:total-account-value-display known-metrics)))
    ;; Existing HyperEVM reads are stale-while-revalidate. A later transient
    ;; failure keeps the same complete, account-owned last good value.
    (is (= (:hyperevm-equity known-metrics)
           (:hyperevm-equity refreshing-metrics)
           (:hyperevm-equity failed-refresh-metrics)))
    (is (= (:total-account-value-display known-metrics)
           (:total-account-value-display refreshing-metrics)
           (:total-account-value-display failed-refresh-metrics)))
    (testing "a completed empty wallet is a confirmed zero"
      (let [metrics (metrics-for confirmed-empty)]
        (is (= 0 (:hyperevm-equity metrics)))
        (is (= (+ (:account-value-display metrics) (:vault-equity metrics))
               (:total-account-value-display metrics)))))
    (doseq [[label state] [["partial read" partial]
                           ["unpriced holdings" unpriced]
                           ["first read pending" pending]
                           ["first read failed" failed-first-read]
                           ["another account's cached balance" other-account]]]
      (testing label
        (let [metrics (metrics-for state)]
          (is (nil? (:hyperevm-equity metrics)))
          (is (nil? (:total-account-value-display metrics))))))))
