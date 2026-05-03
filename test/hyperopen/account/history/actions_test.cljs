(ns hyperopen.account.history.actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.account.history.actions :as history-actions]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.platform :as platform]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]))

(defn- info-funding-row
  [time-ms coin usdc signed-size funding-rate]
  (funding-history/normalize-info-funding-row
   {:time time-ms
    :delta {:type "funding"
            :coin coin
            :usdc usdc
            :szi signed-size
            :fundingRate funding-rate}}))

(deftest normalize-order-history-page-covers-single-arity-and-clamping-test
  (is (= 1 (history-actions/normalize-order-history-page nil)))
  (is (= 1 (history-actions/normalize-order-history-page "0")))
  (is (= 7 (history-actions/normalize-order-history-page "7")))
  (is (= 3 (history-actions/normalize-order-history-page "7" 3)))
  (is (= 1 (history-actions/normalize-order-history-page "9" 0)))
  (is (= 1 (history-actions/normalize-order-history-page "abc" "nope"))))

(deftest normalize-order-history-page-supports-localized-integer-inputs-test
  (is (= 1234
         (history-actions/normalize-order-history-page (str "1\u202F234") nil "fr-FR")))
  (is (= 100
         (history-actions/normalize-order-history-page-size "100,0" "fr-FR")))
  (is (= 1
         (history-actions/normalize-order-history-page "abc" 2000 "fr-FR"))))

(deftest apply-order-history-page-input-parses-localized-page-input-test
  (let [state {:ui {:locale "fr-FR"}
               :account-info {:order-history {:page-input (str "1\u202F234")}}}]
    (is (= [[:effects/save-many [[[:account-info :order-history :page] 1234]
                                 [[:account-info :order-history :page-input] "1234"]]]]
           (history-actions/apply-order-history-page-input state 2000)))))

(deftest restore-open-orders-sort-settings-covers-valid-and-fallback-values-test
  (with-redefs [platform/local-storage-get (fn [key]
                                             (case key
                                               "open-orders-sort-by" "Price"
                                               "open-orders-sort-direction" "asc"
                                               nil))]
    (let [store (atom {:account-info {}})]
      (history-actions/restore-open-orders-sort-settings! store)
      (is (= {:column "Price" :direction :asc}
             (get-in @store [:account-info :open-orders-sort])))))
  (with-redefs [platform/local-storage-get (fn [key]
                                             (case key
                                               "open-orders-sort-by" "Unsupported"
                                               "open-orders-sort-direction" "sideways"
                                               nil))]
    (let [store (atom {:account-info {}})]
      (history-actions/restore-open-orders-sort-settings! store)
      (is (= {:column "Time" :direction :desc}
             (get-in @store [:account-info :open-orders-sort]))))))

(deftest set-funding-history-filters-normalizes-paths-and-datetime-values-test
  (let [datetime-text "2026-01-02T03:04:05"
        datetime-ms (js/Math.floor (.parse js/Date datetime-text))]
    (is (= [[:effects/save [:account-info :funding-history :draft-filters :start-time-ms]
             datetime-ms]]
           (history-actions/set-funding-history-filters {} [:draft-filters :start-time-ms] datetime-text)))
    (is (= [[:effects/save [:account-info :funding-history :filters :end-time-ms] nil]]
           (history-actions/set-funding-history-filters {} [:filters :end-time-ms] "  ")))
    (is (= [[:effects/save [:account-info :funding-history :filter-open?] true]]
           (history-actions/set-funding-history-filters {} :filter-open? true)))
    (is (= [[:effects/save [:account-info :funding-history :coin-search] "42"]]
           (history-actions/set-funding-history-filters {} :coin-search 42)))
    (is (= [[:effects/save [:account-info :funding-history :coin-suggestions-open?] true]]
           (history-actions/set-funding-history-filters {} :coin-suggestions-open? "yes")))))

