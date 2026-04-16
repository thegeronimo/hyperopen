(ns hyperopen.startup.init-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.startup.init :as startup-init]))

(deftest init-runs-startup-sequence-in-deterministic-order-test
  (let [calls (atom [])
        scheduled-callback (atom nil)
        startup-runtime (atom {:old "state"})
        store (atom {:active-asset "BTC"})
        default-startup-state {:deferred-scheduled? false
                               :bootstrapped-address nil
                               :summary-logged? false}
        handle-wallet-connected (fn [] :connected)
        record-store-call (fn [label]
                            (fn [store-arg]
                              (swap! calls conj [label (= store store-arg)])))]
    (startup-init/init!
     {:log-fn (fn [message]
                (swap! calls conj [:log message]))
      :startup-runtime startup-runtime
      :default-startup-runtime-state (fn [] default-startup-state)
      :mark-performance! (fn [mark-name]
                           (swap! calls conj [:mark mark-name]))
      :schedule-startup-summary-log! (fn []
                                       (swap! calls conj :schedule-summary))
      :store store
      :restore-ui-font-preference! (fn []
                                     (swap! calls conj :restore-font))
      :restore-ui-locale-preference! (record-store-call :restore-ui-locale)
      :restore-chart-options! (record-store-call :restore-chart-options)
      :restore-orderbook-ui! (record-store-call :restore-orderbook-ui)
      :restore-vaults-snapshot-range! (record-store-call :restore-vaults-snapshot-range)
      :restore-agent-storage-mode! (record-store-call :restore-agent-storage-mode)
      :restore-agent-passkey-capability! (record-store-call :restore-agent-passkey-capability)
      :restore-trading-settings! (record-store-call :restore-trading-settings)
      :restore-spectate-mode-preferences! (record-store-call :restore-spectate-mode-preferences)
      :restore-spectate-mode-url! (record-store-call :restore-spectate-mode-url)
      :restore-trade-route-tab! (record-store-call :restore-trade-route-tab)
      :restore-route-query-state! (record-store-call :restore-route-query-state)
      :restore-active-asset! (record-store-call :restore-active-asset)
      :restore-asset-selector-markets-cache! (record-store-call :restore-selector-markets-cache)
      :restore-asset-selector-sort-settings! (record-store-call :restore-asset-selector-sort)
      :restore-portfolio-summary-time-range! (record-store-call :restore-portfolio-summary-time-range)
      :restore-leaderboard-preferences! (record-store-call :restore-leaderboard-preferences)
      :restore-open-orders-sort-settings! (record-store-call :restore-open-orders-sort)
      :restore-funding-history-pagination-settings! (record-store-call :restore-funding-history-pagination)
      :restore-trade-history-pagination-settings! (record-store-call :restore-trade-history-pagination)
      :restore-order-history-pagination-settings! (record-store-call :restore-order-history-pagination)
      :set-on-connected-handler! (fn [handler]
                                   (swap! calls conj [:set-on-connected-handler (= handle-wallet-connected handler)]))
      :handle-wallet-connected handle-wallet-connected
      :init-wallet! (record-store-call :init-wallet)
      :init-router! (record-store-call :init-router)
      :install-asset-selector-shortcuts! (fn []
                                           (swap! calls conj :install-asset-selector-shortcuts))
      :install-position-tpsl-clickaway! (fn []
                                          (swap! calls conj :install-position-tpsl-clickaway))
      :register-icon-service-worker! (fn []
                                       (swap! calls conj :register-icon-service-worker))
      :yield-to-main! (fn []
                        (swap! calls conj :yield-to-main)
                        (js/Promise.resolve :yielded))
      :initialize-remote-data-streams! (fn []
                                         (swap! calls conj :initialize-remote-data-streams))
      :schedule-post-render-startup! (fn [callback]
                                       (reset! scheduled-callback callback)
                                       (swap! calls conj :scheduled))
      :kick-render! (record-store-call :kick-render)})

    (is (= default-startup-state @startup-runtime))
    (is (= [[:log "Initializing Hyperopen..."]
            [:mark "app:init:start"]
            :schedule-summary
            :restore-font
            [:restore-ui-locale true]
            [:restore-chart-options true]
            [:restore-orderbook-ui true]
            [:restore-vaults-snapshot-range true]
            [:restore-agent-storage-mode true]
            [:restore-agent-passkey-capability true]
            [:restore-trading-settings true]
            [:restore-spectate-mode-preferences true]
            [:restore-spectate-mode-url true]
            [:restore-trade-route-tab true]
            [:restore-route-query-state true]
            [:restore-active-asset true]
            [:restore-selector-markets-cache true]
            [:set-on-connected-handler true]
            [:init-wallet true]
            [:init-router true]
            [:restore-vaults-snapshot-range true]
            [:restore-route-query-state true]
            [:kick-render true]
            :scheduled]
           @calls))
    (is (fn? @scheduled-callback))))

