(ns hyperopen.portfolio.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.actions :as actions]))

(deftest toggle-portfolio-summary-scope-dropdown-opens-and-closes-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] true]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-summary-scope-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? false
                          :summary-time-range-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-summary-scope-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? true
                          :summary-time-range-dropdown-open? false}}))))

(deftest toggle-portfolio-summary-time-range-dropdown-opens-and-closes-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] true]]]]
         (actions/toggle-portfolio-summary-time-range-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? true
                          :summary-time-range-dropdown-open? false}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-summary-time-range-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? false
                          :summary-time-range-dropdown-open? true}}))))

(deftest select-portfolio-summary-scope-normalizes-and-closes-dropdowns-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope] :perps]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]]]]
         (actions/select-portfolio-summary-scope {} "perp")))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope] :all]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]]]]
         (actions/select-portfolio-summary-scope {} :unknown))))

(deftest select-portfolio-summary-time-range-normalizes-and-closes-dropdowns-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-time-range] :all-time]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]]]]
         (actions/select-portfolio-summary-time-range {} "allTime")))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-time-range] :week]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]]]
          [:effects/fetch-candle-snapshot :coin "BTC" :interval :15m :bars 800]
          [:effects/fetch-candle-snapshot :coin "ETH" :interval :15m :bars 800]]
         (actions/select-portfolio-summary-time-range
          {:portfolio-ui {:returns-benchmark-coins ["BTC" "ETH" "BTC"]
                          :returns-benchmark-coin "DOGE"}}
          :week)))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-time-range] :week]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]]]
          [:effects/fetch-candle-snapshot :coin "BTC" :interval :15m :bars 800]]
         (actions/select-portfolio-summary-time-range
          {:portfolio-ui {:returns-benchmark-coin "BTC"}}
          :week)))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-time-range] :month]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]]]]
         (actions/select-portfolio-summary-time-range {} :unknown))))

(deftest select-portfolio-chart-tab-normalizes-and-saves-selected-tab-test
  (is (= [[:effects/save
           [:portfolio-ui :chart-tab]
           :account-value]]
         (actions/select-portfolio-chart-tab {} "accountValue")))
  (is (= [[:effects/save
           [:portfolio-ui :chart-tab]
           :returns]]
         (actions/select-portfolio-chart-tab {} "return")))
  (is (= [[:effects/save
           [:portfolio-ui :chart-tab]
           :returns]
          [:effects/fetch-candle-snapshot :coin "ETH" :interval :1h :bars 800]
          [:effects/fetch-candle-snapshot :coin "SPY" :interval :1h :bars 800]]
         (actions/select-portfolio-chart-tab
          {:portfolio-ui {:returns-benchmark-coins ["ETH" "SPY"]
                          :summary-time-range :month}}
          :returns)))
  (is (= [[:effects/save
           [:portfolio-ui :chart-tab]
           :returns]
          [:effects/fetch-candle-snapshot :coin "ETH" :interval :1h :bars 800]]
         (actions/select-portfolio-chart-tab
          {:portfolio-ui {:returns-benchmark-coin "ETH"
                          :summary-time-range :month}}
          :returns)))
  (is (= [[:effects/save
           [:portfolio-ui :chart-tab]
           :pnl]]
         (actions/select-portfolio-chart-tab {} :pnl)))
  (is (= [[:effects/save
           [:portfolio-ui :chart-tab]
           :pnl]]
         (actions/select-portfolio-chart-tab {} :unknown))))

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
           true]]
         (actions/set-portfolio-returns-benchmark-suggestions-open {} true)))
  (is (= [[:effects/save
           [:portfolio-ui :returns-benchmark-suggestions-open?]
           false]]
         (actions/set-portfolio-returns-benchmark-suggestions-open {} nil))))

(deftest select-and-clear-portfolio-returns-benchmark-test
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] ["SPY" "QQQ"]]
            [[:portfolio-ui :returns-benchmark-coin] "SPY"]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] true]]]
          [:effects/fetch-candle-snapshot :coin "QQQ" :interval :1d :bars 5000]]
         (actions/select-portfolio-returns-benchmark
          {:portfolio-ui {:summary-time-range :all-time
                          :returns-benchmark-coins ["SPY"]}}
          "QQQ")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] ["SPY"]]
            [[:portfolio-ui :returns-benchmark-coin] "SPY"]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] true]]]]
         (actions/select-portfolio-returns-benchmark
          {:portfolio-ui {:summary-time-range :month
                          :returns-benchmark-coins ["SPY"]}}
          "SPY")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] ["SPY"]]
            [[:portfolio-ui :returns-benchmark-coin] "SPY"]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] true]]]]
         (actions/select-portfolio-returns-benchmark
          {:portfolio-ui {:summary-time-range :all-time
                          :returns-benchmark-coin "SPY"}}
          "SPY")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] []]
            [[:portfolio-ui :returns-benchmark-coin] nil]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]]
         (actions/select-portfolio-returns-benchmark
          {:portfolio-ui {:summary-time-range :day}}
          "   ")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] []]
            [[:portfolio-ui :returns-benchmark-coin] nil]
            [[:portfolio-ui :returns-benchmark-search] ""]
            [[:portfolio-ui :returns-benchmark-suggestions-open?] false]]]]
         (actions/clear-portfolio-returns-benchmark {}))))

(deftest remove-portfolio-returns-benchmark-test
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] ["QQQ"]]
            [[:portfolio-ui :returns-benchmark-coin] "QQQ"]]]]
         (actions/remove-portfolio-returns-benchmark
          {:portfolio-ui {:returns-benchmark-coins ["SPY" "QQQ"]}}
          "SPY")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :returns-benchmark-coins] []]
            [[:portfolio-ui :returns-benchmark-coin] nil]]]]
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
            [[:portfolio-ui :returns-benchmark-suggestions-open?] true]]]
          [:effects/fetch-candle-snapshot :coin "SPY" :interval :15m :bars 800]]
         (actions/handle-portfolio-returns-benchmark-search-keydown
          {:portfolio-ui {:summary-time-range :week}}
          "Enter"
          "SPY")))
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
  (is (= {:interval :1d :bars 5000}
         (actions/returns-benchmark-candle-request :all-time))))