(deftest toggle-funding-history-filter-open-covers-open-and-closed-branches-test
  (with-redefs [platform/now-ms (constantly 2000)]
    (let [filters {:coin-set #{"" "BTC"}
                   :start-time-ms 10
                   :end-time-ms 20}
          normalized-filters (funding-history/normalize-funding-history-filters filters 2000)
          draft-filters {:coin-set #{"ETH" ""}
                         :start-time-ms 30
                         :end-time-ms 40}
          normalized-draft (funding-history/normalize-funding-history-filters draft-filters 2000)]
      (is (= [[:effects/save-many [[[:account-info :funding-history :filter-open?] true]
                                   [[:account-info :funding-history :draft-filters] normalized-filters]
                                   [[:account-info :funding-history :coin-search] ""]
                                   [[:account-info :funding-history :coin-suggestions-open?] false]]]]
             (history-actions/toggle-funding-history-filter-open
              {:account-info {:funding-history {:filter-open? false
                                                :filters filters}}})))
      (is (= [[:effects/save-many [[[:account-info :funding-history :filter-open?] false]
                                   [[:account-info :funding-history :draft-filters] normalized-draft]
                                   [[:account-info :funding-history :coin-search] ""]
                                   [[:account-info :funding-history :coin-suggestions-open?] false]]]]
             (history-actions/toggle-funding-history-filter-open
              {:account-info {:funding-history {:filter-open? true
                                                :filters filters
                                                :draft-filters draft-filters}}})))
      (is (= [[:effects/save-many [[[:account-info :funding-history :filter-open?] false]
                                   [[:account-info :funding-history :draft-filters] normalized-filters]
                                   [[:account-info :funding-history :coin-search] ""]
                                   [[:account-info :funding-history :coin-suggestions-open?] false]]]]
             (history-actions/toggle-funding-history-filter-open
              {:account-info {:funding-history {:filter-open? true
                                                :filters filters}}}))))))

(deftest toggle-and-reset-funding-history-filter-draft-covers-coin-and-reset-branches-test
  (let [draft-filters {:coin-set #{"BTC"}
                       :start-time-ms 10
                       :end-time-ms 20}
        state {:account-info {:funding-history {:draft-filters draft-filters}}}]
    (is (= [[:effects/save [:account-info :funding-history :draft-filters]
             {:coin-set #{"BTC" "ETH"}
              :start-time-ms 10
              :end-time-ms 20}]]
           (history-actions/toggle-funding-history-filter-coin state "ETH")))
    (is (= [[:effects/save [:account-info :funding-history :draft-filters]
             {:coin-set #{}
              :start-time-ms 10
              :end-time-ms 20}]]
           (history-actions/toggle-funding-history-filter-coin state "BTC")))
    (is (= [[:effects/save [:account-info :funding-history :draft-filters]
             {:coin-set #{"SOL"}
              :start-time-ms 10
              :end-time-ms 20}]]
           (history-actions/toggle-funding-history-filter-coin
            {:account-info {:funding-history {:draft-filters {:start-time-ms 10
                                                              :end-time-ms 20}}}}
            "SOL")))
    (is (= []
           (history-actions/toggle-funding-history-filter-coin state "   "))))
  (with-redefs [platform/now-ms (constantly 2000)]
    (let [filters {:coin-set #{"BTC" ""}
                   :start-time-ms 50
                   :end-time-ms 75}
          normalized-filters (funding-history/normalize-funding-history-filters filters 2000)]
      (is (= [[:effects/save-many [[[:account-info :funding-history :draft-filters] normalized-filters]
                                   [[:account-info :funding-history :filter-open?] false]
                                   [[:account-info :funding-history :coin-search] ""]
                                   [[:account-info :funding-history :coin-suggestions-open?] false]]]]
             (history-actions/reset-funding-history-filter-draft
              {:account-info {:funding-history {:filter-open? true
                                                :filters filters}}}))))))

(deftest add-funding-history-filter-coin-and-keydown-handler-cover-enter-and-escape-test
  (let [draft-filters {:coin-set #{"BTC"}
                       :start-time-ms 10
                       :end-time-ms 20}
        state {:account-info {:funding-history {:draft-filters draft-filters}}}]
    (is (= [[:effects/save-many [[[:account-info :funding-history :draft-filters]
                                   {:coin-set #{"BTC" "ETH"}
                                    :start-time-ms 10
                                    :end-time-ms 20}]
                                  [[:account-info :funding-history :coin-search] ""]
                                  [[:account-info :funding-history :coin-suggestions-open?] true]]]]
           (history-actions/add-funding-history-filter-coin state "ETH")))
    (is (= []
           (history-actions/add-funding-history-filter-coin state "  ")))
    (is (= [[:effects/save-many [[[:account-info :funding-history :draft-filters]
                                   {:coin-set #{"BTC" "SOL"}
                                    :start-time-ms 10
                                    :end-time-ms 20}]
                                  [[:account-info :funding-history :coin-search] ""]
                                  [[:account-info :funding-history :coin-suggestions-open?] true]]]]
           (history-actions/handle-funding-history-coin-search-keydown state "Enter" "SOL")))
    (is (= [[:effects/save [:account-info :funding-history :coin-suggestions-open?] false]]
           (history-actions/handle-funding-history-coin-search-keydown state "Escape" nil)))
    (is (= []
           (history-actions/handle-funding-history-coin-search-keydown state "Tab" "SOL")))))

