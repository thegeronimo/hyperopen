(ns hyperopen.views.account-info.vm-hyperevm-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.hyperevm.panel-slice :as panel-slice]
            [hyperopen.views.account-info.vm :as vm]))

(use-fixtures :each
  (fn [f]
    (vm/reset-account-info-vm-cache!)
    (f)
    (vm/reset-account-info-vm-cache!)))

(def ^:private state (fixture/state))

(def ^:private owner-staking
  "The owner has 30 HYPE in the 7-day unstaking queue."
  {:staking {:delegator-summary {:delegated 100
                                 :undelegated 0
                                 :total-pending-withdrawal 30
                                 :pending-withdrawals 1}
             :delegator-summary-address fixture/owner}})

(deftest balances-tab-appends-hyperevm-rows-with-move-targets-test
  (let [view-model (vm/account-info-vm state)
        rows (:balance-rows view-model)
        evm-rows (filterv #(= :hyperevm (:location %)) rows)]
    (is (= ["hyperevm-150" "hyperevm-0" "hyperevm-1"] (mapv :key evm-rows)))
    (is (= evm-rows (subvec rows (- (count rows) 3))) "HyperEVM rows follow the HyperCore rows")
    (is (every? #(vector? (:move-targets %)) rows) "every row, HyperEVM ones included, has targets")
    (is (= :all (:balances-location-filter view-model)))
    (is (true? (:has-hyperevm-rows? view-model)))
    (is (= :ready (:hyperevm-status view-model)))
    (is (nil? (:hyperevm-moves-blocked-message view-model)))
    (testing "the tab badge counts HyperEVM rows too"
      (is (= 10 (get-in view-model [:tab-counts :balances]))))))

(deftest other-tabs-do-not-build-hyperevm-rows-test
  (let [view-model (vm/account-info-vm (assoc-in state [:account-info :selected-tab] :positions))]
    (is (= [] (:balance-rows view-model)))
    (is (= 10 (get-in view-model [:tab-counts :balances])) "the badge still counts them")))

(deftest unstaking-hype-annotates-the-hypercore-row-not-the-hyperevm-one-test
  (let [staked (merge state owner-staking)
        rows (:balance-rows (vm/account-info-vm staked))
        core-hype (some #(when (= "spot-150" (:key %)) %) rows)
        evm-hype (some #(when (= "hyperevm-150" (:key %)) %) rows)]
    (is (= 30 (:unstaking-hype core-hype)))
    (is (nil? (:unstaking-hype evm-hype)))))

(deftest location-filter-and-status-reach-the-view-model-test
  (let [filtered (vm/account-info-vm (assoc-in state [:account-info :balances-location-filter] :hyperevm))
        unread (vm/account-info-vm (assoc-in state [:hyperevm :balances :by-address] {}))]
    (is (= :hyperevm (:balances-location-filter filtered)))
    (is (= 10 (count (:balance-rows filtered)))
        "the view-model keeps every row; the tab applies the filter")
    (is (= :loading (:hyperevm-status unread)))
    (is (false? (:has-hyperevm-rows? unread)))
    (is (= 7 (get-in unread [:tab-counts :balances])))
    (testing "a retry of a failed first read keeps saying unavailable"
      (let [failed (fixture/with-evm-entry state {:status :error :error "x"})
            retrying (hyperevm-balances/apply-loading failed {:addresses [fixture/owner]
                                                              :requested-at-ms 5})]
        (is (= :unavailable (:hyperevm-status (vm/account-info-vm failed))))
        (is (= :unavailable (:hyperevm-status (vm/account-info-vm retrying))))
        (is (= (panel-slice/balances-panel-slice failed)
               (panel-slice/balances-panel-slice retrying))
            "the /trade slice does not change, so the panel does not repaint")))))

(defn- sliced
  "What /trade hands the account panel: the state with its `:hyperevm`
   replaced by the projected slice."
  [state*]
  (merge (dissoc state* :hyperevm) (panel-slice/balances-panel-slice state*)))

(deftest the-trade-slice-gives-the-same-balances-view-model-test
  (doseq [[label state*] [["connected master" state]
                          ["subaccount selected"
                           (assoc state :account-context
                                  {:subaccounts {:rows [{:sub-account-user fixture/subaccount
                                                         :master fixture/owner}]
                                                 :selected-address fixture/subaccount}})]
                          ["HyperEVM filter" (assoc-in state [:account-info :balances-location-filter]
                                                       :hyperevm)]
                          ["high gas price"
                           (assoc-in state [:hyperevm :balances :by-address fixture/owner :gas-price-wei]
                                     "40000000000")]
                          ["first read failed"
                           (fixture/with-evm-entry state {:status :error :stale? false :error "x"})]
                          ["a retry of the failed first read in flight"
                           (hyperevm-balances/apply-loading
                            (fixture/with-evm-entry state {:status :error :stale? false :error "x"})
                            {:addresses [fixture/owner] :requested-at-ms 5})]]]
    (testing label
      (let [full (vm/account-info-vm state*)
            projected (vm/account-info-vm (sliced state*))]
        (is (= (select-keys full [:balance-rows :tab-counts :balances-location-filter
                                  :has-hyperevm-rows? :hyperevm-status
                                  :hyperevm-moves-blocked-message])
               (select-keys projected [:balance-rows :tab-counts :balances-location-filter
                                       :has-hyperevm-rows? :hyperevm-status
                                       :hyperevm-moves-blocked-message])))))))

(deftest a-high-gas-price-raises-the-hype-reserve-test
  (let [expensive (assoc-in state [:hyperevm :balances :by-address fixture/owner :gas-price-wei]
                            "40000000000")
        hype (some #(when (= "hyperevm-150" (:key %)) %)
                   (:balance-rows (vm/account-info-vm expensive)))]
    ;; 30000 gas x 2 x 40 gwei = 0.0024 HYPE, above the 0.001 floor.
    (is (= "0.0024" (:gas-reserve-text hype)))
    (is (= "12.4976" (:available-text hype)))))

(deftest a-poll-that-changes-nothing-shown-keeps-the-slice-equal-test
  (let [before (panel-slice/balances-panel-slice state)
        polled (-> state
                   (hyperevm-balances/apply-loading {:addresses [fixture/owner]
                                                     :requested-at-ms (+ fixture/now-ms 30000)})
                   (hyperevm-balances/apply-success fixture/owner
                                                    (+ fixture/now-ms 30000)
                                                    {:native-wei (:native-wei fixture/evm-entry)
                                                     :token-units (:token-units fixture/evm-entry)
                                                     :gas-price-wei "110000000"}
                                                    (:token-indexes fixture/evm-entry)
                                                    (+ fixture/now-ms 31000))
                   (assoc-in [:hyperevm :bridge :evm-system-units 1] "508596915622676377079999999"))
        loading (hyperevm-balances/apply-loading state {:addresses [fixture/owner]
                                                        :requested-at-ms (+ fixture/now-ms 30000)})]
    (is (= before (panel-slice/balances-panel-slice loading)) "a refresh in flight")
    (is (= before (panel-slice/balances-panel-slice polled))
        "a new read with the same balances, a new gas price and a moved PURR pool")
    (is (not= before (panel-slice/balances-panel-slice
                      (assoc-in state [:hyperevm :balances :by-address fixture/owner :native-wei]
                                "13000000000000000000")))
        "a balance change reaches the panel")
    (is (not= before (panel-slice/balances-panel-slice
                      (assoc-in state [:hyperevm :bridge :evm-system-units 1] "0")))
        "a bridge side emptying reaches the panel")))
