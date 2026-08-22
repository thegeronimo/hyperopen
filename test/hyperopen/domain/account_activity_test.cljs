(ns hyperopen.domain.account-activity-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.account-activity :as account-activity]))

(deftest sub-tab-catalog-matches-reference-order-and-labels-test
  (is (= [:all
          :account-transfers
          :deposits-withdrawals
          :spot-transfers
          :internal-transfers
          :earn
          :vaults
          :staking
          :auctions]
         (mapv :id account-activity/sub-tabs)))
  (is (= ["All"
          "Account Transfers"
          "Deposits and Withdrawals"
          "Spot Transfers"
          "Internal Transfers"
          "Earn"
          "Vaults"
          "Staking"
          "Auctions"]
         (mapv :label account-activity/sub-tabs)))
  (is (= :all account-activity/default-sub-tab)))

(deftest normalize-sub-tab-accepts-keywords-strings-and-falls-back-test
  (is (= :vaults (account-activity/normalize-sub-tab :vaults)))
  (is (= :vaults (account-activity/normalize-sub-tab "vaults")))
  (is (= :deposits-withdrawals (account-activity/normalize-sub-tab "Deposits and Withdrawals")))
  (is (= :account-transfers (account-activity/normalize-sub-tab "account transfers")))
  (is (= :all (account-activity/normalize-sub-tab "not-a-sub-tab")))
  (is (= :all (account-activity/normalize-sub-tab nil))))

(deftest ledger-type-sub-tabs-port-the-reference-predicate-table-test
  (testing "Account Transfers carries exactly the reference's three types"
    (is (= #{:account-transfers}
           (account-activity/sub-tabs-for-type "send")))
    (is (= #{:account-transfers}
           (account-activity/sub-tabs-for-type "account-class-transfer")))
    (is (= #{:account-transfers}
           (account-activity/sub-tabs-for-type "activate-dex-abstraction"))))
  (testing "single-type sub-tabs"
    (is (= #{:deposits-withdrawals} (account-activity/sub-tabs-for-type "deposit")))
    (is (= #{:deposits-withdrawals} (account-activity/sub-tabs-for-type "withdraw")))
    (is (= #{:spot-transfers} (account-activity/sub-tabs-for-type "spot-transfer")))
    (is (= #{:auctions} (account-activity/sub-tabs-for-type "deploy-gas-auction"))))
  (testing "every vault type lands on Vaults"
    (is (= [#{:vaults} #{:vaults} #{:vaults} #{:vaults} #{:vaults}]
           (mapv account-activity/sub-tabs-for-type
                 ["vault-create"
                  "vault-deposit"
                  "vault-distribution"
                  "vault-withdraw"
                  "vault-leader-commission"]))))
  (testing "staking covers the reference type and the real Hyperliquid staking deltas"
    (is (= [#{:staking} #{:staking} #{:staking} #{:staking} #{:staking}]
           (mapv account-activity/sub-tabs-for-type
                 ["c-staking-transfer"
                  "delegate"
                  "undelegate"
                  "c-deposit"
                  "c-withdraw"])))))

(deftest no-ledger-type-is-dropped-the-way-the-reference-drops-them-test
  (testing "sub-account transfers are recovered onto Internal Transfers"
    ;; The reference gives subAccountTransfer a label but no displayedInTabs, so
    ;; its ingest guard drops the row before the store -- it appears on no
    ;; sub-tab, not even All. It was 945 of 5,146 rows in the sampled ledgers.
    (is (= #{:internal-transfers}
           (account-activity/sub-tabs-for-type "sub-account-transfer"))))
  (testing "Earn is wired rather than permanently empty"
    (is (= #{:earn} (account-activity/sub-tabs-for-type "borrow-lend")))
    (is (= #{:earn} (account-activity/sub-tabs-for-type "rewards-claim"))))
  (testing "types with no natural sub-tab still appear under All"
    (doseq [type-key ["liquidation" "spot-genesis" "welcome-bonus"]]
      (is (= #{} (account-activity/sub-tabs-for-type type-key))
          (str type-key " should claim no sub-tab of its own"))
      (is (account-activity/row-visible-in-sub-tab? {:type-key type-key} :all)
          (str type-key " must still be visible under All"))))
  (testing "an unknown future type is visible under All rather than discarded"
    (is (account-activity/row-visible-in-sub-tab? {:type-key "some-new-type-2027"} :all))
    (is (not (account-activity/row-visible-in-sub-tab? {:type-key "some-new-type-2027"}
                                                       :vaults)))))

(deftest row-visible-in-sub-tab-routes-rows-by-type-test
  (is (account-activity/row-visible-in-sub-tab? {:type-key "spot-transfer"} :spot-transfers))
  (is (not (account-activity/row-visible-in-sub-tab? {:type-key "spot-transfer"}
                                                     :internal-transfers)))
  (is (account-activity/row-visible-in-sub-tab? {:type-key "vault-withdraw"} :vaults))
  (is (account-activity/row-visible-in-sub-tab? {:type-key "vault-withdraw"} :all)))

(deftest rows-for-sub-tab-filters-and-preserves-order-test
  (let [rows [{:id "a" :type-key "deposit"}
              {:id "b" :type-key "spot-transfer"}
              {:id "c" :type-key "withdraw"}
              {:id "d" :type-key "sub-account-transfer"}]]
    (is (= ["a" "b" "c" "d"] (mapv :id (account-activity/rows-for-sub-tab rows :all))))
    (is (= ["a" "c"] (mapv :id (account-activity/rows-for-sub-tab rows :deposits-withdrawals))))
    (is (= ["b"] (mapv :id (account-activity/rows-for-sub-tab rows :spot-transfers))))
    (is (= ["d"] (mapv :id (account-activity/rows-for-sub-tab rows :internal-transfers))))
    (is (= [] (mapv :id (account-activity/rows-for-sub-tab rows :staking))))))

(deftest sub-tab-counts-report-rows-per-sub-tab-test
  (let [rows [{:id "a" :type-key "deposit"}
              {:id "b" :type-key "spot-transfer"}
              {:id "c" :type-key "withdraw"}]
        counts (account-activity/sub-tab-counts rows)]
    (is (= 3 (:all counts)))
    (is (= 2 (:deposits-withdrawals counts)))
    (is (= 1 (:spot-transfers counts)))
    (is (= 0 (:vaults counts)))))

(deftest visible-columns-hide-the-same-columns-the-reference-hides-test
  (testing "the full ten-column set, in reference order"
    (is (= [:time :status :asset :action :from :to :destination :account-change :usd-value :fee]
           account-activity/columns))
    (is (= account-activity/columns (account-activity/visible-columns :all))))
  (testing "Spot Transfers hides Action and Destination"
    (is (= [:time :status :asset :from :to :account-change :usd-value :fee]
           (account-activity/visible-columns :spot-transfers))))
  (testing "every other sub-tab keeps all ten columns"
    ;; The reference also hides Action on Account Transfers. We keep it, because
    ;; that sub-tab carries three distinct types whose only distinguishing
    ;; column is Action.
    (doseq [sub-tab [:all :account-transfers :deposits-withdrawals :internal-transfers
                     :earn :vaults :staking :auctions]]
      (is (= 10 (count (account-activity/visible-columns sub-tab)))
          (str sub-tab " should render all ten columns")))))

(deftest column-labels-match-the-reference-headers-test
  (is (= ["Time" "Status" "Asset" "Action" "From" "To" "Destination"
          "Account Change" "USD Value" "Fee"]
         (mapv account-activity/column-label account-activity/columns))))
