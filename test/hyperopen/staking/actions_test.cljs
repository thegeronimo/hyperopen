(ns hyperopen.staking.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.core-bootstrap.test-support.effect-extractors :as effect-extractors]
            [hyperopen.staking.actions :as actions]))

(def ^:private wallet-address
  "0x1234567890abcdef1234567890abcdef12345678")

(deftest parse-staking-route-supports-route-and-non-route-paths-test
  (is (= {:kind :page
          :path "/staking"}
         (actions/parse-staking-route "/staking/")))
  (is (= ""
         (actions/normalize-route-path nil)))
  (is (= "/staking"
         (actions/normalize-route-path " /staking///?tab=rewards#history ")))
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
            [:effects/api-fetch-staking-validator-summaries]
            [:effects/api-fetch-staking-delegator-summary wallet-address]
            [:effects/api-fetch-staking-delegations wallet-address]
            [:effects/api-fetch-staking-rewards wallet-address]
            [:effects/api-fetch-staking-history wallet-address]
            [:effects/api-fetch-staking-spot-state wallet-address]]
           (actions/load-staking-route
            {:wallet {:address wallet-address}}
            "/staking")))
    (let [effects (actions/load-staking-route {} "/staking")]
      (is (= [[:effects/save [:staking-ui :form-error] nil]
              [:effects/save-many
               [[[:staking :delegator-summary] nil]
                [[:staking :delegations] []]
                [[:staking :rewards] []]
                [[:staking :history] []]
                [[:staking :errors :delegator-summary] nil]
                [[:staking :errors :delegations] nil]
                [[:staking :errors :rewards] nil]
                [[:staking :errors :history] nil]]]
              [:effects/api-fetch-staking-validator-summaries]]
             effects))
      (is (effect-extractors/projection-before-heavy? effects heavy-effect-ids))))
  (is (= []
         (actions/load-staking-route {} "/portfolio"))))

(deftest normalize-staking-validator-sort-column-supports-aliases-and-defaults-test
  (is (= :your-stake
         (actions/normalize-staking-validator-sort-column :yourstake)))
  (is (= :apr
         (actions/normalize-staking-validator-sort-column " est apr ")))
  (is (= :asc
         (actions/normalize-staking-validator-sort-direction "ascending")))
  (is (= :desc
         (actions/normalize-staking-validator-sort-direction :unexpected)))
  (is (= {:column :status
          :direction :asc}
         (actions/normalize-staking-validator-sort
          {:column "status"
           :direction "ascending"})))
  (is (= :stake
         (actions/normalize-staking-validator-sort-column nil)))
  (is (= :stake
         (actions/normalize-staking-validator-sort-column :not-a-column))))

(deftest set-staking-active-tab-normalizes-aliases-and-defaults-test
  (is (= [[:effects/save [:staking-ui :active-tab] :staking-reward-history]]
         (actions/set-staking-active-tab {} "rewards")))
  (is (= [[:effects/save [:staking-ui :active-tab] :staking-action-history]]
         (actions/set-staking-active-tab {} :actions)))
  (is (= [[:effects/save [:staking-ui :active-tab] :validator-performance]]
         (actions/set-staking-active-tab {} :unsupported))))

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
         (actions/set-staking-validator-timeframe {} "1d")))
  (is (= [[:effects/save-many
           [[[:staking-ui :validator-timeframe] :week]
            [[:staking-ui :validator-timeframe-dropdown-open?] false]
            [[:staking-ui :validator-page] 0]]]]
         (actions/set-staking-validator-timeframe {} :unsupported))))

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
  (is (= [[:effects/save [:staking-ui :validator-dropdown-open?] false]]
         (actions/set-staking-form-field {} :validator-dropdown-open? "yes")))
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
              :kind :stake
              :anchor nil}]
            [[:staking-ui :transfer-direction] :spot->staking]
            [[:staking-ui :validator-search-query] ""]
            [[:staking-ui :validator-dropdown-open?] false]
            [[:staking-ui :form-error] nil]]]]
         (actions/open-staking-action-popover
          {:staking-ui {:transfer-direction :sideways}}
          :stake)))
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
  (is (= [[:effects/save-many
           [[[:staking-ui :action-popover]
             {:open? true
              :kind :unstake
              :anchor {:left 18.5
                       :viewport-height 900}}]
            [[:staking-ui :transfer-direction] :staking->spot]
            [[:staking-ui :validator-search-query] ""]
            [[:staking-ui :validator-dropdown-open?] false]
            [[:staking-ui :form-error] nil]]]]
         (actions/open-staking-action-popover
          {:staking-ui {:transfer-direction :staking->spot}}
          :unstake
          #js {:left "18.5"
               :viewportHeight "900"
               :width "garbage"})))
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
           :spot {:clearinghouse-state {:balances [{:coin "HYPE"
                                                    :available 2}]}}
           :staking-ui {:deposit-amount "1.25"}}))))

(deftest submit-staking-delegate-requires-validator-selection-test
  (is (= [[:effects/save [:staking-ui :form-error]
           "Select a validator before staking."]
          [:effects/save [:staking-ui :submitting :delegate?] false]]
         (actions/submit-staking-delegate
          {:wallet {:address wallet-address}
           :staking {:delegator-summary {:undelegated 5}}
           :staking-ui {:delegate-amount "1"
                        :selected-validator ""}}))))
