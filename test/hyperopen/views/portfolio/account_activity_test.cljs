(ns hyperopen.views.portfolio.account-activity-test
  "Rendering coverage for the portfolio Account Activity tab.

   Split out of `hyperopen.views.portfolio-view-test`, which sits three lines
   under the 500-line namespace-size limit and cannot absorb these."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.views.chart.d3.hover-state :as chart-hover-state]
            [hyperopen.views.portfolio.test-support :refer [class-values
                                                            collect-strings
                                                            find-first-node
                                                            find-nodes
                                                            sample-state]]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(use-fixtures :each
  (fn [f]
    (chart-hover-state/clear-hover-state!)
    (f)
    (chart-hover-state/clear-hover-state!)))

(def ^:private viewer "0x1111111111111111111111111111111111111111")
(def ^:private counterparty "0x9999999999999999999999999999999999999999")

(def ^:private ledger-rows
  [{:time 1770000000000
    :hash "0xdeposit"
    :delta {:type "deposit" :usdc "100.0"}}
   {:time 1770000100000
    :hash "0xwithdraw"
    :delta {:type "withdraw" :usdc "25.5" :fee "1.0"}}
   {:time 1770000200000
    :hash "0xgenesis"
    :delta {:type "spotGenesis" :token "HYPE" :amount "2.67"}}
   {:time 1770000300000
    :hash "0xspot"
    :delta {:type "spotTransfer"
            :token "HYPE"
            :amount "1.0"
            :usdcValue "42.5"
            :user counterparty
            :destination viewer}}
   {:time 1770000400000
    :hash "0xclass"
    :delta {:type "accountClassTransfer" :usdc "500.0" :toPerp true}}
   {:time 1770000500000
    :hash "0xsub"
    :delta {:type "subAccountTransfer"
            :usdc "12.0"
            :user viewer
            :destination counterparty}}
   {:time 1770000600000
    :hash "0xvaultw"
    :delta {:type "vaultWithdraw" :vault counterparty :netWithdrawnUsd "480.25"}}])

(defn- activity-state
  ([] (activity-state {}))
  ([portfolio-overrides]
   (-> sample-state
       (assoc-in [:portfolio-ui :account-info-tab] :deposits-withdrawals)
       (assoc-in [:wallet :address] viewer)
       (update :portfolio merge
               {:ledger-updates ledger-rows
                :ledger-loading? false
                :ledger-error nil}
               portfolio-overrides))))

(defn- rendered-text
  [state]
  (set (collect-strings (portfolio-view/portfolio-view state))))

(deftest account-activity-renders-the-reference-sub-tab-strip-test
  (let [view-node (portfolio-view/portfolio-view (activity-state))
        strip (find-first-node view-node
                               #(= "account-activity-sub-tab-strip" (get-in % [1 :data-role])))
        text (set (collect-strings view-node))]
    (is (some? strip) "the sub-tab strip renders")
    (testing "all nine sub-tabs, with the reference's labels"
      (doseq [label ["All" "Account Transfers" "Deposits and Withdrawals" "Spot Transfers"
                     "Internal Transfers" "Earn" "Vaults" "Staking" "Auctions"]]
        (is (contains? text label) (str "missing sub-tab " label))))
    (testing "All is selected by default and dispatches the sub-tab action"
      (let [all-button (find-first-node view-node
                                        #(= "account-activity-sub-tab-all"
                                            (get-in % [1 :data-role])))]
        (is (true? (get-in all-button [1 :aria-pressed])))
        (is (= [[:actions/set-portfolio-account-activity-sub-tab :all]]
               (get-in all-button [1 :on :click])))))))

(deftest account-activity-renders-the-ten-reference-columns-test
  (let [text (rendered-text (activity-state))]
    (doseq [header ["Time" "Status" "Asset" "Action" "From" "To" "Destination"
                    "Account Change" "USD Value" "Fee"]]
      (is (contains? text header) (str "missing column " header)))
    (testing "the pre-parity column names are gone"
      (is (not (contains? text "Source")))
      (is (not (contains? text "Account Value Change"))))))

(deftest account-activity-headers-are-sortable-test
  (let [view-node (portfolio-view/portfolio-view (activity-state))
        sort-buttons (find-nodes view-node
                                 #(= :actions/sort-portfolio-account-activity
                                     (first (first (get-in % [1 :on :click])))))]
    (is (= 10 (count sort-buttons)) "every column header dispatches a sort")))

(deftest account-activity-rows-render-payload-derived-values-test
  (let [text (rendered-text (activity-state))]
    (testing "each type keeps its own action label"
      (doseq [label ["Deposit" "Withdrawal" "Genesis Distribution" "Spot Transfer"
                     "Account Transfer" "Sub Account Transfer" "Vault Withdrawal"]]
        (is (contains? text label) (str "missing action label " label))))
    (testing "signed amounts carry an explicit sign, not colour alone"
      (is (contains? text "+100 USDC"))
      (is (contains? text "-25.5 USDC"))
      (is (contains? text "+2.67 HYPE")))
    (testing "an INCOMING spot transfer is a credit"
      ;; The viewer is the payload's destination, so this must be positive. The
      ;; pre-parity table hardcoded every spot transfer negative.
      (is (contains? text "+1 HYPE")))
    (testing "an OUTGOING sub-account transfer is a debit"
      (is (contains? text "-12 USDC")))
    (testing "endpoints name real venues"
      (doseq [venue ["Arbitrum" "Trading Account" "Spot" "Perps" "Vault"]]
        (is (contains? text venue) (str "missing venue " venue))))
    (testing "a vault withdrawal renders at all"
      ;; Its value lives in netWithdrawnUsd, which the old amount probe never
      ;; looked at, so the row used to be dropped entirely.
      (is (contains? text "+480.25 USDC")))
    (testing "USD value comes from usdcValue where the payload supplies it"
      (is (contains? text "$42.50")))
    (testing "the fee keeps its own asset"
      (is (contains? text "1 USDC")))
    (is (contains? text "Completed"))))

(deftest account-activity-destination-address-is-truncated-test
  (let [text (rendered-text (activity-state))]
    (is (contains? text "0x9999...9999")
        "a counterparty address renders truncated, not in full")))

(deftest account-activity-loading-empty-and-error-states-test
  (let [loading (rendered-text (activity-state {:ledger-loading? true}))
        empty (rendered-text (activity-state {:ledger-updates []}))
        error (rendered-text (activity-state {:ledger-loading? true
                                              :ledger-error "ledger-fail"}))]
    (is (contains? loading "Loading account activity..."))
    (is (contains? empty "No account activity"))
    (is (contains? error "ledger-fail"))
    (is (not (contains? error "Loading account activity...")))))

(deftest account-activity-tab-stays-wired-to-the-portfolio-tab-strip-test
  (let [view-node (portfolio-view/portfolio-view (activity-state))
        table-node (find-first-node view-node
                                    #(= "portfolio-account-activity" (get-in % [1 :data-role])))
        tab-button (find-first-node
                    view-node
                    #(= [[:actions/set-portfolio-account-info-tab :deposits-withdrawals]]
                        (get-in % [1 :on :click])))]
    (is (some? table-node))
    (is (contains? (set (class-values tab-button)) "account-info-tab-button-active"))
    (testing "the retired card and CTA stay retired"
      (is (nil? (find-first-node view-node
                                 #(= "portfolio-deposits-withdrawals-card"
                                     (get-in % [1 :data-role])))))
      (is (nil? (find-first-node view-node
                                 #(= "portfolio-funding-action-deposit"
                                     (get-in % [1 :data-role]))))))))
