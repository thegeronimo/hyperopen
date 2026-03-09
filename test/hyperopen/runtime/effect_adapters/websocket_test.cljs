(ns hyperopen.runtime.effect-adapters.websocket-test
  (:require [cljs.test :refer-macros [deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.orderbook.price-aggregation :as price-agg]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.app-effects :as app-effects]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.websocket :as ws-adapters]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.startup.restore :as startup-restore]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.candles :as candles]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.diagnostics-effects :as diagnostics-effects]
            [hyperopen.websocket.diagnostics-runtime :as diagnostics-runtime]
            [hyperopen.websocket.health-projection :as health-projection]
            [hyperopen.websocket.health-runtime :as health-runtime]
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.subscriptions-runtime :as subscriptions-runtime]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.websocket.webdata2 :as webdata2]))

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
                  telemetry/market-projection-flush-events (fn []
                                                            (throw (js/Error. "raw flush events should not be remapped")))
                  telemetry/market-projection-flush-diagnostics-events (constantly [expected-entry])
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

(deftest append-diagnostics-event-adapter-passes-runtime-limit-to-projection-test
  (let [store (atom {})
        calls (atom [])]
    (with-redefs [health-projection/append-diagnostics-event
                  (fn [state event at-ms details limit]
                    (swap! calls conj [state event at-ms details limit])
                    (assoc state :last-event [event at-ms details limit]))]
      (ws-adapters/append-diagnostics-event! store :health/check 111 {:detail true}))
    (let [[[state event at-ms details limit]] @calls]
      (is (= {} state))
      (is (= :health/check event))
      (is (= 111 at-ms))
      (is (= {:detail true} details))
      (is (= runtime-state/diagnostics-timeline-limit limit))
      (is (= [:health/check 111 {:detail true} runtime-state/diagnostics-timeline-limit]
             (:last-event @store))))))

(deftest sync-websocket-health-delegates-to-runtime-aware-helper-test
  (let [store (atom {})
        calls (atom [])]
    (with-redefs [ws-adapters/sync-websocket-health-with-runtime!
                  (fn [runtime store* & {:keys [force? projected-fingerprint]}]
                    (swap! calls conj {:runtime runtime
                                       :store store*
                                       :force? force?
                                       :projected-fingerprint projected-fingerprint})
                    :sync-result)]
      (is (= :sync-result
             (ws-adapters/sync-websocket-health! store :force? true :projected-fingerprint "fp"))))
    (is (= [{:runtime nil
             :store store
             :force? true
             :projected-fingerprint "fp"}]
           @calls))))

(deftest fetch-candle-snapshot-adapter-builds-default-and-custom-dependencies-test
  (let [store (atom {})
        calls (atom [])
        custom-log-fn (fn [& _] nil)
        custom-request-fn (fn [& _] nil)
        custom-success-fn (fn [& _] nil)
        custom-error-fn (fn [& _] nil)]
    (with-redefs [app-effects/fetch-candle-snapshot!
                  (fn [deps]
                    (swap! calls conj deps)
                    :fetch-result)]
      (is (= :fetch-result
             (ws-adapters/fetch-candle-snapshot nil store :coin "ETH" :interval :4h :bars 10)))
      (is (= :fetch-result
             ((ws-adapters/make-fetch-candle-snapshot
               {:log-fn custom-log-fn
                :request-candle-snapshot-fn custom-request-fn
                :apply-candle-snapshot-success custom-success-fn
                :apply-candle-snapshot-error custom-error-fn})
              nil
              store
              :coin "BTC"))))
    (let [[default-call custom-call] @calls]
      (is (= store (:store default-call)))
      (is (= "ETH" (:coin default-call)))
      (is (= :4h (:interval default-call)))
      (is (= 10 (:bars default-call)))
      (is (identical? telemetry/log! (:log-fn default-call)))
      (is (identical? api/request-candle-snapshot!
                      (:request-candle-snapshot-fn default-call)))
      (is (identical? api-projections/apply-candle-snapshot-success
                      (:apply-candle-snapshot-success default-call)))
      (is (identical? api-projections/apply-candle-snapshot-error
                      (:apply-candle-snapshot-error default-call)))

      (is (= store (:store custom-call)))
      (is (= "BTC" (:coin custom-call)))
      (is (= :1d (:interval custom-call)))
      (is (= 330 (:bars custom-call)))
      (is (identical? custom-log-fn (:log-fn custom-call)))
      (is (identical? custom-request-fn
                      (:request-candle-snapshot-fn custom-call)))
      (is (identical? custom-success-fn
                      (:apply-candle-snapshot-success custom-call)))
      (is (identical? custom-error-fn
                      (:apply-candle-snapshot-error custom-call))))))

