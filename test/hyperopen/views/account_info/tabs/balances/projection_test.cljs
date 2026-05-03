(ns hyperopen.views.account-info.tabs.balances.projection-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]))

(deftest build-balance-rows-attaches-contract-id-for-non-usdc-spot-and-leaves-usdc-rows-empty-test
  (let [rows (projections/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "12.5"
                                                    :totalMarginUsed "2.5"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8
                                :tokenId "0x11111111111111111111111111111111"}
                               {:index 1
                                :name "HYPE"
                                :weiDecimals 5
                                :tokenId "0x22222222222222222222222222222222"}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "HYPE"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (nil? (:contract-id (get by-coin "USDC (Perps)"))))
    (is (nil? (:contract-id (get by-coin "USDC (Spot)"))))
    (is (= 5.0
           (shared/parse-num (:usdc-value (get by-coin "USDC (Spot)")))))
    (is (= "0x22222222222222222222222222222222"
           (:contract-id (get by-coin "HYPE"))))))

(deftest build-balance-rows-attaches-contract-id-when-token-reference-is-symbol-test
  (let [rows (projections/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "1.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8}
                               {:name "MEOW"
                                :weiDecimals 5
                                :contractAddress "0x3333333333333333333333333333333333333333"}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "MEOW"
                                                 :token "MEOW"
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (= "0x3333333333333333333333333333333333333333"
           (:contract-id (get by-coin "MEOW"))))))

(deftest build-balance-rows-attaches-contract-id-from-balance-payload-keys-test
  (let [rows (projections/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "1.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8}
                               {:index 1
                                :name "HYPE"
                                :weiDecimals 5}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "HYPE"
                                                 :token 1
                                                 :tokenAddress "0x4444444444444444444444444444444444444444"
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (= "0x4444444444444444444444444444444444444444"
           (:contract-id (get by-coin "HYPE"))))))

(deftest build-balance-rows-attaches-contract-id-from-nested-token-metadata-test
  (let [rows (projections/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "1.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8}
                               {:index 1
                                :name "PUFF"
                                :weiDecimals 5
                                :tokenInfo {:evmContract {:address "0X5555555555555555555555555555555555555555"}}}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "PUFF"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (= "0X5555555555555555555555555555555555555555"
           (:contract-id (get by-coin "PUFF"))))))

(deftest build-balance-rows-extracts-embedded-hex-contract-id-test
  (let [rows (projections/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "1.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8}
                               {:index 1
                                :name "BOLT"
                                :weiDecimals 5
                                :contractAddress "eip155:42161/erc20:0x6666666666666666666666666666666666666666"}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "BOLT"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (= "0x6666666666666666666666666666666666666666"
           (:contract-id (get by-coin "BOLT"))))))

(deftest build-balance-rows-ignores-generic-address-keys-for-contract-id-test
  (let [rows (projections/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "1.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8}
                               {:index 1
                                :name "HYPE"
                                :weiDecimals 5
                                :address "0x7777777777777777777777777777777777777777"}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "HYPE"
                                                 :token 1
                                                 :address "0x8888888888888888888888888888888888888888"
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (nil? (:contract-id (get by-coin "HYPE"))))))

(deftest build-balance-rows-unified-mode-prefers-spot-usdc-without-perps-double-count-test
  (let [rows (projections/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "3.03"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs [{:markPx "1"}]}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 6
                                :tokenId "0x11111111111111111111111111111111"}
                               {:index 1
                                :name "MEOW"
                                :weiDecimals 6
                                :tokenId "0x22222222222222222222222222222222"}]
                      :universe [{:tokens [0 0]
                                  :index 0}]}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "201.42"
                                                 :entryNtl "0"}
                                                {:coin "MEOW"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "1.0"
                                                 :entryNtl "0"}]}}
              {:mode :unified
               :abstraction-raw "unifiedAccount"})
        by-coin (into {} (map (juxt :coin identity)) rows)
        usdc-row (get by-coin "USDC")]
    (is (= 2 (count rows)))
    (is (some? usdc-row))
    (is (= 201.42 (shared/parse-num (:total-balance usdc-row))))
    (is (= 201.42 (shared/parse-num (:usdc-value usdc-row))))
    (is (true? (:transfer-disabled? usdc-row)))
    (is (nil? (get by-coin "USDC (Spot)")))
    (is (nil? (get by-coin "USDC (Perps)")))))

