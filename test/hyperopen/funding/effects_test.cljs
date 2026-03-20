(ns hyperopen.funding.effects-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.effects :as effects]))

(deftest funding-effect-helper-normalizers-cover_chain_address_and_token_branches_test
  (let [normalize-chain-id @#'hyperopen.funding.effects/normalize-chain-id
        normalize-address @#'hyperopen.funding.effects/normalize-address
        non-blank-text @#'hyperopen.funding.effects/non-blank-text
        canonical-chain-token @#'hyperopen.funding.effects/canonical-chain-token
        same-chain-token? @#'hyperopen.funding.effects/same-chain-token?
        normalize-asset-key @#'hyperopen.funding.effects/normalize-asset-key]
    (is (= "0xa4b1" (normalize-chain-id "42161")))
    (is (= "0xa4b1" (normalize-chain-id " 0xA4B1 ")))
    (is (nil? (normalize-chain-id "not-a-chain-id")))
    (is (= "0x1111111111111111111111111111111111111111"
           (normalize-address " 0x1111111111111111111111111111111111111111 ")))
    (is (nil? (normalize-address "0x1234")))
    (is (= "Bitcoin" (non-blank-text " Bitcoin ")))
    (is (nil? (non-blank-text "   ")))
    (is (= "bitcoin" (canonical-chain-token " btc ")))
    (is (= "monad" (canonical-chain-token "monad")))
    (is (= true (same-chain-token? "btc" "bitcoin")))
    (is (= false (same-chain-token? "btc" "solana")))
    (is (= :btc (normalize-asset-key " BTC ")))
    (is (= :eth (normalize-asset-key :eth)))
    (is (nil? (normalize-asset-key 42)))))

(deftest funding-effect-protocol-address-validation-covers-supported-and-mismatched-source-shapes-test
  (let [protocol-address-matches-source-chain?
        @#'hyperopen.funding.effects/protocol-address-matches-source-chain?]
    (is (true? (protocol-address-matches-source-chain?
                "bitcoin"
                "bc1qpz0qv7jw4x3kg8qdpv9k7n4kl2f5dx6n9d5p3s")))
    (is (true? (protocol-address-matches-source-chain?
                "bitcoin"
                "tb1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq8m4h69")))
    (is (true? (protocol-address-matches-source-chain?
                "bitcoin"
                "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy")))
    (is (true? (protocol-address-matches-source-chain?
                "ethereum"
                "0x1111111111111111111111111111111111111111")))
    (is (true? (protocol-address-matches-source-chain?
                "monad"
                "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")))
    (is (true? (protocol-address-matches-source-chain?
                "plasma"
                "0x2222222222222222222222222222222222222222")))
    (is (true? (protocol-address-matches-source-chain?
                "solana"
                "So11111111111111111111111111111111111111112")))
    (is (false? (protocol-address-matches-source-chain?
                 "ethereum"
                 "bc1qpz0qv7jw4x3kg8qdpv9k7n4kl2f5dx6n9d5p3s")))
    (is (false? (protocol-address-matches-source-chain?
                 "solana"
                 "0x1111111111111111111111111111111111111111")))
    (is (false? (protocol-address-matches-source-chain?
                 "unknown"
                 "0x1111111111111111111111111111111111111111")))
    (is (false? (protocol-address-matches-source-chain?
                 "bitcoin"
                 "   ")))))

(deftest funding-effect-unit-and-error-helpers-cover-numeric-and-message-fallbacks-test
  (let [parse-usdc-units @#'hyperopen.funding.effects/parse-usdc-units
        parse-usdh-units @#'hyperopen.funding.effects/parse-usdh-units
        usdc-units->amount-text @#'hyperopen.funding.effects/usdc-units->amount-text
        fallback-exchange-response-error @#'hyperopen.funding.effects/fallback-exchange-response-error
        fallback-runtime-error-message @#'hyperopen.funding.effects/fallback-runtime-error-message
        wallet-error-message @#'hyperopen.funding.effects/wallet-error-message]
    (is (= "1234500" (.toString (parse-usdc-units "1.2345"))))
    (is (= "100000001" (.toString (parse-usdh-units "1.00000001"))))
    (is (nil? (parse-usdc-units "01.23")))
    (is (nil? (parse-usdh-units "1.123456789")))
    (is (= "0.000001" (usdc-units->amount-text (js/BigInt "1"))))
    (is (= "12.34" (usdc-units->amount-text (js/BigInt "12340000"))))
    (is (= "problem" (fallback-exchange-response-error {:error "problem"
                                                        :message "ignored"})))
    (is (= "message" (fallback-exchange-response-error {:message "message"})))
    (is (= "response" (fallback-exchange-response-error {:response "response"})))
    (is (= "Unknown exchange error" (fallback-exchange-response-error {})))
    (is (= "runtime boom" (fallback-runtime-error-message (js/Error. "runtime boom"))))
    (is (= "plain fallback" (fallback-runtime-error-message "plain fallback")))
    (is (= "Deposit transaction rejected in wallet."
           (wallet-error-message (doto #js {} (aset "code" 4001)))))
    (is (= "Deposit transaction rejected in wallet."
           (wallet-error-message (js/Error. "User rejected the request"))))
    (is (= "wallet offline"
           (wallet-error-message (js/Error. " wallet offline "))))
    (is (= "Unknown wallet error"
           (wallet-error-message (js/Error. "   "))))))

(deftest funding-effect-config-resolution-prefers-action-wallet-and-default-branches-test
  (let [resolve-deposit-chain-config @#'hyperopen.funding.effects/resolve-deposit-chain-config
        resolve-hyperunit-base-urls @#'hyperopen.funding.effects/resolve-hyperunit-base-urls
        resolve-hyperunit-base-url @#'hyperopen.funding.effects/resolve-hyperunit-base-url]
    (is (= "Arbitrum Sepolia"
           (:network-label
            (resolve-deposit-chain-config
             (atom {:wallet {:chain-id "0xa4b1"}})
             {:chainId "0x66eee"}))))
    (is (= "Arbitrum Sepolia"
           (:network-label
            (resolve-deposit-chain-config
             (atom {:wallet {:chain-id "421614"}})
             {}))))
    (is (= "Arbitrum"
           (:network-label
            (resolve-deposit-chain-config
             (atom {:wallet {:chain-id "0xdeadbeef"}})
             {}))))
    (is (= ["https://api.hyperunit-testnet.xyz"]
           (resolve-hyperunit-base-urls
            (atom {:wallet {:chain-id "0x66eee"}}))))
    (is (= ["https://api.hyperunit.xyz"]
           (resolve-hyperunit-base-urls
            (atom {:wallet {:chain-id "0xa4b1"}}))))
    (is (= "https://api.hyperunit-testnet.xyz"
           (resolve-hyperunit-base-url
            (atom {:wallet {:chain-id "421614"}}))))))

(deftest funding-effect-route-and-rpc-wrappers-pass-expected-options-test
  (async done
    (let [fetch-lifi-quote! @#'hyperopen.funding.effects/fetch-lifi-quote!
          fetch-across-approval! @#'hyperopen.funding.effects/fetch-across-approval!
          fetch-hyperunit-address! @#'hyperopen.funding.effects/fetch-hyperunit-address!
          fetch-hyperunit-address-with-source-fallbacks!
          @#'hyperopen.funding.effects/fetch-hyperunit-address-with-source-fallbacks!
          read-erc20-balance-units! @#'hyperopen.funding.effects/read-erc20-balance-units!
          read-erc20-allowance-units! @#'hyperopen.funding.effects/read-erc20-allowance-units!
          seen (atom {})]
      (with-redefs [hyperopen.funding.infrastructure.route-clients/fetch-lifi-quote!
                    (fn [opts]
                      (swap! seen assoc :lifi opts)
                      (js/Promise.resolve opts))
                    hyperopen.funding.infrastructure.route-clients/fetch-across-approval!
                    (fn [opts]
                      (swap! seen assoc :across opts)
                      (js/Promise.resolve opts))
                    hyperopen.funding.infrastructure.hyperunit-address-client/fetch-hyperunit-address!
                    (fn [base-url source-chain destination-chain asset destination-address]
                      (swap! seen assoc
                             :direct-address
                             [base-url source-chain destination-chain asset destination-address])
                      (js/Promise.resolve {:status "ok"}))
                    hyperopen.funding.infrastructure.hyperunit-address-client/fetch-hyperunit-address-with-source-fallbacks!
                    (fn [opts]
                      (swap! seen assoc :fallback-address opts)
                      (js/Promise.resolve opts))
                    hyperopen.funding.infrastructure.erc20-rpc/read-erc20-balance-units!
                    (fn [provider-request-fn provider token-address owner-address]
                      (swap! seen assoc
                             :balance
                             {:provider-request-fn provider-request-fn
                              :provider provider
                              :token-address token-address
                              :owner-address owner-address})
                      (js/Promise.resolve (js/BigInt "5")))
                    hyperopen.funding.infrastructure.erc20-rpc/read-erc20-allowance-units!
                    (fn [provider-request-fn provider token-address owner-address spender-address]
                      (swap! seen assoc
                             :allowance
                             {:provider-request-fn provider-request-fn
                              :provider provider
                              :token-address token-address
                              :owner-address owner-address
                              :spender-address spender-address})
                      (js/Promise.resolve (js/BigInt "9")))]
        (-> (js/Promise.all
             #js[(fetch-lifi-quote! "0xowner" (js/BigInt "1000000") "0xtoken")
                 (fetch-across-approval! "0xowner" (js/BigInt "2500000") "0xusdc")
                 (fetch-hyperunit-address! "https://api.hyperunit.xyz"
                                           "bitcoin"
                                           "hyperliquid"
                                           "btc"
                                           "0xowner")
                 (fetch-hyperunit-address-with-source-fallbacks!
                  "https://api.hyperunit.xyz"
                  ["https://api.hyperunit.xyz" "https://api.hyperunit-testnet.xyz"]
                  "btc"
                  "hyperliquid"
                  "btc"
                  "0xowner")
                 (read-erc20-balance-units! :provider "0xtoken" "0xowner")
                 (read-erc20-allowance-units! :provider "0xtoken" "0xowner" "0xspender")])
            (.then (fn [_results]
                     (is (= "hyperopen"
                            (get-in @seen [:lifi :integrator])))
                     (is (= "1000000"
                            (.toString (get-in @seen [:lifi :amount-units]))))
                     (is (= "0xtoken"
                            (get-in @seen [:lifi :to-token-address])))
                     (is (= "https://app.across.to/api/swap/approval"
                            (get-in @seen [:across :base-url])))
                     (is (= "2500000"
                            (.toString (get-in @seen [:across :amount-units]))))
                     (is (= ["https://api.hyperunit.xyz"
                             "bitcoin"
                             "hyperliquid"
                             "btc"
                             "0xowner"]
                            (:direct-address @seen)))
                     (is (= ["bitcoin" "btc"]
                            (get-in @seen [:fallback-address :source-chain-candidates])))
                     (is (= ["https://api.hyperunit.xyz" "https://api.hyperunit-testnet.xyz"]
                            (get-in @seen [:fallback-address :base-urls])))
                     (is (fn? (get-in @seen [:balance :provider-request-fn])))
                     (is (= :provider (get-in @seen [:balance :provider])))
                     (is (= "0xspender"
                            (get-in @seen [:allowance :spender-address])))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected wrapper helper failure: " err))
                      (done))))))))
