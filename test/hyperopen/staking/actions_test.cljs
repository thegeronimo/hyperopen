(ns hyperopen.staking.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.core-bootstrap.test-support.effect-extractors :as effect-extractors]
            [hyperopen.staking.actions :as actions]))

(def ^:private wallet-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private staking-not-ready-message
  "Staking account data is still loading. Please try again.")

(def ^:private validator-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private cleared-staking-user-projections
  [[[:staking :delegator-summary] nil]
   [[:staking :delegator-summary-address] nil]
   [[:staking :delegations] []]
   [[:staking :rewards] []]
   [[:staking :history] []]
   [[:staking :spot-state] nil]
   [[:staking :loading :delegator-summary] false]
   [[:staking :loading :delegations] false]
   [[:staking :loading :rewards] false]
   [[:staking :loading :history] false]
   [[:staking :loading :spot-state] false]
   [[:staking :errors :delegator-summary] nil]
   [[:staking :errors :delegations] nil]
   [[:staking :errors :rewards] nil]
   [[:staking :errors :history] nil]
   [[:staking :errors :spot-state] nil]
   [[:staking :loaded-for :delegator-summary] nil]
   [[:staking :loaded-for :delegations] nil]
   [[:staking :loaded-for :rewards] nil]
   [[:staking :loaded-for :history] nil]
   [[:staking :loaded-for :spot-state] nil]])

(deftest parse-staking-route-supports-route-and-non-route-paths-test
  (is (= {:kind :page
          :path "/staking"}
         (actions/parse-staking-route "/staking/")))
  (is (= {:kind :page
          :path "/staking"}
         (actions/parse-staking-route "/staking?tab=validators")))
  (is (= {:kind :other
          :path "/trade"}
         (actions/parse-staking-route "/trade"))))

(deftest load-staking-route-emits-validator-and-user-load-effects-test
  (let [heavy-effect-ids #{:effects/api-fetch-staking-validator-summaries
                           :effects/api-fetch-staking-delegator-summary
                           :effects/api-fetch-staking-delegations
                           :effects/api-fetch-staking-rewards
                           :effects/api-fetch-staking-history
                           :effects/api-fetch-staking-spot-state}]
    (is (= [[:effects/save [:staking-ui :form-error] nil]
            [:effects/save [:staking :account-address] wallet-address]
            [:effects/save-many cleared-staking-user-projections]
            [:effects/api-fetch-staking-validator-summaries]
            [:effects/api-fetch-staking-delegator-summary wallet-address]
            [:effects/api-fetch-staking-delegations wallet-address]
            [:effects/api-fetch-staking-rewards wallet-address]
            [:effects/api-fetch-staking-history wallet-address]
            [:effects/api-fetch-staking-spot-state wallet-address]]
           (actions/load-staking-route
            {:wallet {:address wallet-address}}
            "/staking")))
    (let [route-effects (actions/load-staking-route {} "/staking")]
      (is (= [[:effects/save [:staking-ui :form-error] nil]
              [:effects/save [:staking :account-address] nil]
              [:effects/save-many cleared-staking-user-projections]
              [:effects/api-fetch-staking-validator-summaries]]
             route-effects))
      (is (effect-extractors/projection-before-heavy? route-effects heavy-effect-ids))))
  (is (= []
         (actions/load-staking-route {} "/portfolio"))))

