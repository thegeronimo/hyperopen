(ns hyperopen.views.account-info.navigation-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info-view :as view]))

(deftest tab-navigation-renders-hide-small-toggle-only-on-balances-tab-test
  (let [counts {:balances 1 :positions 1}
        balances-nav (view/tab-navigation :balances counts true {} {} {} {} {} nil "hype")
        positions-nav (view/tab-navigation :positions counts true {})
        balances-toggle-input (hiccup/find-first-node balances-nav
                                               #(= "hide-small-balances"
                                                   (get-in % [1 :id])))
        balances-toggle-classes (hiccup/node-class-set balances-toggle-input)
        balances-toggle-label (hiccup/find-first-node balances-nav
                                               #(contains? (hiccup/direct-texts %) "Hide Small Balances"))
        balances-search-input (hiccup/find-first-node balances-nav
                                                      #(= [[:actions/set-account-info-coin-search :balances [:event.target/value]]]
                                                          (get-in % [1 :on :input])))
        positions-toggle-input (hiccup/find-first-node positions-nav
                                                #(= "hide-small-balances"
                                                    (get-in % [1 :id])))]
    (is (contains? (hiccup/node-class-set balances-nav) "justify-between"))
    (is (some? balances-toggle-input))
    (is (true? (get-in balances-toggle-input [1 :checked])))
    (is (contains? balances-toggle-classes "trade-toggle-checkbox"))
    (is (contains? balances-toggle-classes "h-4"))
    (is (contains? balances-toggle-classes "w-4"))
    (is (contains? (hiccup/node-class-set balances-toggle-label) "text-trading-text"))
    (is (some? balances-search-input))
    (is (= "hype" (get-in balances-search-input [1 :value])))
    (is (= "Coins..." (get-in balances-search-input [1 :placeholder])))
    (is (nil? positions-toggle-input))))

(deftest tab-navigation-renders-funding-history-actions-in-right-controls-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :funding-history counts false {:filter-open? false})
        filter-button (hiccup/find-first-node nav #(contains? (hiccup/direct-texts %) "Filter"))
        view-all-button (hiccup/find-first-node nav #(contains? (hiccup/direct-texts %) "View All"))
        export-button (hiccup/find-first-node nav #(contains? (hiccup/direct-texts %) "Export as CSV"))
        export-button-classes (hiccup/node-class-set export-button)]
    (is (some? filter-button))
    (is (some? view-all-button))
    (is (some? export-button))
    (is (= [[:actions/toggle-funding-history-filter-open]]
           (get-in filter-button [1 :on :click])))
    (is (= [[:actions/view-all-funding-history]]
           (get-in view-all-button [1 :on :click])))
    (is (= [[:actions/export-funding-history-csv]]
           (get-in export-button [1 :on :click])))
    (is (contains? export-button-classes "text-trading-green"))
    (is (contains? export-button-classes "font-normal"))))

(deftest tab-navigation-renders-order-history-filter-actions-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :order-history
                                 counts
                                 false
                                 {}
                                 {:status-filter :short
                                  :coin-search "nv"
                                  :filter-open? true})
        filter-button (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "Short")
                                                         (= [[:actions/toggle-order-history-filter-open]]
                                                            (get-in % [1 :on :click]))))
        filter-button-classes (hiccup/node-class-set filter-button)
        search-input (hiccup/find-first-node nav #(= [[:actions/set-account-info-coin-search :order-history [:event.target/value]]]
                                                     (get-in % [1 :on :input])))
        short-option (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "Short")
                                                        (= [[:actions/set-order-history-status-filter :short]]
                                                            (get-in % [1 :on :click]))))]
    (is (some? filter-button))
    (is (some? search-input))
    (is (= "nv" (get-in search-input [1 :value])))
    (is (some? short-option))
    (is (= [[:actions/toggle-order-history-filter-open]]
           (get-in filter-button [1 :on :click])))
    (is (= "1" (get-in filter-button [1 :style :--btn-focus-scale])))
    (is (contains? filter-button-classes "focus:outline-none"))
    (is (contains? filter-button-classes "focus-visible:outline-none"))
    (is (= [[:actions/set-order-history-status-filter :short]]
           (get-in short-option [1 :on :click])))))

(deftest tab-navigation-renders-open-orders-direction-filter-actions-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :open-orders
                                 counts
                                 false
                                 {}
                                 {}
                                 {:direction-filter :short
                                  :coin-search "eth"
                                  :filter-open? true}
                                 nil)
        filter-button (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "Short")
                                                         (= [[:actions/toggle-open-orders-direction-filter-open]]
                                                            (get-in % [1 :on :click]))))
        filter-button-classes (hiccup/node-class-set filter-button)
        search-input (hiccup/find-first-node nav #(= [[:actions/set-account-info-coin-search :open-orders [:event.target/value]]]
                                                     (get-in % [1 :on :input])))
        all-option (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "All")
                                                     (= [[:actions/set-open-orders-direction-filter :all]]
                                                        (get-in % [1 :on :click]))))
        short-option (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "Short")
                                                       (= [[:actions/set-open-orders-direction-filter :short]]
                                                          (get-in % [1 :on :click]))))]
    (is (some? filter-button))
    (is (= "1" (get-in filter-button [1 :style :--btn-focus-scale])))
    (is (contains? filter-button-classes "focus:outline-none"))
    (is (contains? filter-button-classes "focus-visible:outline-none"))
    (is (some? search-input))
    (is (= "eth" (get-in search-input [1 :value])))
    (is (some? all-option))
    (is (some? short-option))))

