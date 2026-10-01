(ns hyperopen.hyperevm.domain.txs
  "The HyperEVM transactions behind a HyperEVM -> HyperCore move.

   They encode business rules, which is why they are domain code: where each
   kind of token goes, and that USDC must be approved and then deposited
   through Circle's CoreDepositWallet (a plain USDC transfer to its system
   address is never credited, and USDC sent straight to the
   CoreDepositWallet is lost).

   Every builder reads its addresses and amount from a `hyperEvmToCore`
   action that `hyperopen.funding.domain.transfer-invariants/check-request`
   has already accepted, and pins `:chainId` to HyperEVM mainnet (0x3e7), so
   a wallet that changed network can never send the other chain's native
   coin to a HyperEVM system address. Builders return nil on invalid input
   rather than throwing. Every value is a string: JSON-RPC hex quantities or
   `0x` calldata, never a BigInt."
  (:require [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.fees :as fees]
            [hyperopen.hyperevm.domain.units :as units]))

(def chain-id
  "Every HyperEVM transaction the app sends is pinned to this chain."
  (:chain-id chain/mainnet))

(defn- positive-units
  [value]
  (let [units* (units/to-bigint value)]
    (when (and units* (> units* units/zero))
      units*)))

(defn- envelope
  "`{:from :to :chainId}` plus `fields`, or nil when an address or any
   field is missing."
  [from to fields]
  (let [from* (abi/normalize-address from)
        to* (abi/normalize-address to)]
    (when (and from* to* (every? some? (vals fields)))
      (merge {:from from* :to to* :chainId chain-id} fields))))

(defn native-to-core-tx
  "Native HYPE to HYPE's system address (0x2222…): a plain value transfer
   with no calldata."
  [from {:keys [recipient units]}]
  (when (= (abi/normalize-address recipient) (:hype-system-address chain/mainnet))
    (envelope from recipient {:value (some-> (positive-units units) abi/quantity-hex)})))

(defn erc20-to-core-tx
  "ERC-20 `transfer(systemAddress, units)` on the token's contract."
  [from {:keys [tokenAddress recipient units]}]
  (envelope from tokenAddress {:data (some->> (positive-units units)
                                              (abi/encode-transfer recipient))}))

(defn usdc-approve-tx
  "Native USDC `approve(CoreDepositWallet, units)` for exactly the amount
   being moved, never an unlimited allowance."
  [from {:keys [tokenAddress spender units]}]
  (envelope from tokenAddress {:data (some->> (positive-units units)
                                              (abi/encode-approve spender))}))

(defn usdc-core-deposit-tx
  "CoreDepositWallet `deposit(units, destinationDex)`; `destinationDex`
   4294967295 credits Spot."
  [from {:keys [spender units destinationDex]}]
  (envelope from spender {:data (some-> (positive-units units)
                                        (abi/encode-core-deposit destinationDex))}))

(defn transfer-plan
  "The transactions a `hyperEvmToCore` action sends, in order, as
   `[{:step :gas-kind :tx :optional?}]`. `:step` names the progress step
   (`:send`, `:approve`, `:deposit`), `:gas-kind` its fallback gas limit,
   and `:optional?` marks the USDC approve, which is skipped when the
   allowance already covers the amount. A `:tx` is nil when the action
   cannot be encoded. An unknown `:kind` throws: there is deliberately no
   default branch."
  [from action]
  (case (:kind action)
    "native" [{:step :send :gas-kind :native :tx (native-to-core-tx from action)}]
    "erc20" [{:step :send :gas-kind :erc20 :tx (erc20-to-core-tx from action)}]
    "usdcCoreDeposit" [{:step :approve :gas-kind :usdc-approve :optional? true
                        :tx (usdc-approve-tx from action)}
                       {:step :deposit :gas-kind :usdc-deposit
                        :tx (usdc-core-deposit-tx from action)}]))

(defn estimate-request
  "The `eth_estimateGas` params for `tx`: the call itself, without chain or
   fee fields."
  [tx]
  (select-keys tx [:from :to :value :data]))

(defn with-fees
  "`tx` with an explicit gas limit and EIP-1559 fees: `maxFeePerGas`, and a
   zero priority fee (burned on HyperEVM, and `eth_maxPriorityFeePerGas`
   answers 0). `gasPrice` is never set, so a wallet cannot mix fee models.
   `gas-limit` and `max-fee-per-gas` are BigInts; nil when either is
   missing."
  [tx gas-limit max-fee-per-gas]
  (let [gas (some-> gas-limit abi/quantity-hex)
        max-fee (some-> max-fee-per-gas abi/quantity-hex)]
    (when (and tx gas max-fee)
      (assoc tx :gas gas :maxFeePerGas max-fee :maxPriorityFeePerGas "0x0"))))

(defn priced-tx
  "`tx` priced for sending: the gas limit from `estimate-text` (or the
   `gas-kind` fallback) and the max fee from `gas-price-wei-text`."
  [tx gas-kind estimate-text gas-price-wei-text]
  (with-fees tx
             (fees/gas-limit estimate-text gas-kind)
             (fees/max-fee-per-gas-wei gas-price-wei-text)))

(defn allowance-covers?
  "Whether an ERC-20 allowance (decimal units text) covers `units`. False
   when either is unknown."
  [allowance-text units]
  (let [allowance (units/to-bigint allowance-text)
        needed (units/to-bigint units)]
    (boolean (and allowance needed (>= allowance needed)))))
