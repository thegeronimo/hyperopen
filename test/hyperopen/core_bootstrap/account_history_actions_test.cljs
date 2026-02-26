(ns hyperopen.core-bootstrap.account-history-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.core.compat :as core]
            [hyperopen.core-bootstrap.test-support.effect-extractors :as effect-extractors]
            [hyperopen.core-bootstrap.test-support.browser-mocks :as browser-mocks]
            [hyperopen.platform :as platform]))

(def with-test-local-storage browser-mocks/with-test-local-storage)
(def ^:private account-tab-heavy-effect-ids
  #{:effects/api-fetch-user-funding-history
    :effects/api-fetch-historical-orders})
(def ^:private funding-history-heavy-effect-ids
  #{:effects/api-fetch-user-funding-history})

(deftest select-account-info-tab-funding-history-saves-selection-before-fetch-test
  (let [state {:account-info {:selected-tab :balances
                              :funding-history {:filters {:coin-set #{}
                                                          :start-time-ms 0
                                                          :end-time-ms 1000}
                                                :request-id 2}}
               :orders {:fundings-raw []}}
        effects (core/select-account-info-tab state :funding-history)
        immediate (first effects)
        path-values (second immediate)]
    (is (= :effects/save-many (first immediate)))
    (is (= [:account-info :selected-tab]
           (-> path-values first first)))
    (is (= :funding-history
           (-> path-values first second)))
    (is (effect-extractors/projection-before-heavy? effects account-tab-heavy-effect-ids))
    (is (effect-extractors/phase-order-valid? effects account-tab-heavy-effect-ids))
    (is (empty? (effect-extractors/duplicate-heavy-effect-ids effects account-tab-heavy-effect-ids)))
    (is (= [:effects/api-fetch-user-funding-history 3]
           (second effects)))))

(deftest apply-funding-history-filters-resets-pagination-and-refetches-only-on-time-range-change-test
  (let [base-state {:account-info {:funding-history {:filters {:coin-set #{}
                                                          :start-time-ms 1000
                                                          :end-time-ms 2000}
                                                    :draft-filters {:coin-set #{"BTC"}
                                                                    :start-time-ms 1000
                                                                    :end-time-ms 2000}
                                                    :page 4
                                                    :page-input "4"
                                                    :request-id 5}}
                    :orders {:fundings-raw []}}
        no-refetch (core/apply-funding-history-filters base-state)
        with-refetch (core/apply-funding-history-filters
                      (assoc-in base-state
                                [:account-info :funding-history :draft-filters :end-time-ms]
                                3000))]
    (is (some #(= [[:account-info :funding-history :page] 1] %)
              (-> no-refetch first second)))
    (is (some #(= [[:account-info :funding-history :page-input] "1"] %)
              (-> no-refetch first second)))
    (is (not-any? #(= :effects/api-fetch-user-funding-history (first %))
                  no-refetch))
    (is (effect-extractors/projection-before-heavy? with-refetch funding-history-heavy-effect-ids))
    (is (effect-extractors/phase-order-valid? with-refetch funding-history-heavy-effect-ids))
    (is (empty? (effect-extractors/duplicate-heavy-effect-ids with-refetch funding-history-heavy-effect-ids)))
    (is (some #(= :effects/api-fetch-user-funding-history (first %))
              with-refetch))))

(deftest sort-funding-history-toggles-direction-on-same-column-test
  (let [state {:account-info {:funding-history {:sort {:column "Time"
                                                       :direction :desc}
                                                :page 3
                                                :page-input "3"}}}
        effects (core/sort-funding-history state "Time")]
    (is (= [[:effects/save-many [[[:account-info :funding-history :sort]
                                  {:column "Time" :direction :asc}]
                                 [[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           effects))))

(deftest sort-funding-history-uses-mixed-default-direction-for-new-columns-test
  (let [state {:account-info {:funding-history {:sort {:column "Time"
                                                       :direction :desc}
                                                :page 2
                                                :page-input "2"}}}
        coin-effects (core/sort-funding-history state "Coin")
        payment-effects (core/sort-funding-history state "Payment")]
    (is (= [[:effects/save-many [[[:account-info :funding-history :sort]
                                  {:column "Coin" :direction :asc}]
                                 [[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           coin-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :sort]
                                  {:column "Payment" :direction :desc}]
                                 [[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           payment-effects))))

(deftest view-all-funding-history-resets-pagination-before-fetch-test
  (let [state {:account-info {:funding-history {:filters {:coin-set #{"BTC"}
                                                          :start-time-ms 1000
                                                          :end-time-ms 2000}
                                                :request-id 3
                                                :page 7
                                                :page-input "7"}}
               :orders {:fundings-raw []}}
        effects (core/view-all-funding-history state)
        path-values (-> effects first second)]
    (is (some #(= [[:account-info :funding-history :page] 1] %) path-values))
    (is (some #(= [[:account-info :funding-history :page-input] "1"] %) path-values))
    (is (effect-extractors/projection-before-heavy? effects funding-history-heavy-effect-ids))
    (is (effect-extractors/phase-order-valid? effects funding-history-heavy-effect-ids))
    (is (empty? (effect-extractors/duplicate-heavy-effect-ids effects funding-history-heavy-effect-ids)))
    (is (= [:effects/api-fetch-user-funding-history 4]
           (second effects)))))

(deftest funding-history-pagination-page-size-normalizes-and-persists-test
  (with-test-local-storage
    (fn []
      (let [state {:account-info {:funding-history {:page-size 25
                                                    :page 8
                                                    :page-input "8"}}}
            effects (core/set-funding-history-page-size state "100")]
        (is (= [[:effects/save-many [[[:account-info :funding-history :page-size] 100]
                                     [[:account-info :funding-history :page] 1]
                                     [[:account-info :funding-history :page-input] "1"]]]
                [:effects/local-storage-set "funding-history-page-size" "100"]]
               effects))
        (let [invalid-effects (core/set-funding-history-page-size state "13")]
          (is (= [[:effects/save-many [[[:account-info :funding-history :page-size] 50]
                                       [[:account-info :funding-history :page] 1]
                                       [[:account-info :funding-history :page-input] "1"]]]
                  [:effects/local-storage-set "funding-history-page-size" "50"]]
                 invalid-effects)))))))

(deftest funding-history-pagination-set-page-clamps-and-syncs-input-test
  (let [state {:account-info {:funding-history {:page 2
                                                :page-input "2"}}}
        within (core/set-funding-history-page state 3 5)
        too-high (core/set-funding-history-page state 99 5)
        too-low (core/set-funding-history-page state -2 5)]
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 3]
                                 [[:account-info :funding-history :page-input] "3"]]]]
           within))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 5]
                                 [[:account-info :funding-history :page-input] "5"]]]]
           too-high))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           too-low))))

(deftest funding-history-pagination-next-prev-and-input-apply-test
  (let [state {:account-info {:funding-history {:page 2
                                                :page-input "2"}}}
        next-effects (core/next-funding-history-page state 3)
        prev-effects (core/prev-funding-history-page state 3)
        at-end-effects (core/next-funding-history-page
                        {:account-info {:funding-history {:page 3 :page-input "3"}}}
                        3)
        typed-state {:account-info {:funding-history {:page 1 :page-input "12"}}}
        apply-effects (core/apply-funding-history-page-input typed-state 4)
        invalid-apply-effects (core/apply-funding-history-page-input
                               {:account-info {:funding-history {:page 1 :page-input "abc"}}}
                               4)
        keydown-effects (core/handle-funding-history-page-input-keydown typed-state "Enter" 4)
        keydown-nop (core/handle-funding-history-page-input-keydown typed-state "Escape" 4)]
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 3]
                                 [[:account-info :funding-history :page-input] "3"]]]]
           next-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           prev-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 3]
                                 [[:account-info :funding-history :page-input] "3"]]]]
           at-end-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 4]
                                 [[:account-info :funding-history :page-input] "4"]]]]
           apply-effects))
    (is (= [[:effects/save-many [[[:account-info :funding-history :page] 1]
                                 [[:account-info :funding-history :page-input] "1"]]]]
           invalid-apply-effects))
    (is (= apply-effects keydown-effects))
    (is (= [] keydown-nop))))