(deftest tab-navigation-renders-trade-history-direction-filter-actions-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :trade-history
                                 counts
                                 false
                                 {}
                                 {:direction-filter :short
                                  :coin-search "nv"
                                  :filter-open? true}
                                 {}
                                 {}
                                 {}
                                 nil
                                 "")
        filter-button (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "Short")
                                                         (= [[:actions/toggle-trade-history-direction-filter-open]]
                                                            (get-in % [1 :on :click]))))
        search-input (hiccup/find-first-node nav #(= [[:actions/set-account-info-coin-search :trade-history [:event.target/value]]]
                                                     (get-in % [1 :on :input])))
        short-option (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "Short")
                                                        (= [[:actions/set-trade-history-direction-filter :short]]
                                                           (get-in % [1 :on :click]))))
        long-option (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "Long")
                                                       (= [[:actions/set-trade-history-direction-filter :long]]
                                                          (get-in % [1 :on :click]))))]
    (is (some? filter-button))
    (is (some? search-input))
    (is (= "nv" (get-in search-input [1 :value])))
    (is (some? short-option))
    (is (some? long-option))))

(deftest tab-navigation-renders-positions-direction-filter-actions-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :positions
                                 counts
                                 false
                                 {}
                                 {}
                                 {}
                                 {:direction-filter :short
                                  :coin-search "eth"
                                  :filter-open? true}
                                 {}
                                 nil)
        filter-button (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "Short")
                                                         (= [[:actions/toggle-positions-direction-filter-open]]
                                                            (get-in % [1 :on :click]))))
        filter-button-classes (hiccup/node-class-set filter-button)
        search-input (hiccup/find-first-node nav #(= [[:actions/set-account-info-coin-search :positions [:event.target/value]]]
                                                     (get-in % [1 :on :input])))
        all-option (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "All")
                                                     (= [[:actions/set-positions-direction-filter :all]]
                                                        (get-in % [1 :on :click]))))
        short-option (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "Short")
                                                       (= [[:actions/set-positions-direction-filter :short]]
                                                          (get-in % [1 :on :click]))))]
    (is (some? filter-button))
    (is (= "1" (get-in filter-button [1 :style :--btn-focus-scale])))
    (is (contains? filter-button-classes "focus:outline-none"))
    (is (contains? filter-button-classes "focus-visible:outline-none"))
    (is (some? search-input))
    (is (= "eth" (get-in search-input [1 :value])))
    (is (some? all-option))
    (is (some? short-option))))

(deftest tab-navigation-renders-positions-count-when-positive-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :positions counts false {})
        positions-tab-node (hiccup/find-first-node nav #(contains? (hiccup/direct-texts %) "Positions (4)"))]
    (is (some? positions-tab-node))))

