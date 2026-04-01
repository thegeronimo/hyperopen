(ns hyperopen.trading-crypto.module-exports-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.trading-crypto.module]))

(deftest trading-crypto-module-exports-are-available-test
  (let [module (aget js/globalThis "hyperopen" "trading_crypto" "module")
        export-names ["createAgentCredentials"
                      "privateKeyToAgentAddress"
                      "signL1ActionWithPrivateKey"
                      "signApproveAgentAction"
                      "signUsdClassTransferAction"
                      "signSendAssetAction"
                      "signCDepositAction"
                      "signCWithdrawAction"
                      "signTokenDelegateAction"
                      "signWithdraw3Action"]]
    (doseq [export-name export-names]
      (is (fn? (aget module export-name))))))
