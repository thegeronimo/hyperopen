(ns hyperopen.account.history.actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.account.history.actions :as history-actions]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.platform :as platform]))

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
           (history-actions/set-funding-history-filters {} :filter-open? true)))))

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
                                   [[:account-info :funding-history :draft-filters] normalized-filters]]]]
             (history-actions/toggle-funding-history-filter-open
              {:account-info {:funding-history {:filter-open? false
                                                :filters filters}}})))
      (is (= [[:effects/save-many [[[:account-info :funding-history :filter-open?] false]
                                   [[:account-info :funding-history :draft-filters] normalized-draft]]]]
             (history-actions/toggle-funding-history-filter-open
              {:account-info {:funding-history {:filter-open? true
                                                :filters filters
                                                :draft-filters draft-filters}}})))
      (is (= [[:effects/save-many [[[:account-info :funding-history :filter-open?] false]
                                   [[:account-info :funding-history :draft-filters] normalized-filters]]]]
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
            "SOL"))))
  (with-redefs [platform/now-ms (constantly 2000)]
    (let [filters {:coin-set #{"BTC" ""}
                   :start-time-ms 50
                   :end-time-ms 75}
          normalized-filters (funding-history/normalize-funding-history-filters filters 2000)]
      (is (= [[:effects/save-many [[[:account-info :funding-history :draft-filters] normalized-filters]
                                   [[:account-info :funding-history :filter-open?] false]]]]
             (history-actions/reset-funding-history-filter-draft
              {:account-info {:funding-history {:filter-open? true
                                                :filters filters}}}))))))

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
  (is (= [[:effects/save-many [[[:account-info :order-history :status-filter] :filled]
                               [[:account-info :order-history :filter-open?] false]
                               [[:account-info :order-history :page] 1]
                               [[:account-info :order-history :page-input] "1"]]]]
         (history-actions/set-order-history-status-filter nil "FiLlEd")))
  (is (= [[:effects/save-many [[[:account-info :order-history :status-filter] :all]
                               [[:account-info :order-history :filter-open?] false]
                               [[:account-info :order-history :page] 1]
                               [[:account-info :order-history :page-input] "1"]]]]
         (history-actions/set-order-history-status-filter nil "invalid-status"))))

(deftest set-hide-small-balances-updates-flag-test
  (is (= [[:effects/save [:account-info :hide-small-balances?] true]]
         (history-actions/set-hide-small-balances nil true))))
