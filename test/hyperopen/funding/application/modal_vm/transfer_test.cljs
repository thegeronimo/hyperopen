(ns hyperopen.funding.application.modal-vm.transfer-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.token-pricing :as token-pricing]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.contracts :as contracts]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.platform :as platform]))

(defn- vm
  [state]
  (with-redefs [platform/now-ms (constantly support/now-ms)]
    (funding-actions/funding-modal-view-model state)))

(def ^:private spot->evm {:transfer-from :spot :transfer-to :hyperevm})
(def ^:private evm->spot {:transfer-from :hyperevm :transfer-to :spot})

(defn- no-gas
  [state]
  (support/with-evm-entry state (assoc support/evm-entry :native-wei "0")))

(def ^:private run-steps
  [{:id :switch-network :label "Wallet switched to HyperEVM" :status :done}
   {:id :approve :label "Approve 1,000 USDC" :status :active :detail "Confirm in your wallet"}
   {:id :deposit :label "Deposit to HyperCore Spot" :status :pending}])

(deftest legacy-route-keeps-its-form-and-labels-test
  (let [view-model (vm (support/state {:to-perp? true :amount-input "12"}))
        transfer (:transfer view-model)]
    (is (contracts/funding-modal-vm-valid? view-model))
    (is (= "Transfer" (:title view-model)))
    (is (= :transfer/form (get-in view-model [:content :kind])))
    (is (= {:from :spot :to :perps :kind :core-internal :valid? true} (:route transfer)))
    (is (false? (get-in transfer [:asset :visible?])))
    (is (= "USDC" (get-in transfer [:amount :symbol])))
    (is (= [] (:summary transfer)))
    (is (= "Transfer" (get-in transfer [:actions :submit-label])))
    (is (= {:label "Spot" :before "2,105.40 USDC" :after "2,093.40 USDC" :delta "−12.00"
            :available "2,105.40 USDC"}
           (get-in transfer [:balances :from])))
    (is (= "Your wallet 0x123…5678" (get-in transfer [:destination :display])))))

(deftest core-to-evm-form-model-test
  (let [view-model (vm (support/state (assoc spot->evm :transfer-asset support/hype-index
                                             :amount-input "250")))
        transfer (:transfer view-model)]
    (is (contracts/funding-modal-vm-valid? view-model))
    (testing "places are segmented options with actions and data-roles"
      (is (= {:id :hyperevm :label "HyperEVM" :selected? true :disabled? false :reason nil
              :action [:actions/set-funding-transfer-location :to :hyperevm]
              :data-role "funding-transfer-to-hyperevm"}
             (last (:to-options transfer))))
      (is (= [:actions/swap-funding-transfer-locations] (:swap-action transfer))))
    (testing "the asset list shows held linked tokens, the choice marked"
      (is (true? (get-in transfer [:asset :visible?])))
      (is (= {:index 150 :symbol "HYPE" :balance-display "412.08" :selected? true
              :disabled? false :reason nil
              :action [:actions/select-funding-transfer-asset 150]}
             (first (get-in transfer [:asset :options]))))
      (is (= {:symbol "HYPE" :notice nil :explorer-url nil} (get-in transfer [:asset :selected]))))
    (testing "balances before and after the move"
      (is (= {:from {:label "Spot" :before "412.08 HYPE" :after "162.08 HYPE" :delta "−250.00"
                     :available "412.08 HYPE"}
              :to {:label "HyperEVM" :before "12.50 HYPE" :after "262.50 HYPE" :delta "+250.00"
                   :available "12.50 HYPE"}}
             (:balances transfer))))
    (testing "summary, estimate and submit label follow the design"
      (is (= [{:label "Network fee" :value "None" :tone :neutral}
              {:label "Arrives" :value "Next HyperEVM block, usually under 5 seconds" :tone :neutral}
              {:label "Wallet network" :value "No switch needed" :tone :neutral}]
             (:summary transfer)))
      (is (= "≈ $11,170.00" (:usd-estimate transfer)))
      (is (= "Move 250 HYPE to HyperEVM" (get-in transfer [:actions :submit-label])))
      (is (false? (get-in transfer [:actions :submit-disabled?])))
      (is (= ["25%" "50%" "75%"] (mapv :label (:percent-actions transfer))))
      (is (= [:actions/set-funding-transfer-amount-percent 50]
             (:action (second (:percent-actions transfer))))))
    (testing "MAX and symbol are the route's own"
      (is (= "412.08" (get-in transfer [:amount :max-display])))
      (is (= "412.08" (get-in transfer [:amount :max-input])))
      (is (= "HYPE" (:max-symbol view-model))))))

