(ns hyperopen.views.vaults.detail.activity.tables-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.vaults.detail.activity.table-chrome :as chrome]
            [hyperopen.views.vaults.detail.activity.tables :as tables]))

(def ^:private standard-cols
  [{:id :time-ms :label "Time"}])

(defn- td-with-text
  [view text]
  (hiccup/find-first-node view
                          #(and (= :td (first %))
                                (contains? (set (hiccup/collect-strings %)) text))))

(defn- state-cell
  [view text]
  (td-with-text view text))

(deftest table-tone-and-style-helpers-test
  (is (= "text-[#1fa67d]" (chrome/position-pnl-class 1)))
  (is (= "text-ho-sell-hi" (chrome/position-pnl-class -1)))
  (is (= "text-trading-text" (chrome/position-pnl-class nil)))
  (is (= "text-[#1fa67d]" (chrome/side-tone-class :long)))
  (is (= "text-ho-sell-hi" (chrome/side-tone-class :short)))
  (is (= "text-trading-text" (chrome/side-tone-class :flat)))
  (is (= "text-ho-accent-bright" (chrome/side-coin-tone-class :long)))
  (is (= "text-[#eaafb8]" (chrome/side-coin-tone-class :short)))
  (is (= "text-trading-text" (chrome/side-coin-tone-class :flat)))
  (is (= nil (chrome/side-coin-cell-style :flat)))
  (is (= "12px" (get (chrome/side-coin-cell-style :long) :padding-left)))
  (is (str/includes? (:background (chrome/side-coin-cell-style :long))
                     "linear-gradient(90deg,rgb(31,166,125)"))
  (is (= "text-[#1fa67d]" (chrome/status-tone-class :positive)))
  (is (= "text-ho-sell-hi" (chrome/status-tone-class :negative)))
  (is (= "text-[#9aa7ad]" (chrome/status-tone-class :neutral)))
  (is (= "text-trading-text" (chrome/status-tone-class :other)))
  (is (= "text-[#1fa67d]" (chrome/ledger-type-tone-class :deposit)))
  (is (= "text-ho-sell-hi" (chrome/ledger-type-tone-class :withdraw)))
  (is (= "text-trading-text" (chrome/ledger-type-tone-class :other))))

