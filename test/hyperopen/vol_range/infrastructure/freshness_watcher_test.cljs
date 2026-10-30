(ns hyperopen.vol-range.infrastructure.freshness-watcher-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [hyperopen.vol-range.infrastructure.freshness-watcher :as watcher]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def ^:private day-ms 86400000)
(def ^:private rollover (+ (* day-ms (js/Math.floor (/ sf/now-ms day-ms))) day-ms 1800000))

(deftest dispatches-refresh-when-visible-ticket-data-goes-stale-test
  (async done
    (let [store (atom (sf/base-state))
          calls (atom [])
          clock (atom sf/now-ms)]
      (watcher/install-vol-range-freshness-watcher!
       {:store store :now-ms-fn #(deref clock)
        :dispatch! (fn [_ _ effects] (swap! calls conj effects))})
      ;; fresh data: a store change does not dispatch
      (swap! store assoc :tick 1)
      (js/setTimeout
       (fn []
         (is (= [] @calls) "fresh data never refetches")
         (reset! clock rollover)
         (swap! store assoc :tick 2)
         (js/setTimeout
          (fn []
            (is (= [[[:actions/refresh-vol-range-daily-candles]]] @calls)
                "after the UTC rollover the watcher requests a refresh")
            (testing "a recorded request quiesces the watcher"
              (swap! store assoc-in [:vol-range :daily-fetch "@107" :requested-at-ms] rollover)
              (swap! store assoc :tick 3)
              (js/setTimeout (fn []
                               (is (= 1 (count @calls)))
                               (remove-watch store :hyperopen.vol-range.infrastructure.freshness-watcher/vol-range-freshness)
                               (done))
                             5))) 5))
       5))))
