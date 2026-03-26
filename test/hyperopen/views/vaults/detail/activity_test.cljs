(ns hyperopen.views.vaults.detail.activity-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.vaults.detail.activity :as activity]))

(def base-activity-panel-props
  {:activity-tabs [{:value :performance-metrics
                    :label "Performance Metrics"
                    :count 1}
                   {:value :positions
                    :label "Positions"
                    :count 2}
                   {:value :balances
                    :label "Balances"
                    :count 0}]
   :activity-table-config {:positions {:supports-direction-filter? true
                                       :columns [{:id :coin :label "Coin"}
                                                 {:id :size :label "Size"}]}
                           :balances {:supports-direction-filter? false
                                      :columns [{:id :coin :label "Coin"}]}
                           :trade-history {:columns [{:id :time-ms :label "Time"}]}
                           :funding-history {:columns [{:id :time-ms :label "Time"}]}
                           :order-history {:columns [{:id :time-ms :label "Time"}]}
                           :deposits-withdrawals {:columns [{:id :time-ms :label "Time"}]}}
   :activity-direction-filter :all
   :activity-filter-open? false
   :activity-filter-options [{:value :all :label "All"}
                             {:value :long :label "Long"}
                             {:value :short :label "Short"}]
   :activity-sort-state-by-tab {}
   :activity-loading {}
   :activity-errors {}
   :activity-balances []
   :activity-positions []
   :activity-open-orders []
   :activity-twaps []
   :activity-fills []
   :activity-funding-history []
   :activity-order-history []
   :activity-deposits-withdrawals []
   :activity-depositors []})