(deftest load-staking-uses-owner-for-every-user-projection-with-selected-subaccount-test
  (let [owner "0x1111111111111111111111111111111111111111"
        selected "0x2222222222222222222222222222222222222222"
        state {:wallet {:address owner}
               :account-context {:spectate-mode {:active? false :address nil}
                                 :subaccounts {:selected-address selected
                                               :rows [{:sub-account-user selected
                                                       :master owner}]}}}
        effects (actions/load-staking state)
        addressed-effects (filterv #(contains? #{:effects/api-fetch-staking-delegator-summary
                                                 :effects/api-fetch-staking-delegations
                                                 :effects/api-fetch-staking-rewards
                                                 :effects/api-fetch-staking-history
                                                 :effects/api-fetch-staking-spot-state}
                                               (first %))
                                  effects)]
    (is (= [[:effects/api-fetch-staking-delegator-summary owner]
            [:effects/api-fetch-staking-delegations owner]
            [:effects/api-fetch-staking-rewards owner]
            [:effects/api-fetch-staking-history owner]
            [:effects/api-fetch-staking-spot-state owner]]
           addressed-effects))
    (is (not-any? #(= selected (second %)) addressed-effects))))

(deftest load-staking-switches-account-and-clears-user-projections-before-fetching-test
  (let [owner "0x1111111111111111111111111111111111111111"
        effects (actions/load-staking {:wallet {:address owner}})
        account-index (first (keep-indexed (fn [idx effect]
                                             (when (= [:effects/save [:staking :account-address] owner]
                                                      effect)
                                               idx))
                                           effects))
        clear-index (first (keep-indexed (fn [idx effect]
                                           (when (= :effects/save-many (first effect))
                                             idx))
                                         effects))
        first-fetch-index (first (keep-indexed (fn [idx effect]
                                                  (when (contains? #{:effects/api-fetch-staking-validator-summaries
                                                                     :effects/api-fetch-staking-delegator-summary
                                                                     :effects/api-fetch-staking-delegations
                                                                     :effects/api-fetch-staking-rewards
                                                                     :effects/api-fetch-staking-history
                                                                     :effects/api-fetch-staking-spot-state}
                                                                   (first effect))
                                                    idx))
                                                effects))
        clear-values (if (number? clear-index)
                       (into {} (second (nth effects clear-index)))
                       {})]
    (is (number? account-index))
    (is (number? clear-index))
    (is (number? first-fetch-index))
    (is (and (number? account-index)
             (number? first-fetch-index)
             (< account-index first-fetch-index)))
    (is (and (number? clear-index)
             (number? first-fetch-index)
             (< clear-index first-fetch-index)))
    (doseq [[path expected]
            [[[:staking :delegator-summary] nil]
             [[:staking :delegations] []]
             [[:staking :rewards] []]
             [[:staking :history] []]
             [[:staking :spot-state] nil]
             [[:staking :loading :delegator-summary] false]
             [[:staking :loading :delegations] false]
             [[:staking :loading :rewards] false]
             [[:staking :loading :history] false]
             [[:staking :loading :spot-state] false]
             [[:staking :errors :delegator-summary] nil]
             [[:staking :errors :delegations] nil]
             [[:staking :errors :rewards] nil]
             [[:staking :errors :history] nil]
             [[:staking :errors :spot-state] nil]
             [[:staking :loaded-for :delegator-summary] nil]
             [[:staking :loaded-for :delegations] nil]
             [[:staking :loaded-for :rewards] nil]
             [[:staking :loaded-for :history] nil]
             [[:staking :loaded-for :spot-state] nil]]]
      (is (= expected (get clear-values path)) (str "clear " path)))))

(deftest normalize-staking-validator-sort-column-supports-aliases-and-defaults-test
  (is (= :your-stake
         (actions/normalize-staking-validator-sort-column :yourstake)))
  (is (= :apr
         (actions/normalize-staking-validator-sort-column " est apr ")))
  (is (= :stake
         (actions/normalize-staking-validator-sort-column nil)))
  (is (= :stake
         (actions/normalize-staking-validator-sort-column :not-a-column))))

(deftest direct-balance-row-available-prefers-supported-direct-fields-test
  (let [direct-balance-row-available @#'hyperopen.staking.actions/direct-balance-row-available]
    (is (= 10.5
           (direct-balance-row-available {:available "10.5"
                                          :availableBalance "8"
                                          :free "7"})))
    (is (= 8
           (direct-balance-row-available {:availableBalance "8"})))
    (is (= 7
           (direct-balance-row-available {:free "7"})))
    (is (nil? (direct-balance-row-available {:available "NaN"
                                             :availableBalance ""
                                             :free nil})))))

(deftest derived-balance-row-available-uses-total-minus-hold-when-needed-test
  (let [derived-balance-row-available @#'hyperopen.staking.actions/derived-balance-row-available]
    (is (= 8
           (derived-balance-row-available {:total "10"
                                           :hold "2"})))
    (is (= 12
           (derived-balance-row-available {:totalBalance "12"})))
    (is (= -1
           (derived-balance-row-available {:total "5"
                                           :hold "6"})))
    (is (nil? (derived-balance-row-available {:hold "2"})))))

(deftest balance-row-available-wraps-direct-and-derived-values-test
  (let [balance-row-available @#'hyperopen.staking.actions/balance-row-available]
    (is (= 10.5
           (balance-row-available {:available "10.5"
                                   :free "7"})))
    (is (= 3
           (balance-row-available {:total "5"
                                   :hold "2"})))
    (is (= 0
           (balance-row-available {:total "5"
                                   :hold "6"})))
    (is (nil? (balance-row-available {:available "NaN"})))
    (is (nil? (balance-row-available nil)))))

(deftest set-staking-validator-sort-toggles-direction-and-switches-columns-test
  (is (= [[:effects/save [:staking-ui :validator-sort]
           {:column :stake
            :direction :asc}]
          [:effects/save [:staking-ui :validator-page] 0]]
         (actions/set-staking-validator-sort
          {:staking-ui {:validator-sort {:column :stake
                                         :direction :desc}}}
          :stake)))
  (is (= [[:effects/save [:staking-ui :validator-sort]
           {:column :name
            :direction :asc}]
          [:effects/save [:staking-ui :validator-page] 0]]
         (actions/set-staking-validator-sort
          {:staking-ui {:validator-sort {:column :stake
                                         :direction :desc}}}
          :name)))
  (is (= [[:effects/save [:staking-ui :validator-sort]
           {:column :apr
            :direction :desc}]
          [:effects/save [:staking-ui :validator-page] 0]]
         (actions/set-staking-validator-sort
          {:staking-ui {:validator-sort {:column :stake
                                         :direction :asc}}}
          "est apr"))))

(deftest staking-validator-timeframe-menu-actions-test
  (is (= [[:effects/save [:staking-ui :validator-timeframe-dropdown-open?] true]]
         (actions/toggle-staking-validator-timeframe-menu
          {:staking-ui {:validator-timeframe-dropdown-open? false}})))
  (is (= [[:effects/save [:staking-ui :validator-timeframe-dropdown-open?] false]]
         (actions/toggle-staking-validator-timeframe-menu
          {:staking-ui {:validator-timeframe-dropdown-open? true}})))
  (is (= [[:effects/save [:staking-ui :validator-timeframe-dropdown-open?] false]]
         (actions/close-staking-validator-timeframe-menu {})))
  (is (= [[:effects/save-many
           [[[:staking-ui :validator-timeframe] :day]
            [[:staking-ui :validator-timeframe-dropdown-open?] false]
            [[:staking-ui :validator-page] 0]]]]
         (actions/set-staking-validator-timeframe {} "1d"))))

(deftest set-staking-validator-page-clamps-to-non-negative-integers-test
  (is (= [[:effects/save [:staking-ui :validator-page] 3]]
         (actions/set-staking-validator-page {} "3.7")))
  (is (= [[:effects/save [:staking-ui :validator-page] 0]]
         (actions/set-staking-validator-page {} "-5")))
  (is (= [[:effects/save [:staking-ui :validator-page] 0]]
         (actions/set-staking-validator-page {} "not-a-number"))))

(deftest set-staking-validator-show-all-toggles-and-resets-page-test
  (is (= [[:effects/save-many
           [[[:staking-ui :validator-show-all?] true]
            [[:staking-ui :validator-page] 0]]]]
         (actions/set-staking-validator-show-all
          {:staking-ui {:validator-page 2}}
          true)))
  (is (= [[:effects/save-many
           [[[:staking-ui :validator-show-all?] false]
            [[:staking-ui :validator-page] 0]]]]
         (actions/set-staking-validator-show-all
          {:staking-ui {:validator-page 1}}
          false))))

(deftest set-staking-form-field-normalizes-validator-and-ignores-unknown-fields-test
  (is (= [[:effects/save [:staking-ui :selected-validator]
           "0x1234567890abcdef1234567890abcdef12345678"]]
         (actions/set-staking-form-field
          {}
          :selected-validator
          " 0x1234567890ABCDEF1234567890ABCDEF12345678 ")))
  (is (= [[:effects/save [:staking-ui :selected-validator] ""]]
         (actions/set-staking-form-field
          {}
          :selected-validator
          "not-an-address")))
  (is (= [[:effects/save [:staking-ui :validator-search-query] "foundation"]]
         (actions/set-staking-form-field {} :validator-search-query "foundation")))
  (is (= [[:effects/save [:staking-ui :validator-dropdown-open?] true]]
         (actions/set-staking-form-field {} :validator-dropdown-open? true)))
  (is (= []
         (actions/set-staking-form-field {} :not-a-field "1"))))

(deftest select-staking-validator-saves-selection-and-resets-search-state-test
  (is (= [[:effects/save-many
           [[[:staking-ui :selected-validator]
             "0x1234567890abcdef1234567890abcdef12345678"]
            [[:staking-ui :validator-search-query] ""]
            [[:staking-ui :validator-dropdown-open?] false]]]]
         (actions/select-staking-validator
          {}
          "0x1234567890ABCDEF1234567890ABCDEF12345678"))))

(deftest staking-action-popover-actions-normalize-kind-anchor-and-direction-test
  (is (= [[:effects/save-many
           [[[:staking-ui :action-popover]
             {:open? true
              :kind :transfer
              :anchor {:left 12
                       :right 44
                       :top 6
                       :viewport-width 1024}}]
            [[:staking-ui :transfer-direction] :spot->staking]
            [[:staking-ui :validator-search-query] ""]
            [[:staking-ui :validator-dropdown-open?] false]
            [[:staking-ui :form-error] nil]]]]
         (actions/open-staking-action-popover
          {}
          :transfer
          {"left" "12"
           "right" "44"
           :top "6"
           :viewportWidth 1024
           :height "not-a-number"})))
  (is (= []
         (actions/open-staking-action-popover {} :unknown nil)))
  (is (= [[:effects/save [:staking-ui :transfer-direction] :spot->staking]]
         (actions/set-staking-transfer-direction {} :deposit)))
  (is (= [[:effects/save [:staking-ui :transfer-direction] :staking->spot]]
         (actions/set-staking-transfer-direction {} "staking-to-spot"))))

(deftest staking-action-popover-close-and-escape-actions-test
  (is (= [[:effects/save-many
           [[[:staking-ui :action-popover]
             {:open? false
              :kind nil
              :anchor nil}]
            [[:staking-ui :validator-search-query] ""]
            [[:staking-ui :validator-dropdown-open?] false]]]]
         (actions/close-staking-action-popover {})))
  (is (= [[:effects/save-many
           [[[:staking-ui :action-popover]
             {:open? false
              :kind nil
              :anchor nil}]
            [[:staking-ui :validator-search-query] ""]
            [[:staking-ui :validator-dropdown-open?] false]]]]
         (actions/handle-staking-action-popover-keydown {} "Escape")))
  (is (= []
         (actions/handle-staking-action-popover-keydown {} "Enter"))))

(deftest submit-staking-deposit-validates-wallet-and-builds-cdeposit-request-test
  (is (= [[:effects/save [:staking-ui :form-error]
           "Connect your wallet before transferring to staking balance."]
          [:effects/save [:staking-ui :submitting :deposit?] false]]
         (actions/submit-staking-deposit
          {:staking-ui {:deposit-amount "1"}})))
  (is (= [[:effects/save [:staking-ui :form-error] nil]
          [:effects/save [:staking-ui :submitting :deposit?] true]
          [:effects/api-submit-staking-deposit
           {:kind :deposit
            :action {:type "cDeposit"
                     :wei 125000000}}]]
         (actions/submit-staking-deposit
          {:wallet {:address wallet-address}
           :staking {:account-address wallet-address
                     :loaded-for {:spot-state wallet-address}
                     :spot-state {:balances [{:coin "HYPE"
                                              :available 2}]}}
           :staking-ui {:deposit-amount "1.25"}}))))

(deftest staking-deposit-validation-and-max-use-staking-owned-spot-state-test
  (let [state {:wallet {:address wallet-address}
               :spot {:clearinghouse-state {:balances [{:coin "HYPE"
                                                        :total "1"
                                                        :hold "0"}]}}
               :staking {:account-address wallet-address
                         :loaded-for {:spot-state wallet-address}
                         :spot-state {:balances [{:coin "HYPE"
                                                  :total "9"
                                                  :hold "2"}]}}
               :staking-ui {:deposit-amount "5"}}]
    (is (= [[:effects/save [:staking-ui :deposit-amount] "7"]]
           (actions/set-staking-deposit-amount-to-max state)))
    (is (= [[:effects/save [:staking-ui :form-error] nil]
            [:effects/save [:staking-ui :submitting :deposit?] true]
            [:effects/api-submit-staking-deposit
             {:kind :deposit
              :action {:type "cDeposit"
                       :wei 500000000}}]]
           (actions/submit-staking-deposit state)))))

(deftest submit-staking-withdraw-validates-blockers-wallet-amount-and-balance-test
  (is (= [[:effects/save [:staking-ui :form-error]
           "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds."]
          [:effects/save [:staking-ui :submitting :withdraw?] false]]
         (actions/submit-staking-withdraw
          {:account-context {:spectate-mode {:active? true
                                             :address wallet-address}}
           :wallet {:address wallet-address}
           :staking-ui {:withdraw-amount "1"}})))
  (is (= [[:effects/save [:staking-ui :form-error]
           "Connect your wallet before withdrawing from staking balance."]
          [:effects/save [:staking-ui :submitting :withdraw?] false]]
         (actions/submit-staking-withdraw
          {:staking-ui {:withdraw-amount "1"}})))
  (is (= [[:effects/save [:staking-ui :form-error]
           "Enter a valid amount up to 8 decimals."]
          [:effects/save [:staking-ui :submitting :withdraw?] false]]
         (actions/submit-staking-withdraw
          {:wallet {:address wallet-address}
           :staking-ui {:withdraw-amount "not-a-number"}})))
  (is (= [[:effects/save [:staking-ui :form-error]
           "Amount exceeds available staking balance."]
          [:effects/save [:staking-ui :submitting :withdraw?] false]]
         (actions/submit-staking-withdraw
          {:wallet {:address wallet-address}
           :staking {:delegator-summary {:undelegated 1}}
           :staking-ui {:withdraw-amount "1.25"}})))
  (is (= [[:effects/save [:staking-ui :form-error] nil]
          [:effects/save [:staking-ui :submitting :withdraw?] true]
          [:effects/api-submit-staking-withdraw
           {:kind :withdraw
            :action {:type "cWithdraw"
                     :wei 125000000}}]]
         (actions/submit-staking-withdraw
          {:wallet {:address wallet-address}
           :staking {:account-address wallet-address
                     :loaded-for {:delegator-summary wallet-address}
                     :delegator-summary {:undelegated 2}}
           :staking-ui {:withdraw-amount "1.25"}}))))

(deftest staking-submissions-require-current-account-provenance-test
  (let [other-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        cases [{:label "deposit"
                :submit actions/submit-staking-deposit
                :resource :spot-state
                :submitting-key :deposit?
                :submit-effect :effects/api-submit-staking-deposit
                :staking {:spot-state {:balances [{:coin "HYPE" :available 3}]}}
                :staking-ui {:deposit-amount "1"}}
               {:label "withdraw"
                :submit actions/submit-staking-withdraw
                :resource :delegator-summary
                :submitting-key :withdraw?
                :submit-effect :effects/api-submit-staking-withdraw
                :staking {:delegator-summary {:undelegated 3}}
                :staking-ui {:withdraw-amount "1"}}
               {:label "delegate"
                :submit actions/submit-staking-delegate
                :resource :delegator-summary
                :submitting-key :delegate?
                :submit-effect :effects/api-submit-staking-delegate
                :staking {:delegator-summary {:undelegated 3}}
                :staking-ui {:delegate-amount "1"
                             :selected-validator validator-address}}
               {:label "undelegate"
                :submit actions/submit-staking-undelegate
                :resource :delegations
                :submitting-key :undelegate?
                :submit-effect :effects/api-submit-staking-undelegate
                :staking {:delegations [{:validator validator-address :amount 3}]}
                :staking-ui {:undelegate-amount "1"
                             :selected-validator validator-address}}]]
    (doseq [{:keys [label submit resource submitting-key submit-effect staking staking-ui]} cases
            [scenario provenance]
            [["requires saved account identity" {}]
             ["requires resource loaded for that identity"
              {:account-address wallet-address
               :loaded-for {resource other-address}}]]]
      (let [effects (submit {:wallet {:address wallet-address}
                             :staking (merge staking provenance)
                             :staking-ui staking-ui})]
        (is (= [[:effects/save [:staking-ui :form-error] staking-not-ready-message]
                [:effects/save [:staking-ui :submitting submitting-key] false]]
               effects)
            (str label " " scenario))
        (is (not-any? #(= submit-effect (first %)) effects)
            (str label " " scenario " must not submit"))))
    (doseq [{:keys [label submit resource submit-effect staking staking-ui]}
            (filter #(contains? #{:delegate? :undelegate?} (:submitting-key %)) cases)
            :let [effects (submit {:wallet {:address wallet-address}
                                   :staking (assoc staking
                                                   :account-address wallet-address
                                                   :loaded-for {resource wallet-address})
                                   :staking-ui staking-ui})]]
      (is (some #(= submit-effect (first %)) effects)
          (str label " submits when its current resource provenance matches")))))

(deftest submit-staking-delegate-requires-validator-selection-test
  (is (= [[:effects/save [:staking-ui :form-error]
           "Select a validator before staking."]
          [:effects/save [:staking-ui :submitting :delegate?] false]]
         (actions/submit-staking-delegate
          {:wallet {:address wallet-address}
           :staking {:delegator-summary {:undelegated 5}}
           :staking-ui {:delegate-amount "1"
                        :selected-validator ""}}))))