(deftest select-account-info-tab-and-export-funding-history-csv-cover-default-and-filtered-projection-test
  (let [btc-row (info-funding-row 1700003600000 "BTC" "0.1000" "10" "0.0001")
        eth-row (info-funding-row 1700000000000 "ETH" "0.0500" "5" "0.0002")
        state {:account-info {:funding-history {:filters {:coin-set #{"BTC"}
                                                          :start-time-ms 0
                                                          :end-time-ms 2000000000000}}}
               :orders {:fundings-raw [eth-row btc-row]}}]
    (is (= [[:effects/save [:account-info :selected-tab] :balances]]
           (history-actions/select-account-info-tab state :balances)))
    (is (= [[:effects/export-funding-history-csv [btc-row]]]
           (history-actions/export-funding-history-csv state)))))

(deftest select-account-info-tab-order-history-uses-effective-address-for-freshness-test
  (with-redefs [platform/now-ms (constantly 100000)]
    (let [spectate-address "0xdddddddddddddddddddddddddddddddddddddddd"
          state {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                 :account-context {:spectate-mode {:active? true
                                                :address spectate-address}}
                 :account-info {:order-history {:loading? false
                                                :loaded-at-ms 99000
                                                :loaded-for-address spectate-address
                                                :error nil}}}]
      (is (= [[:effects/save [:account-info :selected-tab] :order-history]]
             (history-actions/select-account-info-tab state :order-history))))))

(deftest select-account-info-tab-syncs-trade-url-with-market-and-tab-query-test
  (let [state {:router {:path "/trade"}
               :active-asset "ETH"
               :account-info {:selected-tab :balances}}
        effects (history-actions/select-account-info-tab state :outcomes)]
    (is (= [[:effects/save [:account-info :selected-tab] :outcomes]
            [:effects/push-state "/trade?market=ETH&tab=outcomes"]]
           effects))))

(deftest select-account-info-tab-sync-preserves-spectate-query-when-active-test
  (let [spectate-address "0xdddddddddddddddddddddddddddddddddddddddd"
        state {:router {:path "/trade"}
               :active-asset "ETH"
               :account-info {:selected-tab :balances}
               :account-context {:spectate-mode {:active? true
                                                 :address spectate-address}}}
        effects (history-actions/select-account-info-tab state :positions)]
    (is (= [[:effects/save [:account-info :selected-tab] :positions]
            [:effects/push-state
             "/trade?market=ETH&tab=positions&spectate=0xdddddddddddddddddddddddddddddddddddddddd"]]
           effects))))

(deftest sort-positions-balances-and-open-orders-cover-direction-branches-test
  (testing "positions and balances toggle to desc only for same-column asc"
    (is (= [[:effects/save [:account-info :positions-sort] {:column "Coin" :direction :desc}]]
           (history-actions/sort-positions
            {:account-info {:positions-sort {:column "Coin" :direction :asc}}}
            "Coin")))
    (is (= [[:effects/save [:account-info :balances-sort] {:column "Coin" :direction :asc}]]
           (history-actions/sort-balances
            {:account-info {:balances-sort {:column "Value" :direction :asc}}}
            "Coin"))))
  (testing "open orders uses mixed default direction for new columns and persists to storage"
    (is (= [[:effects/save [:account-info :open-orders-sort] {:column "Time" :direction :desc}]
            [:effects/local-storage-set "open-orders-sort-by" "Time"]
            [:effects/local-storage-set "open-orders-sort-direction" "desc"]]
           (history-actions/sort-open-orders
            {:account-info {:open-orders-sort {:column "Time" :direction :asc}}}
            "Time")))
    (is (= [[:effects/save [:account-info :open-orders-sort] {:column "Price" :direction :desc}]
            [:effects/local-storage-set "open-orders-sort-by" "Price"]
            [:effects/local-storage-set "open-orders-sort-direction" "desc"]]
           (history-actions/sort-open-orders
            {:account-info {:open-orders-sort {:column "Coin" :direction :asc}}}
            "Price")))
    (is (= [[:effects/save [:account-info :open-orders-sort] {:column "Coin" :direction :asc}]
            [:effects/local-storage-set "open-orders-sort-by" "Coin"]
            [:effects/local-storage-set "open-orders-sort-direction" "asc"]]
           (history-actions/sort-open-orders
            {:account-info {:open-orders-sort {:column "Price" :direction :desc}}}
            "Coin")))))

