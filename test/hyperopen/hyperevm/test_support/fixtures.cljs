(ns hyperopen.hyperevm.test-support.fixtures
  "Real Hyperliquid payloads for the HyperEVM tests. Nothing here is invented:
   every row was copied from a live response and is keywordized the way the
   info client (`js->clj :keywordize-keys true`) stores it, which keeps the
   snake_case `:evm_extra_wei_decimals` key.")

;; Provenance: `curl -s -X POST https://api.hyperliquid.xyz/info
;; -H 'content-type: application/json' -d '{"type":"spotMeta"}'` on
;; 2026-09-30T14:06Z (mainnet: 503 tokens, 172 with an evmContract). Rows are
;; verbatim; the `:universe` half of the payload is omitted.

(def usdc-row
  {:name "USDC" :szDecimals 8 :weiDecimals 8 :index 0
   :tokenId "0x6d1e7cde53ba9467b783cb7c530ce054" :isCanonical true
   :evmContract {:address "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24"
                 :evm_extra_wei_decimals -2}
   :fullName nil :deployerTradingFeeShare "0.0"})

(def purr-row
  {:name "PURR" :szDecimals 0 :weiDecimals 5 :index 1
   :tokenId "0xc1fb593aeffbeb02f85e0308e9956a90" :isCanonical true
   :evmContract {:address "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
                 :evm_extra_wei_decimals 13}
   :fullName nil :deployerTradingFeeShare "0.0"})

;; HOPE's on-chain decimals() returns 0 although weiDecimals + extra is 5
;; (Multicall3 decimals() sweep, 2026-09-30), so it must stay unverified.
(def hope-row
  {:name "HOPE" :szDecimals 0 :weiDecimals 5 :index 122
   :tokenId "0xe6ac9d5a7cf91cdd9fdf34fcbbdd58e9" :isCanonical false
   :evmContract {:address "0x869ac826b78bc1d9501014994196e41025b5224b"
                 :evm_extra_wei_decimals 0}
   :fullName "Purr $HOPE for the $HYPE! No planned utility."
   :deployerTradingFeeShare "0.0"})

;; Mainnet HYPE is linked (native on HyperEVM) but reports evmContract null.
(def hype-row
  {:name "HYPE" :szDecimals 2 :weiDecimals 8 :index 150
   :tokenId "0x0d01dc56dcaaca66ad901c959b4011ec" :isCanonical false
   :evmContract nil
   :fullName "Hyperliquid" :deployerTradingFeeShare "0.0"})

(def ubtc-row
  {:name "UBTC" :szDecimals 5 :weiDecimals 10 :index 197
   :tokenId "0x8f254b963e8468305d409b33aa137c67" :isCanonical false
   :evmContract {:address "0x9fdbda0a5e284c32744d2f17ee5c74b284993463"
                 :evm_extra_wei_decimals -2}
   :fullName "Unit Bitcoin" :deployerTradingFeeShare "1.0"})

;; FUNT sits at ARRAY POSITION 458 of the live `:tokens` vector but has
;; `:index 478`: the first place position and index diverge on mainnet.
(def funt-row
  {:name "FUNT" :szDecimals 1 :weiDecimals 6 :index 478
   :tokenId "0x1aaa916f86510ab37b20fbffeb9c05bb" :isCanonical false
   :evmContract {:address "0xd6f92d754818307d0e2853eada247178f1ae605b"
                 :evm_extra_wei_decimals 2}
   :fullName "Funtoken" :deployerTradingFeeShare "1.0"})

(def funt-live-array-position 458)

(def mainnet-spot-meta
  "A mainnet spotMeta subset in live array order. FUNT's position here is
   5, not its index 478, which is the property the tests need."
  {:tokens [usdc-row purr-row hope-row hype-row ubtc-row funt-row]})

;; Provenance: `curl -s -X POST https://api.hyperliquid-testnet.xyz/info
;; -d '{"type":"spotMeta"}'` on 2026-09-30T14:16Z. Testnet reports HYPE's
;; evmContract as the zero address with extra 10, unlike mainnet's null.
(def testnet-hype-row
  {:name "HYPE" :szDecimals 2 :weiDecimals 8 :index 1105
   :tokenId "0x7317beb7cceed72ef0b346074cc8e7ab" :isCanonical false
   :evmContract {:address "0x0000000000000000000000000000000000000000"
                 :evm_extra_wei_decimals 10}
   :fullName "Hyperliquid" :deployerTradingFeeShare "0.0"})

(def testnet-usdc-row
  {:name "USDC" :szDecimals 8 :weiDecimals 8 :index 0
   :tokenId "0xeb62eee3685fc4c43992febcd9e75443" :isCanonical true
   :evmContract {:address "0x0b80659a4076e9e93c7dbe0f10675a16a3e5c206"
                 :evm_extra_wei_decimals -2}
   :fullName nil :deployerTradingFeeShare "0.0"})

(def testnet-spot-meta
  {:tokens [testnet-usdc-row testnet-hype-row]})

;; --- live HyperEVM RPC probe -------------------------------------------------
;;
;; Provenance: one JSON-RPC batch POSTed to https://rpc.hyperliquid.xyz/evm on
;; 2026-09-30T14:11:05Z: [eth_call Multicall3.aggregate3(probe-calls),
;; eth_gasPrice]. The calldata below is what foundry's `cast calldata` produced
;; for the same three calls, and `cast abi-decode` of the response gave
;; [(true, 948706777700642844709365332 wei), (true, 186077856409651989714),
;; (false, 0x)]: native HYPE held by 0x2222… (the pre-minted supply), PURR
;; somebody sent to 0x2222… (and lost), and USDC's CoreDepositWallet reverting
;; because it has no balanceOf.

(def probe-owner "0x2222222222222222222222222222222222222222")

(def probe-calls
  [{:target "0xca11bde05977b3631167028862be2a173976ca11"
    :allow-failure? false
    :call-data "0x4d2301cc0000000000000000000000002222222222222222222222222222222222222222"}
   {:target "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
    :allow-failure? true
    :call-data "0x70a082310000000000000000000000002222222222222222222222222222222222222222"}
   {:target "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24"
    :allow-failure? true
    :call-data "0x70a082310000000000000000000000002222222222222222222222222222222222222222"}])

(def probe-aggregate3-calldata
  (str "0x82ad56cb"
       "0000000000000000000000000000000000000000000000000000000000000020"
       "0000000000000000000000000000000000000000000000000000000000000003"
       "0000000000000000000000000000000000000000000000000000000000000060"
       "0000000000000000000000000000000000000000000000000000000000000120"
       "00000000000000000000000000000000000000000000000000000000000001e0"
       "000000000000000000000000ca11bde05977b3631167028862be2a173976ca11"
       "0000000000000000000000000000000000000000000000000000000000000000"
       "0000000000000000000000000000000000000000000000000000000000000060"
       "0000000000000000000000000000000000000000000000000000000000000024"
       "4d2301cc00000000000000000000000022222222222222222222222222222222"
       "2222222200000000000000000000000000000000000000000000000000000000"
       "0000000000000000000000009b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
       "0000000000000000000000000000000000000000000000000000000000000001"
       "0000000000000000000000000000000000000000000000000000000000000060"
       "0000000000000000000000000000000000000000000000000000000000000024"
       "70a0823100000000000000000000000022222222222222222222222222222222"
       "2222222200000000000000000000000000000000000000000000000000000000"
       "0000000000000000000000006b9e773128f453f5c2c60935ee2de2cbc5390a24"
       "0000000000000000000000000000000000000000000000000000000000000001"
       "0000000000000000000000000000000000000000000000000000000000000060"
       "0000000000000000000000000000000000000000000000000000000000000024"
       "70a0823100000000000000000000000022222222222222222222222222222222"
       "2222222200000000000000000000000000000000000000000000000000000000"))

(def probe-aggregate3-result
  (str "0x"
       "0000000000000000000000000000000000000000000000000000000000000020"
       "0000000000000000000000000000000000000000000000000000000000000003"
       "0000000000000000000000000000000000000000000000000000000000000060"
       "00000000000000000000000000000000000000000000000000000000000000e0"
       "0000000000000000000000000000000000000000000000000000000000000160"
       "0000000000000000000000000000000000000000000000000000000000000001"
       "0000000000000000000000000000000000000000000000000000000000000040"
       "0000000000000000000000000000000000000000000000000000000000000020"
       "00000000000000000000000000000000000000000310c07978c3d1e3b9162654"
       "0000000000000000000000000000000000000000000000000000000000000001"
       "0000000000000000000000000000000000000000000000000000000000000040"
       "0000000000000000000000000000000000000000000000000000000000000020"
       "00000000000000000000000000000000000000000000000a1659588597734cd2"
       "0000000000000000000000000000000000000000000000000000000000000000"
       "0000000000000000000000000000000000000000000000000000000000000040"
       "0000000000000000000000000000000000000000000000000000000000000000"))

(def probe-gas-price-hex "0x666f5c3")

(def probe-native-wei "948706777700642844709365332")

(def probe-purr-units "186077856409651989714")

(def probe-gas-price-wei "107410883")
