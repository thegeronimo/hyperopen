(ns hyperopen.funding.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.actions :as funding-actions]))

(defn- base-state
  []
  {:wallet {:address "0x1234567890abcdef1234567890abcdef12345678"}
   :spot {:clearinghouse-state {:balances [{:coin "USDC" :available "12.5" :total "12.5" :hold "0"}]}}
   :webdata2 {:clearinghouseState {:availableToWithdraw "8.5"
                                   :marginSummary {:accountValue "20"
                                                   :totalMarginUsed "11.5"}}}
   :funding-ui {:modal (funding-actions/default-funding-modal-state)}})

(defn- expected-open-modal
  [mode & {:keys [legacy-kind] :or {legacy-kind nil}}]
  {:open? true
   :mode mode
   :legacy-kind legacy-kind
   :anchor nil
   :opener-data-role nil
   :focus-return-data-role nil
   :focus-return-token 0
   :send-token nil
   :send-symbol nil
   :send-prefix-label nil
   :send-max-amount nil
   :send-max-display nil
   :send-max-input ""
   :deposit-step :asset-select
   :deposit-search-input ""
   :withdraw-step :asset-select
   :withdraw-search-input ""
   :deposit-selected-asset-key nil
   :deposit-generated-address nil
   :deposit-generated-signatures nil
   :deposit-generated-asset-key nil
   :amount-input ""
   :to-perp? true
   :destination-input "0x1234567890abcdef1234567890abcdef12345678"
   :withdraw-selected-asset-key :usdc
   :withdraw-generated-address nil
   :hyperunit-lifecycle (funding-actions/default-hyperunit-lifecycle-state)
   :hyperunit-fee-estimate (funding-actions/default-hyperunit-fee-estimate-state)
   :hyperunit-withdrawal-queue (funding-actions/default-hyperunit-withdrawal-queue-state)
   :submitting? false
   :error nil})

(deftest open-funding-modal-actions-set-mode-and-open-state-test
  (let [state (base-state)]
    (is (= [[:effects/save [:funding-ui :modal]
             (assoc (expected-open-modal :send)
                    :destination-input ""
                    :send-token "xyz:GOLD"
                    :send-symbol "GOLD"
                    :send-prefix-label "xyz"
                    :send-max-amount 4.25
                    :send-max-display "4.250000"
                    :send-max-input "4.250000")]]
           (funding-actions/open-funding-send-modal state
                                                   {:token "xyz:GOLD"
                                                    :symbol "GOLD"
                                                    :prefix-label "xyz"
                                                    :max-amount "4.25"
                                                    :max-display "4.250000"
                                                    :max-input "4.250000"})))
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :deposit)]
            [:effects/api-fetch-hyperunit-fee-estimate]]
           (funding-actions/open-funding-deposit-modal state)))
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :transfer)]]
           (funding-actions/open-funding-transfer-modal state)))
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :withdraw)]
            [:effects/api-fetch-hyperunit-withdrawal-queue]
            [:effects/api-fetch-hyperunit-fee-estimate]]
           (funding-actions/open-funding-withdraw-modal state)))))

(deftest open-funding-modal-actions-normalize-anchor-bounds-test
  (let [state (base-state)
        anchor {:left "940"
                :right 1220
                :top "502.5"
                :bottom 536
                :width 280
                :height "34"
                :viewport-width 1600
                :viewport-height "900"}]
    (is (= [[:effects/save [:funding-ui :modal]
             (assoc (expected-open-modal :deposit)
                    :anchor {:left 940
                             :right 1220
                             :top 502.5
                             :bottom 536
                             :width 280
                             :height 34
                             :viewport-width 1600
                             :viewport-height 900})]
            [:effects/api-fetch-hyperunit-fee-estimate]]
           (funding-actions/open-funding-deposit-modal state anchor)))))

(deftest open-funding-send-modal-normalizes-js-anchor-bounds-test
  (let [state (base-state)
        anchor #js {:left "16"
                    :right 414
                    :top "760"
                    :bottom 800
                    :width 398
                    :height "40"
                    "viewport-width" 430
                    "viewport-height" "932"}]
    (is (= [[:effects/save [:funding-ui :modal]
             (assoc (expected-open-modal :send)
                    :destination-input ""
                    :anchor {:left 16
                             :right 414
                             :top 760
                             :bottom 800
                             :width 398
                             :height 40
                    :viewport-width 430
                    :viewport-height 932})]]
           (funding-actions/open-funding-send-modal state nil anchor)))))

(deftest close-funding-modal-resets-state-and-restores-focus-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        (assoc (expected-open-modal :deposit)
                               :opener-data-role "funding-action-deposit"
                               :focus-return-token 2
                               :deposit-selected-asset-key :usdc
                               :amount-input "25"))]
    (is (= [[:effects/save [:funding-ui :modal]
             (assoc (funding-actions/default-funding-modal-state)
                    :focus-return-data-role "funding-action-deposit"
                    :focus-return-token 3)]
            [:effects/restore-dialog-focus]]
           (funding-actions/close-funding-modal state)))))

