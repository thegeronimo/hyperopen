(ns hyperopen.runtime.effect-adapters-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.asset-selector.query :as asset-selector-query]
            [hyperopen.funding.history-cache :as funding-cache]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.app-effects :as app-effects]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.common :as common]
            [hyperopen.runtime.effect-adapters.websocket :as ws-adapters]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.health-runtime :as health-runtime]
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]
            [hyperopen.websocket.subscriptions-runtime :as subscriptions-runtime]))

(deftest facade-shared-adapters-delegate-to-common-module-test
  (is (identical? common/save effect-adapters/save))
  (is (identical? common/save-many effect-adapters/save-many))
  (is (identical? common/local-storage-set effect-adapters/local-storage-set))
  (is (identical? common/local-storage-set-json effect-adapters/local-storage-set-json))
  (is (identical? common/push-state effect-adapters/push-state))
  (is (identical? common/replace-state effect-adapters/replace-state))
  (is (identical? common/schedule-animation-frame! effect-adapters/schedule-animation-frame!)))

(deftest facade-websocket-adapters-delegate-to-websocket-module-test
  (is (identical? ws-adapters/append-diagnostics-event! effect-adapters/append-diagnostics-event!))
  (is (identical? ws-adapters/sync-websocket-health-with-runtime! effect-adapters/sync-websocket-health-with-runtime!))
  (is (identical? ws-adapters/sync-websocket-health! effect-adapters/sync-websocket-health!))
  (is (identical? ws-adapters/make-fetch-candle-snapshot effect-adapters/make-fetch-candle-snapshot))
  (is (identical? ws-adapters/fetch-candle-snapshot effect-adapters/fetch-candle-snapshot))
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

(deftest sync-asset-selector-active-ctx-subscriptions-diffs-owner-scoped-coins-test
  (let [store (atom {:asset-selector {}})
        sent-messages (atom [])
        original-state @active-ctx/active-asset-ctx-state]
    (reset! active-ctx/active-asset-ctx-state {:subscriptions #{"BTC" "SOL"}
                                                :owners-by-coin {"BTC" #{:asset-selector}
                                                                 "SOL" #{:asset-selector}}
                                                :coins-by-owner {:asset-selector #{"BTC" "SOL"}}
                                                :contexts {}})
    (try
      (with-redefs [asset-selector-query/selector-visible-market-coins (fn [_]
                                                                          #{"BTC" "ETH"})
                    ws-client/send-message! (fn [message]
                                              (swap! sent-messages conj message)
                                              true)]
        (effect-adapters/sync-asset-selector-active-ctx-subscriptions nil store))
      (is (= [{:method "subscribe"
               :subscription {:type "activeAssetCtx"
                              :coin "ETH"}}
              {:method "unsubscribe"
               :subscription {:type "activeAssetCtx"
                              :coin "SOL"}}]
             @sent-messages))
      (is (= #{"BTC" "ETH"}
             (active-ctx/get-subscribed-coins-by-owner :asset-selector)))
      (finally
        (reset! active-ctx/active-asset-ctx-state original-state)))))

(deftest sync-active-asset-funding-predictability-projects-loading-and-success-test
  (async done
    (let [store (atom {:active-assets {:contexts {}
                                       :loading false}})
          request-calls (atom [])
          start-ms (platform/now-ms)
          rows [{:time-ms (- start-ms (* 2 60 60 1000))
                 :funding-rate-raw 0.001}
                {:time-ms (- start-ms (* 60 60 1000))
                 :funding-rate-raw 0.002}]]
      (with-redefs [funding-cache/sync-market-funding-history-cache!
                    (fn [coin]
                      (swap! request-calls conj coin)
                      (js/Promise.resolve {:rows rows}))]
        (let [promise (effect-adapters/sync-active-asset-funding-predictability nil store "btc")]
          (is (= true
                 (get-in @store [:active-assets :funding-predictability :loading-by-coin "BTC"])))
          (-> promise
              (.then (fn [_]
                       (is (= ["BTC"] @request-calls))
                       (is (= false
                              (get-in @store [:active-assets :funding-predictability :loading-by-coin "BTC"])))
                       (let [summary (get-in @store [:active-assets :funding-predictability :by-coin "BTC"])
                             loaded-at-ms (get-in @store [:active-assets :funding-predictability :loaded-at-ms-by-coin "BTC"])]
                         (is (= 2 (:sample-count summary)))
                         (is (number? (:mean summary)))
                         (is (number? (:stddev summary)))
                         (is (number? loaded-at-ms))
                         (is (>= loaded-at-ms start-ms)))
                       (is (nil?
                            (get-in @store [:active-assets :funding-predictability :error-by-coin "BTC"])))
                       (done)))
              (.catch (async-support/unexpected-error done))))))))

(deftest sync-active-asset-funding-predictability-projects-error-without-clearing-last-summary-test
  (async done
    (let [store (atom {:active-assets {:contexts {}
                                       :loading false
                                       :funding-predictability {:by-coin {"BTC" {:mean 0.001}}
                                                                :loading-by-coin {}
                                                                :error-by-coin {}
                                                                :loaded-at-ms-by-coin {}}}})
          start-ms (platform/now-ms)]
      (with-redefs [funding-cache/sync-market-funding-history-cache!
                    (fn [_coin]
                      (js/Promise.reject (js/Error. "boom")))]
        (let [promise (effect-adapters/sync-active-asset-funding-predictability nil store "BTC")]
          (-> promise
              (.then (fn [_]
                       (is (= false
                              (get-in @store [:active-assets :funding-predictability :loading-by-coin "BTC"])))
                       (is (= "boom"
                              (get-in @store [:active-assets :funding-predictability :error-by-coin "BTC"])))
                       (is (= {:mean 0.001}
                              (get-in @store [:active-assets :funding-predictability :by-coin "BTC"])))
                       (let [loaded-at-ms (get-in @store [:active-assets :funding-predictability :loaded-at-ms-by-coin "BTC"])]
                         (is (number? loaded-at-ms))
                         (is (>= loaded-at-ms start-ms)))
                       (done)))
              (.catch (async-support/unexpected-error done))))))))
