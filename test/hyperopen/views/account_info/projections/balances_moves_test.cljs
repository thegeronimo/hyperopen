(ns hyperopen.views.account-info.projections.balances-moves-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-transfer-preview]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.views.account-info.projections.balances-hyperevm :as balances-hyperevm]
            [hyperopen.views.account-info.projections.balances-moves :as balances-moves]))

(def ^:private state (fixture/state))

(def ^:private core-rows
  [{:key "perps-usdc" :selection-coin "USDC" :coin "USDC (Perps)" :transfer-dex ""
    :transfer-to-perp? false :total-balance 800 :available-balance 800}
   {:key "perps-usdc-xyz" :selection-coin "xyz:USDC" :coin "USDC (Perps) xyz"
    :transfer-dex "xyz" :transfer-to-perp? false :total-balance 5 :available-balance 5}
   {:key "spot-0" :selection-coin "USDC" :coin "USDC (Spot)" :token 0
    :transfer-dex "" :transfer-to-perp? true :total-balance 2105.4 :available-balance 2105.4}
   {:key "spot-150" :selection-coin "HYPE" :coin "HYPE" :token 150
    :total-balance 412.08 :available-balance 412.08}
   {:key "spot-1" :selection-coin "PURR" :coin "PURR" :token 1
    :total-balance 9800 :available-balance 9800}
   {:key "spot-6" :selection-coin "SIX" :coin "SIX" :token 6
    :total-balance 10 :available-balance 10}
   {:key "spot-122" :selection-coin "HOPE" :coin "HOPE" :token 122
    :total-balance 7 :available-balance 7}
   {:key "spot-296" :selection-coin "JOFF" :coin "JOFF" :token 296
    :total-balance 5 :available-balance 5}
   {:key "spot-9999" :selection-coin "NOTLINKED" :coin "NOTLINKED" :token 9999
    :total-balance 3 :available-balance 3}
   {:key "staking-unstaking-hype" :selection-coin "HYPE" :coin "HYPE"
    :total-balance 0 :available-balance 0 :staking-only? true}])

(defn- annotated
  ([] (annotated state))
  ([state*]
   (balances-moves/with-move-targets
    (into core-rows (balances-hyperevm/hyperevm-rows state* core-rows))
    state*)))