(deftest history-page-input-setters-stringify-non-strings-and-preserve-strings-test
  (is (= [[:effects/save [:account-info :funding-history :page-input] "12"]]
         (history-actions/set-funding-history-page-input nil 12)))
  (is (= [[:effects/save [:account-info :funding-history :page-input] "03"]]
         (history-actions/set-funding-history-page-input nil "03")))
  (is (= [[:effects/save [:account-info :trade-history :page-input] ""]]
         (history-actions/set-trade-history-page-input nil nil)))
  (is (= [[:effects/save [:account-info :order-history :page-input] "8"]]
         (history-actions/set-order-history-page-input nil 8))))

(deftest set-order-history-status-filter-parses-strings-and-falls-back-to-all-test
  (is (= [[:effects/save-many [[[:account-info :order-history :status-filter] :short]
                               [[:account-info :order-history :filter-open?] false]
                               [[:account-info :order-history :page] 1]
                               [[:account-info :order-history :page-input] "1"]]]]
         (history-actions/set-order-history-status-filter nil "ShOrT")))
  (is (= [[:effects/save-many [[[:account-info :order-history :status-filter] :all]
                               [[:account-info :order-history :filter-open?] false]
                               [[:account-info :order-history :page] 1]
                               [[:account-info :order-history :page-input] "1"]]]]
         (history-actions/set-order-history-status-filter nil "invalid-status"))))

(deftest open-orders-direction-filter-actions-normalize-and-close-dropdown-test
  (is (= [[:effects/save [:account-info :open-orders :filter-open?] true]]
         (history-actions/toggle-open-orders-direction-filter-open
          {:account-info {:open-orders {:filter-open? false}}})))
  (is (= [[:effects/save [:account-info :open-orders :filter-open?] false]]
         (history-actions/toggle-open-orders-direction-filter-open
          {:account-info {:open-orders {:filter-open? true}}})))
  (is (= [[:effects/save-many [[[:account-info :open-orders :direction-filter] :short]
                               [[:account-info :open-orders :filter-open?] false]]]]
         (history-actions/set-open-orders-direction-filter nil "ShOrT")))
  (is (= [[:effects/save-many [[[:account-info :open-orders :direction-filter] :all]
                               [[:account-info :open-orders :filter-open?] false]]]]
         (history-actions/set-open-orders-direction-filter nil "invalid-filter"))))

(deftest positions-direction-filter-actions-normalize-and-close-dropdown-test
  (is (= [[:effects/save [:account-info :positions :filter-open?] true]]
         (history-actions/toggle-positions-direction-filter-open
          {:account-info {:positions {:filter-open? false}}})))
  (is (= [[:effects/save [:account-info :positions :filter-open?] false]]
         (history-actions/toggle-positions-direction-filter-open
          {:account-info {:positions {:filter-open? true}}})))
  (is (= [[:effects/save-many [[[:account-info :positions :direction-filter] :short]
                               [[:account-info :positions :filter-open?] false]]]]
         (history-actions/set-positions-direction-filter nil "ShOrT")))
  (is (= [[:effects/save-many [[[:account-info :positions :direction-filter] :all]
                               [[:account-info :positions :filter-open?] false]]]]
         (history-actions/set-positions-direction-filter nil "invalid-filter"))))

(deftest trade-history-direction-filter-actions-normalize-close-dropdown-and-reset-pagination-test
  (is (= [[:effects/save [:account-info :trade-history :filter-open?] true]]
         (history-actions/toggle-trade-history-direction-filter-open
          {:account-info {:trade-history {:filter-open? false}}})))
  (is (= [[:effects/save [:account-info :trade-history :filter-open?] false]]
         (history-actions/toggle-trade-history-direction-filter-open
          {:account-info {:trade-history {:filter-open? true}}})))
  (is (= [[:effects/save-many [[[:account-info :trade-history :direction-filter] :short]
                               [[:account-info :trade-history :filter-open?] false]
                               [[:account-info :trade-history :page] 1]
                               [[:account-info :trade-history :page-input] "1"]]]]
         (history-actions/set-trade-history-direction-filter nil "ShOrT")))
  (is (= [[:effects/save-many [[[:account-info :trade-history :direction-filter] :all]
                               [[:account-info :trade-history :filter-open?] false]
                               [[:account-info :trade-history :page] 1]
                               [[:account-info :trade-history :page-input] "1"]]]]
         (history-actions/set-trade-history-direction-filter nil "invalid-filter"))))

(deftest set-hide-small-balances-updates-flag-test
  (is (= [[:effects/save [:account-info :hide-small-balances?] true]]
         (history-actions/set-hide-small-balances nil true))))

