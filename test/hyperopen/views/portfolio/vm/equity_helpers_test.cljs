(ns hyperopen.views.portfolio.vm.equity-helpers-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.vm.equity :as vm-equity]))

(deftest equity-helpers-follow-classic-and-unified-account-contracts-test
  (is (true? (vm-equity/top-up-abstraction-enabled? {:account {:mode :unified}})))
  (is (false? (vm-equity/top-up-abstraction-enabled? {:account {:mode :classic}})))
  (is (= 12 (vm-equity/earn-balance {:borrow-lend {:total-supplied-usd "12"}})))
  (is (= 30 (vm-equity/vault-equity {:webdata2 {:totalVaultEquity "30"}}
                                    {:totalVaultEquity "10"})))
  (is (= 10 (vm-equity/vault-equity {}
                                    {:totalVaultEquity "10"})))
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
                                             :earn-equity 40}))))
