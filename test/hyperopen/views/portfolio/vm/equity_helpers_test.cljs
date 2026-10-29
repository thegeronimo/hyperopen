(ns hyperopen.views.portfolio.vm.equity-helpers-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.vm.equity :as vm-equity]))

(deftest equity-helpers-follow-classic-and-unified-account-contracts-test
  (is (true? (vm-equity/top-up-abstraction-enabled? {:account {:mode :unified}})))
  (is (false? (vm-equity/top-up-abstraction-enabled? {:account {:mode :classic}})))
  ;; Earn is the owned lending state's supplied value net of borrows.
  (let [owner "0x1111111111111111111111111111111111111111"]
    (is (= 12 (vm-equity/earn-balance {:wallet {:address owner}
                                       :borrow-lend {:user-address owner
                                                     :total-supplied-usd 15
                                                     :total-borrowed-usd 3}})))
    (is (= 0 (vm-equity/earn-balance {:wallet {:address owner}
                                      :borrow-lend {:user-address "0x2222222222222222222222222222222222222222"
                                                    :total-supplied-usd 15}}))))
  (is (= 30 (vm-equity/vault-equity {:wallet {:address "0x1111111111111111111111111111111111111111"}
                                     :vaults {:user-equities [{:equity-raw "12.5"}
                                                              {:equity-raw "17.5"}]
                                              :user-equities-for-address "0x1111111111111111111111111111111111111111"}})))
  (is (= 0 (vm-equity/vault-equity {}))
      "no account shown")
  (is (nil? (vm-equity/vault-equity {:wallet {:address "0x1111111111111111111111111111111111111111"}
                                     :webdata2 {:totalVaultEquity "30"}}))
      "webData2 is retired; its total is never read and unknown is not zero")
  (is (= 25 (vm-equity/perp-account-equity {:webdata2 {:clearinghouseState {:marginSummary {:accountValue "25"}}}}
                                           {:cross-account-value "4"
                                            :perps-value "3"})))
  (is (= 15 (vm-equity/perp-account-equity {:webdata2 {:clearinghouseState {:crossMarginSummary {:accountValue "15"}}}}
                                           {})))
  (is (= 5 (vm-equity/perp-account-equity {}
                                          {:cross-account-value "5"})))
  (is (= 7 (vm-equity/spot-account-equity {:spot-equity "7"})))
  (is (= 9 (vm-equity/staking-account-hype {:staking {:total-hype "9"}})))
  (is (= 8 (vm-equity/staking-account-hype {:staking {:total "8"}})))
  (is (= 1009.5
         (vm-equity/staking-account-hype
          {:staking {:delegator-summary {:delegated "1008.25"
                                          :undelegated "1.0"
                                          :total-pending-withdrawal "0.25"}}})))
  (is (= 3.5
         (vm-equity/staking-account-hype
          {:staking {:delegations [{:amount "1.25"}
                                   {:amount "2.25"}]}})))
  (is (= 0 (vm-equity/staking-value-usd nil 10)))
  (is (= 105 (vm-equity/compute-total-equity {:top-up-enabled? false
                                              :vault-equity 10
                                              :spot-equity 20
                                              :staking-value-usd 5
                                              :perp-equity 30
                                              :earn-equity 40})))
  (is (= 35 (vm-equity/compute-total-equity {:top-up-enabled? true
                                             :vault-equity 10
                                             :spot-equity 20
                                             :staking-value-usd 5
                                             :perp-equity 30
                                             :earn-equity 40})))
  (is (nil? (vm-equity/compute-total-equity {:top-up-enabled? false
                                             :vault-equity nil
                                             :spot-equity 20
                                             :perp-equity 30}))
      "a total without the vaults is unknown, not a smaller number"))