(deftest core-to-evm-fee-and-verification-notice-test
  (let [purr (:transfer (vm (support/state (assoc spot->evm :transfer-asset support/purr-index))))
        six (:transfer (vm (support/state (assoc spot->evm :transfer-asset support/six-index))))]
    (is (= "≈ 0.00002 HYPE, paid from Spot" (:value (first (:summary purr)))))
    (is (= "Move PURR to HyperEVM" (get-in purr [:actions :submit-label])))
    (is (= (str "SIX is linked to contract 0x41d…151f on HyperEVM. Hyperliquid doesn't"
                " check linked contracts, so verify it before moving large amounts.")
           (get-in six [:asset :selected :notice])))
    (is (= "https://hyperevmscan.io/token/0x41de34fc45a770ebcd50200be93f080b4b05151f"
           (get-in six [:asset :selected :explorer-url])))
    (is (= :bridge-empty (get-in six [:blocked :code])))))

(deftest evm-to-core-labels-follow-the-wallet-network-test
  (let [state (support/state (assoc evm->spot :transfer-asset support/hype-index :amount-input "10"))
        switching (:transfer (vm state))
        on-hyperevm (:transfer (vm (assoc-in state [:wallet :chain-id] "0x3e7")))]
    (is (= "Switch network & move 10 HYPE to Spot" (get-in switching [:actions :submit-label])))
    (is (= "Move 10 HYPE to Spot" (get-in on-hyperevm [:actions :submit-label])))
    (is (= "Switches to HyperEVM (chain 999)" (:value (last (:summary switching)))))
    (is (= "Already on HyperEVM" (:value (last (:summary on-hyperevm)))))
    (is (= "≈ 0.000012 HYPE on HyperEVM · 12.50 available" (:value (first (:summary switching)))))
    (is (= "MAX keeps 0.001 HYPE on HyperEVM for gas." (get-in switching [:amount :notice])))
    (is (= "12.499" (get-in switching [:amount :max-display])))))

(deftest usdc-activation-fee-row-test
  (let [transfer (:transfer (vm (assoc-in (support/state (assoc evm->spot
                                                                :transfer-asset support/usdc-index
                                                                :amount-input "5"))
                                          [:hyperevm :core-account support/owner] :missing)))]
    (is (= {:label "Activation fee" :value "1 USDC (first transfer only)" :tone :warn}
           (last (:summary transfer))))
    (is (= "A few seconds after the deposit confirms" (:value (second (:summary transfer)))))))

(deftest gas-block-shows-the-fix-before-any-amount-test
  (let [view-model (vm (no-gas (support/state (assoc evm->spot :transfer-asset support/purr-index))))
        transfer (:transfer view-model)]
    (is (contracts/funding-modal-vm-valid? view-model))
    (is (= {:code :no-evm-gas
            :checking? false
            :title "You need HYPE on HyperEVM to pay gas"
            :message "You have 0 HYPE there. This move needs about 0.000024 HYPE."
            :explorer-url nil
            :fix {:label "Send 0.05 HYPE from Spot to HyperEVM"
                  :action [:actions/submit-funding-transfer-gas-topup]
                  :status :idle
                  :status-message "From your 412.08 HYPE on Spot · no network switch · a few seconds"
                  :disabled? false
                  :reason nil}}
           (:blocked transfer)))
    (is (= "Add gas to continue" (get-in transfer [:actions :submit-label])))
    (is (true? (get-in transfer [:actions :submit-disabled?])))
    (is (false? (get-in view-model [:feedback :visible?]))
        "the blocked card replaces the red status line")))

