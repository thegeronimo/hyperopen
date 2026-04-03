(ns hyperopen.app.startup-test
  (:require [cljs.test :refer-macros [deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.app.startup :as app-startup]
            [hyperopen.route-modules :as route-modules]
            [hyperopen.router :as router]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.trading-indicators-modules :as trading-indicators-modules]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.startup.collaborators :as startup-collaborators]
            [hyperopen.startup.init :as startup-init]
            [hyperopen.startup.runtime :as startup-runtime]
            [hyperopen.wallet.core :as wallet]))

(deftest init-builds-startup-sequence-from-permanent-boundaries-test
  (let [store (atom {:active-asset "BTC"})
        runtime (atom {:startup {}})
        captured-init-deps (atom nil)
        summary-calls (atom [])]
    (with-redefs [startup-collaborators/startup-base-deps
                  (fn [deps]
                    (merge
                     {:store (:store deps)
                      :runtime (:runtime deps)
                      :dispatch! (fn [& _] nil)
                      :log-fn (fn [& _] nil)}
                     deps))
                  startup-init/init!
                  (fn [deps]
                    (reset! captured-init-deps deps))
                  startup-runtime/schedule-startup-summary-log!
                  (fn [deps]
                    (swap! summary-calls conj deps))]
      (app-startup/init! {:runtime runtime
                          :store store})
      (is (map? @captured-init-deps))
      (is (identical? startup-runtime/default-startup-runtime-state
                      (:default-startup-runtime-state @captured-init-deps)))
      ((:schedule-startup-summary-log! @captured-init-deps))
      (is (= [runtime-state/startup-summary-delay-ms]
             (map :delay-ms @summary-calls))))))

(deftest init-passes-trading-settings-restore-hook-into-startup-init-test
  (let [store (atom {})
        runtime (atom {})
        captured-init-deps (atom nil)]
    (with-redefs [startup-collaborators/startup-base-deps
                  (fn [deps]
                    (merge
                     {:store (:store deps)
                      :runtime (:runtime deps)
                      :dispatch! (fn [& _] nil)
                      :log-fn (fn [& _] nil)}
                     deps))
                  startup-init/init!
                  (fn [deps]
                    (reset! captured-init-deps deps))]
      (app-startup/init! {:runtime runtime
                          :store store})
      (is (fn? (:restore-trading-settings! @captured-init-deps))))))

(deftest initialize-remote-data-streams-routes-through-runtime-owned-callbacks-test
  (let [store (atom {})
        runtime (atom {})
        callback-calls (atom [])]
    (with-redefs [startup-collaborators/startup-base-deps
                  (fn [deps]
                    (merge
                     {:store (:store deps)
                      :runtime (:runtime deps)}
                     deps))
                  startup-runtime/initialize-remote-data-streams!
                  (fn [deps]
                    ((:install-address-handlers! deps))
                    ((:start-critical-bootstrap! deps))
                    ((:schedule-deferred-bootstrap! deps))
                    :ok)
                  startup-runtime/install-address-handlers!
                  (fn [_deps]
                    (swap! callback-calls conj :install-address-handlers))
                  startup-runtime/start-critical-bootstrap!
                  (fn [_deps]
                    (swap! callback-calls conj :start-critical-bootstrap))
                  startup-runtime/schedule-deferred-bootstrap!
                  (fn [deps]
                    (swap! callback-calls conj :schedule-deferred-bootstrap)
                    ((:run-deferred-bootstrap! deps)))
                  startup-runtime/run-deferred-bootstrap!
                  (fn [_deps]
                    (swap! callback-calls conj :run-deferred-bootstrap))]
      (is (= :ok
             (app-startup/initialize-remote-data-streams!
              {:runtime runtime
               :store store})))
      (is (= [:install-address-handlers
              :start-critical-bootstrap
              :schedule-deferred-bootstrap
              :run-deferred-bootstrap]
             @callback-calls)))))

