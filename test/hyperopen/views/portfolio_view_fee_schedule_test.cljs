(ns hyperopen.views.portfolio-view-fee-schedule-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.chart.d3.hover-state :as chart-hover-state]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(use-fixtures :each
  (fn [f]
    (chart-hover-state/clear-hover-state!)
    (f)
    (chart-hover-state/clear-hover-state!)))

(def ^:private base-state
  {:account {:mode :classic}
   :portfolio-ui {:summary-scope :all
                  :summary-time-range :month
                  :chart-tab :account-value
                  :summary-scope-dropdown-open? false
                  :summary-time-range-dropdown-open? false
                  :performance-metrics-time-range-dropdown-open? false}
   :portfolio {:summary-by-key {:month {:pnlHistory [[1 10] [2 15]]
                                        :accountValueHistory [[1 100] [2 100]]
                                        :vlm 2255561.85}}
               :user-fees {:userCrossRate 0.00045
                           :userAddRate 0.00015
                           :dailyUserVlm [{:exchange 100
                                           :userCross 70
                                           :userAdd 30}]}}
   :account-info {:selected-tab :balances
                  :loading false
                  :error nil
                  :hide-small-balances? false}
   :orders {:open-orders []
            :open-orders-snapshot []
            :open-orders-snapshot-by-dex {}
            :fills []
            :fundings []
            :order-history []}
   :webdata2 {}
   :borrow-lend {:total-supplied-usd 0}
   :spot {:meta nil
          :clearinghouse-state nil}
   :perp-dex-clearinghouse {}})

(deftest portfolio-view-renders-fee-schedule-popover-outside-hover-cached-sections-test
  (let [closed-node (portfolio-view/portfolio-view base-state)
        closed-trigger (hiccup/find-by-data-role closed-node "portfolio-fee-schedule-trigger")]
    (is (some? closed-trigger))
    (is (= "false" (get-in closed-trigger [1 :aria-expanded])))
    (is (nil? (hiccup/find-by-data-role closed-node "portfolio-fee-schedule-dialog")))
    (chart-hover-state/set-surface-hover-active! :portfolio true)
    (let [open-node (portfolio-view/portfolio-view
                     (-> base-state
                         (assoc-in [:portfolio-ui :fee-schedule-open?] true)
                         (assoc-in [:portfolio-ui :fee-schedule-anchor]
                                   {:left 24
                                    :right 190
                                    :top 220
                                    :viewport-width 900
                                    :viewport-height 900})))
          open-trigger (hiccup/find-by-data-role open-node "portfolio-fee-schedule-trigger")
          dialog (hiccup/find-by-data-role open-node "portfolio-fee-schedule-dialog")]
      (is (= "true" (get-in open-trigger [1 :aria-expanded])))
      (is (some? dialog))
      (is (= {:left "200px"
              :top "200px"
              :width "480px"}
             (get-in dialog [1 :style])))
      (is (contains? (set (hiccup/collect-strings dialog)) "Fee Schedule")))))