(deftest set-account-info-coin-search-updates-tab-specific-state-test
  (is (= [[:effects/save [:account-info :balances-coin-search] "ETH"]]
         (history-actions/set-account-info-coin-search nil :balances "ETH")))
  (is (= [[:effects/save [:account-info :positions :coin-search] "nv"]]
         (history-actions/set-account-info-coin-search nil "PoSiTiOnS" "nv")))
  (is (= [[:effects/save [:account-info :open-orders :coin-search] "sol"]]
         (history-actions/set-account-info-coin-search nil :open-orders "sol")))
  (is (= [[:effects/save-many [[[:account-info :trade-history :coin-search] "123"]
                               [[:account-info :trade-history :page] 1]
                               [[:account-info :trade-history :page-input] "1"]]]]
         (history-actions/set-account-info-coin-search nil :trade-history 123)))
  (is (= [[:effects/save-many [[[:account-info :order-history :coin-search] "42"]
                               [[:account-info :order-history :page] 1]
                               [[:account-info :order-history :page-input] "1"]]]]
         (history-actions/set-account-info-coin-search nil :order-history 42)))
  (is (= [[:effects/save [:account-info :balances-coin-search] "fallback"]]
         (history-actions/set-account-info-coin-search nil :unknown "fallback"))))

(deftest position-tpsl-modal-actions-open-update-close-test
  (let [row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        open-effects (history-actions/open-position-tpsl-modal {} row)
        opened-modal (get-in (first open-effects) [1 0 1])
        reset-reduce-popover (get-in (first open-effects) [1 1 1])
        reset-margin-modal (get-in (first open-effects) [1 2 1])
        updated-effects (history-actions/set-position-tpsl-modal-field
                         {:positions-ui {:tpsl-modal opened-modal}}
                         [:tp-price]
                         "20.25")
        closed-effects (history-actions/close-position-tpsl-modal {})]
    (is (= :effects/save-many
           (ffirst open-effects)))
    (is (true? (:open? opened-modal)))
    (is (= "xyz:NVDA" (:coin opened-modal)))
    (is (= (position-reduce/default-popover-state) reset-reduce-popover))
    (is (= (position-margin/default-modal-state) reset-margin-modal))
    (is (= "20.25"
           (get-in (nth (first updated-effects) 2) [:tp-price])))
    (is (= [[:effects/save [:positions-ui :tpsl-modal]
             (position-tpsl/default-modal-state)]]
           closed-effects))
    (is (= [[:effects/save [:positions-ui :tpsl-modal]
             (position-tpsl/default-modal-state)]]
           (history-actions/handle-position-tpsl-modal-keydown {} "Escape")))
    (is (= []
           (history-actions/handle-position-tpsl-modal-keydown {} "Enter")))))

(deftest position-modal-open-actions-propagate-ui-locale-test
  (let [row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        state {:ui {:locale "fr-FR"}}
        tpsl-effects (history-actions/open-position-tpsl-modal state row)
        margin-effects (history-actions/open-position-margin-modal state row)
        reduce-effects (history-actions/open-position-reduce-popover state row)
        tpsl-modal (get-in (first tpsl-effects) [1 0 1])
        margin-modal (get-in (first margin-effects) [1 0 1])
        reduce-popover (get-in (first reduce-effects) [1 0 1])]
    (is (= "fr-FR" (:locale tpsl-modal)))
    (is (= "fr-FR" (:locale margin-modal)))
    (is (= "fr-FR" (:locale reduce-popover)))))

(deftest position-overlay-open-actions-normalize-js-anchor-objects-test
  (let [row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        js-anchor #js {:left 390
                       :right 414
                       :top 884
                       :bottom 908
                       :width 24
                       :height 24
                       "viewport-width" 430
                       "viewport-height" 932}
        tpsl-effects (history-actions/open-position-tpsl-modal {} row js-anchor)
        margin-effects (history-actions/open-position-margin-modal {} row js-anchor)
        reduce-effects (history-actions/open-position-reduce-popover {} row js-anchor)
        tpsl-anchor (get-in (first tpsl-effects) [1 0 1 :anchor])
        margin-anchor (get-in (first margin-effects) [1 0 1 :anchor])
        reduce-anchor (get-in (first reduce-effects) [1 0 1 :anchor])]
    (is (= {:left 390
            :right 414
            :top 884
            :bottom 908
            :width 24
            :height 24
            :viewport-width 430
            :viewport-height 932}
           tpsl-anchor))
    (is (= tpsl-anchor margin-anchor))
    (is (= tpsl-anchor reduce-anchor))))