(deftest reload-runtime-bindings-reinstalls-reload-safe-handlers-test
  (let [store (atom {})
        runtime (atom {})
        calls (atom [])
        router-opts (atom nil)]
    (with-redefs [startup-collaborators/startup-base-deps
                  (fn [deps]
                    (merge
                     {:store (:store deps)
                      :runtime (:runtime deps)
                      :dispatch! (fn [& _] nil)
                      :init-active-ctx! (fn [runtime-store]
                                          (swap! calls conj [:active-ctx (identical? store runtime-store)]))
                      :init-candles! (fn [runtime-store]
                                       (swap! calls conj [:candles (identical? store runtime-store)]))
                      :init-orderbook! (fn [runtime-store]
                                         (swap! calls conj [:orderbook (identical? store runtime-store)]))
                      :init-trades! (fn [runtime-store]
                                      (swap! calls conj [:trades (identical? store runtime-store)]))
                      :init-user-ws! (fn [runtime-store]
                                       (swap! calls conj [:user-ws (identical? store runtime-store)]))
                      :init-webdata2! (fn [runtime-store]
                                        (swap! calls conj [:webdata2 (identical? store runtime-store)]))}
                     deps))
                  app-startup/init-router!
                  (fn [runtime-store opts]
                    (swap! calls conj [:router (identical? store runtime-store)])
                    (reset! router-opts opts))
                  wallet/attach-listeners!
                  (fn [runtime-store]
                    (swap! calls conj [:wallet-listeners (identical? store runtime-store)]))
                  startup-runtime/install-asset-selector-shortcuts!
                  (fn [{runtime-store :store
                        dispatch! :dispatch!}]
                    (swap! calls conj [:shortcuts (identical? store runtime-store) (fn? dispatch!)]))
                  startup-runtime/install-position-tpsl-clickaway!
                  (fn [{runtime-store :store
                        dispatch! :dispatch!}]
                    (swap! calls conj [:clickaway (identical? store runtime-store) (fn? dispatch!)]))
                  app-startup/reload-address-handlers!
                  (fn [system]
                    (swap! calls conj [:address-handlers
                                       (= runtime (:runtime system))
                                       (= store (:store system))]))]
      (app-startup/reload-runtime-bindings! {:runtime runtime
                                             :store store})
      (is (= [[:router true]
              [:wallet-listeners true]
              [:shortcuts true true]
              [:clickaway true true]
              [:active-ctx true]
              [:candles true]
              [:orderbook true]
              [:trades true]
              [:user-ws true]
              [:webdata2 true]
              [:address-handlers true true]]
             @calls))
      (is (true? (:skip-route-set? @router-opts)))
      (is (false? (:defer-initial-trade-module-loads? @router-opts))))))

(deftest reload-address-handlers-skips-current-address-bootstrap-until-a-new-event-arrives-test
  (let [store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}})
        runtime (atom {})
        stop-calls (atom [])
        remove-calls (atom [])
        init-calls (atom [])
        added-handlers (atom [])
        sync-calls (atom 0)
        bootstrap-calls (atom [])]
    (with-redefs [startup-collaborators/startup-base-deps
                  (fn [deps]
                    (merge
                     {:store (:store deps)
                      :runtime (:runtime deps)
                      :init-with-webdata2! (fn [store-arg subscribe-fn unsubscribe-fn]
                                             (swap! init-calls conj [store-arg subscribe-fn unsubscribe-fn]))
                      :add-handler! (fn [handler]
                                      (swap! added-handlers conj handler))
                      :remove-handler! (fn [handler-name]
                                         (swap! remove-calls conj handler-name))
                      :stop-watching! (fn [store-arg]
                                        (swap! stop-calls conj store-arg))
                      :sync-current-address! (fn [_store-arg]
                                               (swap! sync-calls inc))
                      :create-user-handler (fn [_subscribe-fn _unsubscribe-fn]
                                             {:kind :user-handler})
                      :subscribe-user! (fn [& _] nil)
                      :unsubscribe-user! (fn [& _] nil)
                      :subscribe-webdata2! (fn [& _] nil)
                      :unsubscribe-webdata2! (fn [& _] nil)
                      :address-handler-reify (fn [on-change handler-name]
                                               {:kind :address-handler
                                                :name handler-name
                                                :on-change on-change})}
                     deps))
                  app-startup/bootstrap-account-data!
                  (fn [system address]
                    (swap! bootstrap-calls conj [system address]))]
      (app-startup/reload-address-handlers! {:runtime runtime
                                             :store store})
      (is (= [store] @stop-calls))
      (is (= ["webdata2-subscription-handler"
              "user-ws-subscription-handler"
              "startup-account-bootstrap-handler"]
             @remove-calls))
      (is (= 1 (count @init-calls)))
      (is (= 2 (count @added-handlers)))
      (is (zero? @sync-calls))
      (is (empty? @bootstrap-calls))
      (let [address-handler (last @added-handlers)]
        ((:on-change address-handler) "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        (is (= [[{:runtime runtime
                  :store store}
                 "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]]
               @bootstrap-calls))))))

(deftest bootstrap-account-data-forwards-stage-b-through-runtime-boundary-test
  (let [store (atom {})
        runtime (atom {})
        stage-b-calls (atom [])]
    (with-redefs [startup-collaborators/startup-base-deps
                  (fn [deps]
                    (merge
                     {:store (:store deps)
                      :runtime (:runtime deps)
                      :stage-b-account-bootstrap! (fn [address dexs]
                                                    (swap! stage-b-calls conj [address dexs]))}
                     deps))
                  startup-runtime/bootstrap-account-data!
                  (fn [deps]
                    ((:stage-b-account-bootstrap! deps) "0xabc" ["dex-a"])
                    :ok)]
      (is (= :ok
             (app-startup/bootstrap-account-data!
              {:runtime runtime
               :store store}
              "0xabc")))
      (is (= [["0xabc" ["dex-a"]]]
             @stage-b-calls)))))