(deftest gas-fix-states-test
  (let [fix (fn [state] (get-in (vm state) [:transfer :blocked :fix]))
        base (no-gas (support/state (assoc evm->spot :transfer-asset support/purr-index)))]
    (is (= [:submitting true "Sending 0.05 HYPE…"]
           ((juxt :status :disabled? :status-message)
            (fix (assoc-in base [:funding-ui :modal :transfer-gas-topup] {:status :submitting})))))
    (is (= [:sent true "Sent 0.05 HYPE. Waiting for it to arrive…"]
           ((juxt :status :disabled? :status-message)
            (fix (assoc-in base [:funding-ui :modal :transfer-gas-topup] {:status :sent})))))
    (is (= [:failed false "Exchange rejected."]
           ((juxt :status :disabled? :status-message)
            (fix (assoc-in base [:funding-ui :modal :transfer-gas-topup]
                           {:status :failed :error "Exchange rejected."})))))
    (is (= [true "You need at least 0.05 HYPE on Spot."]
           ((juxt :disabled? :reason)
            (fix (assoc-in base [:spot :clearinghouse-state :balances]
                           [{:coin "HYPE" :token 150 :total "0.02" :hold "0"}])))))))

(deftest unknown-hyperevm-data-shows-checking-without-a-fix-test
  (let [transfer (:transfer (vm (support/with-evm-entry
                                  (support/state (assoc evm->spot :transfer-asset support/purr-index))
                                  {:status :loading :requested-at-ms 1})))]
    (is (= :hyperevm-unavailable (get-in transfer [:blocked :code])))
    (is (true? (get-in transfer [:blocked :checking?])) "only waiting on a read")
    (is (= "Checking HyperEVM balances…" (get-in transfer [:blocked :message])))
    (is (nil? (get-in transfer [:blocked :fix])))))

(deftest an-unknown-source-never-claims-it-holds-nothing-test
  (doseq [[label entry message] [["loading" {:status :loading :requested-at-ms 1}
                                  "Checking HyperEVM balances…"]
                                 ["a failed first read" {:status :error :error "rate limited"
                                                         :error-kind :rate-limited}
                                  "HyperEVM balances are unavailable right now."]]]
    (testing label
      (let [transfer (:transfer (vm (support/with-evm-entry (support/state evm->spot) entry)))]
        (is (= [] (get-in transfer [:asset :options])))
        (is (nil? (get-in transfer [:asset :empty-message]))
            "no \"No linked tokens on HyperEVM yet.\" while the balances are unknown")
        (is (= [:hyperevm-unavailable true message]
               ((juxt :code :checking? :message) (:blocked transfer)))
            "the source's read is what the form waits on, even with no asset chosen"))))
  (testing "Spot balances not loaded yet"
    (let [transfer (:transfer (vm (assoc-in (support/state spot->evm) [:spot :clearinghouse-state] nil)))]
      (is (nil? (get-in transfer [:asset :empty-message])))
      (is (= "Checking your Spot balances…" (get-in transfer [:blocked :message])))))
  (testing "a read source that holds nothing says so"
    (let [transfer (:transfer (vm (support/with-evm-entry
                                    (support/state evm->spot)
                                    (assoc support/evm-entry :native-wei "0" :token-units {}))))]
      (is (= "No linked tokens on HyperEVM yet." (get-in transfer [:asset :empty-message])))
      (is (nil? (:blocked transfer))))))