(deftest activity-private-formatting-and-tone-helpers-test
  (let [format-activity-count @#'activity/format-activity-count
        format-signed @#'activity/format-signed-percent-from-decimal
        format-metric @#'activity/format-metric-value
        resolved-columns @#'activity/resolved-benchmark-metric-columns
        benchmark-row-value @#'activity/benchmark-row-value
        row-visible? @#'activity/performance-metric-row-visible?
        position-pnl-class @#'activity/position-pnl-class
        side-tone-class @#'activity/side-tone-class
        side-coin-tone-class @#'activity/side-coin-tone-class
        side-coin-cell-style @#'activity/side-coin-cell-style
        status-tone-class @#'activity/status-tone-class
        ledger-type-tone-class @#'activity/ledger-type-tone-class]
    (is (nil? (format-activity-count nil)))
    (is (nil? (format-activity-count 0)))
    (is (= "100+" (format-activity-count 100)))
    (is (= "7" (format-activity-count 7)))
    (is (= "+1.23%" (format-signed 0.01234)))
    (is (= "-3.45%" (format-signed -0.0345)))
    (is (= "0.00%" (format-signed -0.000001)))
    (is (= "--" (format-metric :date "   ")))
    (is (= "2026-03-01" (format-metric :date "2026-03-01")))
    (is (= "8" (format-metric :integer 8.4)))
    (is (= [{:coin "BTC" :label "Bitcoin"}]
           (resolved-columns {:benchmark-columns [{:coin " BTC " :label "Bitcoin"}]
                              :benchmark-selected? true
                              :benchmark-label "Benchmark"
                              :benchmark-coin "BTC"})))
    (is (= [{:coin "ETH" :label "Benchmark"}]
           (resolved-columns {:benchmark-columns []
                              :benchmark-selected? true
                              :benchmark-label nil
                              :benchmark-coin "ETH"})))
    (is (= [{:coin "__benchmark__" :label "Benchmark"}]
           (resolved-columns {:benchmark-columns nil
                              :benchmark-selected? false
                              :benchmark-label nil
                              :benchmark-coin nil})))
    (is (= 1.1 (benchmark-row-value {:benchmark-values {"BTC" 1.1}} "BTC")))
    (is (= 2.2 (benchmark-row-value {:benchmark-values {"ETH" 3.3}
                                     :benchmark-value 2.2}
                                    "BTC")))
    (is (true? (row-visible? {:kind :percent
                              :value nil
                              :benchmark-values {"BTC" 0.2}}
                             [{:coin "BTC"}])))
    (is (nil? (row-visible? {:kind :percent
                             :value nil
                             :benchmark-values {"BTC" nil}}
                            [{:coin "BTC"}])))
    (is (= "text-[#1fa67d]" (position-pnl-class 1)))
    (is (= "text-[#ed7088]" (position-pnl-class -1)))
    (is (= "text-trading-text" (position-pnl-class nil)))
    (is (= "text-[#1fa67d]" (side-tone-class :long)))
    (is (= "text-[#ed7088]" (side-tone-class :short)))
    (is (= "text-trading-text" (side-tone-class :flat)))
    (is (= "text-[#97fce4]" (side-coin-tone-class :long)))
    (is (= "text-[#eaafb8]" (side-coin-tone-class :short)))
    (is (= "text-trading-text" (side-coin-tone-class :flat)))
    (is (= nil (side-coin-cell-style :flat)))
    (is (= "12px" (get (side-coin-cell-style :long) :padding-left)))
    (is (= "text-[#1fa67d]" (status-tone-class :positive)))
    (is (= "text-[#ed7088]" (status-tone-class :negative)))
    (is (= "text-[#9aa7ad]" (status-tone-class :neutral)))
    (is (= "text-trading-text" (status-tone-class :other)))
    (is (= "text-[#1fa67d]" (ledger-type-tone-class :deposit)))
    (is (= "text-[#ed7088]" (ledger-type-tone-class :withdraw)))
    (is (= "text-trading-text" (ledger-type-tone-class :other)))))

(deftest activity-panel-renders-performance-metrics-and-hides-empty-rows-test
  (let [view (activity/activity-panel
              (assoc base-activity-panel-props
                     :selected-activity-tab :performance-metrics
                     :performance-metrics {:benchmark-selected? true
                                           :benchmark-label "Bitcoin"
                                           :benchmark-columns [{:coin "BTC"
                                                                :label "Bitcoin"}]
                                           :benchmark-coin "BTC"
                                           :timeframe-options [{:value :month :label "30D"}]
                                           :timeframe-menu-open? true
                                           :selected-timeframe :month
                                           :groups [{:id :risk
                                                     :rows [{:key :sharpe
                                                             :label "Sharpe"
                                                             :kind :ratio
                                                             :value 1.23
                                                             :benchmark-values {"BTC" 0.98}}
                                                            {:key :omega
                                                             :label "Omega"
                                                             :description "Ratio of gains above the target return to losses below it."
                                                             :kind :ratio
                                                             :value 1.11
                                                             :portfolio-status :low-confidence
                                                             :portfolio-reason :daily-coverage-gate-failed
                                                             :benchmark-values {"BTC" 0.94}
                                                             :benchmark-statuses {"BTC" :low-confidence}
                                                             :benchmark-reasons {"BTC" :daily-coverage-gate-failed}}
                                                            {:key :hidden
                                                             :label "Hidden"
                                                             :kind :ratio
                                                             :value nil
                                                             :benchmark-values {"BTC" nil}}]}]}))
        sharpe-row (hiccup/find-first-node view
                                           #(= "vault-detail-performance-metric-sharpe"
                                               (get-in % [1 :data-role])))
        hidden-row (hiccup/find-first-node view
                                           #(= "vault-detail-performance-metric-hidden"
                                               (get-in % [1 :data-role])))
        estimated-banner (hiccup/find-first-node view
                                                 #(= "vault-detail-performance-metrics-estimated-banner"
                                                     (get-in % [1 :data-role])))
        estimated-banner-tooltip (hiccup/find-first-node view
                                                         #(= "vault-detail-performance-metrics-estimated-banner-tooltip"
                                                             (get-in % [1 :data-role])))
        estimated-banner-panel (hiccup/find-first-node estimated-banner-tooltip
                                                       #(= "tooltip" (get-in % [1 :role])))
        omega-label-tooltip (hiccup/find-first-node view
                                                    #(= "vault-detail-performance-metric-omega-label-tooltip"
                                                        (get-in % [1 :data-role])))
        omega-label-tooltip-panel (hiccup/find-first-node omega-label-tooltip
                                                          #(= "tooltip" (get-in % [1 :role])))
        estimated-mark (hiccup/find-first-node view
                                               #(= "vault-detail-performance-metric-omega-estimated-mark"
                                                   (get-in % [1 :data-role])))
        vault-low-confidence-cell (hiccup/find-first-node view
                                                          #(= "vault-detail-performance-metric-omega-vault-value"
                                                              (get-in % [1 :data-role])))
        benchmark-low-confidence-cell (hiccup/find-first-node view
                                                              #(= "vault-detail-performance-metric-omega-benchmark-value-BTC"
                                                                  (get-in % [1 :data-role])))
        badge-node (hiccup/find-first-node view
                                           #(= "vault-detail-performance-metric-omega-vault-value-status-badge"
                                               (get-in % [1 :data-role])))
        benchmark-label (hiccup/find-first-node view
                                                #(= "vault-detail-performance-metrics-benchmark-label"
                                                    (get-in % [1 :data-role])))
        activity-panel (hiccup/find-first-node view
                                               #(= "vault-detail-activity-panel"
                                                   (get-in % [1 :data-role])))
        scroll-region (hiccup/find-first-node view
                                              #(= "vault-detail-performance-metrics-scroll-region"
                                                  (get-in % [1 :data-role])))
        timeframe-trigger (hiccup/find-first-node view
                                                  #(= "vault-detail-performance-metrics-timeframe-trigger"
                                                      (get-in % [1 :data-role])))
        timeframe-menu (hiccup/find-first-node view
                                               #(= "vault-detail-performance-metrics-timeframe-options"
                                                   (get-in % [1 :data-role])))
        timeframe-option (hiccup/find-first-node view
                                                 #(= "vault-detail-performance-metrics-timeframe-option-month"
                                                     (get-in % [1 :data-role])))]
    (is (some? sharpe-row))
    (is (nil? hidden-row))
    (is (some? estimated-banner))
    (is (contains? (set (hiccup/collect-strings estimated-banner))
                   "Some metrics are estimated from incomplete daily data."))
    (is (contains? (set (hiccup/collect-strings estimated-banner-tooltip))
                   "Estimated rows stay visible when the selected range does not meet the usual reliability gates."))
    (is (contains? (set (hiccup/collect-strings estimated-banner-tooltip))
                   "Estimated from incomplete daily coverage."))
    (is (contains? (hiccup/node-class-set estimated-banner-panel) "border-base-300"))
    (is (contains? (hiccup/node-class-set estimated-banner-panel) "bg-gray-800"))
    (is (contains? (set (hiccup/collect-strings omega-label-tooltip))
                   "Omega"))
    (is (contains? (set (hiccup/collect-strings omega-label-tooltip))
                   "Ratio of gains above the target return to losses below it."))
    (is (contains? (hiccup/node-class-set omega-label-tooltip-panel) "border-base-300"))
    (is (contains? (hiccup/node-class-set omega-label-tooltip-panel) "bg-gray-800"))
    (is (= "~" (first (hiccup/collect-strings estimated-mark))))
    (is (contains? (set (get-in vault-low-confidence-cell [1 :class]))
                   "text-[#9fb4bb]"))
    (is (contains? (set (get-in benchmark-low-confidence-cell [1 :class]))
                   "text-[#9fb4bb]"))
    (is (nil? badge-node))
    (is (some? benchmark-label))
    (is (contains? (set (get-in activity-panel [1 :class])) "max-h-[75vh]"))
    (is (some? scroll-region))
    (is (= "region" (get-in scroll-region [1 :role])))
    (is (= "Vault performance metrics" (get-in scroll-region [1 :aria-label])))
    (is (= 0 (get-in scroll-region [1 :tab-index])))
    (is (some? timeframe-trigger))
    (is (= [[:actions/toggle-vault-detail-performance-metrics-timeframe-dropdown]]
           (get-in timeframe-trigger [1 :on :click])))
    (is (= true (get-in timeframe-trigger [1 :aria-expanded])))
    (is (= "open" (get-in timeframe-menu [1 :data-ui-state])))
    (is (some? timeframe-option))
    (is (nil? (hiccup/find-first-node view #(= :select (first %)))))))

(deftest activity-panel-renders-filter-states-and-fallback-tab-message-test
  (let [positions-view (activity/activity-panel
                        (-> base-activity-panel-props
                            (assoc :selected-activity-tab :positions
                                   :activity-filter-open? true
                                   :activity-direction-filter :long)))
        filter-toggle (hiccup/find-first-node positions-view
                                              #(= [[:actions/toggle-vault-detail-activity-filter-open]]
                                                  (get-in % [1 :on :click])))
        short-option (hiccup/find-first-node positions-view
                                             #(= [[:actions/set-vault-detail-activity-direction-filter :short]]
                                                 (get-in % [1 :on :click])))
        balances-view (activity/activity-panel
                       (-> base-activity-panel-props
                           (assoc :selected-activity-tab :balances)))
        disabled-filter-toggle (hiccup/find-first-node balances-view
                                                       #(= [[:actions/toggle-vault-detail-activity-filter-open]]
                                                           (get-in % [1 :on :click])))
        fallback-view (activity/activity-panel
                       (-> base-activity-panel-props
                           (assoc :selected-activity-tab :not-supported)))
        fallback-text (set (hiccup/collect-strings fallback-view))]
    (is (some? filter-toggle))
    (is (not (true? (get-in filter-toggle [1 :disabled]))))
    (is (some? short-option))
    (is (some? disabled-filter-toggle))
    (is (true? (get-in disabled-filter-toggle [1 :disabled])))
    (is (contains? fallback-text "This activity stream is not available yet for vaults."))))