(deftest position-reduce-popover-actions-open-update-close-test
  (let [row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        open-effects (history-actions/open-position-reduce-popover {} row)
        opened-popover (get-in (first open-effects) [1 0 1])
        reset-tpsl-modal (get-in (first open-effects) [1 1 1])
        reset-margin-modal (get-in (first open-effects) [1 2 1])
        updated-effects (history-actions/set-position-reduce-popover-field
                         {:positions-ui {:reduce-popover opened-popover}}
                         [:size-percent-input]
                         "75")
        preset-effects (history-actions/set-position-reduce-size-percent
                        {:positions-ui {:reduce-popover opened-popover}}
                        25)
        mid-effects (history-actions/set-position-reduce-limit-price-to-mid
                     {:positions-ui {:reduce-popover (assoc opened-popover :limit-price "")}})
        closed-effects (history-actions/close-position-reduce-popover {})
        submit-effects (history-actions/submit-position-reduce-close {})
        close-all-effects (history-actions/trigger-close-all-positions {})]
    (is (= :effects/save-many
           (ffirst open-effects)))
    (is (true? (:open? opened-popover)))
    (is (= "xyz:NVDA" (:coin opened-popover)))
    (is (= "10" (:mid-price opened-popover)))
    (is (= (position-tpsl/default-modal-state) reset-tpsl-modal))
    (is (= (position-margin/default-modal-state) reset-margin-modal))
    (is (= "75"
           (get-in (nth (first updated-effects) 2) [:size-percent-input])))
    (is (= "25"
           (get-in (nth (first preset-effects) 2) [:size-percent-input])))
    (is (= (:mid-price opened-popover)
           (get-in (nth (first mid-effects) 2) [:limit-price])))
    (is (= [[:effects/save [:positions-ui :reduce-popover]
             (position-reduce/default-popover-state)]]
           closed-effects))
    (is (= [[:effects/save [:positions-ui :reduce-popover]
             (position-reduce/default-popover-state)]]
           (history-actions/handle-position-reduce-popover-keydown {} "Escape")))
    (is (= []
           (history-actions/handle-position-reduce-popover-keydown {} "Enter")))
    (is (= [[:effects/save
             [:positions-ui :reduce-popover]
             (assoc (position-reduce/default-popover-state)
                    :error "Place Order")]]
           submit-effects))
    (is (= [] close-all-effects))))

(deftest position-reduce-popover-parses-localized-input-values-test
  (let [row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        state {:ui {:locale "fr-FR"}}
        open-effects (history-actions/open-position-reduce-popover state row)
        opened-popover (get-in (first open-effects) [1 0 1])
        percent-effects (history-actions/set-position-reduce-popover-field
                         {:ui {:locale "fr-FR"}
                          :positions-ui {:reduce-popover opened-popover}}
                         [:size-percent-input]
                         "25,5")
        percent-popover (get-in (first percent-effects) [2])
        market-state {:asset-selector {:market-by-key {"perp:xyz:NVDA"
                                                       {:coin "xyz:NVDA"
                                                        :market-type :perp
                                                        :asset-id 123
                                                        :mark 10}}}}
        submit-effects (history-actions/submit-position-reduce-close
                        (assoc market-state
                               :trading-settings {:confirm-close-position? false}
                               :ui {:locale "fr-FR"}
                               :positions-ui {:reduce-popover (assoc percent-popover
                                                                     :close-type :limit
                                                                     :limit-price "11,5")}))
        submitted-order (get-in submit-effects [1 1 :action :orders 0])]
    (is (= "fr-FR" (:locale opened-popover)))
    (is (= "25.5" (:size-percent-input percent-popover)))
    (is (= :effects/api-submit-order
           (first (second submit-effects))))
    (is (= "11.5" (:p submitted-order)))
    (is (= "0.1275" (:s submitted-order)))))

