(ns hyperopen.views.trading-chart.utils.chart-interop-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]))

(deftest apply-persisted-visible-range-applies-stored-time-range-test
  (let [requested-key (atom nil)
        applied-range (atom nil)
        time-scale #js {:setVisibleRange (fn [range]
                                           (reset! applied-range (js->clj range :keywordize-keys true)))}
        chart #js {:timeScale (fn [] time-scale)}]
    (with-redefs [platform/local-storage-get (fn [key]
                                               (reset! requested-key key)
                                               "{\"kind\":\"time\",\"from\":10,\"to\":20}")]
      (is (= true (chart-interop/apply-persisted-visible-range! chart :1d)))
      (is (= "chart-visible-time-range:1d" @requested-key))
      (is (= {:from 10 :to 20} @applied-range)))))

(deftest apply-persisted-visible-range-ignores-invalid-storage-test
  (let [applied? (atom false)
        time-scale #js {:setVisibleRange (fn [_] (reset! applied? true))}
        chart #js {:timeScale (fn [] time-scale)}]
    (with-redefs [platform/local-storage-get (fn [_] "not-json")]
      (is (= false (chart-interop/apply-persisted-visible-range! chart :1d)))
      (is (false? @applied?)))))

(deftest subscribe-visible-range-persistence-persists-time-range-and-cleans-up-test
  (let [handler* (atom nil)
        unsubscribe-called? (atom false)
        writes (atom [])
        time-scale #js {:getVisibleRange (fn [] #js {:from 31 :to 45})
                        :subscribeVisibleTimeRangeChange (fn [handler]
                                                           (reset! handler* handler))
                        :unsubscribeVisibleTimeRangeChange (fn [handler]
                                                             (when (identical? handler @handler*)
                                                               (reset! unsubscribe-called? true)))}
        chart #js {:timeScale (fn [] time-scale)}]
    (with-redefs [platform/local-storage-set! (fn [key value]
                                                (swap! writes conj [key value]))
                  platform/set-timeout! (fn [f _ms]
                                          (f)
                                          :timeout-id)
                  platform/clear-timeout! (fn [_] nil)]
      (let [cleanup (chart-interop/subscribe-visible-range-persistence! chart :1h)]
        (is (fn? @handler*))
        (@handler* #js {})
        (is (= 1 (count @writes)))
        (let [[key raw] (first @writes)
              payload (js->clj (js/JSON.parse raw) :keywordize-keys true)]
          (is (= "chart-visible-time-range:1h" key))
          (is (= {:kind "time" :from 31 :to 45} payload)))
        (cleanup)
        (is @unsubscribe-called?)))))

(deftest subscribe-visible-range-persistence-falls-back-to-logical-range-test
  (let [handler* (atom nil)
        writes (atom [])
        time-scale #js {:getVisibleLogicalRange (fn [] #js {:from 5 :to 8})
                        :subscribeVisibleLogicalRangeChange (fn [handler]
                                                              (reset! handler* handler))
                        :unsubscribeVisibleLogicalRangeChange (fn [_] nil)}
        chart #js {:timeScale (fn [] time-scale)}]
    (with-redefs [platform/local-storage-set! (fn [key value]
                                                (swap! writes conj [key value]))
                  platform/set-timeout! (fn [f _ms]
                                          (f)
                                          :timeout-id)
                  platform/clear-timeout! (fn [_] nil)]
      (let [cleanup (chart-interop/subscribe-visible-range-persistence! chart :5m)]
        (is (fn? @handler*))
        (@handler* #js {})
        (let [[key raw] (first @writes)
              payload (js->clj (js/JSON.parse raw) :keywordize-keys true)]
          (is (= "chart-visible-time-range:5m" key))
          (is (= {:kind "logical" :from 5 :to 8} payload)))
        (cleanup)))))

(deftest transform-data-for-heikin-ashi-computes-deterministic-candles-test
  (let [raw-candles [{:time 1 :open 10 :high 15 :low 8 :close 12}
                     {:time 2 :open 12 :high 16 :low 11 :close 14}]
        transformed (chart-interop/transform-data-for-heikin-ashi raw-candles)]
    (is (= 2 (count transformed)))
    (let [first-candle (first transformed)
          second-candle (second transformed)]
      (is (= {:time 1
              :open 11
              :high 15
              :low 8
              :close 11.25}
             first-candle))
      (is (= 11.125 (:open second-candle)))
      (is (= 13.25 (:close second-candle)))
      (is (= 16 (:high second-candle)))
      (is (= 11 (:low second-candle))))))

(deftest transform-data-for-columns-adds-directional-color-test
  (let [raw-candles [{:time 1 :open 10 :high 11 :low 9 :close 12}
                     {:time 2 :open 12 :high 13 :low 11 :close 10}]
        transformed (vec (chart-interop/transform-data-for-columns raw-candles))]
    (is (= [{:time 1 :value 12 :color "#26a69a"}
            {:time 2 :value 10 :color "#ef5350"}]
           transformed))))

(deftest transform-data-for-high-low-builds-floating-range-bars-test
  (let [raw-candles [{:time 1 :open 10 :high 16 :low 8 :close 12}
                     {:time 2 :open 12 :high 18 :low 10 :close 16}]
        transformed (vec (chart-interop/transform-data-for-high-low raw-candles))]
    (is (= [{:time 1 :open 8 :high 16 :low 8 :close 16}
            {:time 2 :open 10 :high 18 :low 10 :close 18}]
           transformed))))

(deftest set-series-data-applies-hlc-area-transform-test
  (let [applied-options (atom nil)
        applied-data (atom nil)
        series #js {:applyOptions (fn [opts]
                                    (reset! applied-options (js->clj opts :keywordize-keys true)))
                    :setData (fn [data]
                               (reset! applied-data (js->clj data :keywordize-keys true)))}
        raw-candles [{:time 1 :open 10 :high 16 :low 8 :close 12}
                     {:time 2 :open 12 :high 18 :low 10 :close 16}]]
    (chart-interop/set-series-data! series raw-candles :hlc-area)
    (is (= [{:time 1 :value 12}
            {:time 2 :value 14.666666666666666}]
           @applied-data))
    (is (= "price" (get-in @applied-options [:priceFormat :type])))))
