(ns hyperopen.funding.domain.evm-transfer-preview-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]))

(defn- preview
  ([modal-overrides]
   (preview (support/state modal-overrides) modal-overrides))
  ([state _modal-overrides]
   (evm-preview/evm-transfer-preview state
                                     (get-in state [:funding-ui :modal])
                                     support/now-ms)))

(defn- blocked-code
  [result]
  (get-in result [:blocked :code]))

(def ^:private spot->evm {:transfer-from :spot :transfer-to :hyperevm})
(def ^:private evm->spot {:transfer-from :hyperevm :transfer-to :spot})

(deftest core-to-evm-builds-the-exact-send-asset-test
  (let [result (preview (assoc spot->evm :transfer-asset support/purr-index :amount-input "100"))]
    (is (= true (:ok? result)))
    (is (= {:type "sendAsset"
            :destination "0x2000000000000000000000000000000000000001"
            :sourceDex "spot"
            :destinationDex "spot"
            :token "PURR:0xc1fb593aeffbeb02f85e0308e9956a90"
            :amount "100"
            :fromSubAccount ""}
           (get-in result [:request :action])))
    (is (= :core->evm (get-in result [:request :route])))
    (is (= {:token-index 1 :symbol "PURR" :amount "100" :owner support/owner
            :chain-id "0x3e7" :from :spot}
           (get-in result [:request :evm])))))

(deftest spot-usdc-to-evm-floors-to-six-decimals-test
  (let [result (preview (assoc spot->evm :transfer-asset support/usdc-index
                               :amount-input "12.34567891"))]
    (is (= true (:ok? result)))
    (is (= {:type "sendAsset"
            :destination "0x2000000000000000000000000000000000000000"
            :sourceDex "spot"
            :destinationDex "spot"
            :token "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
            :amount "12.345678"
            :fromSubAccount ""}
           (get-in result [:request :action])))
    (is (= "Rounded down to 12.345678 USDC (6 decimals max)." (:notice result)))))

(deftest perps-to-evm-is-not-a-route-test
  ;; Only `sourceDex "spot"` sends to a system address were seen bridged
  ;; live; one leaving the Perps dex could strand the USDC on HyperCore.
  (testing "USDC goes Perps -> Spot -> HyperEVM"
    (let [result (preview {:transfer-from :perps :transfer-to :hyperevm
                           :transfer-asset support/usdc-index :amount-input "5"})]
      (is (= :invalid-route (blocked-code result)))
      (is (= transfer-route/perps-to-evm-reason (get-in result [:blocked :message])))
      (is (nil? (:request result)))))
  (testing "pooled accounts (unified or DEX abstraction) keep their own reason"
    (doseq [account [{:mode :unified}
                     {:mode :classic :abstraction-raw "dexAbstraction"}]]
      (let [state (assoc (support/state {:transfer-from :perps :transfer-to :hyperevm
                                         :transfer-asset support/usdc-index
                                         :amount-input "5"})
                         :account account)
            result (preview state nil)]
        (is (= :invalid-route (blocked-code result)) (pr-str account))
        (is (= "Perps and Spot share one USDC balance on this account. Move USDC to HyperEVM from Spot."
               (get-in result [:blocked :message]))
            (pr-str account))))))

(deftest evm-to-core-builds-the-client-only-pseudo-action-test
  (testing "native HYPE goes to 0x2222…"
    (let [result (preview (assoc evm->spot :transfer-asset support/hype-index :amount-input "10"))]
      (is (= true (:ok? result)))
      (is (= {:type "hyperEvmToCore"
              :kind "native"
              :tokenIndex 150
              :symbol "HYPE"
              :tokenAddress nil
              :spender nil
              :recipient "0x2222222222222222222222222222222222222222"
              :amount "10"
              :units "10000000000000000000"
              :destinationDex 4294967295
              :chainId "0x3e7"}
             (get-in result [:request :action])))
      (is (= [:native] (get-in result [:request :evm :gas-kinds])))))
  (testing "USDC deposits through Circle's CoreDepositWallet, with no recipient"
    (let [action (get-in (preview (assoc evm->spot :transfer-asset support/usdc-index
                                         :amount-input "1000"))
                         [:request :action])]
      (is (= "usdcCoreDeposit" (:kind action)))
      (is (nil? (:recipient action)))
      (is (= "0xb88339cb7199b77e23db6e890353e22632ba630f" (:tokenAddress action)))
      (is (= "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24" (:spender action)))
      (is (= "1000000000" (:units action)))))
  (testing "an ERC-20 goes to its own system address"
    (let [action (get-in (preview (assoc evm->spot :transfer-asset support/purr-index
                                         :amount-input "20"))
                         [:request :action])]
      (is (= "erc20" (:kind action)))
      (is (= "0x2000000000000000000000000000000000000001" (:recipient action)))
      (is (= "20000000000000000000" (:units action))))))

