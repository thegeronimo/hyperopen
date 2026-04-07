(ns hyperopen.funding.effects.common-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.effects.common :as common]))

(deftest funding-effect-common-normalizers-cover-chain-address-token-and-asset-branches-test
  (is (= "0xa4b1" (common/normalize-chain-id "42161")))
  (is (= "0xa4b1" (common/normalize-chain-id " 0xA4B1 ")))
  (is (nil? (common/normalize-chain-id "not-a-chain-id")))
  (is (= "0x1111111111111111111111111111111111111111"
         (common/normalize-address " 0x1111111111111111111111111111111111111111 ")))
  (is (nil? (common/normalize-address "0x1234")))
  (is (= "Bitcoin" (common/non-blank-text " Bitcoin ")))
  (is (nil? (common/non-blank-text "   ")))
  (is (= "bitcoin" (common/canonical-chain-token " btc ")))
  (is (= "monad" (common/canonical-chain-token "monad")))
  (is (= true (common/same-chain-token? "btc" "bitcoin")))
  (is (= false (common/same-chain-token? "btc" "solana")))
  (is (= ["bitcoin" "btc"] (common/hyperunit-source-chain-candidates " BTC ")))
  (is (= ["hyperliquid"] (common/hyperunit-source-chain-candidates "hyperliquid")))
  (is (= [] (common/hyperunit-source-chain-candidates nil)))
  (is (= :btc (common/normalize-asset-key " BTC ")))
  (is (= :eth (common/normalize-asset-key :eth)))
  (is (nil? (common/normalize-asset-key 42))))

(deftest funding-effect-common-protocol-address-validation-covers-supported-and-mismatched-source-shapes-test
  (is (true? (common/protocol-address-matches-source-chain?
              "bitcoin"
              "bc1qpz0qv7jw4x3kg8qdpv9k7n4kl2f5dx6n9d5p3s")))
  (is (true? (common/protocol-address-matches-source-chain?
              "bitcoin"
              "tb1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq8m4h69")))
  (is (true? (common/protocol-address-matches-source-chain?
              "bitcoin"
              "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy")))
  (is (true? (common/protocol-address-matches-source-chain?
              "ethereum"
              "0x1111111111111111111111111111111111111111")))
  (is (true? (common/protocol-address-matches-source-chain?
              "monad"
              "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")))
  (is (true? (common/protocol-address-matches-source-chain?
              "plasma"
              "0x2222222222222222222222222222222222222222")))
  (is (true? (common/protocol-address-matches-source-chain?
              "solana"
              "So11111111111111111111111111111111111111112")))
  (is (false? (common/protocol-address-matches-source-chain?
               "ethereum"
               "bc1qpz0qv7jw4x3kg8qdpv9k7n4kl2f5dx6n9d5p3s")))
  (is (false? (common/protocol-address-matches-source-chain?
               "solana"
               "0x1111111111111111111111111111111111111111")))
  (is (false? (common/protocol-address-matches-source-chain?
               "unknown"
               "0x1111111111111111111111111111111111111111")))
  (is (false? (common/protocol-address-matches-source-chain?
               "bitcoin"
               "   "))))

(deftest funding-effect-common-unit-and-error-helpers-cover-numeric-and-message-fallbacks-test
  (is (= "1234500" (.toString (common/parse-usdc-units "1.2345"))))
  (is (= "100000001" (.toString (common/parse-usdh-units "1.00000001"))))
  (is (nil? (common/parse-usdc-units "01.23")))
  (is (nil? (common/parse-usdh-units "1.123456789")))
  (is (= "0.000001" (common/usdc-units->amount-text (js/BigInt "1"))))
  (is (= "12.34" (common/usdc-units->amount-text (js/BigInt "12340000"))))
  (is (= "problem" (common/fallback-exchange-response-error {:error "problem"
                                                             :message "ignored"})))
  (is (= "message" (common/fallback-exchange-response-error {:message "message"})))
  (is (= "response" (common/fallback-exchange-response-error {:response "response"})))
  (is (= "Unknown exchange error" (common/fallback-exchange-response-error {})))
  (is (= "runtime boom" (common/fallback-runtime-error-message (js/Error. "runtime boom"))))
  (is (= "plain fallback" (common/fallback-runtime-error-message "plain fallback")))
  (is (= "Deposit transaction rejected in wallet."
         (common/wallet-error-message (doto #js {} (aset "code" 4001)))))
  (is (= "Deposit transaction rejected in wallet."
         (common/wallet-error-message (js/Error. "User rejected the request"))))
  (is (= "wallet offline"
         (common/wallet-error-message (js/Error. " wallet offline "))))
  (is (= "Unknown wallet error"
         (common/wallet-error-message (js/Error. "   ")))))

(deftest funding-effect-common-config-resolution-prefers-action-wallet-and-default-branches-test
  (is (= "Arbitrum Sepolia"
         (:network-label
          (common/resolve-deposit-chain-config
           (atom {:wallet {:chain-id "0xa4b1"}})
           {:chainId "0x66eee"}))))
  (is (= "Arbitrum Sepolia"
         (:network-label
          (common/resolve-deposit-chain-config
           (atom {:wallet {:chain-id "421614"}})
           {}))))
  (is (= "Arbitrum"
         (:network-label
          (common/resolve-deposit-chain-config
           (atom {:wallet {:chain-id "0xdeadbeef"}})
           {}))))
  (is (= ["https://api.hyperunit-testnet.xyz"]
         (common/resolve-hyperunit-base-urls
          (atom {:wallet {:chain-id "0x66eee"}}))))
  (is (= ["https://api.hyperunit.xyz"]
         (common/resolve-hyperunit-base-urls
          (atom {:wallet {:chain-id "0xa4b1"}}))))
  (is (= "https://api.hyperunit-testnet.xyz"
         (common/resolve-hyperunit-base-url
          (atom {:wallet {:chain-id "421614"}})))))
