(ns hyperopen.funding.domain.transfer-route-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]))

(deftest normalize-location-accepts-the-three-places-only-test
  (is (= :perps (transfer-route/normalize-location :perps)))
  (is (= :hyperevm (transfer-route/normalize-location " HyperEVM ")))
  (is (= :spot (transfer-route/normalize-location "spot")))
  (is (nil? (transfer-route/normalize-location :evm)))
  (is (nil? (transfer-route/normalize-location "")))
  (is (nil? (transfer-route/normalize-location 1)))
  (is (= 150 (transfer-route/normalize-asset-index "150")))
  (is (= 0 (transfer-route/normalize-asset-index 0)))
  (is (nil? (transfer-route/normalize-asset-index -1)))
  (is (nil? (transfer-route/normalize-asset-index "HYPE"))))

(deftest transfer-route-derives-legacy-and-rejects-partial-pairs-test
  (testing "both places nil derive the legacy route from :to-perp?"
    (is (= {:from :spot :to :perps :valid? true :legacy? true}
           (transfer-route/transfer-route {:to-perp? true})))
    (is (= {:from :perps :to :spot :valid? true :legacy? true}
           (transfer-route/transfer-route {:to-perp? false}))))
  (testing "explicit pairs win over :to-perp?"
    (is (= {:from :spot :to :hyperevm :valid? true :legacy? false}
           (transfer-route/transfer-route {:to-perp? true
                                           :transfer-from :spot
                                           :transfer-to "hyperevm"}))))
  (testing "partial, equal and HyperEVM -> Perps pairs are invalid, never legacy"
    (is (= {:from :spot :to nil :valid? false :legacy? false}
           (transfer-route/transfer-route {:to-perp? true :transfer-from :spot})))
    (is (false? (:valid? (transfer-route/transfer-route {:transfer-from :spot
                                                         :transfer-to :spot}))))
    (is (false? (:valid? (transfer-route/transfer-route {:transfer-from :hyperevm
                                                         :transfer-to :perps}))))))

(deftest route-kind-follows-the-hyperevm-side-test
  (is (= :core-internal (transfer-route/route-kind {:from :spot :to :perps})))
  (is (= :core->evm (transfer-route/route-kind {:from :spot :to :hyperevm})))
  (is (= :core->evm (transfer-route/route-kind {:from :perps :to :hyperevm})))
  (is (= :evm->core (transfer-route/route-kind {:from :hyperevm :to :spot})))
  (is (= :evm->core (transfer-route/route-kind {:from :hyperevm :to nil}))))

(deftest with-location-swaps-and-never-lands-on-hyperevm-to-perps-test
  (is (= [:perps :spot] (transfer-route/with-location {:from :spot :to :perps} :from :perps))
      "choosing the other side's place swaps")
  (is (= [:spot :hyperevm] (transfer-route/with-location {:from :spot :to :perps} :to :hyperevm)))
  (is (= [:hyperevm :spot] (transfer-route/with-location {:from :spot :to :perps} :from :hyperevm))
      "From HyperEVM with To Perps turns To into Spot")
  (is (= [:spot :perps] (transfer-route/with-location {:from :hyperevm :to :spot} :to :perps))
      "To Perps with From HyperEVM turns From into Spot")
  (is (= [:hyperevm :spot] (transfer-route/swapped {:from :spot :to :hyperevm})))
  (is (= [:hyperevm :spot] (transfer-route/swapped {:from :perps :to :hyperevm}))
      "swapping Perps -> HyperEVM lands on HyperEVM -> Spot"))