(deftest open-funding-modal-actions-store-opener-data-role-test
  (let [state (base-state)]
    (is (= [[:effects/save [:funding-ui :modal]
             (assoc (expected-open-modal :deposit)
                    :opener-data-role "funding-action-deposit")]
            [:effects/api-fetch-hyperunit-fee-estimate]]
           (funding-actions/open-funding-deposit-modal state nil "funding-action-deposit")))
    (is (= [[:effects/save [:funding-ui :modal]
             (assoc (expected-open-modal :send)
                    :destination-input ""
                    :opener-data-role "portfolio-action-send")]]
           (funding-actions/open-funding-send-modal state nil nil "portfolio-action-send")))))

(deftest set-funding-modal-compat-preserves-legacy-fallback-test
  (let [state (base-state)]
    (is (= [[:effects/save [:funding-ui :modal]
             (assoc (expected-open-modal :send)
                    :destination-input "")]]
           (funding-actions/set-funding-modal-compat state :send)))
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :legacy :legacy-kind :history)]]
           (funding-actions/set-funding-modal-compat state :history)))))

(deftest submit-funding-send-validates-and-emits-api-effect-test
  (let [state-idle (assoc-in (base-state)
                             [:funding-ui :modal]
                             {:open? true
                              :mode :send
                              :send-token "USDC"
                              :send-symbol "USDC"
                              :send-max-amount 12.5
                              :send-max-display "12.500000"
                              :send-max-input "12.500000"
                              :destination-input ""
                              :amount-input ""})
        state-invalid (assoc-in (base-state)
                                [:funding-ui :modal]
                                {:open? true
                                 :mode :send
                                 :send-token "USDC"
                                 :send-symbol "USDC"
                                 :send-max-amount 12.5
                                 :send-max-display "12.500000"
                                 :send-max-input "12.500000"
                                 :destination-input "abc"
                                 :amount-input "2.25"})
        state-valid (assoc-in (base-state)
                              [:funding-ui :modal]
                              {:open? true
                               :mode :send
                               :send-token "USDC"
                               :send-symbol "USDC"
                               :send-max-amount 12.5
                               :send-max-display "12.500000"
                               :send-max-input "12.500000"
                               :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                               :amount-input "2.25"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] nil]]]]
           (funding-actions/submit-funding-send state-idle)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Enter a valid destination address."]]]]
           (funding-actions/submit-funding-send state-invalid)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-send
             {:action {:type "sendAsset"
                       :destination "0x1234567890abcdef1234567890abcdef12345678"
                       :sourceDex "spot"
                       :destinationDex "spot"
                       :token "USDC"
                       :amount "2.25"
                       :fromSubAccount ""}}]]
           (funding-actions/submit-funding-send state-valid)))))

(deftest submit-funding-transfer-validates-and-emits-api-effect-test
  (let [state-invalid (assoc-in (base-state)
                                [:funding-ui :modal]
                                {:open? true
                                 :mode :transfer
                                 :to-perp? true
                                 :amount-input ""})
        state-valid (assoc-in (base-state)
                              [:funding-ui :modal]
                              {:open? true
                               :mode :transfer
                               :to-perp? false
                               :amount-input "2.25"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Enter a valid amount."]]]]
           (funding-actions/submit-funding-transfer state-invalid)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-transfer
             {:action {:type "usdClassTransfer"
                       :amount "2.25"
                       :toPerp false}}]]
           (funding-actions/submit-funding-transfer state-valid)))))

(deftest submit-funding-actions-block-mutations-while-spectate-mode-active-test
  (let [blocked-state (assoc (base-state)
                             :account-context {:spectate-mode {:active? true
                                                            :address "0x1234567890abcdef1234567890abcdef12345678"}})
        blocked-message account-context/spectate-mode-read-only-message]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] blocked-message]]]]
           (funding-actions/submit-funding-send
            (assoc-in blocked-state
                      [:funding-ui :modal]
                      {:open? true
                       :mode :send
                       :send-token "USDC"
                       :send-symbol "USDC"
                       :send-max-amount 12.5
                       :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                       :amount-input "2.25"}))))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] blocked-message]]]]
           (funding-actions/submit-funding-transfer
            (assoc-in blocked-state
                      [:funding-ui :modal]
                      {:open? true
                       :mode :transfer
                       :to-perp? false
                       :amount-input "2.25"}))))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] blocked-message]]]]
           (funding-actions/submit-funding-withdraw
            (assoc-in blocked-state
                      [:funding-ui :modal]
                      {:open? true
                       :mode :withdraw
                       :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                       :amount-input "6.5"}))))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] blocked-message]]]]
           (funding-actions/submit-funding-deposit
            (assoc-in blocked-state
                      [:funding-ui :modal]
                      {:open? true
                       :mode :deposit
                       :deposit-step :amount-entry
                       :deposit-selected-asset-key :usdc
                       :amount-input "6"}))))))

