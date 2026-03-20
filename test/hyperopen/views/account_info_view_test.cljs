(ns hyperopen.views.account-info-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info-view :as view]))

(deftest account-info-panel-keeps-a-stable-default-height-across-standard-tabs-test
  (let [balances-panel (view/account-info-panel fixtures/sample-account-info-state)
        positions-panel (view/account-info-panel (assoc-in fixtures/sample-account-info-state
                                                          [:account-info :selected-tab]
                                                          :positions))
        balances-classes (hiccup/node-class-set balances-panel)
        positions-classes (hiccup/node-class-set positions-panel)
        content-node (second (vec (hiccup/node-children balances-panel)))
        content-classes (hiccup/node-class-set content-node)]
    (is (contains? balances-classes "h-96"))
    (is (contains? balances-classes "lg:h-[29rem]"))
    (is (contains? positions-classes "h-96"))
    (is (contains? positions-classes "lg:h-[29rem]"))
    (is (contains? balances-classes "flex"))
    (is (contains? balances-classes "flex-col"))
    (is (contains? balances-classes "min-h-0"))
    (is (contains? balances-classes "overflow-hidden"))
    (is (contains? content-classes "flex-1"))
    (is (contains? content-classes "min-h-0"))
    (is (contains? content-classes "overflow-hidden"))))

(deftest account-info-panel-renders-twap-tab-content-instead-of-placeholder-test
  (let [state {:account-info {:selected-tab :twap}
               :orders {:twap-states [[17 {:coin "BTC"
                                           :side "B"
                                           :sz "1.0"
                                           :executedSz "0.4"
                                           :executedNtl "40.0"
                                           :minutes 30
                                           :timestamp 1700000000000
                                           :reduceOnly false}]]}
               :spot {:meta nil
                      :clearinghouse-state nil}
               :account {:mode :classic}
               :perp-dex-clearinghouse {}}
        panel (view/account-info-panel state)
        strings (set (hiccup/collect-strings panel))]
    (is (contains? strings "Terminate"))
    (is (contains? strings "Active (1)"))
    (is (not (contains? strings "TWAP coming soon")))))

(deftest account-info-panel-applies-selected-extra-tab-panel-sizing-overrides-test
  (let [panel (view/account-info-panel
               {:account-info {:selected-tab :performance-metrics}}
               {:extra-tabs [{:id :performance-metrics
                              :label "Performance Metrics"
                              :panel-classes ["min-h-96"]
                              :panel-style {:max-height "min(44rem, calc(100dvh - 22rem))"}
                              :content [:div "Metrics"]}]})
        panel-classes (hiccup/node-class-set panel)
        panel-style (get-in panel [1 :style])]
    (is (contains? panel-classes "min-h-96"))
    (is (not (contains? panel-classes "h-96")))
    (is (= "min(44rem, calc(100dvh - 22rem))"
           (:max-height panel-style)))))

(deftest account-info-panel-allows-default-panel-class-overrides-test
  (let [panel (view/account-info-panel fixtures/sample-account-info-state
                                       {:default-panel-classes ["h-full"]})
        panel-classes (hiccup/node-class-set panel)]
    (is (contains? panel-classes "h-full"))
    (is (not (contains? panel-classes "h-96")))
    (is (not (contains? panel-classes "lg:h-[29rem]")))))
