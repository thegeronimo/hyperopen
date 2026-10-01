(ns hyperopen.views.funding-modal.transfer-evm-view-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.platform :as platform]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.funding-modal :as funding-modal]
            [hyperopen.views.funding-modal.transfer-evm :as transfer-evm]
            [hyperopen.views.funding-modal.transfer-parts :as parts]))

(def ^:private evm->spot {:transfer-from :hyperevm :transfer-to :spot})
(def ^:private spot->evm {:transfer-from :spot :transfer-to :hyperevm})

(def ^:private usdc-steps
  [{:id :switch-network :label "Wallet on HyperEVM" :status :done
    :detail "Chain 999 · trading still works on any network"}
   {:id :approve :label "Approve 1000 USDC" :status :active :detail "Confirm in your wallet"}
   {:id :deposit :label "Deposit to HyperCore Spot" :status :pending
    :detail "Second wallet confirmation"}])

(def ^:private usdc-result
  {:amount "1000" :symbol "USDC" :from :hyperevm :to :spot :owner support/owner
   :started-at-ms support/now-ms :arrived-at-ms nil})

(defn- usdc-run
  [overrides]
  (merge {:phase :running :flow-id "flow-1" :route :evm->core :steps usdc-steps
          :tx-hash nil :tx-url nil :error nil :arrival :idle
          :started-at-ms support/now-ms :result usdc-result}
         overrides))

(defn- usdc-state
  [run]
  (support/state (assoc evm->spot :transfer-asset support/usdc-index :amount-input "1000"
                        :submitting? (= :running (:phase run))
                        :transfer-evm run)))

(defn- shell
  "The whole funding modal for `state`, as the app renders it."
  [state]
  (with-redefs [platform/now-ms (constantly support/now-ms)]
    (funding-modal/funding-modal-view state)))

(defn- by-role
  [node role]
  (hiccup/find-by-data-role node role))

(defn- attrs
  [node]
  (hiccup/node-attrs node))

(defn- hidden?
  [node]
  (contains? (hiccup/node-class-set node) "hidden"))

(defn- text
  [node]
  (str/join "" (hiccup/collect-strings node)))