(deftest submit-funding-withdraw-validates-destination-and-minimum-test
  (let [invalid-destination (assoc-in (base-state)
                                      [:funding-ui :modal]
                                      {:open? true
                                       :mode :withdraw
                                       :destination-input "abc"
                                       :amount-input "10"})
        below-minimum (assoc-in (base-state)
                                [:funding-ui :modal]
                                {:open? true
                                 :mode :withdraw
                                 :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                                 :amount-input "1"})
        valid (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :withdraw
                         :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                         :amount-input "6.5"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Enter a valid destination address."]]]]
           (funding-actions/submit-funding-withdraw invalid-destination)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Minimum withdrawal is 5 USDC."]]]]
           (funding-actions/submit-funding-withdraw below-minimum)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-withdraw
             {:action {:type "withdraw3"
                       :amount "6.5"
                       :destination "0x1234567890abcdef1234567890abcdef12345678"}}]]
           (funding-actions/submit-funding-withdraw valid)))))

(deftest submit-funding-withdraw-supports-btc-hyperunit-send-asset-flow-test
  (let [state (-> (base-state)
                  (assoc-in [:spot :clearinghouse-state :balances]
                            [{:coin "USDC" :available "12.5" :total "12.5" :hold "0"}
                             {:coin "BTC" :available "1.25" :total "1.25" :hold "0"}])
                  (assoc-in [:funding-ui :modal]
                            {:open? true
                             :mode :withdraw
                             :withdraw-selected-asset-key :btc
                             :destination-input "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                             :amount-input "0.25"}))]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-withdraw
             {:action {:type "hyperunitSendAssetWithdraw"
                       :asset "btc"
                       :token "BTC"
                       :amount "0.25"
                       :destination "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                       :destinationChain "bitcoin"
                       :network "Bitcoin"}}]]
           (funding-actions/submit-funding-withdraw state)))))

(deftest submit-funding-withdraw-enforces-hyperunit-asset-minimum-test
  (let [state (-> (base-state)
                  (assoc-in [:spot :clearinghouse-state :balances]
                            [{:coin "USDC" :available "12.5" :total "12.5" :hold "0"}
                             {:coin "BTC" :available "1.25" :total "1.25" :hold "0"}])
                  (assoc-in [:funding-ui :modal]
                            {:open? true
                             :mode :withdraw
                             :withdraw-selected-asset-key :btc
                             :destination-input "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                             :amount-input "0.0001"}))]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Minimum withdrawal is 0.0003 BTC."]]]]
           (funding-actions/submit-funding-withdraw state)))))

(deftest submit-funding-deposit-validates-step-asset-and-amount-test
  (let [no-asset (assoc-in (base-state)
                           [:funding-ui :modal]
                           {:open? true
                            :mode :deposit
                            :deposit-step :amount-entry
                            :deposit-selected-asset-key nil
                            :amount-input "10"})
        invalid-amount (assoc-in (base-state)
                                 [:funding-ui :modal]
                                 {:open? true
                                  :mode :deposit
                                  :deposit-step :amount-entry
                                  :deposit-selected-asset-key :usdc
                                  :amount-input ""})
        below-minimum (assoc-in (base-state)
                                [:funding-ui :modal]
                                {:open? true
                                 :mode :deposit
                                 :deposit-step :amount-entry
                                 :deposit-selected-asset-key :usdc
                                 :amount-input "1"})
        valid-mainnet (assoc-in (base-state)
                                [:funding-ui :modal]
                                {:open? true
                                 :mode :deposit
                                 :deposit-step :amount-entry
                                 :deposit-selected-asset-key :usdc
                                 :amount-input "5.25"})
        valid-testnet (assoc-in (assoc (base-state)
                                       :wallet {:address "0x1234567890abcdef1234567890abcdef12345678"
                                                :chain-id "0x66eee"})
                                [:funding-ui :modal]
                                {:open? true
                                 :mode :deposit
                                 :deposit-step :amount-entry
                                 :deposit-selected-asset-key :usdc
                                 :amount-input "7"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Select an asset to deposit."]]]]
           (funding-actions/submit-funding-deposit no-asset)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Enter a valid amount."]]]]
           (funding-actions/submit-funding-deposit invalid-amount)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Minimum deposit is 5 USDC."]]]]
           (funding-actions/submit-funding-deposit below-minimum)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "bridge2Deposit"
                       :asset "usdc"
                       :amount "5.25"
                       :chainId "0xa4b1"}}]]
           (funding-actions/submit-funding-deposit valid-mainnet)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "bridge2Deposit"
                       :asset "usdc"
                       :amount "7"
                       :chainId "0x66eee"}}]]
           (funding-actions/submit-funding-deposit valid-testnet)))))