(deftest init-wraps-router-with-route-module-loading-dispatch-test
  (let [store (atom {:router {:path "/trade"}})
        runtime (atom {})
        captured-init-deps (atom nil)
        captured-router-opts (atom nil)
        dispatch-calls (atom [])]
    (with-redefs [startup-collaborators/startup-base-deps
                  (fn [deps]
                    (merge
                     {:store (:store deps)
                      :runtime (:runtime deps)
                      :dispatch! (fn [& _] nil)
                      :log-fn (fn [& _] nil)}
                     deps))
                  startup-init/init!
                  (fn [deps]
                    (reset! captured-init-deps deps))
                  route-modules/route-module-id
                  (fn [path]
                    (when (= path "/portfolio")
                      :portfolio))
                  router/init!
                  (fn
                    ([_store]
                     nil)
                    ([_store opts]
                     (reset! captured-router-opts opts)))
                  nxr/dispatch
                  (fn [store-arg _ctx effects]
                    (swap! dispatch-calls conj [store-arg effects]))]
      (app-startup/init! {:runtime runtime
                          :store store})
      ((:init-router! @captured-init-deps) store)
      ((:on-route-change @captured-router-opts) "/portfolio"))
    (is (= [[store [[:effects/load-route-module "/portfolio"]]]]
           @dispatch-calls))))

(deftest init-defers-initial-trade-chart-module-loading-until-post-render-startup-test
  (let [store (atom {:router {:path "/portfolio"}})
        runtime (atom {})
        captured-init-deps (atom nil)
        captured-router-opts (atom nil)
        dispatch-calls (atom [])]
    (with-redefs [startup-collaborators/startup-base-deps
                  (fn [deps]
                    (merge
                     {:store (:store deps)
                      :runtime (:runtime deps)
                      :dispatch! (fn [& _] nil)
                      :log-fn (fn [& _] nil)}
                     deps))
                  startup-init/init!
                  (fn [deps]
                    (reset! captured-init-deps deps))
                  route-modules/route-module-id
                  (constantly nil)
                  trade-modules/trade-chart-ready?
                  (constantly false)
                  trade-modules/trade-chart-loading?
                  (constantly false)
                  router/init!
                  (fn
                    ([_store]
                     nil)
                    ([_store opts]
                     (reset! captured-router-opts opts)))
                  nxr/dispatch
                  (fn [store-arg _ctx effects]
                    (swap! dispatch-calls conj [store-arg effects]))]
      (app-startup/init! {:runtime runtime
                          :store store})
      ((:init-router! @captured-init-deps) store)
      (swap! store assoc-in [:router :path] "/trade")
      ((:on-route-change @captured-router-opts) "/trade")
      (is (= []
             @dispatch-calls))
      (is (nil? (get-in @store [:trade-ui :desktop-secondary-panels-ready?])))
      ((:mark-post-render-trade-secondary-panels-ready! @captured-init-deps) store)
      (is (true? (get-in @store [:trade-ui :desktop-secondary-panels-ready?])))
      ((:load-post-render-route-effects! @captured-init-deps) store))
    (is (= [[store [[:effects/load-trade-chart-module]]]]
           @dispatch-calls))))

(deftest init-loads-trading-indicators-post-render-when-active-indicators-are-restored-test
  (let [store (atom {:router {:path "/trade"}
                     :chart-options {:active-indicators {:sma {:period 20}}}})
        runtime (atom {})
        captured-init-deps (atom nil)
        dispatch-calls (atom [])]
    (with-redefs [startup-collaborators/startup-base-deps
                  (fn [deps]
                    (merge
                     {:store (:store deps)
                      :runtime (:runtime deps)
                      :dispatch! (fn [& _] nil)
                      :log-fn (fn [& _] nil)}
                     deps))
                  startup-init/init!
                  (fn [deps]
                    (reset! captured-init-deps deps))
                  trade-modules/trade-chart-ready?
                  (constantly false)
                  trade-modules/trade-chart-loading?
                  (constantly false)
                  trading-indicators-modules/trading-indicators-ready?
                  (constantly false)
                  trading-indicators-modules/trading-indicators-loading?
                  (constantly false)
                  nxr/dispatch
                  (fn [store-arg _ctx effects]
                    (swap! dispatch-calls conj [store-arg effects]))]
      (app-startup/init! {:runtime runtime
                          :store store})
      ((:load-post-render-route-effects! @captured-init-deps) store))
    (is (= [[store [[:effects/load-trade-chart-module]
                    [:effects/load-trading-indicators-module]]]]
           @dispatch-calls))))