(deftest submit-position-tpsl-validates-and-emits-submit-effect-test
  (let [row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        modal (-> (position-tpsl/from-position-row row)
                  (assoc :tp-price "11.0"))
        state {:positions-ui {:tpsl-modal modal}
               :asset-selector {:market-by-key {"perp:xyz:NVDA"
                                                {:coin "xyz:NVDA"
                                                 :market-type :perp
                                                 :asset-id 123}}}}
        valid-effects (history-actions/submit-position-tpsl state)
        invalid-effects (history-actions/submit-position-tpsl
                         {:positions-ui {:tpsl-modal (position-tpsl/from-position-row row)}
                          :asset-selector {:market-by-key {"perp:xyz:NVDA"
                                                           {:coin "xyz:NVDA"
                                                            :market-type :perp
                                                            :asset-id 123}}}})]
    (is (= :effects/save-many
           (ffirst valid-effects)))
    (is (= [[:positions-ui :tpsl-modal :submitting?] true]
           (first (second (first valid-effects)))))
    (is (= :effects/api-submit-position-tpsl
           (first (second valid-effects))))
    (is (= "order"
           (get-in (second (second valid-effects)) [:action :type])))
    (is (= "tp"
           (get-in (second (second valid-effects))
                   [:action :orders 0 :t :trigger :tpsl])))
    (is (= [[:effects/save-many [[[:positions-ui :tpsl-modal :submitting?] false]
                                 [[:positions-ui :tpsl-modal :error] "Place Order"]]]]
           invalid-effects))))

(deftest submit-position-reduce-close-validates-and-emits-submit-effect-test
  (let [row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        popover (-> (position-reduce/from-position-row row)
                    (assoc :close-type :limit
                           :limit-price "11"))
        market-state {:asset-selector {:market-by-key {"perp:xyz:NVDA"
                                                       {:coin "xyz:NVDA"
                                                        :market-type :perp
                                                        :asset-id 123
                                                        :mark 10}}}}
        valid-effects (history-actions/submit-position-reduce-close
                       (assoc market-state
                              :trading-settings {:confirm-close-position? false}
                              :positions-ui {:reduce-popover popover}))
        invalid-effects (history-actions/submit-position-reduce-close
                         (assoc market-state
                                :positions-ui {:reduce-popover (assoc popover :limit-price "")}))]
    (is (= :effects/save
           (ffirst valid-effects)))
    (is (nil? (get-in (first valid-effects) [2 :error])))
    (is (= :effects/api-submit-order
           (first (second valid-effects))))
    (is (= "order"
           (get-in (second (second valid-effects)) [:action :type])))
    (is (= true
           (get-in (second (second valid-effects)) [:action :orders 0 :r])))
    (is (= false
           (get-in (second (second valid-effects)) [:action :orders 0 :b])))
    (is (= [[:effects/save
             [:positions-ui :reduce-popover]
            (assoc popover
                    :limit-price ""
                    :error "Price is required for limit orders.")]]
           invalid-effects))))

(deftest submit-position-reduce-close-emits-confirm-effect-when-enabled-test
  (let [row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        popover (-> (position-reduce/from-position-row row)
                    (assoc :close-type :limit
                           :limit-price "11"))
        state {:trading-settings {:confirm-close-position? true}
               :positions-ui {:reduce-popover popover}
               :asset-selector {:market-by-key {"perp:xyz:NVDA"
                                                {:coin "xyz:NVDA"
                                                 :market-type :perp
                                                 :asset-id 123
                                                 :mark 10}}}}
        effects (history-actions/submit-position-reduce-close state)
        confirm-effect (first effects)
        payload (second confirm-effect)]
    (is (= 1 (count effects)))
    (is (= :effects/confirm-api-submit-order (first confirm-effect)))
    (is (= :close-position
           (:variant payload)))
    (is (= "Submit this close order?\n\nDisable close-position confirmation in Trading settings if you prefer one-click closes."
           (:message payload)))
    (is (= [[:positions-ui :reduce-popover] (assoc popover :error nil)]
           (first (:path-values payload))))
    (is (= "order"
           (get-in payload [:request :action :type])))))

(deftest position-margin-modal-actions-open-update-close-test
  (let [row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        open-effects (history-actions/open-position-margin-modal {} row)
        opened-modal (get-in (first open-effects) [1 0 1])
        reset-tpsl-modal (get-in (first open-effects) [1 1 1])
        reset-reduce-popover (get-in (first open-effects) [1 2 1])
        updated-effects (history-actions/set-position-margin-modal-field
                         {:positions-ui {:margin-modal opened-modal}}
                         [:amount-input]
                         "1.5")
        percent-effects (history-actions/set-position-margin-amount-percent
                         {:positions-ui {:margin-modal opened-modal}}
                         25)
        max-effects (history-actions/set-position-margin-amount-to-max
                     {:positions-ui {:margin-modal (assoc opened-modal :available-to-add 5)}})
        closed-effects (history-actions/close-position-margin-modal {})]
    (is (= :effects/save-many
           (ffirst open-effects)))
    (is (true? (:open? opened-modal)))
    (is (= "xyz:NVDA" (:coin opened-modal)))
    (is (= (position-tpsl/default-modal-state) reset-tpsl-modal))
    (is (= (position-reduce/default-popover-state) reset-reduce-popover))
    (is (= "1.5"
           (get-in (nth (first updated-effects) 2) [:amount-input])))
    (is (= "25"
           (get-in (nth (first percent-effects) 2) [:amount-percent-input])))
    (is (= "100"
           (get-in (nth (first max-effects) 2) [:amount-percent-input])))
    (is (= [[:effects/save [:positions-ui :margin-modal]
             (position-margin/default-modal-state)]]
           closed-effects))
    (is (= [[:effects/save [:positions-ui :margin-modal]
             (position-margin/default-modal-state)]]
           (history-actions/handle-position-margin-modal-keydown {} "Escape")))
    (is (= []
           (history-actions/handle-position-margin-modal-keydown {} "Enter")))))