(deftest restore-funding-history-pagination-settings-uses-defaults-and-stored-size-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "funding-history-page-size" "100")
      (let [store (atom {:account-info {:funding-history {:page-size 25
                                                          :page 4
                                                          :page-input "4"}}})]
        (core/restore-funding-history-pagination-settings! store)
        (is (= {:page-size 100
                :page 1
                :page-input "1"}
               (select-keys (get-in @store [:account-info :funding-history])
                            [:page-size :page :page-input]))))
      (.setItem js/localStorage "funding-history-page-size" "13")
      (let [store (atom {:account-info {:funding-history {}}})]
        (core/restore-funding-history-pagination-settings! store)
        (is (= 50
               (get-in @store [:account-info :funding-history :page-size])))))))

(deftest trade-history-pagination-page-size-normalizes-and-persists-test
  (with-test-local-storage
    (fn []
      (let [state {:account-info {:trade-history {:page-size 25
                                                  :page 8
                                                  :page-input "8"}}}
            effects (core/set-trade-history-page-size state "100")]
        (is (= [[:effects/save-many [[[:account-info :trade-history :page-size] 100]
                                     [[:account-info :trade-history :page] 1]
                                     [[:account-info :trade-history :page-input] "1"]]]
                [:effects/local-storage-set "trade-history-page-size" "100"]]
               effects))
        (let [invalid-effects (core/set-trade-history-page-size state "13")]
          (is (= [[:effects/save-many [[[:account-info :trade-history :page-size] 50]
                                       [[:account-info :trade-history :page] 1]
                                       [[:account-info :trade-history :page-input] "1"]]]
                  [:effects/local-storage-set "trade-history-page-size" "50"]]
                 invalid-effects)))))))

