(ns hyperopen.hyperevm.domain.chain
  "HyperEVM network constants.

   HyperEVM is Hyperliquid's Ethereum-compatible chain. The same wallet address
   holds ERC-20 tokens and native HYPE there, and the tokens bridge to and from
   the HyperCore spot ledger through per-token *system addresses*.

   Only `mainnet` is routed. Hyperopen's HyperCore info and exchange URLs are
   hard-coded to mainnet, so sending a HyperEVM transaction to testnet while
   Core reads mainnet would strand funds. `testnet` exists for fixtures only.

   Every value below was verified live on 2026-09-30 (eth_chainId, Multicall3
   bytecode, CoreDepositWallet.token()); see the HyperEVM transfer ExecPlan's
   Surprises & Discoveries. Chain ids are lowercase hex without leading zeros,
   because `hyperopen.funding.infrastructure.wallet-rpc/ensure-wallet-chain!`
   compares them against the wallet's normalized chain id verbatim.")

(def mainnet
  "HyperEVM mainnet (chain 999).

   - `:usdc-token-address` is Circle's native USDC ERC-20 (6 decimals). It is
     NOT spotMeta's USDC `evmContract.address`.
   - `:usdc-core-deposit-wallet` IS spotMeta's USDC `evmContract.address`:
     Circle's CoreDepositWallet proxy. It has no ERC-20 surface (balanceOf and
     decimals revert), it is the spender for the USDC approve, and tokens sent
     straight to it are permanently lost.
   - `:core->evm-system-gas` is the gas Hyperliquid charges (at the next small
     block's base fee, in spot HYPE) to move a non-HYPE token to HyperEVM.
   - `:core-deposit-destination-dex` are CoreDepositWallet `deposit` targets."
  {:chain-id "0x3e7"
   :chain-id-decimal 999
   :chain-name "HyperEVM"
   :native-currency {:name "HYPE" :symbol "HYPE" :decimals 18}
   :rpc-url "https://rpc.hyperliquid.xyz/evm"
   :explorer-url "https://hyperevmscan.io"
   :multicall3-address "0xca11bde05977b3631167028862be2a173976ca11"
   :hype-system-address "0x2222222222222222222222222222222222222222"
   :usdc-token-address "0xb88339cb7199b77e23db6e890353e22632ba630f"
   :usdc-core-deposit-wallet "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24"
   :core->evm-system-gas 200000
   :core-deposit-destination-dex {:perps 0 :spot 4294967295}})

(def testnet
  "HyperEVM testnet (chain 998). Fixtures only; never routed by the app.

   Its explorer root answered 404 to curl on 2026-09-30, so `:explorer-url` is
   unverified."
  {:chain-id "0x3e6"
   :chain-id-decimal 998
   :chain-name "HyperEVM Testnet"
   :native-currency {:name "HYPE" :symbol "HYPE" :decimals 18}
   :rpc-url "https://rpc.hyperliquid-testnet.xyz/evm"
   :explorer-url "https://testnet.purrsec.com"
   :multicall3-address "0xca11bde05977b3631167028862be2a173976ca11"
   :hype-system-address "0x2222222222222222222222222222222222222222"
   :usdc-token-address "0x2b3370ee501b4a559b57d449569354196457d8ab"
   :usdc-core-deposit-wallet "0x0b80659a4076e9e93c7dbe0f10675a16a3e5c206"
   :core->evm-system-gas 200000
   :core-deposit-destination-dex {:perps 0 :spot 4294967295}})

(defn- explorer-url
  [chain segment value]
  (let [base (:explorer-url (or chain mainnet))]
    (when (and (string? base)
               (string? value)
               (seq value))
      (str base "/" segment "/" value))))

(defn explorer-tx-url
  "Explorer page for a HyperEVM transaction hash, or nil for a blank hash."
  ([tx-hash]
   (explorer-tx-url mainnet tx-hash))
  ([chain tx-hash]
   (explorer-url chain "tx" tx-hash)))

(defn explorer-token-url
  "Explorer page for a HyperEVM token contract, or nil for a blank address."
  ([address]
   (explorer-token-url mainnet address))
  ([chain address]
   (explorer-url chain "token" address)))
