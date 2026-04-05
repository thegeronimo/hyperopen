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

(deftest install-websocket-watchers-syncs-an-already-connected-runtime-view-on-install-test
  (let [store (atom (base-store))
        runtime-view (atom {:connection {:status :connected
                                         :attempt 0
                                         :next-retry-at-ms nil
                                         :last-close nil
                                         :queue-size 0}
                            :stream {:health-fingerprint nil}})
        connected-calls (atom 0)
        disconnected-calls (atom 0)
        sync-calls (atom [])
        diagnostics-events (atom [])]
    (with-redefs [platform/queue-microtask! (fn [f] (f))
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
      (is (= 1 @connected-calls))
      (is (= 0 @disconnected-calls))
      (is (empty? @sync-calls))
      (is (empty? @diagnostics-events)))))

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

(deftest asset-selector-cache-watch-coalesces-multiple-market-updates-per-frame-test
  (let [store (atom (base-store))
        queued-frame (atom nil)
        persisted (atom [])]
    (watchers/install-store-cache-watchers!
     store
     {:persist-active-market-display! (fn [_] nil)
      :persist-asset-selector-markets-cache! (fn [markets state]
                                               (swap! persisted conj {:markets markets
                                                                      :sort-by (get-in state [:asset-selector :sort-by])}))
      :request-animation-frame! (fn [f]
                                  (reset! queued-frame f)
                                  :frame-id)})
    (swap! store (fn [state]
                   (-> state
                       (assoc-in [:asset-selector :sort-by] :volume)
                       (assoc-in [:asset-selector :loaded-at-ms] 100)
                       (assoc-in [:asset-selector :markets] [{:key "perp:BTC" :coin "BTC"}]))))
    (swap! store (fn [state]
                   (-> state
                       (assoc-in [:asset-selector :sort-by] :name)
                       (assoc-in [:asset-selector :loaded-at-ms] 200)
                       (assoc-in [:asset-selector :markets] [{:key "perp:ETH" :coin "ETH"}]))))
    (is (= 1 (count (keep some? [@queued-frame]))))
    (is (empty? @persisted))
    (@queued-frame 0)
    (is (= [{:markets [{:key "perp:ETH" :coin "ETH"}]
             :sort-by :name}]
           @persisted))))

(deftest asset-selector-cache-watch-ignores-live-market-patches-without-refresh-test
  (let [store (atom (base-store))
        queued-frame (atom nil)
        persisted (atom [])]
    (watchers/install-store-cache-watchers!
     store
     {:persist-active-market-display! (fn [_] nil)
      :persist-asset-selector-markets-cache! (fn [markets state]
                                               (swap! persisted conj {:markets markets
                                                                      :loaded-at-ms (get-in state [:asset-selector :loaded-at-ms])}))
      :request-animation-frame! (fn [f]
                                  (reset! queued-frame f)
                                  :frame-id)})
    (swap! store (fn [state]
                   (-> state
                       (assoc-in [:asset-selector :loaded-at-ms] 100)
                       (assoc-in [:asset-selector :markets] [{:key "perp:BTC"
                                                             :coin "BTC"
                                                             :mark 1.0}]))))
    (@queued-frame 0)
    (reset! queued-frame nil)
    (reset! persisted [])
    (swap! store assoc-in [:asset-selector :markets] [{:key "perp:BTC"
                                                       :coin "BTC"
                                                       :mark 2.0}])
    (is (nil? @queued-frame))
    (is (empty? @persisted))))

(deftest install-store-cache-watchers-replaces-existing-selector-watchers-test
  (let [store (atom (base-store))
        frame-callbacks (atom [])
        persisted (atom [])]
    (watchers/install-store-cache-watchers!
     store
     {:persist-active-market-display! (fn [_] nil)
      :persist-asset-selector-markets-cache! (fn [markets _state]
                                               (swap! persisted conj markets))
      :request-animation-frame! (fn [f]
                                  (swap! frame-callbacks conj f)
                                  :frame-id-1)})
    (watchers/install-store-cache-watchers!
     store
     {:persist-active-market-display! (fn [_] nil)
      :persist-asset-selector-markets-cache! (fn [markets _state]
                                               (swap! persisted conj markets))
      :request-animation-frame! (fn [f]
                                  (swap! frame-callbacks conj f)
                                  :frame-id-2)})
    (swap! store (fn [state]
                   (-> state
                       (assoc-in [:asset-selector :loaded-at-ms] 100)
                       (assoc-in [:asset-selector :markets] [{:key "perp:BTC" :coin "BTC"}]))))
    (doseq [callback @frame-callbacks]
      (callback 0))
    (is (= [[{:key "perp:BTC" :coin "BTC"}]]
           @persisted))))
