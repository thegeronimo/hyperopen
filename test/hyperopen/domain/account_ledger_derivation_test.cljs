(ns hyperopen.domain.account-ledger-derivation-test
  "Payload-driven coverage of the Account Activity row derivation.

   Every fixture here uses the field names Hyperliquid's `userNonFundingLedger
   Updates` actually returns, because the defects this suite pins were all
   defects of reading the payload: the sign came from a type table instead of
   from `user`/`destination`, `toPerp` was never read, `usdcValue` was
   discarded, the fee was labelled USDC regardless of asset, and rows whose
   amount lived in an unprobed key were dropped outright."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.account-ledger :as account-ledger]))

(def ^:private viewer "0xAAAA000000000000000000000000000000000001")
(def ^:private counterparty "0xBBBB000000000000000000000000000000000002")

(defn- row-for
  ([delta] (row-for delta viewer))
  ([delta viewer-address]
   (account-ledger/normalize-ledger-row
    {:time 1770000000000 :hash "0xhash" :delta delta}
    viewer-address)))

(deftest rows-carry-the-machine-readable-type-not-only-a-label-test
  ;; Three distinct types share the label "Send" in the old table, so routing
  ;; rows into sub-tabs is impossible without the type token itself.
  (is (= "internal-transfer" (:type-key (row-for {:type "internalTransfer" :usdc "5"}))))
  (is (= "spot-transfer" (:type-key (row-for {:type "spotTransfer" :token "HYPE" :amount "1"}))))
  (is (= "sub-account-transfer"
         (:type-key (row-for {:type "subAccountTransfer" :usdc "5"})))))

(deftest transfer-direction-comes-from-the-payload-not-a-type-table-test
  (testing "an INCOMING internal transfer is a credit"
    (let [row (row-for {:type "internalTransfer"
                        :usdc "250.5"
                        :user counterparty
                        :destination viewer})]
      (is (= 250.5 (:signed-amount row)))
      (is (= "+250.5 USDC" (:amount-text row)))))
  (testing "an OUTGOING internal transfer is a debit"
    (let [row (row-for {:type "internalTransfer"
                        :usdc "250.5"
                        :user viewer
                        :destination counterparty})]
      (is (= -250.5 (:signed-amount row)))
      (is (= "-250.5 USDC" (:amount-text row)))))
  (testing "the same holds for spot and sub-account transfers"
    (is (pos? (:signed-amount (row-for {:type "spotTransfer"
                                        :token "HYPE"
                                        :amount "1.5"
                                        :user counterparty
                                        :destination viewer}))))
    (is (neg? (:signed-amount (row-for {:type "spotTransfer"
                                        :token "HYPE"
                                        :amount "1.5"
                                        :user viewer
                                        :destination counterparty}))))
    (is (pos? (:signed-amount (row-for {:type "subAccountTransfer"
                                        :usdc "10"
                                        :user counterparty
                                        :destination viewer})))))
  (testing "address comparison is case-insensitive"
    (is (pos? (:signed-amount (row-for {:type "internalTransfer"
                                        :usdc "1"
                                        :user counterparty
                                        :destination (.toLowerCase viewer)})))))
  (testing "with no viewing address a transfer reports as outgoing"
    (is (neg? (:signed-amount (row-for {:type "internalTransfer"
                                        :usdc "1"
                                        :user counterparty
                                        :destination viewer}
                                       nil))))))

(deftest account-class-transfer-names-its-two-ends-from-to-perp-test
  (let [to-perps (row-for {:type "accountClassTransfer" :usdc "100" :toPerp true})
        to-spot (row-for {:type "accountClassTransfer" :usdc "-100" :toPerp false})]
    (is (= ["Spot" "Perps"] [(:from-label to-perps) (:to-label to-perps)]))
    (is (= ["Perps" "Spot"] [(:from-label to-spot) (:to-label to-spot)]))
    (testing "the API already signs this delta, so it passes through"
      (is (= 100 (:signed-amount to-perps)))
      (is (= -100 (:signed-amount to-spot))))))

(deftest deposits-and-withdrawals-keep-their-bridge-endpoints-test
  (let [deposit (row-for {:type "deposit" :usdc "100"})
        withdraw (row-for {:type "withdraw" :usdc "25.5" :fee "1.0"})]
    (is (= ["Arbitrum" "Trading Account"] [(:from-label deposit) (:to-label deposit)]))
    (is (= ["Trading Account" "Arbitrum"] [(:from-label withdraw) (:to-label withdraw)]))
    (is (= 100 (:signed-amount deposit)))
    (is (= -25.5 (:signed-amount withdraw)))))

(deftest rows-without-a-probed-amount-render-instead-of-being-dropped-test
  (testing "vaultWithdraw carries its value in netWithdrawnUsd"
    (let [row (row-for {:type "vaultWithdraw"
                        :vault counterparty
                        :netWithdrawnUsd "480.25"
                        :requestedUsd "500"
                        :commission "1.5"})]
      (is (some? row))
      (is (= "vault-withdraw" (:type-key row)))
      (is (= 480.25 (:signed-amount row)))
      (is (= counterparty (:destination-address row)))))
  (testing "liquidation has no scalar amount at all and still renders"
    (let [row (row-for {:type "liquidation"
                        :accountValue "1000"
                        :leverageType "cross"})]
      (is (some? row))
      (is (= "liquidation" (:type-key row)))
      (is (= "Liquidation" (:action-label row)))
      (is (nil? (:signed-amount row)))
      (is (= "--" (:amount-text row))))))

(deftest usd-value-reads-usdc-value-and-stays-nil-when-unknown-test
  (testing "a spot transfer's own usdcValue is used"
    (let [row (row-for {:type "spotTransfer"
                        :token "HYPE"
                        :amount "1000.1"
                        :usdcValue "79482.95"
                        :user viewer
                        :destination counterparty})]
      (is (= 79482.95 (:usd-value row)))
      (is (= "$79,482.95" (:usd-value-text row)))))
  (testing "USDC-denominated rows are their own USD value"
    (is (= 100 (:usd-value (row-for {:type "deposit" :usdc "100"})))))
  (testing "types the reference leaves blank render -- rather than $0.00"
    (doseq [delta [{:type "cStakingTransfer" :amount "5" :isDeposit true}
                   {:type "deployGasAuction" :amount "5"}]]
      (let [row (row-for delta)]
        (is (nil? (:usd-value row)))
        (is (= "--" (:usd-value-text row))))))
  (testing "a non-USDC token with no usdcValue yields nil, never a fake zero"
    (is (nil? (:usd-value (row-for {:type "spotGenesis" :token "HYPE" :amount "2.67"}))))))

(deftest fee-is-reported-in-the-asset-it-was-charged-in-test
  (testing "a send fee uses feeToken"
    (let [row (row-for {:type "send"
                        :token "HYPE"
                        :amount "1"
                        :fee "0.25"
                        :feeToken "HYPE"
                        :user counterparty
                        :destination viewer})]
      (is (= "HYPE" (:fee-asset row)))
      (is (= "0.25 HYPE" (:fee-text row)))))
  (testing "withdraw and spot-transfer fees are core collateral"
    (is (= "1 USDC" (:fee-text (row-for {:type "withdraw" :usdc "25" :fee "1.0"})))))
  (testing "a zero or absent fee renders --"
    (is (= "--" (:fee-text (row-for {:type "deposit" :usdc "100"}))))
    (is (= "--" (:fee-text (row-for {:type "deposit" :usdc "100" :fee "0"}))))))

(deftest staking-and-vault-signs-follow-the-direction-of-the-move-test
  (is (neg? (:signed-amount (row-for {:type "cStakingTransfer" :amount "5" :isDeposit true}))))
  (is (pos? (:signed-amount (row-for {:type "cStakingTransfer" :amount "5" :isDeposit false}))))
  (is (neg? (:signed-amount (row-for {:type "vaultDeposit" :usdc "10"}))))
  (is (pos? (:signed-amount (row-for {:type "vaultDistribution" :usdc "10"}))))
  (testing "staking rows name the staking venue"
    (let [row (row-for {:type "cStakingTransfer" :amount "5" :isDeposit true})]
      (is (= ["Spot" "Staking"] [(:from-label row) (:to-label row)])))))

(deftest retyping-rules-move-rows-to-the-sub-tab-their-meaning-implies-test
  (testing "a self-send becomes an account class transfer"
    (let [row (row-for {:type "send"
                        :token "USDC"
                        :amount "10"
                        :user viewer
                        :destination viewer})]
      (is (= "account-class-transfer" (:type-key row)))))
  (testing "a spot transfer from a HyperEVM system address is a bridge move"
    ;; System addresses are minted as 0x20 + 34 zeros + a four-hex token index.
    ;; An ordinary wallet address must NOT match -- see the sibling case below.
    (let [row (row-for {:type "spotTransfer"
                        :token "HYPE"
                        :amount "3"
                        :user "0x2000000000000000000000000000000000000001"
                        :destination viewer})]
      (is (= "account-class-transfer" (:type-key row)))
      (is (= ["HyperEVM" "Spot"] [(:from-label row) (:to-label row)]))))
  (testing "an ordinary spot transfer is not retyped"
    (is (= "spot-transfer"
           (:type-key (row-for {:type "spotTransfer"
                                :token "HYPE"
                                :amount "3"
                                :user counterparty
                                :destination viewer}))))))

(deftest unknown-future-types-stay-legible-rather-than-blank-test
  (let [row (row-for {:type "someBrandNewDelta" :usdc "7"})]
    (is (some? row))
    (is (= "some-brand-new-delta" (:type-key row)))
    (is (= "Some Brand New Delta" (:action-label row)))))

(deftest malformed-rows-are-still-rejected-test
  (is (nil? (account-ledger/normalize-ledger-row "not-a-row" viewer))
      "a non-map is not a row")
  (is (nil? (account-ledger/normalize-ledger-row {:time nil :delta {:type "deposit" :usdc "1"}}
                                                 viewer))
      "a row with no timestamp is not orderable")
  (is (nil? (account-ledger/normalize-ledger-row {:time 1 :delta {:usdc "1"}} viewer))
      "a row with no type cannot be routed"))

(deftest merge-passes-the-viewer-address-through-to-both-sources-test
  (let [rest-row {:time 1770000000000
                  :hash "0xincoming"
                  :delta {:type "internalTransfer"
                          :usdc "50"
                          :user counterparty
                          :destination viewer}}
        ws-row {:time 1770000100000
                :hash "0xoutgoing"
                :delta {:type "internalTransfer"
                        :usdc "50"
                        :user viewer
                        :destination counterparty}}
        rows (account-ledger/merge-ledger-rows [rest-row] [ws-row] viewer)]
    (is (= 2 (count rows)))
    (is (= [-50 50] (mapv :signed-amount rows))
        "newest first: the outgoing websocket row, then the incoming REST row")))