(deftest tab-navigation-hides-positions-count-when-zero-test
  (let [counts {:balances 2 :positions 0 :open-orders 3}
        nav (view/tab-navigation :positions counts false {})
        positions-tab-base-node (hiccup/find-first-node nav #(contains? (hiccup/direct-texts %) "Positions"))
        positions-tab-count-node (hiccup/find-first-node nav #(contains? (hiccup/direct-texts %) "Positions (0)"))]
    (is (some? positions-tab-base-node))
    (is (nil? positions-tab-count-node))))

(deftest tab-navigation-uses-white-toned-tab-text-for-selected-and-hover-states-test
  (let [nav (view/tab-navigation :funding-history {} false {})
        selected-tab (hiccup/find-first-node nav #(= [[:actions/select-account-info-tab :funding-history]]
                                                      (get-in % [1 :on :click])))
        selected-classes (hiccup/node-class-set selected-tab)
        inactive-tab (hiccup/find-first-node nav #(= [[:actions/select-account-info-tab :balances]]
                                                      (get-in % [1 :on :click])))
        inactive-classes (hiccup/node-class-set inactive-tab)]
    (is (contains? selected-classes "text-trading-text"))
    (is (not (contains? selected-classes "text-primary")))
    (is (not (contains? selected-classes "bg-base-100")))
    (is (contains? selected-classes "lg:bg-base-100"))
    (is (contains? inactive-classes "hover:text-trading-text"))
    (is (not (contains? inactive-classes "hover:text-primary")))
    (is (not (contains? inactive-classes "hover:bg-base-100")))
    (is (contains? inactive-classes "lg:hover:bg-base-100"))))

(deftest tab-navigation-renders-neutral-positions-freshness-cue-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :positions
                                 counts
                                 false
                                 {}
                                 {}
                                 {:positions {:text "Last update 2m 0s ago"
                                              :tone :neutral}})
        cue-node (hiccup/find-first-node nav #(= "account-tab-freshness-cue" (get-in % [1 :data-role])))
        cue-text (str/join " " (hiccup/collect-strings cue-node))
        cue-text-node (hiccup/find-first-node cue-node #(contains? (hiccup/direct-texts %) "Last update 2m 0s ago"))]
    (is (some? cue-node))
    (is (str/includes? cue-text "Last update 2m 0s ago"))
    (is (contains? (hiccup/node-class-set cue-text-node) "text-base-content/70"))))

(deftest tab-navigation-renders-open-orders-delayed-freshness-cue-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :open-orders
                                 counts
                                 false
                                 {}
                                 {}
                                 {:open-orders {:text "Stale 12s"
                                                :tone :warning}})
        cue-node (hiccup/find-first-node nav #(= "account-tab-freshness-cue" (get-in % [1 :data-role])))
        cue-text (str/join " " (hiccup/collect-strings cue-node))
        cue-text-node (hiccup/find-first-node cue-node #(contains? (hiccup/direct-texts %) "Stale 12s"))]
    (is (some? cue-node))
    (is (str/includes? cue-text "Stale 12s"))
    (is (contains? (hiccup/node-class-set cue-text-node) "text-warning"))))

(deftest account-info-panel-derives-positions-freshness-cue-from-websocket-health-test
  (let [state (-> fixtures/sample-account-info-state
                  (assoc-in [:account-info :selected-tab] :positions)
                  (assoc-in [:websocket-ui :show-surface-freshness-cues?] true)
                  (assoc :wallet {:address "0xabc"})
                  (assoc-in [:websocket :health]
                            {:generated-at-ms 5000
                             :streams {["webData2" nil "0xabc" nil nil]
                                       {:topic "webData2"
                                        :status :n-a
                                        :subscribed? true
                                        :last-payload-at-ms 3000}}}))
        panel (view/account-info-panel state)
        cue-node (hiccup/find-first-node panel #(= "account-tab-freshness-cue" (get-in % [1 :data-role])))
        cue-text (str/join " " (hiccup/collect-strings cue-node))]
    (is (some? cue-node))
    (is (str/includes? cue-text "Last update 2s ago"))))

(deftest account-info-panel-derives-open-orders-stale-cue-from-websocket-health-test
  (let [state (-> fixtures/sample-account-info-state
                  (assoc-in [:account-info :selected-tab] :open-orders)
                  (assoc-in [:websocket-ui :show-surface-freshness-cues?] true)
                  (assoc :wallet {:address "0xabc"})
                  (assoc-in [:websocket :health]
                            {:generated-at-ms 20000
                             :streams {["openOrders" nil "0xabc" nil nil]
                                       {:topic "openOrders"
                                        :status :delayed
                                        :subscribed? true
                                        :last-payload-at-ms 8000
                                        :stale-threshold-ms 5000}}}))
        panel (view/account-info-panel state)
        cue-node (hiccup/find-first-node panel #(= "account-tab-freshness-cue" (get-in % [1 :data-role])))
        cue-text (str/join " " (hiccup/collect-strings cue-node))]
    (is (some? cue-node))
    (is (str/includes? cue-text "Stale 12s"))))

(deftest account-info-panel-hides-freshness-cues-when-toggle-disabled-test
  (let [positions-state (-> fixtures/sample-account-info-state
                            (assoc-in [:account-info :selected-tab] :positions)
                            (assoc-in [:websocket-ui :show-surface-freshness-cues?] false)
                            (assoc :wallet {:address "0xabc"})
                            (assoc-in [:websocket :health]
                                      {:generated-at-ms 5000
                                       :streams {["webData2" nil "0xabc" nil nil]
                                                 {:topic "webData2"
                                                  :status :n-a
                                                  :subscribed? true
                                                  :last-payload-at-ms 3000}}}))
        open-orders-state (-> fixtures/sample-account-info-state
                              (assoc-in [:account-info :selected-tab] :open-orders)
                              (assoc-in [:websocket-ui :show-surface-freshness-cues?] false)
                              (assoc :wallet {:address "0xabc"})
                              (assoc-in [:websocket :health]
                                        {:generated-at-ms 20000
                                         :streams {["openOrders" nil "0xabc" nil nil]
                                                   {:topic "openOrders"
                                                    :status :delayed
                                                    :subscribed? true
                                                    :last-payload-at-ms 8000
                                                    :stale-threshold-ms 5000}}}))
        positions-panel (view/account-info-panel positions-state)
        open-orders-panel (view/account-info-panel open-orders-state)
        positions-cue (hiccup/find-first-node positions-panel #(= "account-tab-freshness-cue" (get-in % [1 :data-role])))
        open-orders-cue (hiccup/find-first-node open-orders-panel #(= "account-tab-freshness-cue" (get-in % [1 :data-role])))]
    (is (nil? positions-cue))
    (is (nil? open-orders-cue))))