(deftest activity-table-helpers-cover-error-loading-empty-and-row-branches-test
  (let [fills-error (set (hiccup/collect-strings (tables/fills-table [] false "Trade history failed." nil standard-cols)))
        fills-loading (set (hiccup/collect-strings (tables/fills-table [] true nil nil standard-cols)))
        fills-empty (set (hiccup/collect-strings (tables/fills-table [] false nil nil standard-cols)))
        fills-rows (tables/fills-table [{:time-ms 1700000000000
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
                                       standard-cols)
        funding-loading (set (hiccup/collect-strings (tables/funding-history-table [] true nil nil standard-cols)))
        funding-empty (set (hiccup/collect-strings (tables/funding-history-table [] false nil nil standard-cols)))
        funding-rows (tables/funding-history-table [{:time-ms 1700000000000
                                                     :coin "BTC"
                                                     :funding-rate 0.0001
                                                     :position-size 3.2
                                                     :side-key :long
                                                     :payment 4.2}]
                                                   false
                                                   nil
                                                   nil
                                                   standard-cols)
        order-error (set (hiccup/collect-strings (tables/order-history-table [] false "Order history failed." nil standard-cols)))
        order-loading (set (hiccup/collect-strings (tables/order-history-table [] true nil nil standard-cols)))
        order-empty (set (hiccup/collect-strings (tables/order-history-table [] false nil nil standard-cols)))
        order-rows (tables/order-history-table [{:time-ms 1700000000000
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
                                               standard-cols)
        ledger-loading (set (hiccup/collect-strings (tables/ledger-table [] true nil nil standard-cols)))
        ledger-empty (set (hiccup/collect-strings (tables/ledger-table [] false nil nil standard-cols)))
        ledger-rows (tables/ledger-table [{:time-ms 1700000000000
                                           :type-key :deposit
                                           :type-label "Deposit"
                                           :amount 120
                                           :signed-amount 120
                                           :hash "0x1234567890abcdef1234567890abcdef"}]
                                         false
                                         nil
                                         nil
                                         standard-cols)
        positions-rows (tables/positions-table [{:coin "BTC"
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
                                               standard-cols)
        balances-empty (set (hiccup/collect-strings (tables/balances-table [] nil standard-cols)))
        positions-empty (set (hiccup/collect-strings (tables/positions-table [] nil standard-cols)))
        open-orders-empty (set (hiccup/collect-strings (tables/open-orders-table [] nil standard-cols)))
        twap-empty (set (hiccup/collect-strings (tables/twap-table [] nil standard-cols)))
        depositors-empty (set (hiccup/collect-strings (tables/depositors-table [] nil standard-cols)))
        position-strings (hiccup/collect-strings positions-rows)
        fill-row-node (td-with-text fills-rows "ETH")
        funding-row-node (td-with-text funding-rows "BTC")
        order-status-node (td-with-text order-rows "Rejected")
        ledger-type-node (td-with-text ledger-rows "Deposit")
        position-coin-node (td-with-text positions-rows "BTC")
        position-funding-node (hiccup/find-first-node positions-rows
                                                      #(and (= :td (first %))
                                                            (some (fn [text]
                                                                    (str/includes? text "4.20"))
                                                                  (hiccup/collect-strings %))))
        position-placeholder-node (td-with-text positions-rows "N/A")]
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
    (is (contains? (hiccup/node-class-set order-status-node) "text-ho-sell-hi"))
    (is (contains? ledger-loading "Loading deposits and withdrawals..."))
    (is (contains? ledger-empty "No deposits or withdrawals available."))
    (is (contains? (hiccup/node-class-set ledger-type-node) "text-[#1fa67d]"))
    (is (contains? balances-empty "No balances available."))
    (is (contains? positions-empty "No active positions."))
    (is (some? position-coin-node))
    (is (some #(= "3x" %) position-strings))
    (is (some #(str/includes? % "USDC") position-strings))
    (is (contains? (hiccup/node-class-set position-funding-node) "text-ho-sell-hi"))
    (is (some? position-placeholder-node))
    (is (contains? open-orders-empty "No open orders."))
    (is (contains? twap-empty "No TWAPs yet."))
    (is (contains? depositors-empty "No depositors available."))))

(deftest activity-async-table-state-precedence-and-colspans-test
  (let [trade-cols (repeat 8 {:id :col :label "Col"})
        funding-cols (repeat 5 {:id :col :label "Col"})
        order-cols (repeat 7 {:id :col :label "Col"})
        ledger-cols (repeat 4 {:id :col :label "Col"})
        fills-error-cell (state-cell (tables/fills-table [{:coin "ETH"}] true "Trade error." nil trade-cols)
                                     "Trade error.")
        funding-loading-cell (state-cell (tables/funding-history-table [{:coin "BTC"}] true nil nil funding-cols)
                                         "Loading funding history...")
        order-empty-cell (state-cell (tables/order-history-table [] false nil nil order-cols)
                                     "No order history available.")
        ledger-empty-cell (state-cell (tables/ledger-table [] false nil nil ledger-cols)
                                      "No deposits or withdrawals available.")]
    (is (= 8 (get-in fills-error-cell [1 :col-span])))
    (is (not (contains? (set (hiccup/collect-strings (tables/fills-table [{:coin "ETH"}] true "Trade error." nil trade-cols)))
                        "Loading trade history...")))
    (is (= 5 (get-in funding-loading-cell [1 :col-span])))
    (is (= 7 (get-in order-empty-cell [1 :col-span])))
    (is (= 4 (get-in ledger-empty-cell [1 :col-span])))))

(deftest depositor-address-cell-reveals-full-address-and-copies-it-test
  (let [address "0x0183fb7deeebd63c86f51c278cf39dcdd2a13834"
        cell (tables/depositor-address-cell {:address address})
        button (hiccup/find-first-node cell #(= :button (first %)))
        strings (set (hiccup/collect-strings cell))]
    (is (= [[:actions/copy-spectate-mode-watchlist-address address]]
           (get-in button [1 :on :click])))
    (is (nil? (get-in button [1 :title])) "no tooltip duplicating the visible address")
    (is (= (str "Copy address " address) (get-in button [1 :aria-label])))
    (is (empty? (hiccup/find-all-nodes cell #(contains? (hiccup/node-class-set %) "hidden")))
        "visibility swap only, never display:none, so the column never resizes")
    (is (contains? strings "0x0183…3834"))
    (is (contains? strings address))
    (is (= "—" (last (tables/depositor-address-cell {:address nil}))))
    (is (contains? (set (hiccup/collect-strings (tables/depositor-address-cell {:address address :leader? true})))
                   "Leader"))))

(deftest depositors-table-shows-copy-feedback-test
  (let [table (tables/depositors-table [] nil [] {:kind :success :message "Address copied to clipboard"})
        quiet (tables/depositors-table [] nil [] nil)]
    (is (contains? (set (hiccup/collect-strings table)) "Address copied to clipboard"))
    (is (not (contains? (set (hiccup/collect-strings quiet)) "Address copied to clipboard")))))