(deftest legacy-balances-stay-unknown-until-read-test
  (let [transfer (:transfer (vm (-> (support/state {:to-perp? true})
                                    (assoc-in [:spot :clearinghouse-state] nil)
                                    (assoc :webdata2 nil))))]
    (is (= {:label "Spot" :before nil :after nil :delta nil :available nil}
           (get-in transfer [:balances :from]))
        "no \"0.00 USDC\" before Spot is read")
    (is (nil? (get-in transfer [:balances :to :before])))))

(deftest transfer-message-sits-in-the-form-test
  (testing "an over-max draft explains the disabled submit in the form, not the shell"
    (let [view-model (vm (support/state (assoc spot->evm :transfer-asset support/hype-index
                                               :amount-input "500")))]
      (is (= "Amount exceeds available balance." (get-in view-model [:transfer :message])))
      (is (true? (get-in view-model [:transfer :actions :submit-disabled?])))
      (is (false? (get-in view-model [:feedback :visible?])))))
  (testing "a submit error shows there too"
    (is (= "Transfer failed: x" (get-in (vm (support/state {:to-perp? true :amount-input "5"
                                                            :error "Transfer failed: x"}))
                                        [:transfer :message]))))
  (testing "a finished move never shows the draft checked against its own new balances"
    (let [view-model (vm (support/state (assoc spot->evm :transfer-asset support/hype-index
                                               :amount-input "500"
                                               :transfer-evm {:phase :succeeded :arrival :arrived})))]
      (is (= :transfer/success (get-in view-model [:content :kind])))
      (is (nil? (get-in view-model [:transfer :message])))
      (is (false? (get-in view-model [:feedback :visible?]))))))