(deftest blank-amount-is-not-ok-and-says-nothing-test
  (is (= {:ok? false} (preview (assoc spot->evm :transfer-asset support/purr-index))))
  (is (= {:ok? false} (preview spot->evm)) "no asset chosen yet"))

(deftest amount-validation-runs-in-order-test
  (let [msg (fn [amount] (:display-message (preview (assoc evm->spot
                                                          :transfer-asset support/hype-index
                                                          :amount-input amount))))]
    (is (= "Enter a valid amount." (msg "abc")))
    (is (= "Enter a valid amount." (msg "1e5")))
    (is (= "Enter an amount greater than 0." (msg "0")))
    (is (= "Enter at least 0.00000001 HYPE." (msg "0.000000001")))
    (is (= "Keep at least 0.001 HYPE on HyperEVM for gas." (msg "12.5"))
        "within the balance but inside the gas reserve")
    (is (= "Amount exceeds available balance." (msg "13"))))
  (testing "amounts above the bridge capacity are refused with the cap"
    (let [state (assoc-in (support/state (assoc evm->spot :transfer-asset support/purr-index
                                                :amount-input "20"))
                          [:hyperevm :bridge :core-system-balances support/purr-index]
                          {:amount "15" :loaded-at-ms support/now-ms})]
      (is (= "The bridge can deliver at most 15 PURR right now."
             (:display-message (preview state nil)))))))

(deftest every-blocked-code-is-reachable-test
  (let [purr-out (assoc spot->evm :transfer-asset support/purr-index :amount-input "1")
        hype-in (assoc evm->spot :transfer-asset support/hype-index :amount-input "1")
        purr-in (assoc evm->spot :transfer-asset support/purr-index :amount-input "1")
        cases
        [[:invalid-route (support/state {:transfer-from :hyperevm :transfer-to :perps})]
         [:read-only (assoc (support/state purr-out) :account-context
                            {:spectate-mode {:active? true :address support/subaccount}})]
         [:subaccount (assoc-in (support/state purr-out) [:account-context :subaccounts]
                                {:selected-address support/subaccount
                                 :rows [{:sub-account-user support/subaccount
                                         :master support/owner}]})]
         [:no-owner (assoc (support/state purr-out) :wallet {:connected? false})]
         [:no-provider (assoc-in (support/state hype-in) [:wallet :connected?] false)]
         [:in-flight (assoc-in (support/state hype-in) [:hyperevm :in-flight support/owner]
                               {:hashes ["0xabc"] :waiting-receipt? true :submitted-at-ms 1})]
         [:no-chain-switch (assoc-in (support/state hype-in)
                                     [:hyperevm :wallet-capabilities "default" :chain-switch]
                                     :unsupported)]
         [:meta-missing (assoc-in (support/state purr-out) [:spot :meta] {:tokens []})]
         [:unmovable-token (support/state (assoc spot->evm :transfer-asset support/hope-index))]
         [:hyperevm-unavailable (support/with-evm-entry (support/state purr-out) nil)]
         [:bridge-empty (support/state (assoc spot->evm :transfer-asset support/six-index))]
         [:no-evm-gas (support/with-evm-entry (support/state purr-in)
                        (assoc support/evm-entry :native-wei "0"))]
         [:no-core-fee (assoc-in (support/state purr-out) [:spot :clearinghouse-state :balances]
                                 [{:coin "PURR" :token 1 :total "9800" :hold "0"}])]
         [:core-account-missing (assoc-in (support/state purr-in)
                                          [:hyperevm :core-account support/owner] :missing)]]]
    (doseq [[code state] cases]
      (testing (name code)
        (let [result (preview state nil)]
          (is (= code (blocked-code result)))
          (is (false? (:ok? result)))
          (is (string? (get-in result [:blocked :message])))
          (is (= (get-in result [:blocked :message]) (:display-message result))))))))