(deftest set-funding-modal-field-selecting-deposit-asset-advances-step-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :asset-select
                         :deposit-selected-asset-key nil
                         :deposit-generated-address "bc1old"
                         :deposit-generated-signatures [{:r "0x1"}]
                         :deposit-generated-asset-key :btc
                         :amount-input "12"})]
    (let [[effect-id path next-modal]
          (first (funding-actions/set-funding-modal-field state
                                                          [:deposit-selected-asset-key]
                                                          :usdc))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= :usdc (:deposit-selected-asset-key next-modal)))
      (is (= :amount-entry (:deposit-step next-modal)))
      (is (= "" (:amount-input next-modal)))
      (is (nil? (:deposit-generated-address next-modal)))
      (is (nil? (:deposit-generated-signatures next-modal)))
      (is (nil? (:deposit-generated-asset-key next-modal))))))

(deftest set-funding-modal-field-amount-input-normalizes-grouping-separators-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :usdc
                         :amount-input ""})
        [_ _ next-modal]
        (first (funding-actions/set-funding-modal-field state
                                                        [:amount-input]
                                                        "100,000.25"))]
    (is (= "100000.25" (:amount-input next-modal)))))

(deftest set-funding-modal-field-clears-hyperunit-lifecycle-on-critical-input-change-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :btc
                         :amount-input "10"
                         :hyperunit-lifecycle {:direction :deposit
                                               :asset-key :btc
                                               :operation-id "op_123"
                                               :state :source-confirmed
                                               :status :pending
                                               :source-tx-confirmations 1
                                               :destination-tx-confirmations nil
                                               :position-in-withdraw-queue nil
                                               :destination-tx-hash nil
                                               :state-next-at 1000
                                               :last-updated-ms 2000
                                               :error nil}})
        [_ _ amount-modal] (first (funding-actions/set-funding-modal-field
                                   state
                                   [:amount-input]
                                   "11"))
        [_ _ destination-modal] (first (funding-actions/set-funding-modal-field
                                        state
                                        [:destination-input]
                                        "0x1234567890abcdef1234567890abcdef12345678"))
        [_ _ withdraw-asset-modal] (first (funding-actions/set-funding-modal-field
                                           state
                                           [:withdraw-selected-asset-key]
                                           :eth))]
    (is (= (funding-actions/default-hyperunit-lifecycle-state)
           (:hyperunit-lifecycle amount-modal)))
    (is (= (funding-actions/default-hyperunit-lifecycle-state)
           (:hyperunit-lifecycle destination-modal)))
    (is (= (funding-actions/default-hyperunit-lifecycle-state)
           (:hyperunit-lifecycle withdraw-asset-modal)))))

(deftest set-funding-modal-field-refreshes-hyperunit-fee-estimate-and-queue-on-withdraw-asset-change-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :withdraw
                         :withdraw-selected-asset-key :usdc
                         :amount-input "10"})
        effects (funding-actions/set-funding-modal-field state
                                                         [:withdraw-selected-asset-key]
                                                         :btc)]
    (is (= :effects/save (ffirst effects)))
    (is (= [:effects/api-fetch-hyperunit-fee-estimate]
           (second effects)))
    (is (= [:effects/api-fetch-hyperunit-withdrawal-queue]
           (nth effects 2)))))

(deftest set-hyperunit-lifecycle-normalizes-snapshot-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :hyperunit-lifecycle (funding-actions/default-hyperunit-lifecycle-state)})
        [effect-id path next-modal] (first (funding-actions/set-hyperunit-lifecycle
                                            state
                                            {:direction "deposit"
                                             :asset-key "btc"
                                             :operation-id "op_123"
                                             :state "SOURCE_CONFIRMED"
                                             :status "IN_PROGRESS"
                                             :source-tx-confirmations "2"
                                             :destination-tx-confirmations 0
                                             :position-in-withdraw-queue "5"
                                             :destination-tx-hash "0xabc123"
                                             :state-next-at "1700000000000"
                                             :last-updated-ms 1700000000123
                                             :error "temporary issue"}))]
    (is (= :effects/save effect-id))
    (is (= [:funding-ui :modal] path))
    (is (= {:direction :deposit
            :asset-key :btc
            :operation-id "op_123"
            :state :source-confirmed
            :status :in-progress
            :source-tx-confirmations 2
            :destination-tx-confirmations 0
            :position-in-withdraw-queue 5
            :destination-tx-hash "0xabc123"
            :state-next-at 1700000000000
            :last-updated-ms 1700000000123
            :error "temporary issue"}
           (:hyperunit-lifecycle next-modal)))))

(deftest clear-hyperunit-lifecycle-resets-to-default-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :hyperunit-lifecycle {:direction :deposit
                                               :asset-key :btc
                                               :operation-id "op_123"
                                               :state :done
                                               :status :done
                                               :source-tx-confirmations 6
                                               :destination-tx-confirmations 1
                                               :position-in-withdraw-queue nil
                                               :destination-tx-hash "0xabc"
                                               :state-next-at nil
                                               :last-updated-ms 1700
                                               :error nil}})
        [_ _ next-modal] (first (funding-actions/clear-hyperunit-lifecycle state))]
    (is (= (funding-actions/default-hyperunit-lifecycle-state)
           (:hyperunit-lifecycle next-modal)))))

