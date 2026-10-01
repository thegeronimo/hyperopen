(ns hyperopen.funding.application.transfer-commands-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.collaborators.order :as order-collaborators]
            [hyperopen.runtime.effect-order-contract :as effect-order-contract]))

(def ^:private new-transfer-keys
  [:transfer-from :transfer-to :transfer-asset :transfer-evm :transfer-gas-topup])

(def ^:private evm->spot {:transfer-from :hyperevm :transfer-to :spot})
(def ^:private spot->evm {:transfer-from :spot :transfer-to :hyperevm})

(defn- saved
  [effects]
  (support/saved-modal effects))

(deftest legacy-open-saves-todays-modal-plus-nil-transfer-keys-test
  (let [state {:wallet {:address support/owner}
               :funding-ui {:modal (funding-actions/default-funding-modal-state)}}
        effects (funding-actions/open-funding-transfer-modal state nil "funding-action-transfer"
                                                             {:dex "xyz" :to-perp? false})
        modal (saved effects)]
    (is (= 2 (count effects)))
    (is (= [:effects/load-surface-module :funding-modal] (first effects)))
    (is (every? #(and (contains? modal %) (nil? (get modal %))) new-transfer-keys))
    ;; Today's saved map, literally, so a legacy opener cannot drift.
    (is (= {:open? true :mode :transfer :legacy-kind nil :anchor nil
            :opener-data-role "funding-action-transfer" :focus-return-data-role nil
            :focus-return-token 0 :send-token nil :send-symbol nil :send-prefix-label nil
            :send-max-amount nil :send-max-display nil :send-max-input ""
            :deposit-step :asset-select :deposit-search-input "" :withdraw-step :asset-select
            :withdraw-search-input "" :deposit-selected-asset-key nil
            :deposit-generated-address nil :deposit-generated-signatures nil
            :deposit-generated-asset-key nil :amount-input "" :to-perp? false
            :transfer-dex "xyz" :transfer-destination-address support/owner
            :transfer-from-subaccount "" :destination-input support/owner
            :withdraw-selected-asset-key :usdc :withdraw-generated-address nil
            :hyperunit-lifecycle (funding-actions/default-hyperunit-lifecycle-state)
            :hyperunit-fee-estimate (funding-actions/default-hyperunit-fee-estimate-state)
            :hyperunit-withdrawal-queue (funding-actions/default-hyperunit-withdrawal-queue-state)
            :submitting? false :error nil}
           (apply dissoc modal new-transfer-keys)))))

(deftest open-preset-routes-and-assets-test
  (testing "a Balances row preset keeps its asset and route"
    (let [modal (saved (funding-actions/open-funding-transfer-modal
                        (support/state) nil "balances-move-spot-1-hyperevm"
                        {:from :spot :to :hyperevm :asset support/purr-index}))]
      (is (= [:spot :hyperevm support/purr-index]
             ((juxt :transfer-from :transfer-to :transfer-asset) modal)))))
  (testing "an asset can be named by symbol"
    (is (= support/hype-index
           (:transfer-asset (saved (funding-actions/open-funding-transfer-modal
                                    (support/state) nil nil
                                    {:from "hyperevm" :to "spot" :asset "HYPE"}))))))
  (testing "a preset without an asset selects the first movable one"
    (is (= support/hype-index
           (:transfer-asset (saved (funding-actions/open-funding-transfer-modal
                                    (support/state) nil nil {:from :spot :to :hyperevm}))))))
  (testing "a Perps/Spot preset drives :to-perp?"
    (let [modal (saved (funding-actions/open-funding-transfer-modal
                        (support/state) nil nil {:from :perps :to :spot}))]
      (is (false? (:to-perp? modal)))
      (is (nil? (:transfer-asset modal)))))
  (testing "a HyperEVM -> Core preset fetches the bridge capacity and activation"
    (let [state (update-in (support/state) [:hyperevm :core-account] dissoc support/owner)
          effects (funding-actions/open-funding-transfer-modal
                   state nil nil {:from :hyperevm :to :spot :asset support/purr-index})]
      (is (= [[:effects/fetch-hyperevm-core-bridge-balance 1
               "0x2000000000000000000000000000000000000001"]
              [:effects/fetch-hyperevm-core-account-status support/owner]]
             (drop 2 effects))))))

(deftest set-location-swaps-syncs-and-reselects-test
  (let [state (support/state {:amount-input "5" :error "old"
                              :transfer-evm {:phase :failed}
                              :transfer-gas-topup {:status :failed}})]
    (testing "choosing HyperEVM as the destination clears the draft and picks an asset"
      (let [modal (saved (funding-actions/set-funding-transfer-location state :to :hyperevm))]
        (is (= [:spot :hyperevm] ((juxt :transfer-from :transfer-to) modal)))
        (is (= "" (:amount-input modal)))
        (is (nil? (:error modal)))
        (is (nil? (:transfer-evm modal)))
        (is (nil? (:transfer-gas-topup modal)))
        (is (= support/hype-index (:transfer-asset modal)))))
    (testing "choosing the other side's place swaps and syncs :to-perp?"
      (let [modal (saved (funding-actions/set-funding-transfer-location state "from" "perps"))]
        (is (= [:perps :spot false] ((juxt :transfer-from :transfer-to :to-perp?) modal)))
        (is (nil? (:transfer-asset modal)))))
    (testing "From HyperEVM keeps a still-movable asset and fetches its capacity"
      (let [effects (funding-actions/set-funding-transfer-location
                     (support/state (assoc spot->evm :transfer-asset support/purr-index))
                     :from :hyperevm)]
        (is (= [:hyperevm :spot support/purr-index]
               ((juxt :transfer-from :transfer-to :transfer-asset) (saved effects))))
        (is (= [:effects/fetch-hyperevm-core-bridge-balance 1
                "0x2000000000000000000000000000000000000001"]
               (second effects)))))
    (testing "an asset the new source does not hold is replaced"
      (is (= support/usdc-index
             (:transfer-asset (saved (funding-actions/set-funding-transfer-location
                                      (support/state (assoc spot->evm
                                                            :transfer-asset support/hope-index))
                                      :from :hyperevm))))))
    (testing "invalid places and locked runs are ignored"
      (is (= [] (funding-actions/set-funding-transfer-location state :from :evm)))
      (is (= [] (funding-actions/set-funding-transfer-location
                 (support/state {:transfer-evm {:phase :running}}) :to :hyperevm))))))

(deftest choosing-the-pressed-place-changes-nothing-test
  (testing "the legacy route keeps its typed amount"
    (let [state (support/state {:to-perp? true :amount-input "12"})]
      (is (= [] (funding-actions/set-funding-transfer-location state :from :spot)))
      (is (= [] (funding-actions/set-funding-transfer-location state :to :perps)))))
  (testing "a gas top-up that was sent stays sent, so its fix stays disabled"
    (let [state (support/state (assoc evm->spot :transfer-asset support/purr-index
                                      :amount-input "20"
                                      :transfer-gas-topup {:status :sent :id "topup-1"}))]
      (doseq [[side location] [[:from :hyperevm] [:to :spot] ["from" "hyperevm"]]]
        (is (= [] (funding-actions/set-funding-transfer-location state side location))
            (str side " " location)))))
  (testing "a real change still clears the draft"
    (is (= "" (:amount-input (saved (funding-actions/set-funding-transfer-location
                                     (support/state {:to-perp? true :amount-input "12"})
                                     :to :hyperevm)))))))

(deftest swap-and-select-asset-test
  (is (= [:hyperevm :spot]
         ((juxt :transfer-from :transfer-to)
          (saved (funding-actions/swap-funding-transfer-locations
                  (support/state (assoc spot->evm :transfer-asset support/purr-index)))))))
  (is (= [:hyperevm :spot]
         ((juxt :transfer-from :transfer-to)
          (saved (funding-actions/swap-funding-transfer-locations
                  (support/state {:transfer-from :perps :transfer-to :hyperevm})))))
      "swapping Perps -> HyperEVM never lands on HyperEVM -> Perps")
  (let [effects (funding-actions/select-funding-transfer-asset
                 (support/state (assoc evm->spot :amount-input "3")) support/purr-index)]
    (is (= support/purr-index (:transfer-asset (saved effects))))
    (is (= "" (:amount-input (saved effects))))
    (is (= 2 (count effects)) "EVM -> Core selection fetches the bridge capacity"))
  (is (= [] (funding-actions/select-funding-transfer-asset (support/state) "not-an-index"))))

(deftest legacy-direction-returns-to-the-derived-route-test
  (let [modal (saved (funding-actions/set-funding-transfer-direction
                      (support/state (assoc spot->evm :transfer-asset 1 :amount-input "4"))
                      false))]
    (is (= [nil nil nil false ""]
           ((juxt :transfer-from :transfer-to :transfer-asset :to-perp? :amount-input) modal))))
  (testing "on the legacy route the toggle only sets the direction, as before"
    (doseq [to-perp? [true false]]
      (let [state (support/state {:to-perp? true :amount-input "5" :error "old"})
            modal (saved (funding-actions/set-funding-transfer-direction state to-perp?))]
        (is (= (assoc (get-in state [:funding-ui :modal]) :to-perp? to-perp? :error nil)
               modal)
            (str "to-perp? " to-perp? " keeps the typed amount"))))))

(defn- max-and-percent-cases
  []
  (testing "EVM routes fill the floored MAX string verbatim"
    (is (= "12.499" (:amount-input (saved (funding-actions/set-funding-amount-to-max
                                           (support/state (assoc evm->spot
                                                                 :transfer-asset support/hype-index)))))))
    (is (= [] (funding-actions/set-funding-amount-to-max
               (support/with-evm-entry
                 (support/state (assoc evm->spot :transfer-asset 1 :amount-input "3"))
                 nil)))
        "an unknown MAX keeps what was typed"))
  (testing "the legacy route keeps the USDC input formatting"
    (is (= "500.25" (:amount-input (saved (funding-actions/set-funding-amount-to-max
                                           (support/state {:to-perp? false})))))))
  (testing "percent chips fill a floored fraction of MAX"
    (is (= "6.2495" (:amount-input (saved (funding-actions/set-funding-transfer-amount-percent
                                           (support/state (assoc evm->spot
                                                                 :transfer-asset support/hype-index))
                                           50)))))
    (is (= "375.1875" (:amount-input (saved (funding-actions/set-funding-transfer-amount-percent
                                             (support/state {:to-perp? false})
                                             75)))))))

(deftest max-and-percent-fill-floored-route-amounts-test
  ;; The HyperCore bridge capacity is aged against the clock the composition
  ;; seam supplies, so pin it to the fixture's clock.
  (with-redefs [platform/now-ms (constantly support/now-ms)]
    (max-and-percent-cases)))

(deftest submit-refuses-double-sends-test
  (testing "a submit already running"
    (is (= [] (funding-actions/submit-funding-transfer
               (support/state (assoc evm->spot :transfer-asset 150 :amount-input "1"
                                     :submitting? true))))))
  (testing "a HyperEVM run that may still confirm, or already went through"
    (doseq [phase [:running :pending :succeeded]]
      (is (= [] (funding-actions/submit-funding-transfer
                 (support/state (assoc evm->spot :transfer-asset 150 :amount-input "1"
                                       :transfer-evm {:phase phase}))))
          (str phase))))
  (testing "an unresolved HyperEVM -> Core transaction"
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error]
                                  "A HyperEVM transfer is still confirming. Wait for it to finish before starting another."]]]]
           (funding-actions/submit-funding-transfer
            (assoc-in (support/state (assoc evm->spot :transfer-asset 150 :amount-input "1"))
                      [:hyperevm :in-flight support/owner]
                      {:hashes ["0xabc"] :waiting-receipt? false})))))
  (testing "a subaccount cannot move to HyperEVM"
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error]
                                  "HyperEVM transfers are available for the master account only."]]]]
           (funding-actions/submit-funding-transfer
            (assoc-in (support/state (assoc spot->evm :transfer-asset 1 :amount-input "1"))
                      [:account-context :subaccounts]
                      {:selected-address support/subaccount
                       :rows [{:sub-account-user support/subaccount :master support/owner}]}))))))