(deftest subscribe-active-asset-adapter-wires-callbacks-and-string-only-persistence-test
  (let [store (atom {})
        captured (atom nil)
        persist-calls (atom [])
        custom-log-fn (fn [& _] nil)
        custom-resolve-market-fn (fn [& _] nil)
        custom-persist-display-fn (fn [& _] nil)
        custom-subscribe-ctx-fn (fn [& _] nil)
        custom-sync-candle-fn (fn [& _] nil)
        custom-clear-candle-fn (fn [& _] nil)
        custom-fetch-fn (fn [& _] nil)]
    (with-redefs [app-effects/local-storage-set!
                  (fn [key value]
                    (swap! persist-calls conj [key value]))
                  subscriptions-runtime/subscribe-active-asset!
                  (fn [deps]
                    (reset! captured deps)
                    ((:persist-active-asset! deps) "ETH")
                    ((:persist-active-asset! deps) :ETH)
                    :subscribe-result)]
      (is (= :subscribe-result
             (ws-adapters/subscribe-active-asset
              store
              "ETH"
              {:log-fn custom-log-fn
               :resolve-market-by-coin-fn custom-resolve-market-fn
               :persist-active-market-display-fn custom-persist-display-fn
               :subscribe-active-asset-ctx-fn custom-subscribe-ctx-fn
               :sync-candle-subscription-fn custom-sync-candle-fn
               :clear-candle-subscription-fn custom-clear-candle-fn
               :fetch-candle-snapshot-fn custom-fetch-fn}))))
    (is (= [["active-asset" "ETH"]] @persist-calls))
    (is (= store (:store @captured)))
    (is (= "ETH" (:coin @captured)))
    (is (identical? custom-log-fn (:log-fn @captured)))
    (is (identical? custom-resolve-market-fn
                    (:resolve-market-by-coin-fn @captured)))
    (is (identical? custom-persist-display-fn
                    (:persist-active-market-display! @captured)))
    (is (identical? custom-subscribe-ctx-fn
                    (:subscribe-active-asset-ctx! @captured)))
    (is (identical? custom-sync-candle-fn
                    (:sync-candle-subscription! @captured)))
    (is (identical? custom-clear-candle-fn
                    (:clear-candle-subscription! @captured)))
    (is (identical? custom-fetch-fn (:fetch-candle-snapshot! @captured)))))

(deftest unsubscribe-active-asset-adapter-supports-default-and-custom-clear-functions-test
  (let [store (atom {})
        calls (atom [])
        custom-clear-fn (fn [& _] nil)]
    (with-redefs [subscriptions-runtime/unsubscribe-active-asset!
                  (fn [deps]
                    (swap! calls conj deps)
                    :unsubscribe-result)]
      (is (= :unsubscribe-result
             (ws-adapters/unsubscribe-active-asset store "ETH")))
      (is (= :unsubscribe-result
             (ws-adapters/unsubscribe-active-asset
              store
              "BTC"
              {:clear-candle-subscription-fn custom-clear-fn}))))
    (let [[default-call custom-call] @calls]
      (is (= store (:store default-call)))
      (is (= "ETH" (:coin default-call)))
      (is (identical? active-ctx/unsubscribe-active-asset-ctx!
                      (:unsubscribe-active-asset-ctx! default-call)))
      (is (identical? candles/clear-owner-subscription!
                      (:clear-candle-subscription! default-call)))

      (is (= store (:store custom-call)))
      (is (= "BTC" (:coin custom-call)))
      (is (identical? custom-clear-fn
                      (:clear-candle-subscription! custom-call))))))

