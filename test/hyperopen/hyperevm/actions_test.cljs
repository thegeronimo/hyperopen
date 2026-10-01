(ns hyperopen.hyperevm.actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.hyperevm.actions :as actions]
            [hyperopen.hyperevm.domain.balances :as balances]
            [hyperopen.hyperevm.test-support.bridge-fixtures :as bridge-fixtures]))

(def ^:private owner "0x1111111111111111111111111111111111111111")

(def ^:private base-state
  {:router {:path "/trade"}
   :wallet {:address owner}
   :spot {:meta bridge-fixtures/bridge-spot-meta}
   :hyperevm (balances/default-state)})

(deftest refresh-hyperevm-balances-emits-a-fetch-when-due-test
  (is (= [[:effects/fetch-hyperevm-balances {:addresses [owner] :requested-at-ms 1000}]]
         (actions/refresh-hyperevm-balances base-state {:now-ms 1000})))
  (is (= []
         (actions/refresh-hyperevm-balances (assoc-in base-state [:router :path] "/vaults")
                                            {:now-ms 1000}))
      "nothing on a surface without HyperEVM balances")
  (is (= []
         (actions/refresh-hyperevm-balances base-state {}))
      "no clock, no plan"))

(deftest refresh-hyperevm-balances-force-and-fast-poll-test
  (let [recent (-> base-state
                   (balances/apply-loading {:addresses [owner] :requested-at-ms 900})
                   (balances/apply-success owner 900
                                           {:native-wei "1" :token-units {} :gas-price-wei "1"}
                                           #{} 950))]
    (is (= [] (actions/refresh-hyperevm-balances recent {:now-ms 1000})))
    (is (= [[:effects/fetch-hyperevm-balances {:addresses [owner] :requested-at-ms 1000}]]
           (actions/refresh-hyperevm-balances recent {:now-ms 1000 :force? true})))
    (testing "a fast-poll window is saved first and makes the address due"
      (is (= [[:effects/save [:hyperevm :fast-poll-until-ms] 61000]
              [:effects/fetch-hyperevm-balances {:addresses [owner] :requested-at-ms 1000}]]
             (actions/refresh-hyperevm-balances recent {:now-ms 1000 :fast-poll-ms 60000}))))
    (testing "but an unforced call never overtakes a read still loading"
      (is (= [[:effects/save [:hyperevm :fast-poll-until-ms] 61000]]
             (actions/refresh-hyperevm-balances
              (balances/apply-loading recent {:addresses [owner] :requested-at-ms 990})
              {:now-ms 1000 :fast-poll-ms 60000}))))
    (testing "a shorter window never cuts a longer one short"
      (is (= [:effects/save [:hyperevm :fast-poll-until-ms] 90000]
             (first (actions/refresh-hyperevm-balances
                     (assoc-in recent [:hyperevm :fast-poll-until-ms] 90000)
                     {:now-ms 1000 :fast-poll-ms 5000})))))))

(deftest refresh-bridge-capacity-fetches-the-system-balance-and-activation-test
  (is (= [[:effects/fetch-hyperevm-core-bridge-balance 1 "0x2000000000000000000000000000000000000001"]
          [:effects/fetch-hyperevm-core-account-status owner]]
         (actions/refresh-hyperevm-bridge-capacity base-state 1)))
  (is (= [[:effects/fetch-hyperevm-core-bridge-balance 150 "0x2222222222222222222222222222222222222222"]
          [:effects/fetch-hyperevm-core-account-status owner]]
         (actions/refresh-hyperevm-bridge-capacity base-state 150))
      "HYPE's Core capacity sits at 0x2222…")
  (is (= [[:effects/fetch-hyperevm-core-account-status owner]]
         (actions/refresh-hyperevm-bridge-capacity base-state 0))
      "USDC is uncapped, but activation still matters")
  (is (= [[:effects/fetch-hyperevm-core-bridge-balance 1 "0x2000000000000000000000000000000000000001"]]
         (actions/refresh-hyperevm-bridge-capacity
          (assoc-in base-state [:hyperevm :core-account owner] :active) 1))
      "an :active account is final and never read again")
  (is (= [[:effects/fetch-hyperevm-core-bridge-balance 1 "0x2000000000000000000000000000000000000001"]
          [:effects/fetch-hyperevm-core-account-status owner]]
         (actions/refresh-hyperevm-bridge-capacity
          (assoc-in base-state [:hyperevm :core-account owner] :missing) 1))
      ":missing is read again, since the owner may have activated since")
  (is (= [[:effects/fetch-hyperevm-core-bridge-balance 1 "0x2000000000000000000000000000000000000001"]]
         (actions/refresh-hyperevm-bridge-capacity (dissoc base-state :wallet) 1))
      "no owner, no activation read")
  (is (= [] (actions/refresh-hyperevm-bridge-capacity base-state 9999))
      "an unlinked index does nothing"))

(deftest check-hyperevm-in-flight-reads-each-pending-hash-test
  (let [state (assoc-in base-state [:hyperevm :in-flight owner]
                        {:flow-id "f" :status :pending :hashes ["0xa" "0xb"] :waiting-receipt? false})]
    (is (= [[:effects/fetch-hyperevm-in-flight-receipt owner "0xb"]]
           (actions/check-hyperevm-in-flight state)))
    (is (= [] (actions/check-hyperevm-in-flight
               (assoc-in state [:hyperevm :in-flight owner :status] :running)))
        "a flow still running waits for its own receipts")
    (is (= [] (actions/check-hyperevm-in-flight base-state)))))

(deftest set-balances-location-filter-saves-a-known-filter-test
  (is (= [[:effects/save [:account-info :balances-location-filter] :hyperevm]]
         (actions/set-balances-location-filter base-state :hyperevm)))
  (is (= [[:effects/save [:account-info :balances-location-filter] :all]]
         (actions/set-balances-location-filter base-state :all)))
  (is (= [] (actions/set-balances-location-filter base-state :perps)))
  (is (= [] (actions/set-balances-location-filter base-state "hyperevm"))))
