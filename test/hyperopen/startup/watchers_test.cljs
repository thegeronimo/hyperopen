(ns hyperopen.startup.watchers-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.platform :as platform]
            [hyperopen.startup.watchers :as watchers]))

(defn- base-store []
  {:websocket {}
   :websocket-ui {:reconnect-count 0}
   :asset-selector {:markets []}})

(deftest runtime-view-health-watch-syncs-only-on-fingerprint-transition-test
  (let [store (atom (base-store))
        runtime-view (atom {:connection {:status :disconnected}
                            :stream {:health-fingerprint {:transport/state :disconnected}}})
        sync-calls (atom [])]
    (with-redefs [platform/queue-microtask! (fn [f] (f))
                  platform/now-ms (constantly 1000)]
      (watchers/install-websocket-watchers!
       {:store store
        :runtime-view runtime-view
        :append-diagnostics-event! (fn [& _] nil)
        :sync-websocket-health! (fn [runtime-store & {:as opts}]
                                  (swap! sync-calls conj {:runtime-store runtime-store
                                                          :opts opts}))
        :on-websocket-connected! (fn [] nil)
        :on-websocket-disconnected! (fn [] nil)})
      (swap! runtime-view assoc-in [:stream :metrics] {:market-coalesced 1})
      (is (empty? @sync-calls))
      (swap! runtime-view assoc-in [:stream :health-fingerprint] {:transport/state :connected})
      (is (= 1 (count @sync-calls)))
      (is (= {:projected-fingerprint {:transport/state :connected}}
             (:opts (first @sync-calls))))
      (swap! runtime-view assoc-in [:stream :now-ms] 2000)
      (is (= 1 (count @sync-calls))))))

(deftest connection-status-watch-forces-sync-only-on-status-transitions-test
  (let [store (atom (base-store))
        runtime-view (atom {:connection {:status :disconnected
                                         :attempt 0
                                         :next-retry-at-ms nil
                                         :last-close nil
                                         :queue-size 0}
                            :stream {:health-fingerprint nil}})
        queue-microtask-calls (atom 0)
        sync-calls (atom [])
        diagnostics-events (atom [])
        connected-calls (atom 0)
        disconnected-calls (atom 0)]
    (with-redefs [platform/queue-microtask! (fn [f]
                                              (swap! queue-microtask-calls inc)
                                              (f))
                  platform/now-ms (constantly 2000)]
      (watchers/install-websocket-watchers!
       {:store store
        :runtime-view runtime-view
        :append-diagnostics-event! (fn [_store event at-ms]
                                     (swap! diagnostics-events conj {:event event
                                                                     :at-ms at-ms}))
        :sync-websocket-health! (fn [_ & {:as opts}]
                                  (swap! sync-calls conj opts))
        :on-websocket-connected! #(swap! connected-calls inc)
        :on-websocket-disconnected! #(swap! disconnected-calls inc)})
      (swap! runtime-view assoc-in [:connection :attempt] 1)
      (testing "Non-status updates do not force health sync"
        (is (empty? @sync-calls)))
      (swap! runtime-view assoc-in [:connection :status] :connected)
      (testing "Status transitions force sync and notify connected callback"
        (is (= [{:force? true}] @sync-calls))
        (is (= 1 @connected-calls))
        (is (= 0 @disconnected-calls))
        (is (= 1 @queue-microtask-calls))
        (is (= [{:event :connected
                 :at-ms 2000}]
               @diagnostics-events))
        (is (= {} (:websocket @store)))))))

(deftest connection-watch-ignores-legacy-projection-field-churn-and-tracks-reconnect-transition-test
  (let [store (atom (base-store))
        runtime-view (atom {:connection {:status :connected
                                         :attempt 0
                                         :next-retry-at-ms nil
                                         :last-close nil
                                         :queue-size 0
                                         :now-ms 1000
                                         :last-activity-at-ms 900}
                            :stream {:health-fingerprint nil}})
        queue-microtask-calls (atom 0)
        sync-calls (atom [])
        diagnostics-events (atom [])]
    (with-redefs [platform/queue-microtask! (fn [f]
                                              (swap! queue-microtask-calls inc)
                                              (f))
                  platform/now-ms (constantly 2000)]
      (watchers/install-websocket-watchers!
       {:store store
        :runtime-view runtime-view
        :append-diagnostics-event! (fn [_store event at-ms]
                                     (swap! diagnostics-events conj {:event event
                                                                     :at-ms at-ms}))
        :sync-websocket-health! (fn [_ & {:as opts}]
                                  (swap! sync-calls conj opts))
        :on-websocket-connected! (fn [] nil)
        :on-websocket-disconnected! (fn [] nil)})
      (swap! runtime-view assoc-in [:connection :now-ms] 2000)
      (swap! runtime-view assoc-in [:connection :last-activity-at-ms] 1900)
      (swap! runtime-view assoc-in [:connection :queue-size] 1)
      (swap! runtime-view assoc-in [:connection :attempt] 2)
      (testing "Legacy projection field churn does not enqueue store writes or force sync"
        (is (= 0 @queue-microtask-calls))
        (is (empty? @sync-calls))
        (is (empty? @diagnostics-events))
        (is (= {} (:websocket @store))))
      (swap! runtime-view assoc-in [:connection :status] :reconnecting)
      (testing "Reconnect transition still updates diagnostics/reconnect counters"
        (is (= 1 @queue-microtask-calls))
        (is (= [{:force? true}] @sync-calls))
        (is (= [{:event :reconnecting
                 :at-ms 2000}]
               @diagnostics-events))
        (is (= 1 (get-in @store [:websocket-ui :reconnect-count])))))))
