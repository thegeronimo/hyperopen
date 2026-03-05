(ns hyperopen.websocket.migration-flags-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.config :as app-config]
            [hyperopen.websocket.migration-flags :as migration-flags]))

(deftest configured-default-flags-use-config-and-fallback-shape-test
  (with-redefs [app-config/config {:ws-migration {:order-fill-ws-first? false
                                                  :startup-bootstrap-ws-first? true
                                                  :candle-subscriptions? true
                                                  :auto-fallback-on-health-degrade? false}}]
    (is (= {:order-fill-ws-first? false
            :startup-bootstrap-ws-first? true
            :candle-subscriptions? true
            :auto-fallback-on-health-degrade? false}
           (migration-flags/configured-default-flags)))))

(deftest effective-flags-accept-state-overrides-test
  (with-redefs [app-config/config {:ws-migration {:order-fill-ws-first? true
                                                  :startup-bootstrap-ws-first? true
                                                  :candle-subscriptions? false
                                                  :auto-fallback-on-health-degrade? true}}]
    (is (= {:order-fill-ws-first? false
            :startup-bootstrap-ws-first? true
            :candle-subscriptions? true
            :auto-fallback-on-health-degrade? true}
           (migration-flags/effective-flags
            {:websocket {:migration-flags {:order-fill-ws-first? false
                                           :candle-subscriptions? true}}})))))

(deftest order-fill-ws-first-enabled-respects-disable-and-health-guardrails-test
  (let [healthy-state {:websocket {:health {:transport {:state :connected
                                                        :freshness :live}
                                            :groups {:orders_oms {:worst-status :live
                                                                  :gap-detected? false}
                                                     :account {:worst-status :live
                                                               :gap-detected? false}}}}}
        degraded-state {:websocket {:health {:transport {:state :connected
                                                         :freshness :live}
                                             :groups {:orders_oms {:worst-status :degraded
                                                                   :gap-detected? true}
                                                      :account {:worst-status :live
                                                                :gap-detected? false}}}}}]
    (is (true? (migration-flags/order-fill-ws-first-enabled? healthy-state)))
    (is (false? (migration-flags/order-fill-ws-first-enabled?
                 (assoc-in healthy-state [:websocket :migration-flags :order-fill-ws-first?] false))))
    (is (false? (migration-flags/order-fill-ws-first-enabled? degraded-state)))
    (is (true? (migration-flags/order-fill-ws-first-enabled?
                (assoc-in degraded-state
                          [:websocket :migration-flags :auto-fallback-on-health-degrade?]
                          false))))))

(deftest should-fetch-candle-snapshot-honors-candle-migration-and-backfill-test
  (let [coin "BTC"
        interval :1h
        base-state {:candles {}
                    :websocket {:health {:transport {:state :connected
                                                     :freshness :live}
                                         :groups {:market_data {:worst-status :live
                                                                :gap-detected? false}}}}}
        candle-migration-enabled (assoc-in base-state
                                           [:websocket :migration-flags :candle-subscriptions?]
                                           true)
        with-cache (assoc-in candle-migration-enabled
                             [:candles coin interval]
                             [{:t 1 :o 1 :h 1 :l 1 :c 1 :v 1}])
        market-degraded (assoc-in candle-migration-enabled
                                  [:websocket :health :groups :market_data]
                                  {:worst-status :degraded
                                   :gap-detected? true})]
    ;; Default candle migration disabled -> keep REST fetches.
    (is (true? (migration-flags/should-fetch-candle-snapshot? base-state coin interval)))
    ;; Candle migration enabled with cached rows -> no REST fetch.
    (is (false? (migration-flags/should-fetch-candle-snapshot? with-cache coin interval)))
    ;; Candle migration enabled but cache missing -> one-shot backfill allowed.
    (is (true? (migration-flags/should-fetch-candle-snapshot? candle-migration-enabled coin interval)))
    ;; Health guardrail should force REST fallback even when candle migration is enabled.
    (is (true? (migration-flags/should-fetch-candle-snapshot? market-degraded coin interval)))))