(deftest activity-panel-renders-performance-metrics-loading-overlay-copy-test
  (let [view (activity/activity-panel
              (assoc base-activity-panel-props
                     :selected-activity-tab :performance-metrics
                     :performance-metrics {:loading? true
                                           :benchmark-selected? true
                                           :benchmark-label "Bitcoin"
                                           :benchmark-columns [{:coin "BTC"
                                                                :label "Bitcoin"}]
                                           :benchmark-coin "BTC"
                                           :timeframe-options [{:value :month :label "30D"}]
                                           :timeframe-menu-open? false
                                           :selected-timeframe :month
                                           :groups [{:id :risk
                                                     :rows [{:key :sharpe
                                                             :label "Sharpe"
                                                             :kind :ratio
                                                             :value 1.23
                                                             :benchmark-values {"BTC" 0.98}}]}]}))
        overlay-node (hiccup/find-first-node view
                                             #(= "vault-detail-performance-metrics-loading-overlay"
                                                 (get-in % [1 :data-role])))
        overlay-strings (set (hiccup/collect-strings overlay-node))]
    (is (some? overlay-node))
    (is (= "status" (get-in overlay-node [1 :role])))
    (is (contains? overlay-strings "Loading benchmark history"))
    (is (contains? overlay-strings
                   "Vault metrics stay visible while benchmark comparisons finish in the background."))))

