(ns hyperopen.funding.domain.spot-tokens-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.funding.domain.spot-tokens :as spot-tokens]))

(def ^:private production-spot-meta
  "Production-shaped `spotMeta` `:tokens` entries. Hyperliquid returns
   `:name`, `:index`, `:tokenId`, `:szDecimals`, and `:weiDecimals`; it never
   returns a prejoined `NAME:0x...` string, which is exactly why the resolver
   under test has to build one."
  {:spot {:meta {:tokens [{:name "USDC"
                           :index 0
                           :tokenId "0x6d1e7cde53ba9467b783cb7c530ce054"
                           :szDecimals 8
                           :weiDecimals 8}
                          {:name "HYPE"
                           :index 150
                           :tokenId "0x0d01dc56dcaaca66ad901c959b4011ec"
                           :szDecimals 2
                           :weiDecimals 8}
                          {:name "PURR"
                           :index 1
                           :tokenId "0xc4bf3f870c0e9465323c0b6ed28096c2"
                           :szDecimals 0
                           :weiDecimals 5}]}}})

(deftest resolves-numeric-balance-token-index-to-a-wire-token-id-test
  (testing "a spotClearinghouseState balance row carries a numeric token index"
    (is (= "HYPE:0x0d01dc56dcaaca66ad901c959b4011ec"
           (spot-tokens/wire-token-id production-spot-meta
                                      {:coin "HYPE" :token 150})))
    (is (= "PURR:0xc4bf3f870c0e9465323c0b6ed28096c2"
           (spot-tokens/wire-token-id production-spot-meta
                                      {:coin "PURR" :token 1}))))
  (testing "the index may arrive as a string or an @-prefixed pair reference"
    (is (= "HYPE:0x0d01dc56dcaaca66ad901c959b4011ec"
           (spot-tokens/wire-token-id production-spot-meta
                                      {:coin "HYPE" :token "150"})))
    (is (= "HYPE:0x0d01dc56dcaaca66ad901c959b4011ec"
           (spot-tokens/wire-token-id production-spot-meta
                                      {:coin "HYPE" :token "@150"})))))

(deftest resolves-usdc-through-metadata-and-falls-back-to-the-mainnet-id-test
  (is (= "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
         (spot-tokens/wire-token-id production-spot-meta
                                    {:coin "USDC" :token 0})))
  (testing "USDC is the one token allowed to fall back without metadata"
    (is (= "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
           (spot-tokens/usdc-wire-token-id {})))
    (is (= "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
           (spot-tokens/wire-token-id {} {:coin "USDC" :token 0})))))

(deftest resolves-by-coin-name-when-the-index-is-absent-test
  (is (= "HYPE:0x0d01dc56dcaaca66ad901c959b4011ec"
         (spot-tokens/wire-token-id production-spot-meta {:coin "HYPE"})))
  (is (= "HYPE:0x0d01dc56dcaaca66ad901c959b4011ec"
         (spot-tokens/wire-token-id production-spot-meta {:coin "hype"}))))

(deftest passes-through-an-already-resolved-wire-token-id-test
  (is (= "HYPE:0x0d01dc56dcaaca66ad901c959b4011ec"
         (spot-tokens/wire-token-id production-spot-meta
                                    {:coin "HYPE"
                                     :token "HYPE:0x0d01dc56dcaaca66ad901c959b4011ec"})))
  (testing "pass-through does not require metadata"
    (is (= "USDH:0xabc"
           (spot-tokens/wire-token-id {} {:coin "USDH" :token "USDH:0xabc"})))))

(deftest returns-nil-for-an-unresolvable-non-usdc-token-test
  (testing "no metadata means no honest answer, so callers can disable the row"
    (is (nil? (spot-tokens/wire-token-id {} {:coin "HYPE" :token 150})))
    (is (nil? (spot-tokens/wire-token-id {:spot {:meta {:tokens []}}}
                                         {:coin "HYPE" :token 150}))))
  (testing "a token missing either half of the identifier never resolves"
    (is (nil? (spot-tokens/wire-token-id
               {:spot {:meta {:tokens [{:name "HYPE" :index 150}]}}}
               {:coin "HYPE" :token 150})))
    (is (nil? (spot-tokens/wire-token-id
               {:spot {:meta {:tokens [{:index 150 :tokenId "0xdead"}]}}}
               {:coin "HYPE" :token 150})))))

(deftest token-symbol-ignores-bare-indexes-test
  (is (= "HYPE" (spot-tokens/token-symbol "HYPE:0x0d01")))
  (is (= "HYPE" (spot-tokens/token-symbol "HYPE")))
  (testing "a bare index names nothing, so it is not a symbol"
    (is (nil? (spot-tokens/token-symbol "150")))
    (is (nil? (spot-tokens/token-symbol "@150")))
    (is (nil? (spot-tokens/token-symbol "  ")))
    (is (nil? (spot-tokens/token-symbol nil)))))

(deftest wire-token-id-predicate-test
  (is (true? (spot-tokens/wire-token-id? "HYPE:0x0d01")))
  (is (false? (spot-tokens/wire-token-id? "HYPE")))
  (is (false? (spot-tokens/wire-token-id? "150")))
  (is (false? (spot-tokens/wire-token-id? nil))))

(deftest resolver-is-reusable-across-a-balance-list-test
  (let [resolver (spot-tokens/resolver production-spot-meta)]
    (is (= ["USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
            "HYPE:0x0d01dc56dcaaca66ad901c959b4011ec"
            nil]
           (mapv #(spot-tokens/resolve-with resolver %)
                 [{:coin "USDC" :token 0}
                  {:coin "HYPE" :token 150}
                  {:coin "WOWZA" :token 9999}])))))