(deftest set-hyperunit-lifecycle-error-updates-error-field-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :hyperunit-lifecycle {:direction :deposit
                                               :asset-key :btc
                                               :operation-id "op_123"
                                               :state :source-confirmed
                                               :status :pending
                                               :source-tx-confirmations 1
                                               :destination-tx-confirmations nil
                                               :position-in-withdraw-queue nil
                                               :destination-tx-hash nil
                                               :state-next-at nil
                                               :last-updated-ms 1700
                                               :error nil}})
        [_ _ next-modal] (first (funding-actions/set-hyperunit-lifecycle-error
                                 state
                                 "  temporary API issue  "))]
    (is (= "temporary API issue"
           (get-in next-modal [:hyperunit-lifecycle :error])))))

(deftest submit-funding-deposit-accepts-comma-grouped-amount-input-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :usdc
                         :amount-input "100,000"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "bridge2Deposit"
                       :asset "usdc"
                       :amount "100000"
                       :chainId "0xa4b1"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-usdt-route-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :usdt
                         :amount-input "10"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "lifiUsdtToUsdcBridge2Deposit"
                       :asset "usdt"
                       :amount "10"
                       :chainId "0xa4b1"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-usdh-route-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :usdh
                         :amount-input "10"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "acrossUsdcToUsdhDeposit"
                       :asset "usdh"
                       :amount "10"
                       :chainId "0xa4b1"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-btc-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :btc
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "btc"
                       :fromChain "bitcoin"
                       :network "Bitcoin"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-eth-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :eth
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "eth"
                       :fromChain "ethereum"
                       :network "Ethereum"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-sol-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :sol
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "sol"
                       :fromChain "solana"
                       :network "Solana"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-2z-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :2z
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "2z"
                       :fromChain "solana"
                       :network "Solana"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-bonk-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :bonk
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "bonk"
                       :fromChain "solana"
                       :network "Solana"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-ena-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :ena
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "ena"
                       :fromChain "ethereum"
                       :network "Ethereum"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-fart-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :fart
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "fart"
                       :fromChain "solana"
                       :network "Solana"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-mon-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :mon
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "mon"
                       :fromChain "monad"
                       :network "Monad"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-pump-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :pump
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "pump"
                       :fromChain "solana"
                       :network "Solana"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-spxs-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :spxs
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "spxs"
                       :fromChain "solana"
                       :network "Solana"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-xpl-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :xpl
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "xpl"
                       :fromChain "plasma"
                       :network "Plasma"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-rejects-unknown-asset-selection-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :does-not-exist
                         :amount-input "10"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Select an asset to deposit."]]]]
           (funding-actions/submit-funding-deposit state)))))

(deftest funding-modal-view-model-includes-multi-asset-deposit-catalog-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :asset-select
                         :deposit-search-input ""
                         :deposit-selected-asset-key nil
                         :amount-input ""})
        view-model (funding-actions/funding-modal-view-model state)
        asset-keys (set (map :key (:deposit-assets view-model)))]
    (is (contains? asset-keys :usdc))
    (is (contains? asset-keys :usdt))
    (is (contains? asset-keys :btc))
    (is (contains? asset-keys :xpl))
    (is (contains? asset-keys :usdh))))

(deftest funding-modal-view-model-exposes-normalized-hyperunit-lifecycle-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :btc
                         :hyperunit-lifecycle {:direction "deposit"
                                               :asset-key "btc"
                                               :operation-id "op_123"
                                               :state "source_confirmed"
                                               :status "in_progress"
                                               :source-tx-confirmations "2"
                                               :destination-tx-confirmations nil
                                               :position-in-withdraw-queue nil
                                               :destination-tx-hash "0xabc"
                                               :state-next-at "1700000000000"
                                               :last-updated-ms "1700000001000"
                                               :error nil}})
        view-model (funding-actions/funding-modal-view-model state)]
    (is (= {:direction :deposit
            :asset-key :btc
            :operation-id "op_123"
            :state :source-confirmed
            :status :in-progress
            :source-tx-confirmations 2
            :destination-tx-confirmations nil
            :position-in-withdraw-queue nil
            :destination-tx-hash "0xabc"
            :state-next-at 1700000000000
            :last-updated-ms 1700000001000
            :error nil}
           (:hyperunit-lifecycle view-model)))
    (is (= false
           (:hyperunit-lifecycle-terminal? view-model)))
    (is (nil? (:hyperunit-lifecycle-outcome view-model)))
    (is (= "https://app.hyperliquid.xyz/explorer/tx/0xabc"
           (:hyperunit-lifecycle-destination-explorer-url view-model)))))

