(ns hyperopen.runtime.effect-adapters.websocket-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.orderbook.price-aggregation :as price-agg]
            [hyperopen.runtime.app-effects :as app-effects]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.websocket :as ws-adapters]
            [hyperopen.startup.restore :as startup-restore]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.candles :as candles]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.subscriptions-runtime :as subscriptions-runtime]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.websocket.infrastructure.transport :as ws-infra]
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

(deftest fetch-candle-snapshot-adapter-builds-live-detail-route-activity-guard-test
  (let [store (atom {:router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}})
        captured (atom nil)
        upstream-active? (atom true)]
    (with-redefs [app-effects/fetch-candle-snapshot!
                  (fn [deps]
                    (reset! captured deps)
                    :fetch-result)]
      (is (= :fetch-result
             (ws-adapters/fetch-candle-snapshot
              nil
              store
              :coin "SPY"
              :detail-route-vault-address "0x1234567890abcdef1234567890abcdef12345678"
              :active?-fn (fn [] @upstream-active?)))))
    (is (fn? (:active?-fn @captured)))
    (is (true? ((:active?-fn @captured))))
    (swap! store assoc-in [:router :path] "/trade")
    (is (false? ((:active?-fn @captured))))
    (reset! upstream-active? false)
    (swap! store assoc-in [:router :path] "/vaults/0x1234567890abcdef1234567890abcdef12345678")
    (is (false? ((:active?-fn @captured))))))

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

(deftest sync-active-outcome-market-side-streams-subscribes-both-sides-test
  (let [market {:key "outcome:1"
                :coin "outcome:1"
                :market-type :outcome
                :outcome-sides [{:side-index 0 :coin "#10"}
                                {:side-index 1 :coin "#11"}]}
        store (atom {:active-asset "#10"
                     :active-market market})
        orderbook-calls (atom [])
        trade-calls (atom [])
        active-ctx-calls (atom [])]
    (is (= ["#10" "#11"]
           (ws-adapters/sync-active-outcome-market-side-streams!
            store
            {:subscribe-orderbook-fn (fn [_store coin]
                                       (swap! orderbook-calls conj coin))
             :subscribe-trades-fn (fn [coin]
                                    (swap! trade-calls conj coin))
             :subscribe-active-asset-ctx-fn (fn [coin]
                                              (swap! active-ctx-calls conj coin))})))
    (is (= ["#10" "#11"] @orderbook-calls))
    (is (= ["#10" "#11"] @trade-calls))
    (is (= ["#10" "#11"] @active-ctx-calls))))

(deftest fetch-asset-selector-markets-effect-resyncs-active-outcome-side-streams-test
  (async done
    (let [market {:key "outcome:1"
                  :coin "outcome:1"
                  :market-type :outcome
                  :outcome-sides [{:side-index 0 :coin "#10"}
                                  {:side-index 1 :coin "#11"}]}
          store (atom {:active-asset "#10"
                       :asset-selector {}})
          original-orderbook-state @orderbook/orderbook-state
          original-trades-state @trades/trades-state
          original-active-ctx-state @active-ctx/active-asset-ctx-state
          original-ws-state @ws-client/runtime-state
          restore-state! (fn []
                           (reset! orderbook/orderbook-state original-orderbook-state)
                           (reset! trades/trades-state original-trades-state)
                           (reset! active-ctx/active-asset-ctx-state original-active-ctx-state)
                           (reset! ws-client/runtime-state original-ws-state))]
      (reset! orderbook/orderbook-state {:subscriptions {}
                                          :books {}})
      (reset! trades/trades-state {:subscriptions #{}
                                    :trades []
                                    :trades-by-coin {}})
      (reset! active-ctx/active-asset-ctx-state {:subscriptions #{}
                                                 :owners-by-coin {}
                                                 :coins-by-owner {}
                                                 :contexts {}})
      (swap! ws-client/runtime-state
             assoc
             :clock (ws-infra/make-function-clock (fn [] 1700000000000)
                                                  (fn [] 0.5)))
      (with-redefs [api/request-asset-selector-markets!
                    (fn [_store opts]
                      (js/Promise.resolve
                       {:phase (:phase opts)
                        :market-state {:markets [market]
                                       :market-by-key {"outcome:1" market}
                                       :active-market market
                                       :loaded-at-ms 123}}))]
        (-> (effect-adapters/fetch-asset-selector-markets-effect nil store {:phase :bootstrap})
            (.then
             (fn [_]
               (is (= #{"#10" "#11"}
                      (set (keys (orderbook/get-subscriptions)))))
               (is (= #{"#10" "#11"}
                      (:subscriptions @trades/trades-state)))
               (is (= #{"#10" "#11"}
                      (active-ctx/get-subscribed-coins-by-owner :active-asset)))
               (restore-state!)
               (done)))
            (.catch
             (fn [err]
               (restore-state!)
               (is false (str "Unexpected selector fetch error: " err))
               (done))))))))

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
