(ns hyperopen.funding.effects.transport-runtime
  (:require [hyperopen.funding.application.deposit-submit :as deposit-submit]
            [hyperopen.funding.application.hyperunit-submit :as hyperunit-submit]
            [hyperopen.funding.effects.common :as common]
            [hyperopen.funding.effects.hyperunit-runtime :as hyperunit-runtime]
            [hyperopen.funding.infrastructure.erc20-rpc :as erc20-rpc]
            [hyperopen.funding.infrastructure.hyperunit-address-client :as hyperunit-address-client]
            [hyperopen.funding.infrastructure.hyperunit-client :as hyperunit-client]
            [hyperopen.funding.infrastructure.route-clients :as route-clients]
            [hyperopen.funding.infrastructure.wallet-rpc :as wallet-rpc]
            [hyperopen.wallet.core :as wallet]))

(def encode-erc20-transfer-call-data
  erc20-rpc/encode-erc20-transfer-call-data)

(def encode-erc20-approve-call-data
  erc20-rpc/encode-erc20-approve-call-data)

(def provider-request!
  wallet-rpc/provider-request!)

(def ensure-wallet-chain!
  wallet-rpc/ensure-wallet-chain!)

(def wait-for-transaction-receipt!
  wallet-rpc/wait-for-transaction-receipt!)

(def send-and-confirm-evm-transaction!
  wallet-rpc/send-and-confirm-evm-transaction!)

(def across-approval->swap-config
  route-clients/across-approval->swap-config)

(def lifi-quote->swap-config
  route-clients/lifi-quote->swap-config)

(def with-hyperunit-base-url-fallbacks!
  hyperunit-client/with-hyperunit-base-url-fallbacks!)

(defn read-erc20-balance-units!
  [provider token-address owner-address]
  (erc20-rpc/read-erc20-balance-units!
   provider-request!
   provider
   token-address
   owner-address))

(defn read-erc20-allowance-units!
  [provider token-address owner-address spender-address]
  (erc20-rpc/read-erc20-allowance-units!
   provider-request!
   provider
   token-address
   owner-address
   spender-address))

(defn fetch-lifi-quote!
  [from-address amount-units to-token-address]
  (route-clients/fetch-lifi-quote! {:from-address from-address
                                    :amount-units amount-units
                                    :to-token-address to-token-address
                                    :from-chain-id common/arbitrum-mainnet-chain-id-decimal
                                    :to-chain-id common/arbitrum-mainnet-chain-id-decimal
                                    :from-token-address common/arbitrum-usdt-address
                                    :integrator "hyperopen"}))

(defn fetch-across-approval!
  [from-address amount-units usdc-address]
  (route-clients/fetch-across-approval! {:base-url common/across-swap-approval-base-url
                                         :from-address from-address
                                         :amount-units amount-units
                                         :input-token-address usdc-address
                                         :origin-chain-id common/arbitrum-mainnet-chain-id-decimal
                                         :output-token-address common/hypercore-usdh-address
                                         :destination-chain-id common/hypercore-chain-id-decimal}))

(defn fetch-hyperunit-address!
  [base-url source-chain destination-chain asset destination-address]
  (hyperunit-address-client/fetch-hyperunit-address!
   base-url
   source-chain
   destination-chain
   asset
   destination-address))

(defn fetch-hyperunit-address-with-source-fallbacks!
  [base-url base-urls source-chain destination-chain asset destination-address]
  (hyperunit-address-client/fetch-hyperunit-address-with-source-fallbacks!
   {:base-url base-url
    :base-urls base-urls
    :source-chain source-chain
    :destination-chain destination-chain
    :asset asset
    :destination-address destination-address
    :with-base-url-fallbacks! with-hyperunit-base-url-fallbacks!
    :source-chain-candidates (common/hyperunit-source-chain-candidates source-chain)
    :canonical-chain-token common/canonical-chain-token
    :canonical-token common/canonical-token}))

(defn hyperunit-request-error-message
  [err {:keys [asset source-chain]}]
  (hyperunit-address-client/hyperunit-request-error-message err
                                                            {:asset asset
                                                             :source-chain source-chain}))