(deftest funding-modal-view-model-derives-terminal-withdraw-outcome-and-explorer-links-test
  (let [state (-> (base-state)
                  (assoc-in [:spot :clearinghouse-state :balances]
                            [{:coin "USDC" :available "12.5" :total "12.5" :hold "0"}
                             {:coin "BTC" :available "1.25" :total "1.25" :hold "0"}])
                  (assoc-in [:funding-ui :modal]
                            {:open? true
                             :mode :withdraw
                             :withdraw-selected-asset-key :btc
                             :amount-input "0.25"
                             :destination-input "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                             :hyperunit-lifecycle {:direction :withdraw
                                                   :asset-key :btc
                                                   :operation-id "op_btc_1"
                                                   :state :failed
                                                   :status :terminal
                                                   :source-tx-confirmations 1
                                                   :destination-tx-confirmations nil
                                                   :position-in-withdraw-queue 4
                                                   :destination-tx-hash "0000abc123"
                                                   :state-next-at nil
                                                   :last-updated-ms 1700000000001
                                                   :error "Bridge broadcast failed"}
                             :hyperunit-withdrawal-queue {:status :ready
                                                          :by-chain {"bitcoin" {:chain "bitcoin"
                                                                                :withdrawal-queue-length 9
                                                                                :last-withdraw-queue-operation-tx-id "0xqueue123"}}
                                                          :requested-at-ms 3
                                                          :updated-at-ms 4
                                                          :error nil}}))
        view-model (funding-actions/funding-modal-view-model state)]
    (is (= true (:hyperunit-lifecycle-terminal? view-model)))
    (is (= :failure (:hyperunit-lifecycle-outcome view-model)))
    (is (= "Needs Attention" (:hyperunit-lifecycle-outcome-label view-model)))
    (is (seq (:hyperunit-lifecycle-recovery-hint view-model)))
    (is (= "https://mempool.space/tx/0000abc123"
           (:hyperunit-lifecycle-destination-explorer-url view-model)))
    (is (= "https://mempool.space/tx/0xqueue123"
           (:withdraw-queue-last-operation-explorer-url view-model)))))

(deftest funding-modal-view-model-derives-terminal-deposit-outcome-and-hyperliquid-explorer-link-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :btc
                         :amount-input ""
                         :hyperunit-lifecycle {:direction :deposit
                                               :asset-key :btc
                                               :operation-id "op_btc_2"
                                               :state :done
                                               :status :completed
                                               :source-tx-confirmations 3
                                               :destination-tx-confirmations 1
                                               :position-in-withdraw-queue nil
                                               :destination-tx-hash "0xabc"
                                               :state-next-at nil
                                               :last-updated-ms 1700000000456
                                               :error nil}})
        view-model (funding-actions/funding-modal-view-model state)]
    (is (= true (:hyperunit-lifecycle-terminal? view-model)))
    (is (= :success (:hyperunit-lifecycle-outcome view-model)))
    (is (= "Completed" (:hyperunit-lifecycle-outcome-label view-model)))
    (is (nil? (:hyperunit-lifecycle-recovery-hint view-model)))
    (is (= "https://app.hyperliquid.xyz/explorer/tx/0xabc"
           (:hyperunit-lifecycle-destination-explorer-url view-model)))))

(deftest funding-modal-view-model-exposes-hyperunit-fee-estimates-and-minimums-test
  (let [state (-> (base-state)
                  (assoc-in [:spot :clearinghouse-state :balances]
                            [{:coin "USDC" :available "12.5" :total "12.5" :hold "0"}
                             {:coin "BTC" :available "1.25" :total "1.25" :hold "0"}])
                  (assoc-in [:funding-ui :modal]
                            {:open? true
                             :mode :withdraw
                             :withdraw-selected-asset-key :btc
                             :amount-input "0.5"
                             :destination-input "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                             :hyperunit-fee-estimate {:status :ready
                                                      :by-chain {"bitcoin" {:chain "bitcoin"
                                                                            :withdrawal-eta "~20 mins"
                                                                            :withdrawal-fee "0.00001"}}
                                                      :requested-at-ms 1
                                                      :updated-at-ms 2
                                                      :error nil}
                             :hyperunit-withdrawal-queue {:status :ready
                                                          :by-chain {"bitcoin" {:chain "bitcoin"
                                                                                :withdrawal-queue-length 9
                                                                                :last-withdraw-queue-operation-tx-id "0xqueue123"}}
                                                          :requested-at-ms 3
                                                          :updated-at-ms 4
                                                          :error nil}}))
        view-model (funding-actions/funding-modal-view-model state)]
    (is (= "~20 mins" (:withdraw-estimated-time view-model)))
    (is (= "0.00001 BTC" (:withdraw-network-fee view-model)))
    (is (= 9 (:withdraw-queue-length view-model)))
    (is (= "0xqueue123" (:withdraw-queue-last-operation-tx-id view-model)))
    (is (= "https://mempool.space/tx/0xqueue123"
           (:withdraw-queue-last-operation-explorer-url view-model)))
    (is (= 0.0003 (:min-withdraw-amount view-model)))
    (is (= "BTC" (:min-withdraw-symbol view-model)))
    (is (= false (:hyperunit-fee-estimate-loading? view-model)))
    (is (nil? (:hyperunit-fee-estimate-error view-model)))
    (is (= false (:hyperunit-withdrawal-queue-loading? view-model)))
    (is (nil? (:hyperunit-withdrawal-queue-error view-model)))))

