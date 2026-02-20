(ns hyperopen.views.trading-chart.utils.chart-interop.baseline-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.baseline :as baseline]))

(deftest baseline-subscription-refreshes-base-value-on-visible-range-change-test
  (let [visible-range* (atom {:from 10 :to 30})
        subscribed-handler* (atom nil)
        unsubscribed?* (atom false)
        applied-options* (atom [])
        time-scale #js {:subscribeVisibleLogicalRangeChange (fn [handler]
                                                              (reset! subscribed-handler* handler))
                        :unsubscribeVisibleLogicalRangeChange (fn [handler]
                                                                (when (identical? handler @subscribed-handler*)
                                                                  (reset! unsubscribed?* true)))}
        price-scale #js {:getVisibleRange (fn []
                                            (clj->js @visible-range*))}
        main-series #js {:priceScale (fn [] price-scale)
                         :applyOptions (fn [opts]
                                         (swap! applied-options* conj (js->clj opts :keywordize-keys true)))}
        chart #js {:timeScale (fn [] time-scale)}
        chart-obj #js {:chart chart
                       :mainSeries main-series}]
    (chart-interop/sync-baseline-base-value-subscription! chart-obj :baseline)
    (is (= 20 (get-in (first @applied-options*) [:baseValue :price])))
    (reset! visible-range* {:from 30 :to 50})
    (@subscribed-handler* #js {})
    (is (= 40 (get-in (last @applied-options*) [:baseValue :price])))
    (chart-interop/sync-baseline-base-value-subscription! chart-obj :line)
    (is @unsubscribed?*)))

(deftest baseline-module-infer-base-value-handles-numeric-like-and-invalid-values-test
  (is (= 15 (baseline/infer-baseline-base-value [{:value "10"}
                                                 {:value 20}])))
  (is (nil? (baseline/infer-baseline-base-value [{:value "bad"}
                                                 {:value nil}]))))

(deftest baseline-module-sync-supports-time-range-subscriptions-and-cleanup-test
  (let [visible-range* (atom {:from 100 :to 120})
        subscribed-handler* (atom nil)
        unsubscribed?* (atom false)
        applied-options* (atom [])
        time-scale #js {:subscribeVisibleTimeRangeChange (fn [handler]
                                                           (reset! subscribed-handler* handler))
                        :unsubscribeVisibleTimeRangeChange (fn [handler]
                                                             (when (identical? handler @subscribed-handler*)
                                                               (reset! unsubscribed?* true)))}
        price-scale #js {:getVisibleRange (fn []
                                            (clj->js @visible-range*))}
        main-series #js {:priceScale (fn [] price-scale)
                         :applyOptions (fn [opts]
                                         (swap! applied-options* conj (js->clj opts :keywordize-keys true)))}
        chart #js {:timeScale (fn [] time-scale)}
        chart-obj #js {:chart chart
                       :mainSeries main-series}]
    (baseline/sync-baseline-base-value-subscription! chart-obj :baseline)
    (is (= 110 (get-in (first @applied-options*) [:baseValue :price])))
    (reset! visible-range* {:from 120 :to 140})
    (@subscribed-handler* #js {})
    (is (= 130 (get-in (last @applied-options*) [:baseValue :price])))
    (baseline/clear-baseline-base-value-subscription! chart-obj)
    (is @unsubscribed?*)))

(deftest baseline-module-sync-without-chart-or-time-scale-is-safe-test
  (let [main-series #js {:priceScale (fn [] nil)
                         :applyOptions (fn [_] nil)}
        chart-obj #js {:chart #js {:timeScale (fn [] nil)}
                       :mainSeries main-series}
        baseline-result (baseline/sync-baseline-base-value-subscription! chart-obj :baseline)
        clear-result (baseline/sync-baseline-base-value-subscription! chart-obj :line)]
    (is (nil? (baseline/sync-baseline-base-value-subscription! nil :baseline)))
    (is (nil? (baseline/clear-baseline-base-value-subscription! nil)))
    (is (some? baseline-result))
    (is (true? clear-result))))

