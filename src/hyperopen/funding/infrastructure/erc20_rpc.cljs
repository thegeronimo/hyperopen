(ns hyperopen.funding.infrastructure.erc20-rpc
  "ERC-20 calls through a wallet provider. The calldata comes from the one
   hand-rolled encoder, `hyperopen.hyperevm.domain.abi`. These encoders throw
   on an invalid address or amount (the abi functions return nil), so a
   caller never sends a transaction without its calldata."
  (:require [clojure.string :as str]
            [hyperopen.hyperevm.domain.abi :as abi]))

(defn- required-call-data
  [call-data label]
  (or call-data
      (throw (js/Error. (str "Invalid ERC-20 " label " call data.")))))

(defn encode-erc20-transfer-call-data
  [to-address amount-units]
  (required-call-data (abi/encode-transfer to-address amount-units) "transfer"))

(defn encode-erc20-approve-call-data
  [spender-address amount-units]
  (required-call-data (abi/encode-approve spender-address amount-units) "approve"))

(defn encode-erc20-balance-of-call-data
  [owner-address]
  (required-call-data (abi/encode-balance-of owner-address) "balanceOf"))

(defn encode-erc20-allowance-call-data
  [owner-address spender-address]
  (required-call-data (abi/encode-allowance owner-address spender-address) "allowance"))

(defn bigint-from-hex
  [value]
  (let [text (-> (or value "0x0")
                 str
                 str/trim
                 str/lower-case)]
    (if (re-matches #"^0x[0-9a-f]+$" text)
      (js/BigInt text)
      (js/BigInt "0"))))

(defn read-erc20-balance-units!
  [provider-request! provider token-address owner-address]
  (-> (provider-request! provider
                         "eth_call"
                         [{:to token-address
                           :data (encode-erc20-balance-of-call-data owner-address)}
                          "latest"])
      (.then bigint-from-hex)))

(defn read-erc20-allowance-units!
  [provider-request! provider token-address owner-address spender-address]
  (-> (provider-request! provider
                         "eth_call"
                         [{:to token-address
                           :data (encode-erc20-allowance-call-data owner-address spender-address)}
                          "latest"])
      (.then bigint-from-hex)))