(deftest build-balance-rows-unified-mode-falls-back-to-perps-usdc-when-spot-usdc-missing-test
  (let [rows (projections/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "3.03"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 6}
                               {:index 1
                                :name "MEOW"
                                :weiDecimals 6}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "MEOW"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "1.0"
                                                 :entryNtl "0"}]}}
              {:mode :unified
               :abstraction-raw "unifiedAccount"})
        by-coin (into {} (map (juxt :coin identity)) rows)
        usdc-row (get by-coin "USDC")]
    (is (= 2 (count rows)))
    (is (some? usdc-row))
    (is (= 3.03 (shared/parse-num (:total-balance usdc-row))))
    (is (= 3.03 (shared/parse-num (:usdc-value usdc-row))))
    (is (true? (:transfer-disabled? usdc-row)))))

(deftest build-balance-rows-usdc-value-falls-back-to-coin-identity-when-meta-missing-test
  (let [rows (projections/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "0.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens []
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "3.03000000"
                                                 :total "204.41936500"
                                                 :entryNtl "0"}]}}
              {:mode :unified
               :abstraction-raw "unifiedAccount"})
        usdc-row (first rows)]
    (is (= 1 (count rows)))
    (is (= "USDC" (:coin usdc-row)))
    (is (= 204.419365 (shared/parse-num (:usdc-value usdc-row))))
    (is (= 201.389365 (shared/parse-num (:available-balance usdc-row))))))

(deftest build-balance-rows-usdc-identity-invariant-holds-without-pricing-context-test
  (let [rows (projections/build-balance-rows
              {:spotAssetCtxs nil}
              {:meta nil
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "204.41"
                                                 :entryNtl "0"}
                                                {:coin "USDC.e"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "0.04"
                                                 :entryNtl "0"}]}})
        usdc-like-rows (filter #(str/starts-with? (:coin %) "USDC") rows)]
    (is (= 2 (count usdc-like-rows)))
    (doseq [row usdc-like-rows]
      (is (= (shared/parse-num (:total-balance row))
             (shared/parse-num (:usdc-value row)))))))

(deftest build-balance-rows-excludes-outcome-side-token-balances-test
  (let [rows (projections/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "0"
                                                    :totalMarginUsed "0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [] :universe []}
               :clearinghouse-state {:balances [{:coin "+0"
                                                 :token 100000000
                                                 :hold "0"
                                                 :total "19"
                                                 :entryNtl "11.0271"}
                                                {:coin "#1"
                                                 :hold "0"
                                                 :total "3"
                                                 :entryNtl "1.8"}
                                                {:coin "HYPE"
                                                 :hold "0"
                                                 :total "2"
                                                 :entryNtl "0"}]}})
        coins (set (map :coin rows))]
    (is (contains? coins "HYPE"))
    (is (not (contains? coins "+0")))
    (is (not (contains? coins "#1")))))

(deftest build-balance-rows-filters-zero-balance-rows-by-default-test
  (let [rows (projections/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "0.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs [{:markPx "1"}]}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 6}
                               {:index 1
                                :name "USDE"
                                :weiDecimals 6}
                               {:index 2
                                :name "HYPE"
                                :weiDecimals 6}]
                      :universe [{:tokens [0 0]
                                  :index 0}]}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "10.0"
                                                 :entryNtl "0"}
                                                {:coin "USDE"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "0.0"
                                                 :entryNtl "0"}
                                                {:coin "HYPE"
                                                 :token 2
                                                 :hold "0.0"
                                                 :total "1.0"
                                                 :entryNtl "0"}]}})
        coins (set (map :coin rows))]
    (is (contains? coins "USDC (Spot)"))
    (is (contains? coins "HYPE"))
    (is (not (contains? coins "USDE")))
    (is (not (contains? coins "USDC (Perps)")))))