(deftest sync-active-candle-subscription-adapter-supports-default-and-custom-dependencies-test
  (let [store (atom {})
        calls (atom [])
        custom-sync-fn (fn [& _] nil)]
    (with-redefs [subscriptions-runtime/sync-active-candle-subscription!
                  (fn [deps]
                    (swap! calls conj deps)
                    :sync-result)]
      (is (= :sync-result
             (ws-adapters/sync-active-candle-subscription store)))
      (is (= :sync-result
             (ws-adapters/sync-active-candle-subscription
              store
              {:interval :5m
               :sync-candle-subscription-fn custom-sync-fn}))))
    (let [[default-call custom-call] @calls]
      (is (= store (:store default-call)))
      (is (nil? (:interval default-call)))
      (is (identical? candles/sync-candle-subscription!
                      (:sync-candle-subscription! default-call)))
      (is (identical? candles/clear-owner-subscription!
                      (:clear-candle-subscription! default-call)))

      (is (= store (:store custom-call)))
      (is (= :5m (:interval custom-call)))
      (is (identical? custom-sync-fn
                      (:sync-candle-subscription! custom-call))))))

(deftest websocket-subscription-wrapper-adapters-delegate-to-runtime-dependencies-test
  (let [store (atom {})
        calls (atom [])]
    (with-redefs [subscriptions-runtime/subscribe-orderbook!
                  (fn [deps]
                    (swap! calls conj [:subscribe-orderbook deps])
                    :subscribe-orderbook-result)
                  subscriptions-runtime/subscribe-trades!
                  (fn [deps]
                    (swap! calls conj [:subscribe-trades deps])
                    :subscribe-trades-result)
                  subscriptions-runtime/unsubscribe-orderbook!
                  (fn [deps]
                    (swap! calls conj [:unsubscribe-orderbook deps])
                    :unsubscribe-orderbook-result)
                  subscriptions-runtime/unsubscribe-trades!
                  (fn [deps]
                    (swap! calls conj [:unsubscribe-trades deps])
                    :unsubscribe-trades-result)
                  subscriptions-runtime/subscribe-webdata2!
                  (fn [deps]
                    (swap! calls conj [:subscribe-webdata2 deps])
                    :subscribe-webdata2-result)
                  subscriptions-runtime/unsubscribe-webdata2!
                  (fn [deps]
                    (swap! calls conj [:unsubscribe-webdata2 deps])
                    :unsubscribe-webdata2-result)]
      (is (= :subscribe-orderbook-result
             (ws-adapters/subscribe-orderbook store "ETH")))
      (is (= :subscribe-trades-result
             (ws-adapters/subscribe-trades "ETH")))
      (is (= :unsubscribe-orderbook-result
             (ws-adapters/unsubscribe-orderbook store "ETH")))
      (is (= :unsubscribe-trades-result
             (ws-adapters/unsubscribe-trades "ETH")))
      (is (= :subscribe-webdata2-result
             (ws-adapters/subscribe-webdata2 "0xuser")))
      (is (= :unsubscribe-webdata2-result
             (ws-adapters/unsubscribe-webdata2 "0xuser"))))
    (let [captured (into {} (map (juxt first second) @calls))]
      (is (= store (get-in captured [:subscribe-orderbook :store])))
      (is (= "ETH" (get-in captured [:subscribe-orderbook :coin])))
      (is (identical? price-agg/normalize-mode
                      (get-in captured [:subscribe-orderbook :normalize-mode-fn])))
      (is (identical? price-agg/mode->subscription-config
                      (get-in captured [:subscribe-orderbook :mode->subscription-config-fn])))
      (is (identical? orderbook/subscribe-orderbook!
                      (get-in captured [:subscribe-orderbook :subscribe-orderbook-fn])))

      (is (= "ETH" (get-in captured [:subscribe-trades :coin])))
      (is (identical? trades/subscribe-trades!
                      (get-in captured [:subscribe-trades :subscribe-trades-fn])))

      (is (= store (get-in captured [:unsubscribe-orderbook :store])))
      (is (= "ETH" (get-in captured [:unsubscribe-orderbook :coin])))
      (is (identical? orderbook/unsubscribe-orderbook!
                      (get-in captured [:unsubscribe-orderbook :unsubscribe-orderbook-fn])))

      (is (= "ETH" (get-in captured [:unsubscribe-trades :coin])))
      (is (identical? trades/unsubscribe-trades!
                      (get-in captured [:unsubscribe-trades :unsubscribe-trades-fn])))

      (is (= "0xuser" (get-in captured [:subscribe-webdata2 :address])))
      (is (identical? webdata2/subscribe-webdata2!
                      (get-in captured [:subscribe-webdata2 :subscribe-webdata2-fn])))

      (is (= "0xuser" (get-in captured [:unsubscribe-webdata2 :address])))
      (is (identical? webdata2/unsubscribe-webdata2!
                      (get-in captured [:unsubscribe-webdata2 :unsubscribe-webdata2-fn]))))))

