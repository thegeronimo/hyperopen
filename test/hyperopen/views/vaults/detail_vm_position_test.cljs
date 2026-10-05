(ns hyperopen.views.vaults.detail-vm-position-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.vaults.detail-vm :as detail-vm]))

(use-fixtures :each
  (fn [f]
    (detail-vm/reset-vault-detail-vm-cache!)
    (f)
    (detail-vm/reset-vault-detail-vm-cache!)))

(def ^:private sample-state
  {:router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}
   :vaults-ui {:detail-tab :your-performance
               :snapshot-range :month
               :detail-loading? false}
   :vaults {:details-by-address {"0x1234567890abcdef1234567890abcdef12345678"
                                 {:name "Vault Detail"
                                  :portfolio {}}}}})

(deftest vault-detail-vm-builds-viewer-position-from-follower-state-and-viewer-ledger-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        viewer-address "0xffffffffffffffffffffffffffffffffffffffff"
        state (-> sample-state
                  (assoc-in [:wallet :address] "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                  (assoc-in [:account-context :spectate-mode] {:active? true
                                                               :address viewer-address})
                  (assoc-in [:vaults :user-equity-by-address] {})
                  (update-in [:vaults :details-by-address vault-address] dissoc :follower-state)
                  (assoc-in [:vaults :viewer-details-by-address vault-address viewer-address]
                            {:follower-state {:user viewer-address
                                              :vault-equity 1100
                                              :pnl 100
                                              :all-time-pnl 150
                                              :vault-entry-time-ms 1000
                                              :lockup-until-ms 2000}})
                  (assoc-in [:vaults :viewer-ledger-by-address vault-address viewer-address]
                            [{:time 1000
                              :hash "0xa"
                              :delta {:type "vaultDeposit" :vault vault-address :usdc "1000"}}]))
        position (:position (detail-vm/vault-detail-vm state {:now-ms (+ 1000 (* 5 86400000))}))]
    (is (= :open (:status position)))
    (is (= viewer-address (:viewer-address position)))
    (is (true? (:spectating? position)))
    (is (= :ready (:ledger-status position)))
    (is (= 1000 (:cost-basis position)))
    (is (= 100 (:unrealized position)))
    (is (= 10 (:unrealized-pct position)))
    (is (= 50 (:realized position)))
    (is (= 5 (:days-held position)))
    (is (= 1 (:transfer-count position)))))

(deftest vault-detail-vm-reports-viewer-ledger-loading-status-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        wallet "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        state (-> sample-state
                  (assoc-in [:wallet :address] wallet)
                  (assoc-in [:vaults :loading :viewer-ledger-by-address vault-address wallet] true))
        position (:position (detail-vm/vault-detail-vm state))]
    (is (= :loading (:ledger-status position)))
    (is (= wallet (:viewer-address position)))))

(deftest vault-detail-vm-labels-the-leader-depositor-with-the-leader-address-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        leader "0x677d00000000000000000000000000000008a4e7"
        state (-> sample-state
                  (assoc-in [:vaults :details-by-address vault-address :leader] leader)
                  (assoc-in [:vaults :details-by-address vault-address :followers]
                            [{:leader? true :vault-equity 10}
                             {:user "0x00000000000000000000000000000000000000f1" :vault-equity 5}]))
        [leader-row other-row] (:activity-depositors (detail-vm/vault-detail-vm state))]
    (is (= leader (:address leader-row)))
    (is (true? (:leader? leader-row)))
    (is (false? (:leader? other-row)))))