(defn- option
  [options side id]
  (some #(when (= id (:id %)) %) (get options side)))

(deftest location-options-explain-every-unavailable-place-test
  (testing "a master account can choose every place"
    (let [options (transfer-route/location-options (support/state) {:from :spot :to :hyperevm})]
      (is (= [:perps :spot :hyperevm] (mapv :id (:from options))))
      (is (= ["Perps" "Spot" "HyperEVM"] (mapv :label (:to options))))
      (is (every? (complement :disabled?) (concat (:from options) (:to options))))
      (is (true? (:selected? (option options :to :hyperevm))))))
  (testing "a selected subaccount disables HyperEVM with a visible reason"
    (let [state (assoc-in (support/state) [:account-context :subaccounts]
                          {:selected-address support/subaccount
                           :rows [{:sub-account-user support/subaccount
                                   :master support/owner}]})
          options (transfer-route/location-options state {:from :spot :to :perps})]
      (is (true? (:disabled? (option options :to :hyperevm))))
      (is (= "HyperEVM transfers are available for the master account only."
             (:reason (option options :to :hyperevm))))))
  (testing "spectate mode keeps its read-only explanation"
    (let [state (assoc (support/state) :account-context
                       {:spectate-mode {:active? true :address support/subaccount}})
          options (transfer-route/location-options state {:from :spot :to :perps})]
      (is (= account-context/spectate-mode-read-only-message
             (:reason (option options :from :hyperevm))))))
  (testing "HyperEVM -> Perps is not a choice"
    (let [options (transfer-route/location-options (support/state) {:from :hyperevm :to :spot})]
      (is (true? (:disabled? (option options :to :perps))))
      (is (= "Move to Spot first, then Spot → Perps." (:reason (option options :to :perps))))))
  (testing "unified accounts cannot newly choose Perps, but a Perps route already chosen stays"
    (let [state (assoc (support/state) :account {:mode :unified})
          evm-options (transfer-route/location-options state {:from :spot :to :hyperevm})
          legacy-options (transfer-route/location-options state {:from :spot :to :perps})]
      (is (true? (:disabled? (option evm-options :to :perps))))
      (is (= transfer-route/unified-perps-reason (:reason (option evm-options :to :perps))))
      (is (false? (:disabled? (option legacy-options :to :perps))))
      (is (= transfer-route/unified-perps-reason (:reason (option legacy-options :to :perps)))))))

(deftest dex-abstraction-accounts-pair-perps-with-hyperevm-only-through-spot-test
  ;; DEX abstraction is folded into `:classic`, yet its Perps USDC is pooled
  ;; with Spot like a unified account's, so Perps <-> HyperEVM goes via Spot.
  (let [state (assoc (support/state) :account {:mode :classic
                                               :abstraction-raw "dexAbstraction"})
        to-evm (transfer-route/location-options state {:from :spot :to :hyperevm})
        from-perps (transfer-route/location-options state {:from :perps :to :spot})]
    (is (true? (:disabled? (option to-evm :from :perps))))
    (is (= transfer-route/pooled-perps-evm-reason (:reason (option to-evm :from :perps))))
    (is (true? (:disabled? (option from-perps :to :hyperevm))))
    (is (= transfer-route/pooled-perps-evm-reason (:reason (option from-perps :to :hyperevm))))
    (is (nil? (:reason (option from-perps :to :spot))) "Perps <-> Spot stays a choice")
    (is (nil? (:reason (option (transfer-route/location-options (support/state)
                                                                {:from :spot :to :hyperevm})
                               :from :perps)))
        "a standard account keeps Perps -> HyperEVM")))

(deftest asset-options-list-held-linked-tokens-by-value-test
  (let [options (transfer-route/asset-options (support/state) {:from :spot :to :hyperevm})
        by-index (into {} (map (juxt :index identity)) options)]
    (testing "priced tokens lead by USD value, unpriced ones follow by name"
      (is (= ["HYPE" "USDC" "PURR" "HOPE" "JOFF" "SIX"] (mapv :symbol options))))
    (testing "unmovable held tokens stay listed, disabled, with the reason"
      (is (true? (:disabled? (get by-index support/joff-index))))
      (is (= transfer-route/unmovable-token-reason (:reason (get by-index support/hope-index)))))
    (testing "movable tokens carry their exact available amount"
      (is (= "412.08" (:available (get by-index support/hype-index))))
      (is (false? (:disabled? (get by-index support/purr-index)))))))

(deftest asset-options-read-the-route-source-test
  (testing "HyperEVM source lists the owner's EVM balances"
    (is (= ["USDC" "HYPE" "PURR"]
           (mapv :symbol (transfer-route/asset-options (support/state)
                                                       {:from :hyperevm :to :spot})))))
  (testing "Perps -> HyperEVM is not a route, so it lists nothing"
    (is (= [] (transfer-route/asset-options (support/state) {:from :perps :to :hyperevm}))))
  (testing "unknown health reads as Checking, never as unmovable"
    (let [state (assoc-in (support/state) [:hyperevm :bridge :token-health] {})
          purr (some #(when (= "PURR" (:symbol %)) %)
                     (transfer-route/asset-options state {:from :spot :to :hyperevm}))]
      (is (= :unknown (:health purr)))
      (is (= transfer-route/checking-token-reason (:reason purr)))))
  (testing "the Perps <-> Spot route has no asset list"
    (is (= [] (transfer-route/asset-options (support/state) {:from :spot :to :perps})))))

(deftest eligible-asset-index-keeps-a-movable-choice-test
  (let [options (transfer-route/asset-options (support/state) {:from :spot :to :hyperevm})]
    (is (= support/purr-index (transfer-route/eligible-asset-index options support/purr-index)))
    (is (= support/hype-index (transfer-route/eligible-asset-index options support/joff-index))
        "an unmovable current asset gives way to the first enabled option")
    (is (= support/hype-index (transfer-route/eligible-asset-index options 9999)))
    (is (nil? (transfer-route/eligible-asset-index [] support/purr-index)))))
