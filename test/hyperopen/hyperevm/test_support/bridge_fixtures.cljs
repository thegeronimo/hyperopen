(ns hyperopen.hyperevm.test-support.bridge-fixtures
  "Real Hyperliquid payloads for the bridge-health and HyperCore-read tests.
   Nothing here is invented; every value was copied from a live response."
  (:require [hyperopen.hyperevm.test-support.fixtures :as fixtures]))

(def six-row
  {:name "SIX" :szDecimals 2 :weiDecimals 8 :index 6
   :tokenId "0x50a9391b4a40caffbe8b16303b95a0c1" :isCanonical false
   :evmContract {:address "0x41de34fc45a770ebcd50200be93f080b4b05151f"
                 :evm_extra_wei_decimals 10}
   :fullName nil :deployerTradingFeeShare "0.0"})

(def joff-row
  {:name "JOFF" :szDecimals 2 :weiDecimals 8 :index 296
   :tokenId "0x8ecbaadbaf3f59a7e9f97af2688d479d" :isCanonical false
   :evmContract {:address "0x5fe5c8627ac1aedc0422c2de6d789d924d81b5c2"
                 :evm_extra_wei_decimals 0}
   :fullName "bildin a purrty gud el 1" :deployerTradingFeeShare "1.0"})

(def bridge-spot-meta
  "The mainnet fixture rows plus SIX and JOFF, in live array order."
  {:tokens [fixtures/usdc-row fixtures/purr-row six-row fixtures/hope-row
            fixtures/hype-row fixtures/ubtc-row joff-row fixtures/funt-row]})

(def purr-system-core-state
  {:balances [{:coin "PURR" :token 1 :total "91403084.7764399946" :hold "0.0"
               :entryNtl "10003597.4974620603"}
              {:coin "HYPE" :token 150 :total "1.45" :hold "0.0" :entryNtl "22.20677"}]})

(def six-system-core-state
  {:balances [{:coin "SIX" :token 6 :total "293.0535384" :hold "0.0"
               :entryNtl "201.31806213"}]})

(def hype-system-core-state
  {:balances [{:coin "HYPE" :token 150 :total "51277474.282994248" :hold "0.0"
               :entryNtl "15765821169.0523014069"}]})

(def missing-user-role {:role "missing"})
(def active-user-role {:role "user"})

(def live-system-units
  {1 "508596915622676377079152451"
   6 "0"
   122 "167579739"
   197 "2099941788654544"
   478 "0"})

(def live-decimals
  {1 18
   6 18
   122 0
   197 8
   478 8})