(deftest reset-startup-state-falls-back-to-runtime-when-startup-runtime-atom-is-absent-test
  (let [runtime (atom {:startup {:deferred-scheduled? true
                                 :bootstrapped-address "0xold"
                                 :summary-logged? true}})
        marks (atom [])
        summaries (atom 0)]
    (startup-init/reset-startup-state!
     {:runtime runtime
      :default-startup-runtime-state (fn []
                                       {:deferred-scheduled? false
                                        :bootstrapped-address nil
                                        :summary-logged? false})
      :mark-performance! (fn [mark]
                           (swap! marks conj mark))
      :schedule-startup-summary-log! (fn []
                                       (swap! summaries inc))})
    (is (= {:deferred-scheduled? false
            :bootstrapped-address nil
            :summary-logged? false}
           (get @runtime :startup)))
    (is (= ["app:init:start"] @marks))
    (is (= 1 @summaries))))

(deftest restore-deferred-ui-state-reapplies-route-query-after-portfolio-local-storage-test
  (let [calls (atom [])
        store (atom {})
        record-store-call (fn [label]
                            (fn [store-arg]
                              (swap! calls conj [label (= store store-arg)])))]
    (startup-init/restore-deferred-ui-state!
     {:store store
      :restore-asset-selector-sort-settings! (record-store-call :restore-asset-selector-sort)
      :restore-portfolio-summary-time-range! (record-store-call :restore-portfolio-summary-time-range)
      :restore-route-query-state! (record-store-call :restore-route-query-state)
      :restore-leaderboard-preferences! (record-store-call :restore-leaderboard-preferences)
      :restore-open-orders-sort-settings! (record-store-call :restore-open-orders-sort)
      :restore-funding-history-pagination-settings! (record-store-call :restore-funding-history-pagination)
      :restore-trade-history-pagination-settings! (record-store-call :restore-trade-history-pagination)
      :restore-order-history-pagination-settings! (record-store-call :restore-order-history-pagination)})
    (is (= [[:restore-asset-selector-sort true]
            [:restore-portfolio-summary-time-range true]
            [:restore-route-query-state true]
            [:restore-leaderboard-preferences true]
            [:restore-open-orders-sort true]
            [:restore-funding-history-pagination true]
            [:restore-trade-history-pagination true]
            [:restore-order-history-pagination true]]
           @calls))))