(deftest trade-history-pagination-set-page-clamps-and-syncs-input-test
  (let [state {:account-info {:trade-history {:page 2
                                              :page-input "2"}}}
        within (core/set-trade-history-page state 3 5)
        too-high (core/set-trade-history-page state 99 5)
        too-low (core/set-trade-history-page state -2 5)]
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 3]
                                 [[:account-info :trade-history :page-input] "3"]]]]
           within))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 5]
                                 [[:account-info :trade-history :page-input] "5"]]]]
           too-high))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           too-low))))

(deftest trade-history-pagination-next-prev-and-input-apply-test
  (let [state {:account-info {:trade-history {:page 2
                                              :page-input "2"}}}
        next-effects (core/next-trade-history-page state 3)
        prev-effects (core/prev-trade-history-page state 3)
        at-end-effects (core/next-trade-history-page
                        {:account-info {:trade-history {:page 3 :page-input "3"}}}
                        3)
        typed-state {:account-info {:trade-history {:page 1 :page-input "12"}}}
        apply-effects (core/apply-trade-history-page-input typed-state 4)
        invalid-apply-effects (core/apply-trade-history-page-input
                               {:account-info {:trade-history {:page 1 :page-input "abc"}}}
                               4)
        keydown-effects (core/handle-trade-history-page-input-keydown typed-state "Enter" 4)
        keydown-nop (core/handle-trade-history-page-input-keydown typed-state "Escape" 4)]
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 3]
                                 [[:account-info :trade-history :page-input] "3"]]]]
           next-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           prev-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 3]
                                 [[:account-info :trade-history :page-input] "3"]]]]
           at-end-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 4]
                                 [[:account-info :trade-history :page-input] "4"]]]]
           apply-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           invalid-apply-effects))
    (is (= apply-effects keydown-effects))
    (is (= [] keydown-nop))))