(deftest blocked-details-carry-what-the-card-needs-test
  (testing "the gas block names the need and offers the fix"
    (let [blocked (:blocked (preview (support/with-evm-entry
                                       (support/state (assoc evm->spot :transfer-asset
                                                             support/usdc-index))
                                       (assoc support/evm-entry :native-wei "0"))
                                     nil))]
      (is (= "You need HYPE on HyperEVM to pay gas" (:title blocked)))
      (is (= "You have 0 HYPE there. This move needs about 0.000064 HYPE." (:message blocked)))
      (is (true? (:fix? blocked)))))
  (testing "an in-flight transaction links to the explorer"
    (let [blocked (:blocked (preview (assoc-in (support/state (assoc evm->spot :transfer-asset
                                                                     support/hype-index))
                                               [:hyperevm :in-flight support/owner]
                                               {:hashes ["0xabc"] :waiting-receipt? false})
                                     nil))]
      (is (= "https://hyperevmscan.io/tx/0xabc" (:explorer-url blocked)))))
  (testing "blocks are decided before any amount is typed"
    (is (= :no-evm-gas
           (blocked-code (preview (support/with-evm-entry
                                    (support/state (assoc evm->spot :transfer-asset
                                                          support/purr-index))
                                    (assoc support/evm-entry :native-wei "0"))
                                  nil))))))

(deftest unknown-hyperevm-data-never-reports-missing-gas-test
  (let [modal (assoc evm->spot :transfer-asset support/purr-index)
        cases {"no read yet" nil
               "loading with no prior read" {:status :loading :requested-at-ms 1}
               "error with no prior read" {:status :error :error "rate limited"}}]
    (doseq [[label entry] cases]
      (testing label
        (let [result (preview (support/with-evm-entry (support/state modal) entry) nil)]
          (is (= :hyperevm-unavailable (blocked-code result)))
          (is (not (get-in result [:blocked :fix?]))))))
    (is (= "HyperEVM balances are unavailable right now."
           (get-in (preview (support/with-evm-entry (support/state modal)
                              {:status :error :error "rate limited"})
                            nil)
                   [:blocked :message])))
    (is (= "Checking HyperEVM balances…"
           (get-in (preview (support/with-evm-entry (support/state modal) nil) nil)
                   [:blocked :message])))))

(deftest checking-states-wait-for-bridge-health-capacity-and-activation-test
  (testing "unknown token health reads as Checking"
    (let [state (assoc-in (support/state (assoc spot->evm :transfer-asset support/purr-index))
                          [:hyperevm :bridge :token-health] {})]
      (is (= "Checking this token's HyperEVM link…"
             (get-in (preview state nil) [:blocked :message])))))
  (testing "a HyperCore bridge balance older than a minute is unknown again"
    (let [state (assoc-in (support/state (assoc evm->spot :transfer-asset support/purr-index))
                          [:hyperevm :bridge :core-system-balances support/purr-index :loaded-at-ms]
                          (- support/now-ms 60000))]
      (is (= :hyperevm-unavailable (blocked-code (preview state nil))))))
  (testing "an unknown HyperCore account waits for the userRole read"
    (let [state (update-in (support/state (assoc evm->spot :transfer-asset support/hype-index))
                           [:hyperevm :core-account] dissoc support/owner)]
      (is (= "Checking your HyperCore account…"
             (get-in (preview state nil) [:blocked :message]))))))

(deftest usdc-into-a-missing-account-pays-the-activation-fee-test
  (let [state (fn [amount]
                (assoc-in (support/state (assoc evm->spot :transfer-asset support/usdc-index
                                                :amount-input amount))
                          [:hyperevm :core-account support/owner] :missing))]
    (is (nil? (:blocked (preview (state "1") nil))))
    (is (= "The first transfer in pays a 1 USDC activation fee, so move more than 1 USDC."
           (:display-message (preview (state "1") nil))))
    (is (true? (:ok? (preview (state "1.5") nil))))))

(deftest gas-topup-request-sends-hype-from-spot-test
  (is (= {:ok? true
          :request {:action {:type "sendAsset"
                             :destination "0x2222222222222222222222222222222222222222"
                             :sourceDex "spot"
                             :destinationDex "spot"
                             :token "HYPE:0x0d01dc56dcaaca66ad901c959b4011ec"
                             :amount "0.05"
                             :fromSubAccount ""}
                    :route :core->evm
                    :purpose :gas-topup
                    :evm {:token-index 150 :symbol "HYPE" :amount "0.05"
                          :owner support/owner :chain-id "0x3e7" :from :spot}}}
         (evm-preview/gas-topup-request (support/state))))
  (is (= {:ok? false :display-message "You need at least 0.05 HYPE on Spot to add gas."}
         (evm-preview/gas-topup-request
          (assoc-in (support/state) [:spot :clearinghouse-state :balances]
                    [{:coin "HYPE" :token 150 :total "0.049" :hold "0"}]))))
  (is (= {:ok? false :display-message account-context/hyperevm-master-only-message}
         (evm-preview/gas-topup-request
          (assoc-in (support/state) [:account-context :subaccounts]
                    {:selected-address support/subaccount
                     :rows [{:sub-account-user support/subaccount :master support/owner}]})))))
