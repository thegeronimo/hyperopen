(ns hyperopen.portfolio.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.actions :as actions]))

(def ^:private replace-shareable-route-query-effect
  [:effects/replace-shareable-route-query])

(deftest toggle-portfolio-summary-scope-dropdown-opens-and-closes-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] true]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-summary-scope-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? false
                          :summary-time-range-dropdown-open? true
                          :performance-metrics-time-range-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-summary-scope-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? true
                          :summary-time-range-dropdown-open? false
                          :performance-metrics-time-range-dropdown-open? false}}))))

(deftest toggle-portfolio-summary-time-range-dropdown-opens-and-closes-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] true]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-summary-time-range-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? true
                          :summary-time-range-dropdown-open? false
                          :performance-metrics-time-range-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-summary-time-range-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? false
                          :summary-time-range-dropdown-open? true
                          :performance-metrics-time-range-dropdown-open? false}}))))

(deftest toggle-portfolio-performance-metrics-time-range-dropdown-opens-and-closes-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] true]]]]
         (actions/toggle-portfolio-performance-metrics-time-range-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? true
                          :summary-time-range-dropdown-open? true
                          :performance-metrics-time-range-dropdown-open? false}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-performance-metrics-time-range-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? false
                          :summary-time-range-dropdown-open? false
                          :performance-metrics-time-range-dropdown-open? true}}))))

(deftest select-portfolio-summary-scope-normalizes-and-closes-dropdowns-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope] :perps]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-summary-scope {} "perp")))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope] :all]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-summary-scope {} :unknown))))

(deftest select-portfolio-summary-time-range-normalizes-and-closes-dropdowns-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-time-range] :three-month]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]
          [:effects/local-storage-set "portfolio-summary-time-range" "three-month"]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-summary-time-range {} "3M")))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-time-range] :all-time]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]
          [:effects/local-storage-set "portfolio-summary-time-range" "all-time"]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-summary-time-range {} "allTime")))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-time-range] :week]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]
          [:effects/local-storage-set "portfolio-summary-time-range" "week"]
          replace-shareable-route-query-effect
          [:effects/fetch-candle-snapshot :coin "BTC" :interval :15m :bars 800]
          [:effects/fetch-candle-snapshot :coin "ETH" :interval :15m :bars 800]]
         (actions/select-portfolio-summary-time-range
          {:portfolio-ui {:returns-benchmark-coins ["BTC"
                                                    "vault:0x1234567890abcdef1234567890abcdef12345678"
                                                    "ETH"
                                                    "BTC"]
                          :returns-benchmark-coin "DOGE"}}
          :week)))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-time-range] :week]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]
          [:effects/local-storage-set "portfolio-summary-time-range" "week"]
          replace-shareable-route-query-effect
          [:effects/fetch-candle-snapshot :coin "BTC" :interval :15m :bars 800]]
         (actions/select-portfolio-summary-time-range
          {:portfolio-ui {:returns-benchmark-coin "BTC"}}
          :week)))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-time-range] :one-year]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]
          [:effects/local-storage-set "portfolio-summary-time-range" "one-year"]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-summary-time-range {} :unknown))))

(deftest restore-portfolio-summary-time-range-loads-normalized-local-storage-preference-test
  (let [store (atom {:portfolio-ui {:summary-time-range :month}})]
    (with-redefs [platform/local-storage-get (fn [_] "3M")]
      (actions/restore-portfolio-summary-time-range! store))
    (is (= :three-month (get-in @store [:portfolio-ui :summary-time-range]))))
  (let [store (atom {:portfolio-ui {:summary-time-range :three-month}})]
    (with-redefs [platform/local-storage-get (fn [_] "not-a-range")]
      (actions/restore-portfolio-summary-time-range! store))
    (is (= :one-year (get-in @store [:portfolio-ui :summary-time-range])))))