(deftest funding-modal-view-model-converts-raw-ethereum-fee-units-to-eth-display-test
  (let [state (-> (base-state)
                  (assoc-in [:funding-ui :modal]
                            {:open? true
                             :mode :deposit
                             :deposit-step :amount-entry
                             :deposit-selected-asset-key :eth
                             :hyperunit-fee-estimate {:status :ready
                                                      :by-chain {"ethereum" {:chain "ethereum"
                                                                             :deposit-eta "5m"
                                                                             :deposit-fee "1500000000000000"}}
                                                      :requested-at-ms 1
                                                      :updated-at-ms 2
                                                      :error nil}}))
        view-model (funding-actions/funding-modal-view-model state)]
    (is (= "5m" (:deposit-estimated-time view-model)))
    (is (= "0.0015 ETH" (:deposit-network-fee view-model)))))

(deftest set-funding-amount-to-max-respects-active-mode-test
  (let [transfer-state (assoc-in (base-state)
                                 [:funding-ui :modal]
                                 {:open? true
                                  :mode :transfer
                                  :to-perp? true
                                  :amount-input ""})
        withdraw-state (assoc-in (base-state)
                                 [:funding-ui :modal]
                                 {:open? true
                                  :mode :withdraw
                                  :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                                  :amount-input ""})]
    (let [[effect-id path next-modal] (first (funding-actions/set-funding-amount-to-max transfer-state))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= "12.5" (:amount-input next-modal)))
      (is (nil? (:error next-modal))))
    (let [[effect-id path next-modal] (first (funding-actions/set-funding-amount-to-max withdraw-state))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= "8.5" (:amount-input next-modal)))
      (is (nil? (:error next-modal))))))

(deftest set-funding-amount-to-max-prefers-unified-usdc-availability-for-withdrawals-test
  (let [state (-> (base-state)
                  (assoc :account {:mode :unified})
                  (assoc-in [:spot :clearinghouse-state :balances]
                            [{:coin "USDC" :available "360.793551" :total "360.793551" :hold "0"}])
                  (assoc-in [:webdata2 :clearinghouseState]
                            {:availableToWithdraw "0"
                             :marginSummary {:accountValue "0"
                                             :totalMarginUsed "0"}})
                  (assoc-in [:funding-ui :modal]
                            {:open? true
                             :mode :withdraw
                             :withdraw-step :amount-entry
                             :withdraw-selected-asset-key :usdc
                             :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                             :amount-input ""}))]
    (let [[effect-id path next-modal] (first (funding-actions/set-funding-amount-to-max state))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= "360.793551" (:amount-input next-modal)))
      (is (nil? (:error next-modal))))))

