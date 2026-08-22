(ns hyperopen.domain.account-ledger-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.account-ledger :as account-ledger]))

(deftest normalize-ledger-rows-builds-main-client-history-rows-test
  (let [rows (account-ledger/normalize-ledger-rows
              [{:time 1770000000000
                :hash "0xdeposit"
                :delta {:type "deposit"
                        :usdc "100.0"}}
               {:time 1770000100000
                :hash "0xwithdraw"
                :delta {:type "withdraw"
                        :usdc "25.5"
                        :fee "1.0"}}
               {:time 1770000200000
                :hash "0xvault"
                :delta {:type "vaultDeposit"
                        :usdc "10.0"}}
               {:time 1770000300000
                :hash "0xgenesis"
                :delta {:type "spotGenesis"
                        :token "HYPE"
                        :amount "2.67"}}
               {:time 1770000400000
                :hash "0xsend"
                :delta {:type "spotTransfer"
                        :token "HYPE"
                        :amount "1.0"}}])]
    (testing "each type keeps its own label rather than collapsing to Send"
      (is (= ["Spot Transfer"
              "Genesis Distribution"
              "Vault Deposit"
              "Withdrawal"
              "Deposit"]
             (mapv :action-label rows))))
    (testing "the machine-readable type is what sub-tab routing reads"
      (is (= ["spot-transfer"
              "spot-genesis"
              "vault-deposit"
              "withdraw"
              "deposit"]
             (mapv :type-key rows))))
    (is (= ["-1 HYPE"
            "+2.67 HYPE"
            "-10 USDC"
            "-25.5 USDC"
            "+100 USDC"]
           (mapv :amount-text rows)))
    (testing "endpoints name real venues instead of Trading Account for everything"
      (is (= ["Spot" "Trading Account" "Perps" "Trading Account" "Arbitrum"]
             (mapv :from-label rows)))
      (is (= ["Spot" "Trading Account" "Vault" "Arbitrum" "Trading Account"]
             (mapv :to-label rows))))
    (is (= ["--" "--" "--" "1 USDC" "--"]
           (mapv :fee-text rows)))
    (is (= (repeat 5 "Completed")
           (map :status-label rows)))))

(deftest normalize-ledger-rows-supports-direct-delta-and-drops-malformed-test
  (let [rows (account-ledger/normalize-ledger-rows
              [{:time 1770000000000
                :type "internalTransfer"
                :usdc "3.0"
                :hash "0xdirect"}
               {:time nil
                :delta {:type "deposit"
                        :usdc "1.0"}}
               {:time 1770000000001
                :delta {:type "unknownWithoutAmount"}}
               "not-a-row"])
        by-type (into {} (map (juxt :type-key identity)) rows)]
    (testing "a delta stated inline on the row still normalises"
      (let [row (get by-type "internal-transfer")]
        (is (some? row))
        (is (= "-3 USDC" (:amount-text row)))
        (is (= "0xdirect" (:hash row)))))
    (testing "a row whose amount cannot be resolved is kept and shown as --"
      ;; It used to be dropped, which is why the Vault Withdrawal and
      ;; Liquidation labels were unreachable by real data.
      (let [row (get by-type "unknown-without-amount")]
        (is (some? row))
        (is (= "--" (:amount-text row)))))
    (testing "rows with no timestamp, and non-maps, are still rejected"
      (is (= 2 (count rows))))))

(deftest merge-ledger-rows-dedupes-rest-and-websocket-duplicates-test
  (let [rest-row {:time 1770000000000
                  :hash "0xdup"
                  :delta {:type "deposit"
                          :usdc "100.0"}}
        ws-row {:time 1770000000000
                :hash "0xdup"
                :delta {:type "deposit"
                        :usdc "100.0"}}
        newer-row {:time 1770000100000
                   :hash "0xnew"
                   :delta {:type "withdraw"
                           :usdc "1.0"}}
        rows (account-ledger/merge-ledger-rows [rest-row newer-row] [ws-row])]
    (is (= 2 (count rows)))
    (is (= ["Withdrawal" "Deposit"] (mapv :action-label rows)))
    (is (= ["0xnew" "0xdup"] (mapv :hash rows)))))