(deftest activity-table-helpers-cover-error-loading-empty-and-row-branches-test
  (let [fills-table @#'activity/fills-table
        funding-history-table @#'activity/funding-history-table
        order-history-table @#'activity/order-history-table
        ledger-table @#'activity/ledger-table
        balances-table @#'activity/balances-table
        positions-table @#'activity/positions-table
        open-orders-table @#'activity/open-orders-table
        twap-table @#'activity/twap-table
        depositors-table @#'activity/depositors-table
        cols [{:id :time-ms :label "Time"}]
        fills-error (set (hiccup/collect-strings (fills-table [] false "Trade history failed." nil cols)))
        fills-loading (set (hiccup/collect-strings (fills-table [] true nil nil cols)))
        fills-empty (set (hiccup/collect-strings (fills-table [] false nil nil cols)))
        fills-rows (fills-table [{:time-ms 1700000000000
                                  :coin "ETH"
                                  :side "Sell"
                                  :side-key :short
                                  :size 1.5
                                  :price 2010.5
                                  :trade-value 3015.75
                                  :fee 1.25
                                  :closed-pnl -12.3}]
                                false
                                nil
                                nil
                                cols)
        funding-loading (set (hiccup/collect-strings (funding-history-table [] true nil nil cols)))
        funding-empty (set (hiccup/collect-strings (funding-history-table [] false nil nil cols)))
        funding-rows (funding-history-table [{:time-ms 1700000000000
                                              :coin "BTC"
                                              :funding-rate 0.0001
                                              :position-size 3.2
                                              :side-key :long
                                              :payment 4.2}]
                                            false
                                            nil
                                            nil
                                            cols)
        order-error (set (hiccup/collect-strings (order-history-table [] false "Order history failed." nil cols)))
        order-loading (set (hiccup/collect-strings (order-history-table [] true nil nil cols)))
        order-empty (set (hiccup/collect-strings (order-history-table [] false nil nil cols)))
        order-rows (order-history-table [{:time-ms 1700000000000
                                          :coin "SOL"
                                          :side "Buy"
                                          :side-key :long
                                          :type "Limit"
                                          :size 10
                                          :price 110
                                          :status "Rejected"
                                          :status-key :negative}]
                                        false
                                        nil
                                        nil
                                        cols)
        ledger-loading (set (hiccup/collect-strings (ledger-table [] true nil nil cols)))
        ledger-empty (set (hiccup/collect-strings (ledger-table [] false nil nil cols)))
        ledger-rows (ledger-table [{:time-ms 1700000000000
                                    :type-key :deposit
                                    :type-label "Deposit"
                                    :amount 120
                                    :signed-amount 120
                                    :hash "0x1234567890abcdef1234567890abcdef"}]
                                  false
                                  nil
                                  nil
                                  cols)
        positions-rows (positions-table [{:coin "BTC"
                                          :leverage 3
                                          :size 1.25
                                          :side-key :long
                                          :position-value 2500
                                          :entry-price 50000
                                          :mark-price 50125
                                          :pnl 125
                                          :roe 0.05
                                          :liq-price nil
                                          :margin nil
                                          :funding -4.2}
                                         {:coin nil
                                          :leverage nil
                                          :size 0.5
                                          :side-key :flat
                                          :position-value nil
                                          :entry-price nil
                                          :mark-price nil
                                          :pnl nil
                                          :roe nil
                                          :liq-price nil
                                          :margin nil
                                          :funding nil}]
                                        nil
                                        cols)
        balances-empty (set (hiccup/collect-strings (balances-table [] nil cols)))
        positions-empty (set (hiccup/collect-strings (positions-table [] nil cols)))
        open-orders-empty (set (hiccup/collect-strings (open-orders-table [] nil cols)))
        twap-empty (set (hiccup/collect-strings (twap-table [] nil cols)))
        depositors-empty (set (hiccup/collect-strings (depositors-table [] nil cols)))
        position-strings (hiccup/collect-strings positions-rows)
        fill-row-node (hiccup/find-first-node fills-rows
                                              #(and (= :td (first %))
                                                    (contains? (set (hiccup/collect-strings %)) "ETH")))
        funding-row-node (hiccup/find-first-node funding-rows
                                                 #(and (= :td (first %))
                                                       (contains? (set (hiccup/collect-strings %)) "BTC")))
        order-status-node (hiccup/find-first-node order-rows
                                                  #(and (= :td (first %))
                                                        (contains? (set (hiccup/collect-strings %)) "Rejected")))
        ledger-type-node (hiccup/find-first-node ledger-rows
                                                 #(and (= :td (first %))
                                                       (contains? (set (hiccup/collect-strings %)) "Deposit")))
        position-coin-node (hiccup/find-first-node positions-rows
                                                   #(and (= :td (first %))
                                                         (contains? (set (hiccup/collect-strings %)) "BTC")))
        position-funding-node (hiccup/find-first-node positions-rows
                                                      #(and (= :td (first %))
                                                            (some (fn [text]
                                                                    (str/includes? text "4.20"))
                                                                  (hiccup/collect-strings %))))
        position-placeholder-node (hiccup/find-first-node positions-rows
                                                          #(and (= :td (first %))
                                                                (contains? (set (hiccup/collect-strings %)) "N/A")))]
    (is (contains? fills-error "Trade history failed."))
    (is (contains? fills-loading "Loading trade history..."))
    (is (contains? fills-empty "No recent fills."))
    (is (some? fill-row-node))
    (is (contains? funding-loading "Loading funding history..."))
    (is (contains? funding-empty "No funding history available."))
    (is (some? funding-row-node))
    (is (contains? order-error "Order history failed."))
    (is (contains? order-loading "Loading order history..."))
    (is (contains? order-empty "No order history available."))
    (is (contains? (hiccup/node-class-set order-status-node) "text-[#ed7088]"))
    (is (contains? ledger-loading "Loading deposits and withdrawals..."))
    (is (contains? ledger-empty "No deposits or withdrawals available."))
    (is (contains? (hiccup/node-class-set ledger-type-node) "text-[#1fa67d]"))
    (is (contains? balances-empty "No balances available."))
    (is (contains? positions-empty "No active positions."))
    (is (some? position-coin-node))
    (is (some #(= "3x" %) position-strings))
    (is (some #(str/includes? % "USDC") position-strings))
    (is (contains? (hiccup/node-class-set position-funding-node) "text-[#ed7088]"))
    (is (some? position-placeholder-node))
    (is (contains? open-orders-empty "No open orders."))
    (is (contains? twap-empty "No TWAPs yet."))
    (is (contains? depositors-empty "No depositors available."))))
