(ns hyperopen.funding.infrastructure.erc20-rpc
  (:require [clojure.string :as str]))

(defn- normalize-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (string? text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn- left-pad-hex
  [hex-value width]
  (let [value (or hex-value "")
        value-len (count value)]
    (if (>= value-len width)
      value
      (str (apply str (repeat (- width value-len) "0")) value))))

(defn encode-erc20-transfer-call-data
  [to-address amount-units]
  (let [to* (normalize-address to-address)
        to-param (left-pad-hex (subs to* 2) 64)
        amount-param (left-pad-hex (.toString amount-units 16) 64)]
    (str "0xa9059cbb" to-param amount-param)))

(defn encode-erc20-approve-call-data
  [spender-address amount-units]
  (let [spender* (normalize-address spender-address)
        spender-param (left-pad-hex (subs spender* 2) 64)
        amount-param (left-pad-hex (.toString amount-units 16) 64)]
    (str "0x095ea7b3" spender-param amount-param)))

(defn encode-erc20-balance-of-call-data
  [owner-address]
  (let [owner* (normalize-address owner-address)
        owner-param (left-pad-hex (subs owner* 2) 64)]
    (str "0x70a08231" owner-param)))

(defn encode-erc20-allowance-call-data
  [owner-address spender-address]
  (let [owner* (normalize-address owner-address)
        spender* (normalize-address spender-address)
        owner-param (left-pad-hex (subs owner* 2) 64)
        spender-param (left-pad-hex (subs spender* 2) 64)]
    (str "0xdd62ed3e" owner-param spender-param)))

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