(deftest initialize-systems-runs-all-phases-without-scheduler-test
  (async done
    (let [calls (atom [])
          store (atom {:wallet {:address nil}})
          handle-wallet-connected (fn [] :connected)]
      (-> (startup-init/initialize-systems!
           {:store store
            :set-on-connected-handler! (fn [handler]
                                         (swap! calls conj [:set-handler (= handler handle-wallet-connected)]))
            :handle-wallet-connected handle-wallet-connected
            :init-wallet! (fn [store-arg]
                            (swap! calls conj [:init-wallet (= store store-arg)]))
            :init-router! (fn [store-arg]
                            (swap! calls conj [:init-router (= store store-arg)]))
            :install-asset-selector-shortcuts! (fn []
                                                 (swap! calls conj :install-asset-selector-shortcuts))
            :install-position-tpsl-clickaway! (fn []
                                                (swap! calls conj :install-position-tpsl-clickaway))
            :mark-post-render-trade-secondary-panels-ready! (fn [store-arg]
                                                              (swap! calls conj [:mark-secondary-panels (= store store-arg)]))
            :load-post-render-route-effects! (fn [store-arg]
                                               (swap! calls conj [:load-post-render-route-effects (= store store-arg)]))
            :yield-to-main! (fn []
                              (swap! calls conj :yield-to-main)
                              (js/Promise.resolve :yielded))
            :restore-deferred-ui-state! (fn []
                                          (swap! calls conj :restore-deferred-ui-state))
            :register-icon-service-worker! (fn []
                                             (swap! calls conj :register-service-worker))
            :initialize-remote-data-streams! (fn []
                                               (swap! calls conj :initialize-streams))
            :kick-render! (fn [store-arg]
                            (swap! calls conj [:kick-render (= store store-arg)]))})
          (.then (fn [_]
                   (is (= [[:set-handler true]
                           [:init-wallet true]
                           [:init-router true]
                           [:kick-render true]
                           :install-asset-selector-shortcuts
                           :install-position-tpsl-clickaway
                           [:mark-secondary-panels true]
                           [:load-post-render-route-effects true]
                           :initialize-streams
                           :yield-to-main
                           :restore-deferred-ui-state
                           :register-service-worker]
                          @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected initialize-systems! error: " err))
                    (done)))))))

(deftest initialize-systems-defers-post-render-background-work-until-yield-test
  (async done
    (let [calls (atom [])
          scheduled-callback (atom nil)
          continue-background! (atom nil)
          store (atom {:wallet {:address nil}})
          handle-wallet-connected (fn [] :connected)]
      (startup-init/initialize-systems!
       {:store store
        :set-on-connected-handler! (fn [_handler]
                                     (swap! calls conj :set-handler))
        :handle-wallet-connected handle-wallet-connected
        :init-wallet! (fn [_store-arg]
                        (swap! calls conj :init-wallet))
        :init-router! (fn [_store-arg]
                        (swap! calls conj :init-router))
        :install-asset-selector-shortcuts! (fn []
                                             (swap! calls conj :install-asset-selector-shortcuts))
        :install-position-tpsl-clickaway! (fn []
                                            (swap! calls conj :install-position-tpsl-clickaway))
        :mark-post-render-trade-secondary-panels-ready! (fn [_store-arg]
                                                          (swap! calls conj :mark-secondary-panels))
        :load-post-render-route-effects! (fn [_store-arg]
                                           (swap! calls conj :load-post-render-route-effects))
        :yield-to-main! (fn []
                          (swap! calls conj :yield-to-main)
                          (js/Promise.
                           (fn [resolve _reject]
                             (reset! continue-background! resolve))))
        :restore-deferred-ui-state! (fn []
                                      (swap! calls conj :restore-deferred-ui-state))
        :register-icon-service-worker! (fn []
                                         (swap! calls conj :register-service-worker))
        :initialize-remote-data-streams! (fn []
                                           (swap! calls conj :initialize-streams))
        :schedule-post-render-startup! (fn [callback]
                                         (reset! scheduled-callback callback)
                                         (swap! calls conj :scheduled))
        :kick-render! (fn [_store-arg]
                        (swap! calls conj :kick-render))})
      (is (= [:set-handler
              :init-wallet
              :init-router
              :kick-render
              :scheduled]
             @calls))
      (is (fn? @scheduled-callback))
      (let [startup-promise (@scheduled-callback)
            flush-microtasks! (fn []
                                (-> (js/Promise.resolve nil)
                                    (.then (fn [_]
                                             (js/Promise.resolve nil)))))]
        (-> (flush-microtasks!)
            (.then (fn [_]
                     (is (= [:set-handler
                             :init-wallet
                             :init-router
                             :kick-render
                             :scheduled
                             :install-asset-selector-shortcuts
                             :install-position-tpsl-clickaway
                             :mark-secondary-panels
                             :load-post-render-route-effects
                             :initialize-streams
                             :yield-to-main]
                            @calls))
                     (is (fn? @continue-background!))
                     (@continue-background!)
                     startup-promise))
            (.then (fn [_]
                     (is (= [:set-handler
                             :init-wallet
                             :init-router
                             :kick-render
                             :scheduled
                             :install-asset-selector-shortcuts
                             :install-position-tpsl-clickaway
                             :mark-secondary-panels
                             :load-post-render-route-effects
                             :initialize-streams
                             :yield-to-main
                             :restore-deferred-ui-state
                             :register-service-worker]
                            @calls))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected scheduled startup error: " err))
                      (done))))))))