(deftest sort-trade-history-toggles-direction-on-same-column-test
  (let [state {:account-info {:trade-history {:sort {:column "Time"
                                                     :direction :desc}
                                              :page 3
                                              :page-input "3"}}}
        effects (core/sort-trade-history state "Time")]
    (is (= [[:effects/save-many [[[:account-info :trade-history :sort]
                                  {:column "Time" :direction :asc}]
                                 [[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           effects))))

(deftest sort-trade-history-uses-mixed-default-direction-for-new-columns-test
  (let [state {:account-info {:trade-history {:sort {:column "Time"
                                                     :direction :desc}
                                              :page 2
                                              :page-input "2"}}}
        coin-effects (core/sort-trade-history state "Coin")
        value-effects (core/sort-trade-history state "Trade Value")]
    (is (= [[:effects/save-many [[[:account-info :trade-history :sort]
                                  {:column "Coin" :direction :asc}]
                                 [[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           coin-effects))
    (is (= [[:effects/save-many [[[:account-info :trade-history :sort]
                                  {:column "Trade Value" :direction :desc}]
                                 [[:account-info :trade-history :page] 1]
                                 [[:account-info :trade-history :page-input] "1"]]]]
           value-effects))))

(deftest restore-trade-history-pagination-settings-uses-defaults-and-stored-size-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "trade-history-page-size" "100")
      (let [store (atom {:account-info {:trade-history {:page-size 25
                                                        :page 4
                                                        :page-input "4"}}})]
        (core/restore-trade-history-pagination-settings! store)
        (is (= {:page-size 100
                :page 1
                :page-input "1"}
               (select-keys (get-in @store [:account-info :trade-history])
                            [:page-size :page :page-input]))))
      (.setItem js/localStorage "trade-history-page-size" "13")
      (let [store (atom {:account-info {:trade-history {}}})]
        (core/restore-trade-history-pagination-settings! store)
        (is (= 50
               (get-in @store [:account-info :trade-history :page-size])))))))

(deftest select-account-info-tab-order-history-saves-selection-before-fetch-test
  (let [state {:account-info {:selected-tab :balances
                              :order-history {:request-id 2}}}
        effects (core/select-account-info-tab state :order-history)
        immediate (first effects)
        path-values (second immediate)]
    (is (= :effects/save-many (first immediate)))
    (is (= [:account-info :selected-tab]
           (-> path-values first first)))
    (is (= :order-history
           (-> path-values first second)))
    (is (effect-extractors/projection-before-heavy? effects account-tab-heavy-effect-ids))
    (is (effect-extractors/phase-order-valid? effects account-tab-heavy-effect-ids))
    (is (empty? (effect-extractors/duplicate-heavy-effect-ids effects account-tab-heavy-effect-ids)))
    (is (= [:effects/api-fetch-historical-orders 3]
           (second effects)))))

(deftest select-account-info-tab-order-history-skips-fetch-when-preloaded-data-is-fresh-test
  (with-redefs [platform/now-ms (constantly 200000)]
    (let [state {:wallet {:address "0xAbC"}
                 :account-info {:selected-tab :balances
                                :order-history {:request-id 2
                                                :loaded-at-ms 150000
                                                :loaded-for-address "0xabc"
                                                :error nil}}
                 :orders {:order-history []}}
          effects (core/select-account-info-tab state :order-history)]
      (is (= [[:effects/save [:account-info :selected-tab] :order-history]]
             effects)))))

(deftest select-account-info-tab-order-history-refetches-when-preload-is-stale-or-address-mismatched-test
  (with-redefs [platform/now-ms (constantly 200000)]
    (let [stale-state {:wallet {:address "0xabc"}
                       :account-info {:selected-tab :balances
                                      :order-history {:request-id 2
                                                      :loaded-at-ms 100000
                                                      :loaded-for-address "0xabc"
                                                      :error nil}}}
          wrong-address-state {:wallet {:address "0xabc"}
                               :account-info {:selected-tab :balances
                                              :order-history {:request-id 2
                                                              :loaded-at-ms 199000
                                                              :loaded-for-address "0xdef"
                                                              :error nil}}}
          stale-effects (core/select-account-info-tab stale-state :order-history)
          wrong-address-effects (core/select-account-info-tab wrong-address-state :order-history)]
      (is (= [:effects/api-fetch-historical-orders 3]
             (second stale-effects)))
      (is (= [:effects/api-fetch-historical-orders 3]
             (second wrong-address-effects))))))

(deftest sort-order-history-toggles-direction-on-same-column-test
  (let [state {:account-info {:order-history {:sort {:column "Time"
                                                     :direction :desc}}}}
        effects (core/sort-order-history state "Time")]
    (is (= [[:effects/save-many [[[:account-info :order-history :sort]
                                  {:column "Time" :direction :asc}]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           effects))))

(deftest sort-order-history-uses-mixed-default-direction-for-new-columns-test
  (let [state {:account-info {:order-history {:sort {:column "Time"
                                                     :direction :desc}}}}
        coin-effects (core/sort-order-history state "Coin")
        oid-effects (core/sort-order-history state "Order ID")]
    (is (= [[:effects/save-many [[[:account-info :order-history :sort]
                                  {:column "Coin" :direction :asc}]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           coin-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :sort]
                                  {:column "Order ID" :direction :desc}]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           oid-effects))))

(deftest order-history-filter-actions-update-paths-and-close-dropdown-test
  (let [state {:account-info {:order-history {:filter-open? false
                                              :status-filter :all}}}
        toggle-effects (core/toggle-order-history-filter-open state)
        set-effects (core/set-order-history-status-filter
                     {:account-info {:order-history {:filter-open? true
                                                     :status-filter :all}}}
                     :short)
        set-invalid-effects (core/set-order-history-status-filter
                             {:account-info {:order-history {:filter-open? true
                                                             :status-filter :all}}}
                             :unknown)]
    (is (= [[:effects/save [:account-info :order-history :filter-open?] true]]
           toggle-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :status-filter] :short]
                                 [[:account-info :order-history :filter-open?] false]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           set-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :status-filter] :all]
                                 [[:account-info :order-history :filter-open?] false]
                                 [[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           set-invalid-effects))))

(deftest order-history-pagination-page-size-normalizes-and-persists-test
  (with-test-local-storage
    (fn []
      (let [state {:account-info {:order-history {:page-size 25
                                                  :page 8
                                                  :page-input "8"}}}
            effects (core/set-order-history-page-size state "100")]
        (is (= [[:effects/save-many [[[:account-info :order-history :page-size] 100]
                                     [[:account-info :order-history :page] 1]
                                     [[:account-info :order-history :page-input] "1"]]]
                [:effects/local-storage-set "order-history-page-size" "100"]]
               effects))
        (let [invalid-effects (core/set-order-history-page-size state "13")]
          (is (= [[:effects/save-many [[[:account-info :order-history :page-size] 50]
                                       [[:account-info :order-history :page] 1]
                                       [[:account-info :order-history :page-input] "1"]]]
                  [:effects/local-storage-set "order-history-page-size" "50"]]
                 invalid-effects)))))))

(deftest order-history-pagination-set-page-clamps-and-syncs-input-test
  (let [state {:account-info {:order-history {:page 2
                                              :page-input "2"}}}
        within (core/set-order-history-page state 3 5)
        too-high (core/set-order-history-page state 99 5)
        too-low (core/set-order-history-page state -2 5)]
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 3]
                                 [[:account-info :order-history :page-input] "3"]]]]
           within))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 5]
                                 [[:account-info :order-history :page-input] "5"]]]]
           too-high))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           too-low))))

