(ns hyperopen.funding.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
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
   :deposit-step :asset-select
   :deposit-search-input ""
   :deposit-selected-asset-key nil
   :amount-input ""
   :to-perp? true
   :destination-input "0x1234567890abcdef1234567890abcdef12345678"
   :submitting? false
   :error nil})

(deftest open-funding-modal-actions-set-mode-and-open-state-test
  (let [state (base-state)]
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :deposit)]]
           (funding-actions/open-funding-deposit-modal state)))
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :transfer)]]
           (funding-actions/open-funding-transfer-modal state)))
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :withdraw)]]
           (funding-actions/open-funding-withdraw-modal state)))))

(deftest set-funding-modal-compat-preserves-legacy-fallback-test
  (let [state (base-state)]
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :transfer)]]
           (funding-actions/set-funding-modal-compat state :send)))
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :legacy :legacy-kind :history)]]
           (funding-actions/set-funding-modal-compat state :history)))))

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
                         :amount-input "12"})]
    (let [[effect-id path next-modal]
          (first (funding-actions/set-funding-modal-field state
                                                          [:deposit-selected-asset-key]
                                                          :usdc))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= :usdc (:deposit-selected-asset-key next-modal)))
      (is (= :amount-entry (:deposit-step next-modal)))
      (is (= "" (:amount-input next-modal))))))

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
