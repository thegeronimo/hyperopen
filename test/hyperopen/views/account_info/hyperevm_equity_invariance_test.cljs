(ns hyperopen.views.account-info.hyperevm-equity-invariance-test
  "HyperEVM funds cannot margin a position. The Balances tab shows HyperEVM
   rows; the classic grouped Account Equity total includes their confirmed USD
   value, while trading equity, the shared balance-row memo and the Portfolio
   summary remain unchanged."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.vm :as account-info-vm]
            [hyperopen.views.portfolio.vm :as portfolio-vm]))

(defn- reset-caches!
  []
  (account-info-vm/reset-account-info-vm-cache!)
  (account-equity-view/reset-account-equity-metrics-cache!)
  (portfolio-vm/reset-portfolio-vm-cache!)
  (reset! portfolio-vm/last-metrics-request nil))

(use-fixtures :each
  (fn [f]
    (reset-caches!)
    (f)
    (reset-caches!)))

(def ^:private whale-evm-entry
  "A HyperEVM wallet worth far more than the HyperCore account: 5,000 HYPE,
   1,000,000 USDC and 250,000 PURR."
  (assoc fixture/evm-entry
         :native-wei "5000000000000000000000"
         :token-units {fixture/usdc-index "1000000000000"
                       fixture/purr-index "250000000000000000000000"}))

(def ^:private with-evm
  (-> (fixture/state)
      (fixture/with-evm-entry whale-evm-entry)
      (assoc :vaults {:user-equities []
                      :user-equities-for-address fixture/owner
                      :loading {:user-equities? false}
                      :errors {:user-equities nil}})
      (assoc :portfolio-ui {:summary-scope :all :summary-time-range :month}
             :portfolio {:summary-by-key {:month {:pnlHistory [[1 10] [2 30]]
                                                  :accountValueHistory [[1 100] [2 100]]
                                                  :vlm 1}}})))

(def ^:private without-evm
  (assoc with-evm :hyperevm (hyperevm-balances/default-state)))

(defn- hyperevm-row?
  [row]
  (= :hyperevm (:location row)))

(defn- fresh
  "`f` of `state*` with every identity cache emptied first, so nothing
   computed for the other state is reused."
  [f state*]
  (reset-caches!)
  (f state*))

(deftest the-balances-tab-shows-hyperevm-rows-test
  (let [rows (:balance-rows (account-info-vm/account-info-vm with-evm))]
    (is (= 3 (count (filter hyperevm-row? rows))))
    (is (< 1000000 (reduce + (keep #(when (hyperevm-row? %) (:usdc-value %)) rows)))
        "they carry a large USD value, so leaking them would move every figure")))

(deftest hyperevm-balances-change-only-the-classic-grouped-total-test
  (let [baseline (fresh account-equity-view/account-equity-metrics without-evm)
        _ (reset-caches!)
        ;; Render the Balances tab first, as the page does, so the HyperEVM
        ;; rows exist when the metrics are derived.
        _ (account-info-vm/account-info-vm with-evm)
        with-rows (account-equity-view/account-equity-metrics with-evm)]
    (is (some? (:spot-equity baseline)))
    (is (= (select-keys baseline [:spot-equity
                                  :perps-value
                                  :account-value-display
                                  :base-balance
                                  :maintenance-margin
                                  :cross-margin-ratio
                                  :cross-account-leverage])
           (select-keys with-rows [:spot-equity
                                   :perps-value
                                   :account-value-display
                                   :base-balance
                                   :maintenance-margin
                                   :cross-margin-ratio
                                   :cross-account-leverage])))
    (is (pos? (:hyperevm-equity with-rows)))
    (is (= (+ (:account-value-display with-rows)
              (:vault-equity with-rows 0)
              (:hyperevm-equity with-rows))
           (:total-account-value-display with-rows)))
    (is (= with-rows (fresh account-equity-view/account-equity-metrics with-evm)))))

(deftest the-shared-balance-row-memo-never-holds-hyperevm-rows-test
  (let [_ (account-info-vm/account-info-vm with-evm)
        memo (derived-cache/memoized-balance-rows (:webdata2 with-evm)
                                                  (:spot with-evm)
                                                  (:account with-evm)
                                                  (get-in with-evm [:asset-selector :market-by-key])
                                                  (:perp-dex-clearinghouse with-evm))]
    (is (seq memo))
    (is (not-any? hyperevm-row? memo))
    (is (not-any? #(:move-targets %) memo)
        "the view-model annotates its own copy, never the shared rows")))

(deftest portfolio-total-equity-is-unchanged-by-hyperevm-balances-test
  (let [summary (fn [state*] (:summary (fresh portfolio-vm/portfolio-vm state*)))
        baseline (summary without-evm)
        with-rows (summary with-evm)]
    (is (number? (:total-equity baseline)))
    (is (= (:total-equity baseline) (:total-equity with-rows)))
    (is (= (:spot-account-equity baseline) (:spot-account-equity with-rows)))
    (is (= (:perps-account-equity baseline) (:perps-account-equity with-rows)))))
