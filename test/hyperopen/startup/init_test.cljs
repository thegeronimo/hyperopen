(ns hyperopen.startup.init-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.startup.init :as startup-init]))

(deftest init-runs-startup-sequence-in-deterministic-order-test
  (let [calls (atom [])
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
      :restore-asset-selector-sort-settings! (record-store-call :restore-asset-selector-sort)
      :restore-chart-options! (record-store-call :restore-chart-options)
      :restore-orderbook-ui! (record-store-call :restore-orderbook-ui)
      :restore-agent-storage-mode! (record-store-call :restore-agent-storage-mode)
      :restore-active-asset! (record-store-call :restore-active-asset)
      :restore-asset-selector-markets-cache! (record-store-call :restore-selector-markets-cache)
      :restore-open-orders-sort-settings! (record-store-call :restore-open-orders-sort)
      :restore-funding-history-pagination-settings! (record-store-call :restore-funding-history-pagination)
      :restore-trade-history-pagination-settings! (record-store-call :restore-trade-history-pagination)
      :restore-order-history-pagination-settings! (record-store-call :restore-order-history-pagination)
      :set-on-connected-handler! (fn [handler]
                                   (swap! calls conj [:set-on-connected-handler (= handle-wallet-connected handler)]))
      :handle-wallet-connected handle-wallet-connected
      :init-wallet! (record-store-call :init-wallet)
      :init-router! (record-store-call :init-router)
      :register-icon-service-worker! (fn []
                                       (swap! calls conj :register-icon-service-worker))
      :initialize-remote-data-streams! (fn []
                                         (swap! calls conj :initialize-remote-data-streams))
      :kick-render! (record-store-call :kick-render)})

    (is (= default-startup-state @startup-runtime))
    (is (= [[:log "Initializing Hyperopen..."]
            [:mark "app:init:start"]
            :schedule-summary
            :restore-font
            [:restore-asset-selector-sort true]
            [:restore-chart-options true]
            [:restore-orderbook-ui true]
            [:restore-agent-storage-mode true]
            [:restore-active-asset true]
            [:restore-selector-markets-cache true]
            [:restore-open-orders-sort true]
            [:restore-funding-history-pagination true]
            [:restore-trade-history-pagination true]
            [:restore-order-history-pagination true]
            [:set-on-connected-handler true]
            [:init-wallet true]
            [:init-router true]
            :register-icon-service-worker
            :initialize-remote-data-streams
            [:kick-render true]]
           @calls))))
