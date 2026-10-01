(ns hyperopen.funding.domain.transfer-invariants-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.domain.transfer-invariants :as invariants]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]))

(defn- request
  [modal-overrides]
  (let [state (support/state modal-overrides)]
    (:request (evm-preview/evm-transfer-preview state (get-in state [:funding-ui :modal])
                                                support/now-ms))))

(def ^:private purr-out
  (request {:transfer-from :spot :transfer-to :hyperevm
            :transfer-asset support/purr-index :amount-input "100"}))

(def ^:private hype-in
  (request {:transfer-from :hyperevm :transfer-to :spot
            :transfer-asset support/hype-index :amount-input "10"}))

(def ^:private usdc-in
  (request {:transfer-from :hyperevm :transfer-to :spot
            :transfer-asset support/usdc-index :amount-input "1000"}))

(deftest requests-built-by-the-preview-pass-test
  (is (nil? (invariants/check-request (support/state) purr-out)))
  (is (nil? (invariants/check-request (support/state) hype-in)))
  (is (nil? (invariants/check-request (support/state) usdc-in)))
  (is (nil? (invariants/check-request (support/state)
                                      (:request (evm-preview/gas-topup-request (support/state)))))))

(deftest send-asset-must-target-its-own-system-address-test
  (testing "another token's system address"
    (is (string? (invariants/check-request
                  (support/state)
                  (assoc-in purr-out [:action :destination]
                            "0x2000000000000000000000000000000000000006")))))
  (testing "HYPE's 0x2222… for a non-HYPE token"
    (is (string? (invariants/check-request
                  (support/state)
                  (assoc-in purr-out [:action :destination]
                            "0x2222222222222222222222222222222222222222")))))
  (testing "a system address for a token that is not linked"
    (is (string? (invariants/check-request
                  (support/state)
                  (assoc-in purr-out [:action :token] "FAKE:0x00")))))
  (testing "ordinary sends to a wallet are left alone, even one starting 0x20"
    (is (nil? (invariants/check-request
               (support/state)
               {:action {:type "sendAsset"
                         :destination "0x20ab567890abcdef1234567890abcdef12345678"
                         :token "PURR:0xc1fb593aeffbeb02f85e0308e9956a90"
                         :amount "1"}})))
    (is (nil? (invariants/check-request (support/state)
                                        {:action {:type "usdClassTransfer"
                                                  :amount "1" :toPerp true}})))))

(deftest hyperevm-to-core-must-match-the-catalog-test
  (doseq [[label bad] [["USDC sent as a plain transfer" (assoc-in usdc-in [:action :kind] "erc20")]
                       ["USDC with a recipient" (assoc-in usdc-in [:action :recipient]
                                                          "0x2000000000000000000000000000000000000000")]
                       ["a different spender" (assoc-in usdc-in [:action :spender]
                                                        "0x2222222222222222222222222222222222222222")]
                       ["HYPE to a token system address"
                        (assoc-in hype-in [:action :recipient]
                                  "0x2000000000000000000000000000000000000001")]
                       ["units that do not match the amount" (assoc-in hype-in [:action :units] "1")]
                       ["another chain" (assoc-in hype-in [:action :chainId] "0x3e6")]
                       ["an unknown token" (assoc-in hype-in [:action :tokenIndex] 99999)]]]
    (testing label
      (is (string? (invariants/check-request (support/state) bad))))))

(deftest send-asset-to-a-system-address-must-be-a-master-spot-move-at-core-precision-test
  (doseq [[label bad] [["from a subaccount (its HyperEVM address has no key)"
                        (assoc-in purr-out [:action :fromSubAccount]
                                  "0xbce774ef2382a4eb9376ea6f20408b318b10b63e")]
                       ["into a perps dex" (assoc-in purr-out [:action :destinationDex] "")]
                       ["more decimals than PURR's Core precision (5)"
                        (assoc-in purr-out [:action :amount] "1.123456")]
                       ["an amount that is not a plain decimal"
                        (assoc-in purr-out [:action :amount] "1e-7")]
                       ["a Core -> EVM route that does not target a system address"
                        (assoc-in purr-out [:action :destination]
                                  "0x1234567890abcdef1234567890abcdef12345678")]]]
    (testing label
      (is (string? (invariants/check-request (support/state) bad)))))
  (testing "trailing zeros are not extra precision"
    (is (nil? (invariants/check-request (support/state)
                                        (assoc-in purr-out [:action :amount] "1.1000000"))))))

(deftest hyperevm-to-core-must-land-in-spot-at-core-precision-test
  (testing "destinationDex 0 would deposit to Perps through CoreWriter, which cannot revert"
    (is (string? (invariants/check-request (support/state)
                                           (assoc-in usdc-in [:action :destinationDex] 0)))))
  (testing "digits beyond HYPE's Core precision (8) would be lost on HyperCore"
    (is (string? (invariants/check-request
                  (support/state)
                  (update hype-in :action assoc
                          :amount "1.123456789"
                          :units "1123456789000000000"))))))