(defn- targets-of
  [rows key*]
  (:move-targets (some #(when (= key* (:key %)) %) rows)))

(defn- summary
  [targets]
  (mapv (juxt :from :to :legacy? :disabled?) targets))

(deftest every-row-gets-its-move-targets-test
  (let [rows (annotated)]
    (is (every? #(vector? (:move-targets %)) rows))
    (testing "USDC keeps its legacy Perps <-> Spot move; only Spot gains HyperEVM"
      (is (= [[:perps :spot true false]]
             (summary (targets-of rows "perps-usdc")))
          "Perps -> HyperEVM goes through Spot")
      (is (= [[:spot :perps true false] [:spot :hyperevm false false]]
             (summary (targets-of rows "spot-0")))))
    (testing "a named-DEX perps row only moves to Spot, as before"
      (is (= [[:perps :spot true false]] (summary (targets-of rows "perps-usdc-xyz")))))
    (testing "linked spot tokens move to HyperEVM"
      (is (= [[:spot :hyperevm false false]] (summary (targets-of rows "spot-150"))))
      (is (= [[:spot :hyperevm false false]] (summary (targets-of rows "spot-1")))))
    (testing "HyperEVM rows move to Spot"
      (is (= [[:hyperevm :spot false false]] (summary (targets-of rows "hyperevm-150"))))
      (is (= [[:hyperevm :spot false false]] (summary (targets-of rows "hyperevm-0"))))
      (is (= [[:hyperevm :spot false false]] (summary (targets-of rows "hyperevm-1")))))
    (testing "nothing to move, or not linked"
      (is (= [] (targets-of rows "spot-9999")))
      (is (= [] (targets-of rows "staking-unstaking-hype"))))))

(deftest targets-carry-their-preset-and-labels-test
  (let [rows (annotated)
        [to-perps to-evm] (targets-of rows "spot-0")
        [to-spot] (targets-of rows "hyperevm-150")]
    (is (= {:row-key "spot-0" :from :spot :to :perps :label "To Perps"
            :aria-label "Move USDC from Spot to Perps" :legacy? true :context nil
            :disabled? false :reason nil}
           to-perps))
    (is (= {:from :spot :to :hyperevm :asset 0} (:context to-evm)))
    (is (= "To HyperEVM" (:label to-evm)))
    (is (= "Move USDC from Spot to HyperEVM" (:aria-label to-evm)))
    (is (= {:row-key "hyperevm-150" :from :hyperevm :to :spot :label "To Spot"
            :aria-label "Move HYPE from HyperEVM to Spot" :legacy? false
            :context {:from :hyperevm :to :spot :asset 150}
            :disabled? false :reason nil}
           to-spot))))

(deftest bridge-safety-shapes-the-targets-test
  (let [rows (annotated)]
    (testing "an empty HyperEVM bridge side disables the move with the reason"
      (is (= [{:disabled? true
               :reason (:bridge-empty-evm evm-transfer-preview/messages)}]
             (mapv #(select-keys % [:disabled? :reason]) (targets-of rows "spot-6")))))
    (testing "a token whose link is broken appears in no move target"
      (is (= [] (targets-of rows "spot-122")) "HOPE: decimals() mismatch")
      (is (= [] (targets-of rows "spot-296")) "JOFF: balanceOf reverts"))
    (testing "a token whose health is not known yet stays visible, disabled"
      (let [unchecked (update-in state [:hyperevm :bridge :token-health] dissoc fixture/purr-index)
            [target] (targets-of (annotated unchecked) "spot-1")]
        (is (true? (:disabled? target)))
        (is (= transfer-route/checking-token-reason (:reason target)))))
    (testing "while the first HyperEVM read fails nothing is being checked, so it says so"
      (let [failing (-> state
                        (update-in [:hyperevm :bridge :token-health] dissoc fixture/purr-index)
                        (fixture/with-evm-entry {:status :error :stale? false :error "rate limited"
                                                 :error-kind :rate-limit}))
            [target] (targets-of (annotated failing) "spot-1")]
        (is (true? (:disabled? target)))
        (is (= (:balances-unavailable evm-transfer-preview/messages) (:reason target)))))))

(deftest legacy-moves-name-their-dex-test
  (let [rows (annotated)
        label (fn [key*] (:aria-label (first (targets-of rows key*))))]
    (is (= "Move USDC from Perps to Spot" (label "perps-usdc")))
    (is (= "Move xyz USDC from Perps to Spot" (label "perps-usdc-xyz"))
        "each named-DEX row's action has its own accessible name")
    (is (= "Move USDC from Spot to Perps" (label "spot-0")))))

(deftest identity-blocks-disable-every-hyperevm-move-test
  (let [subaccount-state (assoc state :account-context
                                {:subaccounts {:rows [{:sub-account-user fixture/subaccount
                                                       :master fixture/owner}]
                                               :selected-address fixture/subaccount}})
        spectating (assoc state :account-context
                          {:spectate-mode {:active? true
                                           :address "0x5555555555555555555555555555555555555555"}})]
    (testing "a subaccount keeps its Perps <-> Spot move but cannot use HyperEVM"
      (let [rows (annotated subaccount-state)
            [legacy evm] (targets-of rows "spot-0")]
        (is (false? (:disabled? legacy)))
        (is (true? (:disabled? evm)))
        (is (= "HyperEVM transfers are available for the master account only." (:reason evm)))
        (is (every? :disabled? (targets-of rows "spot-150")))))
    (testing "spectate mode explains itself on every HyperEVM move"
      (let [rows (annotated spectating)]
        (is (= #{account-context/spectate-mode-read-only-message}
               (->> rows
                    (mapcat :move-targets)
                    (remove :legacy?)
                    (map :reason)
                    set)))))))

(deftest pooled-accounts-move-usdc-to-hyperevm-from-spot-only-test
  (let [unified (assoc state :account {:mode :unified})
        unified-row {:key "spot-0" :selection-coin "USDC" :coin "USDC" :token 0
                     :transfer-disabled? true :total-balance 2905.4 :available-balance 2905.4}
        [target :as targets] (:move-targets (first (balances-moves/with-move-targets [unified-row] unified)))]
    (is (= 1 (count targets)) "no Perps <-> Spot move on the pooled row")
    (is (= {:from :spot :to :hyperevm :asset 0} (:context target))))
  (let [dex-abstraction (assoc state :account {:mode :classic :abstraction-raw "dexAbstraction"})
        rows (balances-moves/with-move-targets core-rows dex-abstraction)]
    (is (= [[:perps :spot true false]] (summary (targets-of rows "perps-usdc")))
        "Perps and Spot share USDC there, so it leaves for HyperEVM from Spot")))
