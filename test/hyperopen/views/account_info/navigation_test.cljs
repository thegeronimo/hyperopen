(ns hyperopen.views.account-info.navigation-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info-view :as view]))

(deftest tab-navigation-renders-hide-small-toggle-only-on-balances-tab-test
  (let [counts {:balances 1 :positions 1}
        balances-nav (view/tab-navigation :balances counts true {})
        positions-nav (view/tab-navigation :positions counts true {})
        balances-toggle-input (hiccup/find-first-node balances-nav
                                               #(= "hide-small-balances"
                                                   (get-in % [1 :id])))
        balances-toggle-classes (hiccup/node-class-set balances-toggle-input)
        balances-toggle-label (hiccup/find-first-node balances-nav
                                               #(contains? (hiccup/direct-texts %) "Hide Small Balances"))
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
                                  :filter-open? true})
        filter-button (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "Short")
                                                         (= [[:actions/toggle-order-history-filter-open]]
                                                            (get-in % [1 :on :click]))))
        filter-button-classes (hiccup/node-class-set filter-button)
        short-option (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "Short")
                                                        (= [[:actions/set-order-history-status-filter :short]]
                                                            (get-in % [1 :on :click]))))]
    (is (some? filter-button))
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
                                  :filter-open? true}
                                 nil)
        filter-button (hiccup/find-first-node nav #(and (contains? (hiccup/direct-texts %) "Short")
                                                         (= [[:actions/toggle-open-orders-direction-filter-open]]
                                                            (get-in % [1 :on :click]))))
        filter-button-classes (hiccup/node-class-set filter-button)
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
                  (assoc :websocket-health
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
                  (assoc :websocket-health
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
                            (assoc :websocket-health
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
                              (assoc :websocket-health
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