(deftest named-funding-modal-actions-update-intended-fields-test
  (let [deposit-state (assoc-in (base-state)
                                [:funding-ui :modal]
                                {:open? true
                                 :mode :deposit
                                 :deposit-step :asset-select
                                 :deposit-search-input ""
                                 :deposit-selected-asset-key nil
                                 :amount-input ""})
        withdraw-state (assoc-in (base-state)
                                 [:funding-ui :modal]
                                 {:open? true
                                  :mode :withdraw
                                  :withdraw-step :asset-select
                                  :withdraw-search-input ""
                                  :withdraw-selected-asset-key :usdc
                                  :destination-input ""
                                  :amount-input ""})
        transfer-state (assoc-in (base-state)
                                 [:funding-ui :modal]
                                 {:open? true
                                 :mode :transfer
                                 :amount-input ""})]
    (let [[effect-id path next-modal] (first (funding-actions/search-funding-deposit-assets
                                              deposit-state
                                              "btc"))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= "btc" (:deposit-search-input next-modal))))
    (let [[effect-id path next-modal] (first (funding-actions/search-funding-withdraw-assets
                                              withdraw-state
                                              "btc"))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= "btc" (:withdraw-search-input next-modal))))
    (let [effects (funding-actions/select-funding-deposit-asset
                   deposit-state
                   :btc)
          [effect-id path next-modal] (first effects)]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= :amount-entry (:deposit-step next-modal)))
      (is (= :btc (:deposit-selected-asset-key next-modal)))
      (is (= [[:effects/api-fetch-hyperunit-fee-estimate]]
             (rest effects))))
    (let [[effect-id path next-modal] (first (funding-actions/enter-funding-deposit-amount
                                              (assoc-in deposit-state
                                                        [:funding-ui :modal]
                                                        {:open? true
                                                         :mode :deposit
                                                         :deposit-step :amount-entry
                                                         :deposit-selected-asset-key :usdc
                                                         :amount-input ""})
                                              "25"))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= :amount-entry (:deposit-step next-modal)))
      (is (= :usdc (:deposit-selected-asset-key next-modal)))
      (is (= "25" (:amount-input next-modal))))
    (let [[effect-id path next-modal] (first (funding-actions/enter-funding-transfer-amount
                                              transfer-state
                                              "4.5"))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= "4.5" (:amount-input next-modal))))
    (let [effects (funding-actions/select-funding-withdraw-asset
                   withdraw-state
                   :btc)
          [effect-id path next-modal] (first effects)]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= :btc (:withdraw-selected-asset-key next-modal)))
      (is (= :amount-entry (:withdraw-step next-modal)))
      (is (= "" (:amount-input next-modal)))
      (is (= "0x1234567890abcdef1234567890abcdef12345678"
             (:destination-input next-modal)))
      (is (= (funding-actions/default-hyperunit-withdrawal-queue-state)
             (:hyperunit-withdrawal-queue next-modal)))
      (is (= [[:effects/api-fetch-hyperunit-fee-estimate]
              [:effects/api-fetch-hyperunit-withdrawal-queue]]
             (rest effects))))
    (let [[effect-id path next-modal] (first (funding-actions/return-to-funding-withdraw-asset-select
                                              (assoc-in withdraw-state
                                                        [:funding-ui :modal]
                                                        {:open? true
                                                         :mode :withdraw
                                                         :withdraw-step :amount-entry
                                                         :withdraw-selected-asset-key :usdc
                                                         :amount-input "6.5"
                                                         :destination-input "0x1234567890abcdef1234567890abcdef12345678"})))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= :asset-select (:withdraw-step next-modal)))
      (is (= "" (:amount-input next-modal))))
    (let [[effect-id path next-modal] (first (funding-actions/enter-funding-withdraw-destination
                                              withdraw-state
                                              "bc1qexample"))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= "bc1qexample" (:destination-input next-modal))))
    (let [[effect-id path next-modal] (first (funding-actions/enter-funding-withdraw-amount
                                              withdraw-state
                                              "6.5"))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= "6.5" (:amount-input next-modal))))))

(deftest funding-deposit-minimum-action-uses-selected-asset-minimum-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :usdc
                         :amount-input ""})
        [effect-id path next-modal] (first (funding-actions/set-funding-deposit-amount-to-minimum state))]
    (is (= :effects/save effect-id))
    (is (= [:funding-ui :modal] path))
    (is (= "5" (:amount-input next-modal)))
    (is (nil? (:error next-modal)))))

(deftest funding-modal-view-model-scopes-lifecycle-to-the-active-direction-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :btc
                         :hyperunit-lifecycle {:direction :withdraw
                                               :asset-key :btc
                                               :operation-id "op_btc_3"
                                               :state :queued
                                               :status :pending
                                               :source-tx-confirmations 1
                                               :destination-tx-confirmations nil
                                               :position-in-withdraw-queue 2
                                               :destination-tx-hash nil
                                               :state-next-at 1700000000000
                                               :last-updated-ms 1700000000000
                                               :error nil}})
        view-model (funding-actions/funding-modal-view-model state)]
    (is (= :deposit/address (get-in view-model [:content :kind])))
    (is (nil? (get-in view-model [:deposit :lifecycle])))
    (is (nil? (get-in view-model [:withdraw :lifecycle])))))

(deftest funding-modal-view-model-uses-stable-next-check-copy-in-lifecycle-panels-test
  (let [state (-> (base-state)
                  (assoc-in [:spot :clearinghouse-state :balances]
                            [{:coin "USDC" :available "12.5" :total "12.5" :hold "0"}
                             {:coin "BTC" :available "1.25" :total "1.25" :hold "0"}])
                  (assoc-in [:funding-ui :modal]
                            {:open? true
                             :mode :withdraw
                             :withdraw-selected-asset-key :btc
                             :destination-input "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                             :amount-input "0.25"
                             :hyperunit-lifecycle {:direction :withdraw
                                                   :asset-key :btc
                                                   :operation-id "op_btc_4"
                                                   :state :queued
                                                   :status :pending
                                                   :source-tx-confirmations 1
                                                   :destination-tx-confirmations nil
                                                   :position-in-withdraw-queue 2
                                                   :destination-tx-hash nil
                                                   :state-next-at 1700000000000
                                                   :last-updated-ms 1700000000000
                                                   :error nil}}))
        view-model (funding-actions/funding-modal-view-model state)]
    (is (= "Scheduled"
           (get-in view-model [:withdraw :lifecycle :next-check-label])))))