(deftest retry-action-follows-what-the-failure-left-test
  (let [draft (assoc spot->evm :transfer-asset support/purr-index :amount-input "100")
        retry (fn [modal-overrides & [f]]
                (get-in (vm ((or f identity) (support/state (merge draft modal-overrides))))
                        [:transfer :evm :retry-action]))]
    (is (= [:actions/submit-funding-transfer]
           (retry {:transfer-evm {:phase :failed :error "Transfer failed: Insufficient balance"}}))
        "the exchange refused it and the draft still submits")
    (is (nil? (retry {:transfer-evm {:phase :failed :maybe-sent? true :error "Transfer failed: timeout"}}))
        "the sendAsset may have applied: only Back to edit")
    (is (nil? (retry {:amount-input "999999" :transfer-evm {:phase :failed}}))
        "a draft that no longer submits: Back to edit shows why")
    (is (= [:actions/retry-funding-transfer-capability]
           (retry {:transfer-from :hyperevm :transfer-to :spot :transfer-evm {:phase :failed}}
                  #(assoc-in % [:hyperevm :wallet-capabilities "default"] {:chain-switch :unsupported}))))
    (is (nil? (retry {:transfer-evm {:phase :succeeded :arrival :arriving}})))))

(deftest confirming-steps-name-the-chain-not-the-wallet-test
  (let [label (fn [steps]
                (get-in (vm (support/state (assoc evm->spot :transfer-asset support/usdc-index
                                                  :amount-input "1000" :submitting? true
                                                  :transfer-evm {:phase :running :flow-id "f"
                                                                 :steps steps})))
                        [:transfer :actions :submit-label]))]
    (is (= "Move 1,000 PURR to HyperEVM"
           (get-in (vm (support/state (assoc spot->evm :transfer-asset support/purr-index
                                             :amount-input "1000")))
                   [:transfer :actions :submit-label]))
        "the submit groups its amount like the run's heading and steps")
    (is (= "Waiting for wallet (step 2 of 3)" (label run-steps)))
    (is (= "Confirming on HyperEVM (step 2 of 3)"
           (label (assoc-in run-steps [1 :detail] "Confirming on HyperEVM…"))))))

(deftest hyperevm-run-phases-model-test
  (let [run-state (fn [run]
                    (support/state (assoc evm->spot :transfer-asset support/usdc-index
                                          :amount-input "1000"
                                          :transfer-evm run)))]
    (testing "running shows progress and waits for the wallet"
      (let [view-model (vm (run-state {:phase :running :flow-id "flow-1" :steps run-steps}))]
        (is (contracts/funding-modal-vm-valid? view-model))
        (is (= :transfer/progress (get-in view-model [:content :kind])))
        (is (= "Waiting for wallet (step 2 of 3)"
               (get-in view-model [:transfer :actions :submit-label])))
        (is (true? (get-in view-model [:transfer :actions :submit-disabled?])))
        (is (= [2 3] ((juxt :step-index :step-count) (get-in view-model [:transfer :evm]))))))
    (doseq [[phase kind] [[:pending :transfer/pending]
                          [:failed :transfer/failed]
                          [:succeeded :transfer/success]]]
      (testing (name phase)
        (let [view-model (vm (run-state {:phase phase :flow-id "flow-1" :steps run-steps
                                         :tx-hash "0xabc" :error "Transfer rejected in wallet."
                                         :arrival :arriving}))]
          (is (contracts/funding-modal-vm-valid? view-model))
          (is (= kind (get-in view-model [:content :kind])))
          (is (= "https://hyperevmscan.io/tx/0xabc" (get-in view-model [:transfer :evm :tx-url])))
          (is (false? (get-in view-model [:transfer :evm :add-to-wallet?]))
              "Add to wallet is offered only when the destination is HyperEVM"))))
    (testing "a finished move to HyperEVM offers Add to wallet"
      (is (true? (get-in (vm (support/state (assoc spot->evm :transfer-asset support/purr-index
                                                   :transfer-evm {:phase :succeeded
                                                                  :arrival :arrived})))
                         [:transfer :evm :add-to-wallet?]))))))

(deftest contract-holds-across-routes-and-states-test
  (doseq [[label state] [["subaccount" (assoc-in (support/state spot->evm) [:account-context :subaccounts]
                                                 {:selected-address support/subaccount
                                                  :rows [{:sub-account-user support/subaccount
                                                          :master support/owner}]})]
                         ["empty source" (support/with-evm-entry
                                           (support/state evm->spot)
                                           (assoc support/evm-entry :native-wei "0" :token-units {}))]
                         ["invalid route" (support/state {:transfer-from :spot})]
                         ["perps to evm" (support/state {:transfer-from :perps :transfer-to :hyperevm
                                                         :transfer-asset 0 :amount-input "abc"})]
                         ["in flight" (assoc-in (support/state (assoc evm->spot :transfer-asset 150))
                                                [:hyperevm :in-flight support/owner]
                                                {:hashes ["0xabc"]})]]]
    (testing label
      (is (contracts/funding-modal-vm-valid? (vm state))))))

(deftest other-modes-skip-the-transfer-work-test
  ;; The view-model runs on every render; Deposit, Withdraw and Send must not
  ;; price, list or balance anything for a Transfer form they never show.
  (doseq [mode [:deposit :withdraw :send]]
    (let [calls (atom 0)
          count-call (fn [f] (fn [& args] (swap! calls inc) (apply f args)))
          state (support/state (assoc spot->evm :mode mode :transfer-asset support/purr-index
                                      :amount-input "10"))
          view-model (with-redefs [token-pricing/market-token-price-usd
                                   (count-call (constantly 1))
                                   transfer-route/location-options
                                   (count-call (constantly {:from [] :to []}))
                                   transfer-route/asset-options
                                   (count-call (constantly []))]
                       (vm state))
          transfer (:transfer view-model)]
      (is (contracts/funding-modal-vm-valid? view-model) (str mode))
      (is (= 0 @calls) (str mode " computes no Transfer options or prices"))
      (is (= [] (:from-options transfer)))
      (is (false? (get-in transfer [:asset :visible?])))
      (is (nil? (:blocked transfer)))
      (is (nil? (:usd-estimate transfer))))))