(deftest select-portfolio-chart-tab-normalizes-and-saves-selected-tab-test
  (is (= [[:effects/save-many
           [[[:portfolio-ui :chart-tab] :account-value]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-chart-tab {} "accountValue")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :chart-tab] :returns]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-chart-tab {} "return")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :chart-tab] :returns]]]
          replace-shareable-route-query-effect
          [:effects/fetch-candle-snapshot :coin "ETH" :interval :1h :bars 800]
          [:effects/fetch-candle-snapshot :coin "SPY" :interval :1h :bars 800]]
         (actions/select-portfolio-chart-tab
          {:portfolio-ui {:returns-benchmark-coins ["ETH"
                                                    "vault:0x1234567890abcdef1234567890abcdef12345678"
                                                    "SPY"]
                          :summary-time-range :month}}
          :returns)))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :chart-tab] :returns]]]
          replace-shareable-route-query-effect
          [:effects/fetch-candle-snapshot :coin "ETH" :interval :1h :bars 800]]
         (actions/select-portfolio-chart-tab
          {:portfolio-ui {:returns-benchmark-coin "ETH"
                          :summary-time-range :month}}
          :returns)))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :chart-tab] :pnl]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-chart-tab {} :pnl)))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :chart-tab] :returns]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-chart-tab {} :unknown))))

(deftest set-portfolio-account-info-tab-normalizes-and-saves-selected-tab-test
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :performance-metrics]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} "performanceMetrics")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :performance-metrics]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} " performanceMetric ")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :deposits-withdrawals]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} "depositsWithdrawals")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :open-orders]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} "openOrders")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :trade-history]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} "tradeHistory")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :funding-history]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} "fundingHistory")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :order-history]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} "orderHistory")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :performance-metrics]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} :unknown))))

(deftest set-portfolio-returns-benchmark-search-and-open-state-test
  (is (= [[:effects/save
           [:portfolio-ui :returns-benchmark-search]
           "spy"]]
         (actions/set-portfolio-returns-benchmark-search {} "spy")))
  (is (= [[:effects/save
           [:portfolio-ui :returns-benchmark-search]
           "42"]]
         (actions/set-portfolio-returns-benchmark-search {} 42)))
  (is (= [[:effects/save
           [:portfolio-ui :returns-benchmark-suggestions-open?]
           true]
          [:effects/api-fetch-vault-index]
          [:effects/api-fetch-vault-summaries]]
         (actions/set-portfolio-returns-benchmark-suggestions-open {} true)))
  (is (= [[:effects/save
           [:portfolio-ui :returns-benchmark-suggestions-open?]
           true]
          [:effects/api-fetch-vault-index]
          [:effects/api-fetch-vault-summaries]]
         (actions/set-portfolio-returns-benchmark-suggestions-open
          {:vaults {}}
          true)))
  (is (= [[:effects/save
           [:portfolio-ui :returns-benchmark-suggestions-open?]
           false]]
         (actions/set-portfolio-returns-benchmark-suggestions-open {} nil))))

(deftest select-and-clear-portfolio-returns-benchmark-test
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] ["SPY" "QQQ"]]
            [[:portfolio-ui :returns-benchmark-coin] "SPY"]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]
          replace-shareable-route-query-effect
          [:effects/fetch-candle-snapshot :coin "QQQ" :interval :1d :bars 5000]]
         (actions/select-portfolio-returns-benchmark
          {:portfolio-ui {:summary-time-range :all-time
                          :returns-benchmark-coins ["SPY"]}}
          "QQQ")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] ["SPY"]]
            [[:portfolio-ui :returns-benchmark-coin] "SPY"]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-returns-benchmark
          {:portfolio-ui {:summary-time-range :month
                          :returns-benchmark-coins ["SPY"]}}
          "SPY")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] ["SPY"]]
            [[:portfolio-ui :returns-benchmark-coin] "SPY"]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-returns-benchmark
          {:portfolio-ui {:summary-time-range :all-time
                          :returns-benchmark-coin "SPY"}}
          "SPY")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] ["vault:0x1234567890abcdef1234567890abcdef12345678"]]
            [[:portfolio-ui :returns-benchmark-coin] "vault:0x1234567890abcdef1234567890abcdef12345678"]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]
          replace-shareable-route-query-effect
          [:effects/api-fetch-vault-benchmark-details "0x1234567890abcdef1234567890abcdef12345678"]]
         (actions/select-portfolio-returns-benchmark
          {:portfolio-ui {:summary-time-range :all-time
                          :returns-benchmark-coins []}}
          "vault:0x1234567890abcdef1234567890abcdef12345678")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] []]
            [[:portfolio-ui :returns-benchmark-coin] nil]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-returns-benchmark
          {:portfolio-ui {:summary-time-range :day}}
          "   ")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] []]
            [[:portfolio-ui :returns-benchmark-coin] nil]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]
          replace-shareable-route-query-effect]
         (actions/clear-portfolio-returns-benchmark {}))))