(deftest submit-emits-the-route-request-test
  ;; The bridge capacity is aged against the composition seam's clock, so
  ;; pin it to the fixture's.
  (let [effects (with-redefs [platform/now-ms (constantly support/now-ms)]
                  (funding-actions/submit-funding-transfer
                   (support/state (assoc spot->evm :transfer-asset 1 :amount-input "100"))))]
    (is (= [:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                [[:funding-ui :modal :error] nil]]]
           (first effects)))
    (is (= "0x2000000000000000000000000000000000000001"
           (get-in (second effects) [1 :action :destination])))
    (is (= effects (effect-order-contract/assert-action-effect-order!
                    :actions/submit-funding-transfer effects {:phase :test})))))

(deftest try-again-after-a-failed-run-submits-the-same-draft-test
  (let [draft (assoc spot->evm :transfer-asset 1 :amount-input "100")
        submit (fn [state]
                 (with-redefs [platform/now-ms (constantly support/now-ms)]
                   (funding-actions/submit-funding-transfer state)))
        effects (submit (support/state (assoc draft :transfer-evm {:phase :failed
                                                                   :error "Transfer failed: x"})))]
    (is (= [:effects/save [:funding-ui :modal :transfer-evm] nil] (first effects))
        "the failed run is cleared first, so the submit effect can start a new one")
    (is (= (submit (support/state draft))
           (vec (rest effects)))
        "then exactly the submit a fresh draft makes")
    (is (= effects (effect-order-contract/assert-action-effect-order!
                    :actions/submit-funding-transfer effects {:phase :test}))))
  (testing "a failure whose outcome is unknown is never sent again in one click"
    (is (= [] (funding-actions/submit-funding-transfer
               (support/state (assoc spot->evm :transfer-asset 1 :amount-input "100"
                                     :transfer-evm {:phase :failed :maybe-sent? true
                                                    :error "Transfer failed: timeout."}))))
        "the POST threw, so the sendAsset may have applied: Back to edit comes first"))
  (testing "a failed run whose draft no longer submits keeps the run and reports why"
    (let [effects (funding-actions/submit-funding-transfer
                   (support/state (assoc spot->evm :transfer-asset 1 :amount-input "999999"
                                         :transfer-evm {:phase :failed})))]
      (is (not-any? #(= :effects/api-submit-funding-transfer (first %)) effects))
      (is (not-any? #(= [:effects/save [:funding-ui :modal :transfer-evm] nil] %) effects)))))

(deftest gas-topup-submits-once-and-keeps-the-draft-test
  (let [draft (assoc evm->spot :transfer-asset 1 :amount-input "20")
        effects (funding-actions/submit-funding-transfer-gas-topup (support/state draft))]
    (is (= [:effects/save [:funding-ui :modal :transfer-gas-topup] {:status :submitting}]
           (first effects)))
    (is (= :gas-topup (get-in (second effects) [1 :purpose])))
    (is (= "0.05" (get-in (second effects) [1 :action :amount])))
    (is (= 2 (count effects)) "the draft modal itself is not rewritten")
    (is (= effects (effect-order-contract/assert-action-effect-order!
                    :actions/submit-funding-transfer-gas-topup effects {:phase :test}))))
  (testing "ignored while submitting or sent"
    (doseq [status [:submitting :sent]]
      (is (= [] (funding-actions/submit-funding-transfer-gas-topup
                 (support/state {:transfer-gas-topup {:status status}}))))))
  (testing "a failed precondition is reported on the fix, not the draft"
    (is (= [[:effects/save [:funding-ui :modal :transfer-gas-topup]
             {:status :failed :error "You need at least 0.05 HYPE on Spot to add gas."}]]
           (funding-actions/submit-funding-transfer-gas-topup
            (assoc-in (support/state) [:spot :clearinghouse-state :balances]
                      [{:coin "HYPE" :token 150 :total "0.01" :hold "0"}]))))))

(deftest run-controls-test
  (testing "Back to edit keeps the draft but never while a run may confirm"
    (let [modal (saved (funding-actions/reset-funding-transfer-evm
                        (support/state (assoc evm->spot :transfer-asset 1 :amount-input "7"
                                              :transfer-evm {:phase :failed} :submitting? true))))]
      (is (= [nil false "7" 1] ((juxt :transfer-evm :submitting? :amount-input :transfer-asset) modal))))
    (is (= [] (funding-actions/reset-funding-transfer-evm
               (support/state {:transfer-evm {:phase :pending}})))))
  (testing "Try again forgets the wallet's refused switch and reopens a failed run"
    (let [state (-> (support/state (assoc evm->spot :transfer-evm {:phase :failed}))
                    (assoc-in [:wallet :selected-provider-id] "io.rabby")
                    (assoc-in [:hyperevm :wallet-capabilities "io.rabby"] {:chain-switch :unsupported}))
          effects (funding-actions/retry-funding-transfer-capability state)]
      (is (= [:effects/save [:hyperevm :wallet-capabilities] {}] (first effects))
          "the whole map is saved: provider keys are strings, effect paths keywords")
      (is (nil? (:transfer-evm (saved effects))))
      (is (= [:effects/save [:hyperevm :wallet-capabilities]
              {"io.metamask" {:chain-switch :unsupported}}]
             (first (funding-actions/retry-funding-transfer-capability
                     (assoc-in state [:hyperevm :wallet-capabilities "io.metamask"]
                               {:chain-switch :unsupported}))))
          "other providers keep what they proved")))
  (testing "Add to wallet only for a route ending on HyperEVM"
    (is (= [[:effects/wallet-watch-asset {:chain-id "0x3e7"
                                          :address "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
                                          :symbol "PURR"
                                          :decimals 18}]]
           (funding-actions/add-funding-transfer-token-to-wallet
            (support/state (assoc spot->evm :transfer-asset 1)))))
    (is (= [[:effects/wallet-watch-asset {:chain-id "0x3e7" :address nil :symbol "HYPE" :decimals 18}]]
           (funding-actions/add-funding-transfer-token-to-wallet
            (support/state (assoc spot->evm :transfer-asset 150)))))
    (is (= [] (funding-actions/add-funding-transfer-token-to-wallet
               (support/state (assoc evm->spot :transfer-asset 1)))))))

(deftest runtime-wires-every-transfer-command-test
  (let [deps (order-collaborators/action-deps)]
    (doseq [[handler-key f] [[:set-funding-transfer-location funding-actions/set-funding-transfer-location]
                             [:swap-funding-transfer-locations funding-actions/swap-funding-transfer-locations]
                             [:select-funding-transfer-asset funding-actions/select-funding-transfer-asset]
                             [:set-funding-transfer-amount-percent
                              funding-actions/set-funding-transfer-amount-percent]
                             [:submit-funding-transfer-gas-topup
                              funding-actions/submit-funding-transfer-gas-topup]
                             [:reset-funding-transfer-evm funding-actions/reset-funding-transfer-evm]
                             [:retry-funding-transfer-capability
                              funding-actions/retry-funding-transfer-capability]
                             [:add-funding-transfer-token-to-wallet
                              funding-actions/add-funding-transfer-token-to-wallet]]]
      (is (identical? f (get deps handler-key)) (str handler-key)))))