(deftest refresh-websocket-health-and-factory-bind-runtime-and-force-flag-test
  (let [store (atom {})
        runtime (atom {})
        calls (atom [])]
    (with-redefs [ws-adapters/sync-websocket-health-with-runtime!
                  (fn [runtime* store* & {:keys [force?]}]
                    (swap! calls conj {:runtime runtime*
                                       :store store*
                                       :force? force?})
                    :refresh-result)]
      (is (= :refresh-result
             (ws-adapters/refresh-websocket-health nil store)))
      (is (= :refresh-result
             (ws-adapters/refresh-websocket-health runtime :ctx store)))
      (is (= :refresh-result
             ((ws-adapters/make-refresh-websocket-health runtime) :ctx store))))
    (is (= [{:runtime nil :store store :force? true}
            {:runtime runtime :store store :force? true}
            {:runtime runtime :store store :force? true}]
           @calls))))

(deftest ws-reset-subscriptions-uses-default-and-custom-diagnostics-options-test
  (let [store (atom {})
        calls (atom [])]
    (with-redefs [diagnostics-runtime/ws-reset-subscriptions!
                  (fn [deps]
                    (swap! calls conj deps)
                    :reset-result)]
      (is (= :reset-result
             (ws-adapters/ws-reset-subscriptions nil store {})))
      (is (= :reset-result
             (ws-adapters/ws-reset-subscriptions nil store {:group :user :source :auto}))))
    (let [[default-call custom-call] @calls]
      (is (= store (:store default-call)))
      (is (= :all (:group default-call)))
      (is (= :manual (:source default-call)))
      (is (identical? ws-client/get-health-snapshot
                      (:get-health-snapshot default-call)))
      (is (= (health-runtime/effective-now-ms 123)
             ((:effective-now-ms default-call) 123)))
      (is (= runtime-state/reset-subscriptions-cooldown-ms
             (:reset-subscriptions-cooldown-ms default-call)))
      (is (identical? ws-client/send-message! (:send-message! default-call)))
      (is (identical? ws-adapters/append-diagnostics-event!
                      (:append-diagnostics-event! default-call)))

      (is (= :user (:group custom-call)))
      (is (= :auto (:source custom-call))))))

(deftest diagnostics-effect-adapters-wire-platform-and-copy-status-seams-test
  (let [store (atom {})
        confirm-call (atom nil)
        copy-call (atom nil)]
    (with-redefs [diagnostics-effects/confirm-ws-diagnostics-reveal!
                  (fn [deps]
                    (reset! confirm-call deps)
                    :confirm-result)
                  diagnostics-effects/copy-websocket-diagnostics!
                  (fn [deps]
                    (reset! copy-call deps)
                    ((:set-copy-status! deps) store :copied)
                    :copy-result)]
      (is (= :confirm-result
             (ws-adapters/confirm-ws-diagnostics-reveal nil store)))
      (is (= :copy-result
             (ws-adapters/copy-websocket-diagnostics nil store))))
    (is (= store (:store @confirm-call)))
    (is (identical? platform/confirm! (:confirm-fn @confirm-call)))
    (is (= store (:store @copy-call)))
    (is (= runtime-state/app-version (:app-version @copy-call)))
    (is (identical? telemetry/log! (:log-fn @copy-call)))
    (is (= :copied (get-in @store [:websocket-ui :copy-status])))))

(deftest restore-active-asset-adapter-uses-default-runtime-dependencies-test
  (let [store (atom {})
        custom-loader (fn [& _] nil)
        call (atom nil)]
    (with-redefs [startup-restore/restore-active-asset!
                  (fn [store* deps]
                    (reset! call [store* deps])
                    :restore-result)]
      (is (= :restore-result
             (ws-adapters/restore-active-asset!
              {:store store
               :load-active-market-display-fn custom-loader}))))
    (let [[store* deps] @call]
      (is (= store store*))
      (is (identical? ws-client/connected? (:connected?-fn deps)))
      (is (identical? nxr/dispatch (:dispatch! deps)))
      (is (identical? custom-loader (:load-active-market-display-fn deps))))))