(deftest remove-portfolio-returns-benchmark-test
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] ["QQQ"]]
            [[:portfolio-ui :returns-benchmark-coin] "QQQ"]]]
          replace-shareable-route-query-effect]
         (actions/remove-portfolio-returns-benchmark
          {:portfolio-ui {:returns-benchmark-coins ["SPY" "QQQ"]}}
          "SPY")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] []]
            [[:portfolio-ui :returns-benchmark-coin] nil]]]
          replace-shareable-route-query-effect]
         (actions/remove-portfolio-returns-benchmark
          {:portfolio-ui {:returns-benchmark-coin "SPY"}}
          "SPY")))
  (is (= []
         (actions/remove-portfolio-returns-benchmark
          {:portfolio-ui {:returns-benchmark-coins ["SPY"]}}
          "   "))))

(deftest handle-portfolio-returns-benchmark-search-keydown-test
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] ["SPY"]]
            [[:portfolio-ui :returns-benchmark-coin] "SPY"]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]
          replace-shareable-route-query-effect
          [:effects/fetch-candle-snapshot :coin "SPY" :interval :15m :bars 800]]
         (actions/handle-portfolio-returns-benchmark-search-keydown
          {:portfolio-ui {:summary-time-range :week}}
          "Enter"
          "SPY")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] ["vault:0x1234567890abcdef1234567890abcdef12345678"]]
            [[:portfolio-ui :returns-benchmark-coin] "vault:0x1234567890abcdef1234567890abcdef12345678"]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]
          replace-shareable-route-query-effect
          [:effects/api-fetch-vault-benchmark-details "0x1234567890abcdef1234567890abcdef12345678"]]
         (actions/handle-portfolio-returns-benchmark-search-keydown
          {:portfolio-ui {:summary-time-range :week}}
          "Enter"
          "vault:0x1234567890abcdef1234567890abcdef12345678")))
  (is (= []
         (actions/handle-portfolio-returns-benchmark-search-keydown
          {:portfolio-ui {:summary-time-range :week}}
          "Enter"
          nil)))
  (is (= [[:effects/save [:portfolio-ui :returns-benchmark-suggestions-open?] false]]
         (actions/handle-portfolio-returns-benchmark-search-keydown
          {}
          "Escape"
          "SPY")))
  (is (= []
         (actions/handle-portfolio-returns-benchmark-search-keydown
          {}
          "ArrowDown"
          "SPY"))))

(deftest returns-benchmark-candle-request-selects-range-specific-window-test
  (is (= {:interval :5m :bars 400}
         (actions/returns-benchmark-candle-request :day)))
  (is (= {:interval :15m :bars 800}
         (actions/returns-benchmark-candle-request :week)))
  (is (= {:interval :1h :bars 800}
         (actions/returns-benchmark-candle-request :month)))
  (is (= {:interval :4h :bars 720}
         (actions/returns-benchmark-candle-request "3M")))
  (is (= {:interval :8h :bars 720}
         (actions/returns-benchmark-candle-request :six-month)))
  (is (= {:interval :12h :bars 900}
         (actions/returns-benchmark-candle-request :one-year)))
  (is (= {:interval :1d :bars 900}
         (actions/returns-benchmark-candle-request "2Y")))
  (is (= {:interval :1d :bars 5000}
         (actions/returns-benchmark-candle-request :all-time))))

(deftest normalize-summary-time-range-recognizes-common-aliases-test
  (is (= :three-month (actions/normalize-summary-time-range "3M")))
  (is (= :three-month (actions/normalize-summary-time-range "quarter")))
  (is (= :six-month (actions/normalize-summary-time-range "halfYear")))
  (is (= :one-year (actions/normalize-summary-time-range "1y")))
  (is (= :two-year (actions/normalize-summary-time-range "two_year"))))

(deftest normalize-summary-time-range-falls-back-for-invalid-values-test
  (is (= :one-year (actions/normalize-summary-time-range nil)))
  (is (= :one-year (actions/normalize-summary-time-range "not-a-range")))
  (is (= {:interval :12h :bars 900}
         (actions/returns-benchmark-candle-request "not-a-range"))))