(deftest submit-position-margin-update-validates-and-emits-submit-effect-test
  (let [row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        modal (-> (position-margin/from-position-row {} row)
                  (assoc :available-to-add 10
                         :amount-input "1.25"))
        state {:positions-ui {:margin-modal modal}
               :asset-selector {:market-by-key {"perp:xyz:NVDA"
                                                {:coin "xyz:NVDA"
                                                 :market-type :perp
                                                 :asset-id 123}}}}
        valid-effects (history-actions/submit-position-margin-update state)
        invalid-effects (history-actions/submit-position-margin-update
                         {:positions-ui {:margin-modal (assoc (position-margin/from-position-row {} row)
                                                              :available-to-add 5)}
                          :asset-selector {:market-by-key {"perp:xyz:NVDA"
                                                           {:coin "xyz:NVDA"
                                                            :market-type :perp
                                                            :asset-id 123}}}})]
    (is (= :effects/save-many
           (ffirst valid-effects)))
    (is (= [[:positions-ui :margin-modal :submitting?] true]
           (first (second (first valid-effects)))))
    (is (= :effects/api-submit-position-margin
           (first (second valid-effects))))
    (is (= "updateIsolatedMargin"
           (get-in (second (second valid-effects)) [:action :type])))
    (is (= 123
           (get-in (second (second valid-effects)) [:action :asset])))
    (is (= 1250000
           (get-in (second (second valid-effects)) [:action :ntli])))
    (is (= true
           (get-in (second (second valid-effects)) [:action :isBuy])))
    (is (= [[:effects/save-many [[[:positions-ui :margin-modal :submitting?] false]
                                 [[:positions-ui :margin-modal :error] "Select an amount"]]]]
           invalid-effects))))

(deftest open-position-margin-modal-preserves-chart-drag-prefill-fields-test
  (let [row (assoc (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                   :prefill-source :chart-liquidation-drag
                   :prefill-margin-mode :add
                   :prefill-margin-amount 1.75
                   :prefill-liquidation-current-price 4.2
                   :prefill-liquidation-target-price 2.1)
        state {:webdata2 {:clearinghouseState {:marginSummary {:accountValue "10"
                                                                :totalMarginUsed "1"}}}}
        effects (history-actions/open-position-margin-modal state row)
        modal (get-in (first effects) [1 0 1])]
    (is (= :chart-liquidation-drag (:prefill-source modal)))
    (is (= :add (:mode modal)))
    (is (= "1.75" (:amount-input modal)))
    (is (= 4.2 (:prefill-liquidation-current-price modal)))
    (is (= 2.1 (:prefill-liquidation-target-price modal)))))

(deftest toggle-account-info-mobile-card-saves-collapses-and-ignores-invalid-inputs-test
  (let [state {:account-info {:mobile-expanded-card {:balances "btc"
                                                     :positions nil
                                                     :trade-history nil}}}]
    (is (= [[:effects/save [:account-info :mobile-expanded-card :balances] "eth"]]
           (history-actions/toggle-account-info-mobile-card state :balances "eth")))
    (is (= [[:effects/save [:account-info :mobile-expanded-card :balances] nil]]
           (history-actions/toggle-account-info-mobile-card state :balances "btc")))
    (is (= [[:effects/save [:account-info :mobile-expanded-card :positions] "nvda"]]
           (history-actions/toggle-account-info-mobile-card state :positions "nvda")))
    (is (= [[:effects/save [:account-info :mobile-expanded-card :trade-history] "42"]]
           (history-actions/toggle-account-info-mobile-card state "trade-history" 42)))
    (is (= []
           (history-actions/toggle-account-info-mobile-card state :open-orders "btc")))
    (is (= []
           (history-actions/toggle-account-info-mobile-card state :balances "   ")))))