(defn- buttons
  [node]
  (hiccup/find-all-nodes node #(= :button (first %))))

(deftest progress-view-shows-the-wallet-steps-test
  (let [node (shell (usdc-state (usdc-run {})))
        progress (by-role node "funding-transfer-progress")
        steps (by-role progress "funding-transfer-steps")
        submit (by-role progress "funding-transfer-submit")]
    (is (some? progress))
    (is (nil? (by-role node "funding-transfer-form")) "no editable form while the wallet is asked")
    (is (nil? (by-role node "funding-transfer-amount-input")))
    (is (nil? (by-role node "funding-transfer-swap")))
    (testing "the route and the amount being moved"
      (is (str/includes? (text (by-role progress "funding-transfer-route")) "HyperEVM"))
      (is (= "Moving1,000 USDC to Spot" (text (by-role progress "funding-transfer-progress-heading")))))
    (testing "the active step alone is announced; each step shows its state"
      (is (nil? (:role (attrs steps))) "the whole list is not re-read on every step event")
      (let [announcement (by-role progress "funding-transfer-step-announcement")]
        (is (= ["status" "polite"] ((juxt :role :aria-live) (attrs announcement))))
        (is (contains? (hiccup/node-class-set announcement) "sr-only"))
        (is (= "Step 2 of 3: Approve 1000 USDC. Confirm in your wallet" (text announcement))))
      (is (= [["funding-transfer-step-switch-network" "done"]
              ["funding-transfer-step-approve" "active"]
              ["funding-transfer-step-deposit" "pending"]]
             (mapv (comp (juxt :data-role :data-step-status) attrs)
                   (hiccup/find-all-nodes steps #(= :li (first %))))))
      (is (str/includes? (text (by-role steps "funding-transfer-step-approve")) "Confirm in your wallet"))
      (is (str/includes? (text (by-role steps "funding-transfer-step-approve")) "In progress")
          "the state is spoken, not only drawn"))
    (testing "the only action waits for the wallet; closing is explained"
      (is (true? (:disabled (attrs submit))))
      (is (nil? (:on (attrs submit))))
      (is (= "Waiting for wallet (step 2 of 3)" (text submit)))
      (is (= transfer-evm/close-note (text (by-role progress "funding-transfer-close-note")))))
    (testing "the heading takes focus when progress starts"
      (let [heading (by-role progress "funding-transfer-progress-heading")]
        (is (= "-1" (:tabindex (attrs heading)))
            "the DOM attribute name; Replicant would write `tab-index` verbatim")
        (is (fn? (:replicant/on-render (attrs heading))))))))

(deftest heading-focus-hook-focuses-once-per-view-test
  (let [timeouts (atom [])
        focused (atom 0)
        memory (atom nil)
        node #js {:isConnected true}
        call (fn [hook life-cycle]
               (hook {:replicant/life-cycle life-cycle
                      :replicant/node node
                      :replicant/memory @memory
                      :replicant/remember #(reset! memory %)}))]
    (aset node "focus" (fn [] (swap! focused inc)))
    (with-redefs [platform/set-timeout! (fn [f ms] (swap! timeouts conj ms) (f) :id)]
      (call (parts/focus-when-shown :progress) :replicant.life-cycle/mount)
      (is (= 1 @focused))
      (is (= [0] @timeouts) "after the dialog's own focus handling")
      (call (parts/focus-when-shown :progress) :replicant.life-cycle/update)
      (is (= 1 @focused) "re-rendering the same view keeps the user's focus")
      (call (parts/focus-when-shown :success) :replicant.life-cycle/update)
      (is (= 2 @focused) "a reused heading that now shows another view takes focus again"))))

(deftest pending-view-only-closes-test
  (let [node (shell (usdc-state (usdc-run {:phase :pending :tx-hash "0xabc"
                                           :steps (assoc-in usdc-steps [2 :status] :active)})))
        pending (by-role node "funding-transfer-pending")]
    (is (= "Submitted — confirmation pending"
           (text (by-role pending "funding-transfer-pending-heading"))))
    (is (str/includes? (text pending) "Check the transaction in your wallet or on the explorer"))
    (is (str/includes? (text pending) "reload the page to start another")
        "a replaced or dropped transaction never confirms: say how to get out")
    (is (not (str/includes? (text pending) "finish on its own")))
    (is (= "https://hyperevmscan.io/tx/0xabc"
           (:href (attrs (by-role pending "funding-transfer-explorer-link")))))
    (is (= ["Close"] (mapv text (buttons pending)))
        "a transaction may still land: nothing here can send it again")
    (is (= {:click [[:actions/close-funding-modal]]}
           (:on (attrs (by-role pending "funding-transfer-done")))))))

(deftest failed-view-offers-back-to-edit-and-try-again-test
  (let [failed-run (usdc-run {:phase :failed :error "Transfer rejected in wallet."
                              :steps (assoc-in usdc-steps [1 :status] :failed)})
        failed (by-role (shell (usdc-state failed-run)) "funding-transfer-failed")]
    (is (= "alert" (:role (attrs (by-role failed "funding-transfer-error")))))
    (is (= "Transfer rejected in wallet." (text (by-role failed "funding-transfer-error"))))
    (is (= "failed" (:data-step-status (attrs (by-role failed "funding-transfer-step-approve")))))
    (is (= {:click [[:actions/reset-funding-transfer-evm]]}
           (:on (attrs (by-role failed "funding-transfer-back-to-edit")))))
    (is (= {:click [[:actions/submit-funding-transfer]]}
           (:on (attrs (by-role failed "funding-transfer-try-again"))))
        "nothing blocks the draft, so Try again sends it again")
    (is (hidden? (by-role failed "funding-transfer-explorer-link-slot"))
        "no transaction was sent, so there is nothing to look up"))
  (testing "a failed run with a transaction links it"
    (let [failed (by-role (shell (usdc-state (usdc-run {:phase :failed :error "Transaction reverted on HyperEVM."
                                                        :tx-hash "0xfeed"})))
                          "funding-transfer-failed")]
      (is (not (hidden? (by-role failed "funding-transfer-explorer-link-slot"))))
      (is (= "https://hyperevmscan.io/tx/0xfeed"
             (:href (attrs (by-role failed "funding-transfer-explorer-link")))))))
  (testing "a failure that may have moved funds: only Back to edit"
    (let [failed (by-role (shell (support/state (assoc spot->evm :transfer-asset support/purr-index
                                                       :amount-input "100"
                                                       :transfer-evm (usdc-run {:phase :failed
                                                                                :route :core->evm
                                                                                :maybe-sent? true
                                                                                :error "Transfer failed: timeout."}))))
                          "funding-transfer-failed")
          try-again (by-role failed "funding-transfer-try-again")]
      (is (hidden? try-again))
      (is (nil? (:on (attrs try-again))))
      (is (= {:click [[:actions/reset-funding-transfer-evm]]}
             (:on (attrs (by-role failed "funding-transfer-back-to-edit")))))))
  (testing "a wallet that refused the network switch: Try again forgets that"
    (let [state (assoc-in (usdc-state (usdc-run {:phase :failed :error "switch"}))
                          [:hyperevm :wallet-capabilities "default"] {:chain-switch :unsupported})
          try-again (by-role (shell state) "funding-transfer-try-again")]
      (is (= {:click [[:actions/retry-funding-transfer-capability]]} (:on (attrs try-again))))
      (is (not (hidden? try-again)))))
  (testing "a send answered without a hash: no Try again until it is resolved"
    (let [state (assoc-in (usdc-state (usdc-run {:phase :failed :error "no hash"}))
                          [:hyperevm :in-flight support/owner]
                          {:status :unconfirmed :waiting-receipt? false :hashes []})
          failed (by-role (shell state) "funding-transfer-failed")
          try-again (by-role failed "funding-transfer-try-again")]
      (is (hidden? try-again))
      (is (nil? (:on (attrs try-again))))
      (is (some? (:on (attrs (by-role failed "funding-transfer-back-to-edit"))))))))

(deftest returning-to-the-form-focuses-its-amount-test
  (let [timeouts (atom [])
        focused (atom 0)
        memory (atom nil)
        input #js {}
        node #js {:isConnected true}
        call (fn [view-id life-cycle]
               ((parts/view-root-hook view-id)
                {:replicant/life-cycle life-cycle
                 :replicant/node node
                 :replicant/memory @memory
                 :replicant/remember #(reset! memory %)}))]
    (aset input "focus" (fn [] (swap! focused inc)))
    (aset node "querySelector" (fn [selector]
                                 (when (= "#funding-transfer-amount-input-field" selector) input)))
    (with-redefs [platform/set-timeout! (fn [f ms] (swap! timeouts conj ms) (f) :id)]
      (call :form :replicant.life-cycle/mount)
      (is (= 0 @focused) "a freshly opened form is left to the dialog")
      (call :form :replicant.life-cycle/update)
      (is (= 0 @focused) "re-rendering the form keeps the user's focus")
      (call :failed :replicant.life-cycle/update)
      (is (= 0 @focused))
      (call :form :replicant.life-cycle/update)
      (is (= 1 @focused) "Back to edit lands in the amount, not on the close button")
      (is (= [0] @timeouts) "after the dialog's own focus handling")
      (call :form :replicant.life-cycle/update)
      (is (= 1 @focused)))))

(defn- purr-success-state
  [run-overrides]
  (support/state (assoc spot->evm :transfer-asset support/purr-index :amount-input "100"
                        :transfer-evm (merge {:phase :succeeded :flow-id "flow-2" :route :core->evm
                                              :steps [{:id :sign :label "Move 100 PURR to HyperEVM"
                                                       :status :done :detail "Confirmed"}]
                                              :tx-hash nil :error nil :arrival :arriving
                                              :result {:amount "100" :symbol "PURR" :from :spot
                                                       :to :hyperevm :owner support/owner
                                                       :started-at-ms support/now-ms
                                                       :arrived-at-ms nil}}
                                             run-overrides))))

(deftest success-view-follows-the-arrival-test
  (testing "arriving"
    (let [success (by-role (shell (purr-success-state {})) "funding-transfer-success")]
      (is (= "arriving" (:data-arrival (attrs success))))
      (is (= "Sent 100 PURR to HyperEVM" (text (by-role success "funding-transfer-success-heading"))))
      (is (= ["status" "polite"] ((juxt :role :aria-live) (attrs (by-role success "funding-transfer-arrival")))))
      (is (= "Arriving on HyperEVM… usually a few seconds."
             (text (by-role success "funding-transfer-arrival"))))))
  (testing "arrived, with the time it took (from when it went through) and the user's wallet"
    (let [success (by-role (shell (purr-success-state {:arrival :arrived
                                                       :result {:amount "100" :symbol "PURR"
                                                                :from :spot :to :hyperevm
                                                                :started-at-ms (- support/now-ms 12000)
                                                                :sent-at-ms support/now-ms
                                                                :arrived-at-ms (+ support/now-ms 3200)}}))
                           "funding-transfer-success")]
      (is (= "100 PURR is on HyperEVM" (text (by-role success "funding-transfer-success-heading"))))
      (is (= "Arrived in 3 seconds at your wallet 0x123…5678"
             (text (by-role success "funding-transfer-arrival"))))
      (is (some? (by-role (by-role success "funding-transfer-result-from") "location-chip-spot")))
      (is (some? (by-role (by-role success "funding-transfer-result-to") "location-chip-hyperevm")))
      (is (= "9,800.00 PURR" (last (hiccup/collect-strings (by-role success "funding-transfer-result-from")))))))
  (testing "slow"
    (is (= "Taking longer than usual — check Account Activity."
           (text (by-role (shell (purr-success-state {:arrival :slow})) "funding-transfer-arrival")))))
  (testing "actions: add to wallet, history, explorer and done"
    (let [success (by-role (shell (purr-success-state {:tx-hash "0xdef"})) "funding-transfer-success")]
      (is (not (hidden? (by-role success "funding-transfer-add-to-wallet-box"))))
      (is (= {:click [[:actions/add-funding-transfer-token-to-wallet]]}
             (:on (attrs (by-role success "funding-transfer-add-to-wallet")))))
      (is (= "View in Account Activity" (text (by-role success "funding-transfer-view-history"))))
      (is (= {:click [[:actions/close-funding-modal]
                      [:actions/set-portfolio-account-info-tab :deposits-withdrawals]
                      [:actions/set-portfolio-account-activity-sub-tab :account-transfers]
                      [:actions/navigate "/portfolio"]]}
             (:on (attrs (by-role success "funding-transfer-view-history"))))
          "the Account Activity tab, on the sub-tab that lists HyperEVM moves")
      (is (= "https://hyperevmscan.io/tx/0xdef"
             (:href (attrs (by-role success "funding-transfer-explorer-link")))))
      (is (= {:click [[:actions/close-funding-modal]]}
             (:on (attrs (by-role success "funding-transfer-done")))))))
  (testing "a move to Spot offers no Add to wallet"
    (let [success (by-role (shell (usdc-state (usdc-run {:phase :succeeded :arrival :arrived})))
                           "funding-transfer-success")]
      (is (hidden? (by-role success "funding-transfer-add-to-wallet-box")))
      (is (nil? (:on (attrs (by-role success "funding-transfer-add-to-wallet")))))
      (is (true? (:disabled (attrs (by-role success "funding-transfer-add-to-wallet"))))
          "never focusable while its slot is hidden"))))

(deftest run-views-render-from-the-real-view-model-test
  (doseq [[phase kind role] [[:running :transfer/progress "funding-transfer-progress"]
                             [:pending :transfer/pending "funding-transfer-pending"]
                             [:failed :transfer/failed "funding-transfer-failed"]
                             [:succeeded :transfer/success "funding-transfer-success"]]]
    (let [state (usdc-state (usdc-run {:phase phase}))
          view-model (with-redefs [platform/now-ms (constantly support/now-ms)]
                       (funding-actions/funding-modal-view-model state))]
      (is (= kind (get-in view-model [:content :kind])) (str phase))
      (is (some? (by-role (shell state) role)) (str phase)))))