(defn submit-hyperunit-address-deposit-request!
  [store owner-address action]
  (hyperunit-submit/submit-hyperunit-address-deposit-request!
   {:normalize-address common/normalize-address
    :non-blank-text common/non-blank-text
    :resolve-hyperunit-base-urls common/resolve-hyperunit-base-urls
    :request-existing-hyperunit-deposit-address! hyperunit-runtime/request-existing-hyperunit-deposit-address!
    :fetch-hyperunit-address-with-source-fallbacks! fetch-hyperunit-address-with-source-fallbacks!
    :hyperunit-request-error-message hyperunit-request-error-message}
   store
   owner-address
   action))

(defn submit-hyperunit-send-asset-withdraw-request!
  [store owner-address action submit-send-asset!]
  (hyperunit-submit/submit-hyperunit-send-asset-withdraw-request!
   {:normalize-address common/normalize-address
    :non-blank-text common/non-blank-text
    :resolve-hyperunit-base-urls common/resolve-hyperunit-base-urls
    :fetch-hyperunit-address-with-source-fallbacks! fetch-hyperunit-address-with-source-fallbacks!
    :fallback-exchange-response-error common/fallback-exchange-response-error
    :hyperunit-request-error-message hyperunit-request-error-message}
   store
   owner-address
   action
   submit-send-asset!))

(defn submit-usdc-bridge2-deposit-tx!
  [store owner-address action]
  (deposit-submit/submit-usdc-bridge2-deposit-tx!
   {:wallet-provider-fn wallet/provider
    :normalize-address common/normalize-address
    :resolve-deposit-chain-config common/resolve-deposit-chain-config
    :parse-usdc-units common/parse-usdc-units
    :ensure-wallet-chain! ensure-wallet-chain!
    :provider-request! provider-request!
    :wait-for-transaction-receipt! wait-for-transaction-receipt!
    :encode-erc20-transfer-call-data encode-erc20-transfer-call-data
    :wallet-error-message common/wallet-error-message}
   store
   owner-address
   action))

(defn submit-usdh-across-deposit-tx!
  [store owner-address action]
  (deposit-submit/submit-usdh-across-deposit-tx!
   {:wallet-provider-fn wallet/provider
    :normalize-address common/normalize-address
    :parse-usdh-units common/parse-usdh-units
    :usdh-route-max-units common/usdh-route-max-units
    :chain-config (get common/chain-config-by-id common/arbitrum-mainnet-chain-id)
    :ensure-wallet-chain! ensure-wallet-chain!
    :fetch-across-approval! fetch-across-approval!
    :across-approval->swap-config across-approval->swap-config
    :send-and-confirm-evm-transaction! send-and-confirm-evm-transaction!
    :wallet-error-message common/wallet-error-message}
   store
   owner-address
   action))

(defn submit-usdt-lifi-bridge2-deposit-tx!
  [store owner-address action]
  (deposit-submit/submit-usdt-lifi-bridge2-deposit-tx!
   {:wallet-provider-fn wallet/provider
    :normalize-address common/normalize-address
    :parse-usdc-units common/parse-usdc-units
    :chain-config (get common/chain-config-by-id common/arbitrum-mainnet-chain-id)
    :ensure-wallet-chain! ensure-wallet-chain!
    :fetch-lifi-quote! fetch-lifi-quote!
    :lifi-quote->swap-config lifi-quote->swap-config
    :read-erc20-allowance-units! read-erc20-allowance-units!
    :encode-erc20-approve-call-data encode-erc20-approve-call-data
    :provider-request! provider-request!
    :wait-for-transaction-receipt! wait-for-transaction-receipt!
    :read-erc20-balance-units! read-erc20-balance-units!
    :submit-usdc-bridge2-deposit! submit-usdc-bridge2-deposit-tx!
    :usdc-units->amount-text common/usdc-units->amount-text
    :bridge-chain-id common/arbitrum-mainnet-chain-id
    :wallet-error-message common/wallet-error-message}
   store
   owner-address
   action))
