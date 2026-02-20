(ns hyperopen.views.trading-chart.utils.chart-interop.visible-range-persistence-test
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

