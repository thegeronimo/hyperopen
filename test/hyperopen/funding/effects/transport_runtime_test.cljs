(ns hyperopen.funding.effects.transport-runtime-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.application.deposit-submit :as deposit-submit]
            [hyperopen.funding.application.hyperunit-submit :as hyperunit-submit]
            [hyperopen.funding.effects.hyperunit-runtime :as hyperunit-runtime]
            [hyperopen.funding.effects.transport-runtime :as transport-runtime]
            [hyperopen.funding.infrastructure.hyperunit-address-client :as hyperunit-address-client]
            [hyperopen.wallet.core :as wallet]))

(deftest funding-effect-transport-runtime-route-and-rpc-wrappers-pass-expected-options-test
  (async done
    (let [seen (atom {})]
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
             #js[(transport-runtime/fetch-lifi-quote! "0xowner" (js/BigInt "1000000") "0xtoken")
                 (transport-runtime/fetch-across-approval! "0xowner" (js/BigInt "2500000") "0xusdc")
                 (transport-runtime/fetch-hyperunit-address! "https://api.hyperunit.xyz"
                                                             "bitcoin"
                                                             "hyperliquid"
                                                             "btc"
                                                             "0xowner")
                 (transport-runtime/fetch-hyperunit-address-with-source-fallbacks!
                  "https://api.hyperunit.xyz"
                  ["https://api.hyperunit.xyz" "https://api.hyperunit-testnet.xyz"]
                  "btc"
                  "hyperliquid"
                  "btc"
                  "0xowner")
                 (transport-runtime/read-erc20-balance-units! :provider "0xtoken" "0xowner")
                 (transport-runtime/read-erc20-allowance-units! :provider "0xtoken" "0xowner" "0xspender")])
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

(deftest funding-effect-transport-runtime-submit-request-wrappers-compose-expected-dependency-maps-test
  (let [store (atom {:wallet {:chain-id "0xa4b1"}})
        action {:type "bridge2Deposit"
                :asset "usdc"
                :amount "5"}
        seen (atom {})]
    (with-redefs [hyperunit-address-client/hyperunit-request-error-message
                  (fn [err ctx]
                    (swap! seen assoc :error-message [err ctx])
                    "request error")
                  hyperunit-submit/submit-hyperunit-address-deposit-request!
                  (fn [deps store* owner-address action*]
                    (swap! seen assoc
                           :address-submit
                           {:deps deps
                            :store store*
                            :owner-address owner-address
                            :action action*})
                    :address-submit-result)
                  hyperunit-submit/submit-hyperunit-send-asset-withdraw-request!
                  (fn [deps store* owner-address action* submit-send-asset!]
                    (swap! seen assoc
                           :withdraw-submit
                           {:deps deps
                            :store store*
                            :owner-address owner-address
                            :action action*
                            :submit-send-asset! submit-send-asset!})
                    :withdraw-submit-result)
                  deposit-submit/submit-usdc-bridge2-deposit-tx!
                  (fn [deps store* owner-address action*]
                    (swap! seen assoc
                           :usdc-deposit
                           {:deps deps
                            :store store*
                            :owner-address owner-address
                            :action action*})
                    :usdc-deposit-result)
                  deposit-submit/submit-usdh-across-deposit-tx!
                  (fn [deps store* owner-address action*]
                    (swap! seen assoc
                           :usdh-deposit
                           {:deps deps
                            :store store*
                            :owner-address owner-address
                            :action action*})
                    :usdh-deposit-result)
                  deposit-submit/submit-usdt-lifi-bridge2-deposit-tx!
                  (fn [deps store* owner-address action*]
                    (swap! seen assoc
                           :usdt-deposit
                           {:deps deps
                            :store store*
                            :owner-address owner-address
                            :action action*})
                    :usdt-deposit-result)]
      (is (= "request error"
             (transport-runtime/hyperunit-request-error-message :boom
                                                                {:asset "btc"
                                                                 :source-chain "bitcoin"})))
      (is (= [:boom {:asset "btc"
                     :source-chain "bitcoin"}]
             (:error-message @seen)))

      (is (= :address-submit-result
             (transport-runtime/submit-hyperunit-address-deposit-request! store "0xowner" action)))
      (is (identical? store
                      (get-in @seen [:address-submit :store])))
      (is (identical? hyperunit-runtime/request-existing-hyperunit-deposit-address!
                      (get-in @seen [:address-submit :deps :request-existing-hyperunit-deposit-address!])))
      (is (fn? (get-in @seen [:address-submit :deps :fetch-hyperunit-address-with-source-fallbacks!])))

      (is (= :withdraw-submit-result
             (transport-runtime/submit-hyperunit-send-asset-withdraw-request! store
                                                                               "0xowner"
                                                                               {:type "sendAsset"}
                                                                               :submit-send-asset)))
      (is (= :submit-send-asset
             (get-in @seen [:withdraw-submit :submit-send-asset!])))
      (is (fn? (get-in @seen [:withdraw-submit :deps :hyperunit-request-error-message])))
      (is (fn? (get-in @seen [:withdraw-submit :deps :fallback-exchange-response-error])))

      (is (= :usdc-deposit-result
             (transport-runtime/submit-usdc-bridge2-deposit-tx! store "0xowner" action)))
      (is (identical? wallet/provider
                      (get-in @seen [:usdc-deposit :deps :wallet-provider-fn])))
      (is (fn? (get-in @seen [:usdc-deposit :deps :resolve-deposit-chain-config])))
      (is (fn? (get-in @seen [:usdc-deposit :deps :wallet-error-message])))

      (is (= :usdh-deposit-result
             (transport-runtime/submit-usdh-across-deposit-tx! store "0xowner" {:type "acrossUsdcToUsdhDeposit"})))
      (is (= "Arbitrum"
             (get-in @seen [:usdh-deposit :deps :chain-config :network-label])))
      (is (= "100000000000000"
             (.toString (get-in @seen [:usdh-deposit :deps :usdh-route-max-units]))))
      (is (identical? wallet/provider
                      (get-in @seen [:usdh-deposit :deps :wallet-provider-fn])))

      (is (= :usdt-deposit-result
             (transport-runtime/submit-usdt-lifi-bridge2-deposit-tx! store "0xowner" {:type "lifiUsdtToUsdcBridge2Deposit"})))
      (is (= "0xa4b1"
             (get-in @seen [:usdt-deposit :deps :bridge-chain-id])))
      (is (= "Arbitrum"
             (get-in @seen [:usdt-deposit :deps :chain-config :network-label])))
      (is (fn? (get-in @seen [:usdt-deposit :deps :submit-usdc-bridge2-deposit!])))
      (is (fn? (get-in @seen [:usdt-deposit :deps :usdc-units->amount-text]))))))
