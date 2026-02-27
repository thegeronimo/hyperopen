(ns hyperopen.runtime.effect-adapters-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.runtime.app-effects :as app-effects]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.health-runtime :as health-runtime]
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]
            [hyperopen.websocket.subscriptions-runtime :as subscriptions-runtime]))

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

(deftest core-effect-handler-adapters-preserve-dispatch-signatures-test
  (let [calls (atom {:save nil
                     :save-many nil
                     :local-storage-set nil
                     :local-storage-set-json nil
                     :push-state nil
                     :replace-state nil})
        store (atom {})]
    (with-redefs [app-effects/save!
                  (fn [store* path value]
                    (swap! calls assoc :save [store* path value]))
                  app-effects/save-many!
                  (fn [store* path-values]
                    (swap! calls assoc :save-many [store* path-values]))
                  app-effects/local-storage-set!
                  (fn [key value]
                    (swap! calls assoc :local-storage-set [key value]))
                  app-effects/local-storage-set-json!
                  (fn [key value]
                    (swap! calls assoc :local-storage-set-json [key value]))
                  app-effects/push-state!
                  (fn [path]
                    (swap! calls assoc :push-state path))
                  app-effects/replace-state!
                  (fn [path]
                    (swap! calls assoc :replace-state path))]
      (effect-adapters/save :ctx store [:router :path] "/trade")
      (effect-adapters/save-many :ctx store [[[:router :path] "/wallet"]])
      (effect-adapters/local-storage-set :ctx store "active-asset" "ETH")
      (effect-adapters/local-storage-set-json :ctx store "asset-favorites" {:ETH true})
      (effect-adapters/push-state :ctx store "/trade")
      (effect-adapters/replace-state :ctx store "/wallet"))
    (is (= [store [:router :path] "/trade"]
           (:save @calls)))
    (is (= [store [[[:router :path] "/wallet"]]]
           (:save-many @calls)))
    (is (= ["active-asset" "ETH"]
           (:local-storage-set @calls)))
    (is (= ["asset-favorites" {:ETH true}]
           (:local-storage-set-json @calls)))
    (is (= "/trade" (:push-state @calls)))
    (is (= "/wallet" (:replace-state @calls)))))

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
