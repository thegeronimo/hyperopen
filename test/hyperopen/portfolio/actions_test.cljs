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
           :pnl]]
         (actions/select-portfolio-chart-tab {} :pnl)))
  (is (= [[:effects/save
           [:portfolio-ui :chart-tab]
           :pnl]]
         (actions/select-portfolio-chart-tab {} :unknown))))
