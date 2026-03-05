(ns hyperopen.runtime.effect-adapters.websocket-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.runtime.app-effects :as app-effects]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.websocket :as ws-adapters]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.health-runtime :as health-runtime]
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]
            [hyperopen.websocket.subscriptions-runtime :as subscriptions-runtime]))

(deftest facade-websocket-adapters-delegate-to-websocket-module-test
  (is (identical? ws-adapters/append-diagnostics-event! effect-adapters/append-diagnostics-event!))
  (is (identical? ws-adapters/sync-websocket-health-with-runtime! effect-adapters/sync-websocket-health-with-runtime!))
  (is (identical? ws-adapters/sync-websocket-health! effect-adapters/sync-websocket-health!))
  (is (identical? ws-adapters/make-fetch-candle-snapshot effect-adapters/make-fetch-candle-snapshot))
  (is (identical? ws-adapters/fetch-candle-snapshot effect-adapters/fetch-candle-snapshot))
  (is (fn? effect-adapters/sync-active-candle-subscription))
  (is (identical? ws-adapters/make-init-websocket effect-adapters/make-init-websocket))
  (is (identical? ws-adapters/init-websocket effect-adapters/init-websocket))
  (is (identical? ws-adapters/make-reconnect-websocket effect-adapters/make-reconnect-websocket))
  (is (identical? ws-adapters/reconnect-websocket effect-adapters/reconnect-websocket))
  (is (identical? ws-adapters/refresh-websocket-health effect-adapters/refresh-websocket-health))
  (is (identical? ws-adapters/make-refresh-websocket-health effect-adapters/make-refresh-websocket-health))
  (is (identical? ws-adapters/ws-reset-subscriptions effect-adapters/ws-reset-subscriptions))
  (is (identical? ws-adapters/confirm-ws-diagnostics-reveal effect-adapters/confirm-ws-diagnostics-reveal))
  (is (identical? ws-adapters/copy-websocket-diagnostics effect-adapters/copy-websocket-diagnostics)))

(deftest subscribe-active-asset-persists-through-local-storage-effect-boundary-test
  (let [persist-calls (atom [])
        store (atom {:asset-selector {:market-by-key {}}
                     :chart-options {:selected-timeframe :1d}})]
    (with-redefs [app-effects/local-storage-set!
                  (fn [key value]
                    (swap! persist-calls conj [key value]))
                  subscriptions-runtime/subscribe-active-asset!
                  (fn [{:keys [persist-active-asset!]}]
                    (persist-active-asset! "ETH"))]
      (effect-adapters/subscribe-active-asset nil store "ETH"))
    (is (= [["active-asset" "ETH"]] @persist-calls))))

(deftest sync-active-candle-subscription-delegates-through-subscriptions-runtime-test
  (let [calls (atom [])
        store (atom {:active-asset "ETH"
                     :chart-options {:selected-timeframe :1h}})]
    (with-redefs [subscriptions-runtime/sync-active-candle-subscription!
                  (fn [deps]
                    (swap! calls conj (select-keys deps [:store :interval])))]
      (effect-adapters/sync-active-candle-subscription nil store :interval :5m))
    (is (= [{:store store :interval :5m}] @calls))))

(deftest make-init-and-reconnect-websocket-build-injected-effect-handlers-test
  (let [calls (atom [])
        log-fn (fn [& _] nil)
        init-connection! (fn [& _] nil)
        force-reconnect! (fn [] nil)
        store (atom {})
        init-websocket (effect-adapters/make-init-websocket {:ws-url "wss://custom.test/ws"
                                                              :log-fn log-fn
                                                              :init-connection! init-connection!})
        reconnect-websocket (effect-adapters/make-reconnect-websocket {:log-fn log-fn
                                                                        :force-reconnect! force-reconnect!})]
    (with-redefs [app-effects/init-websocket!
                  (fn [opts]
                    (swap! calls conj [:init opts]))
                  app-effects/reconnect-websocket!
                  (fn [opts]
                    (swap! calls conj [:reconnect opts]))]
      (init-websocket :ctx store)
      (reconnect-websocket :ctx store))
    (let [[_ init-opts] (first @calls)
          [_ reconnect-opts] (second @calls)]
      (is (= store (:store init-opts)))
      (is (= "wss://custom.test/ws" (:ws-url init-opts)))
      (is (identical? log-fn (:log-fn init-opts)))
      (is (identical? init-connection! (:init-connection! init-opts)))
      (is (identical? log-fn (:log-fn reconnect-opts)))
      (is (identical? force-reconnect! (:force-reconnect! reconnect-opts))))))

(deftest sync-websocket-health-sources-market-projection-flush-events-from-telemetry-ring-test
  (let [store (atom {})
        captured-health (atom nil)
        ring-entry {:seq 42
                    :event :websocket/market-projection-flush
                    :at-ms 777
                    :store-id "emit-store"
                    :pending-count 3
                    :overwrite-count 1
                    :flush-duration-ms 9
                    :queue-wait-ms 4
                    :flush-count 12
                    :max-pending-depth 8
                    :p95-flush-duration-ms 11
                    :queued-total 30
                    :overwrite-total 5
                    :ignored "not-copied"}
        expected-entry (select-keys ring-entry
                                    [:seq
                                     :event
                                     :at-ms
                                     :store-id
                                     :pending-count
                                     :overwrite-count
                                     :flush-duration-ms
                                     :queue-wait-ms
                                     :flush-count
                                     :max-pending-depth
                                     :p95-flush-duration-ms
                                     :queued-total
                                     :overwrite-total])]
    (with-redefs [telemetry/events (fn []
                                     (throw (js/Error. "global telemetry log should not be scanned")))
                  telemetry/market-projection-flush-events (constantly [ring-entry])
                  market-projection-runtime/market-projection-telemetry-snapshot
                  (constantly {:stores [{:store-id "emit-store"
                                         :pending-count 0
                                         :frame-scheduled? false}]})
                  ws-client/get-health-snapshot
                  (constantly {:generated-at-ms 1000
                               :transport {:state :connected
                                           :freshness :live}})
                  health-runtime/sync-websocket-health!
                  (fn [{:keys [get-health-snapshot]}]
                    (reset! captured-health (get-health-snapshot)))]
      (effect-adapters/sync-websocket-health-with-runtime! nil store)
      (let [diagnostics (get @captured-health :market-projection)]
        (is (= 1 (count (:stores diagnostics))))
        (is (= [expected-entry] (:flush-events diagnostics)))
        (is (= telemetry/market-projection-flush-event-limit
               (:flush-event-limit diagnostics)))
        (is (= 1 (:flush-event-count diagnostics)))
        (is (= 42 (:latest-flush-event-seq diagnostics)))
        (is (= 777 (:latest-flush-at-ms diagnostics)))))))