(deftest order-history-pagination-next-prev-and-input-apply-test
  (let [state {:account-info {:order-history {:page 2
                                              :page-input "2"}}}
        next-effects (core/next-order-history-page state 3)
        prev-effects (core/prev-order-history-page state 3)
        at-end-effects (core/next-order-history-page
                        {:account-info {:order-history {:page 3 :page-input "3"}}}
                        3)
        typed-state {:account-info {:order-history {:page 1 :page-input "12"}}}
        apply-effects (core/apply-order-history-page-input typed-state 4)
        invalid-apply-effects (core/apply-order-history-page-input
                               {:account-info {:order-history {:page 1 :page-input "abc"}}}
                               4)
        keydown-effects (core/handle-order-history-page-input-keydown typed-state "Enter" 4)
        keydown-nop (core/handle-order-history-page-input-keydown typed-state "Escape" 4)]
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 3]
                                 [[:account-info :order-history :page-input] "3"]]]]
           next-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           prev-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 3]
                                 [[:account-info :order-history :page-input] "3"]]]]
           at-end-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 4]
                                 [[:account-info :order-history :page-input] "4"]]]]
           apply-effects))
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 1]
                                 [[:account-info :order-history :page-input] "1"]]]]
           invalid-apply-effects))
    (is (= apply-effects keydown-effects))
    (is (= [] keydown-nop))))

(deftest restore-order-history-pagination-settings-uses-defaults-and-stored-size-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "order-history-page-size" "100")
      (let [store (atom {:account-info {:order-history {:page-size 25
                                                        :page 4
                                                        :page-input "4"}}})]
        (core/restore-order-history-pagination-settings! store)
        (is (= {:page-size 100
                :page 1
                :page-input "1"}
               (select-keys (get-in @store [:account-info :order-history])
                            [:page-size :page :page-input]))))
      (.setItem js/localStorage "order-history-page-size" "13")
      (let [store (atom {:account-info {:order-history {}}})]
        (core/restore-order-history-pagination-settings! store)
        (is (= 50
               (get-in @store [:account-info :order-history :page-size])))))))

(deftest refresh-order-history-emits-request-then-fetch-with-tab-aware-loading-test
  (let [selected-state {:account-info {:selected-tab :order-history
                                       :order-history {:request-id 5}}}
        background-state {:account-info {:selected-tab :balances
                                         :order-history {:request-id 5}}}
        selected-effects (core/refresh-order-history selected-state)
        background-effects (core/refresh-order-history background-state)]
    (is (= :effects/save-many (ffirst selected-effects)))
    (is (= [:effects/api-fetch-historical-orders 6]
           (second selected-effects)))
    (is (= true
           (-> selected-effects first second (nth 1) second)))
    (is (= false
           (-> background-effects first second (nth 1) second)))
    (is (= [:effects/api-fetch-historical-orders 6]
           (second background-effects)))))
