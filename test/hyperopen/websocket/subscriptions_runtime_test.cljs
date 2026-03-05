(ns hyperopen.websocket.subscriptions-runtime-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.websocket.subscriptions-runtime :as subscriptions-runtime]))

(deftest subscribe-active-asset-persists-and-projects-canonical-market-test
  (let [persisted-active-assets (atom [])
        persisted-markets (atom [])
        subscribed-coins (atom [])
        synced-candles (atom [])
        fetched-timeframes (atom [])
        market {:key "perp:ETH"
                :coin "ETH"
                :symbol "ETH-USDC"
                :base "ETH"
                :market-type :perp}
        store (atom {:asset-selector {:market-by-key {"perp:ETH" market}}
                     :websocket {:migration-flags {:candle-subscriptions? true}}
                     :chart-options {:selected-timeframe :5m}
                     :active-assets {:contexts {}
                                     :loading false}
                     :active-asset nil
                     :selected-asset nil
                     :active-market nil})]
    (subscriptions-runtime/subscribe-active-asset!
     {:store store
      :coin "ETH"
      :log-fn (fn [& _] nil)
      :resolve-market-by-coin-fn (fn [market-by-key coin]
                                   (get market-by-key (str "perp:" coin)))
      :persist-active-asset! (fn [coin]
                               (swap! persisted-active-assets conj coin))
      :persist-active-market-display! (fn [resolved-market]
                                        (swap! persisted-markets conj resolved-market))
      :subscribe-active-asset-ctx! (fn [coin]
                                     (swap! subscribed-coins conj coin))
      :sync-candle-subscription! (fn [coin interval owner]
                                   (swap! synced-candles conj [coin interval owner]))
      :clear-candle-subscription! (fn [_owner]
                                    (swap! synced-candles conj [:cleared]))
      :fetch-candle-snapshot! (fn [selected-timeframe]
                                (swap! fetched-timeframes conj selected-timeframe))})
    (is (= ["ETH"] @persisted-active-assets))
    (is (= [market] @persisted-markets))
    (is (= ["ETH"] @subscribed-coins))
    (is (= [["ETH" :5m :active-chart]] @synced-candles))
    (is (= [:5m] @fetched-timeframes))
    (is (= true (get-in @store [:active-assets :loading])))
    (is (= "ETH" (:active-asset @store)))
    (is (= "ETH" (:selected-asset @store)))
    (is (= "ETH" (get-in @store [:active-market :coin])))))

(deftest subscribe-active-asset-skips-rest-candle-fetch-when-candle-migration-enabled-and-cache-present-test
  (let [fetched-timeframes (atom [])
        synced-candles (atom [])
        store (atom {:asset-selector {:market-by-key {"perp:ETH" {:key "perp:ETH"
                                                                   :coin "ETH"}}}
                     :websocket {:migration-flags {:candle-subscriptions? true}}
                     :candles {"ETH" {:5m [{:t 1 :o 1 :h 1 :l 1 :c 1 :v 1}]}}
                     :chart-options {:selected-timeframe :5m}
                     :active-assets {:contexts {}
                                     :loading false}})]
    (subscriptions-runtime/subscribe-active-asset!
     {:store store
      :coin "ETH"
      :log-fn (fn [& _] nil)
      :resolve-market-by-coin-fn (fn [market-by-key coin]
                                   (get market-by-key (str "perp:" coin)))
      :persist-active-asset! (fn [_] nil)
      :persist-active-market-display! (fn [_] nil)
      :subscribe-active-asset-ctx! (fn [_] nil)
      :sync-candle-subscription! (fn [coin interval owner]
                                   (swap! synced-candles conj [coin interval owner]))
      :clear-candle-subscription! (fn [_owner]
                                    (swap! synced-candles conj [:cleared]))
      :fetch-candle-snapshot! (fn [selected-timeframe]
                                (swap! fetched-timeframes conj selected-timeframe))})
    (is (= [["ETH" :5m :active-chart]] @synced-candles))
    (is (= [] @fetched-timeframes))))

(deftest subscribe-active-asset-keeps-rest-candle-backfill-when-candle-cache-missing-test
  (let [fetched-timeframes (atom [])
        synced-candles (atom [])
        store (atom {:asset-selector {:market-by-key {"perp:ETH" {:key "perp:ETH"
                                                                   :coin "ETH"}}}
                     :websocket {:migration-flags {:candle-subscriptions? true}}
                     :candles {"ETH" {:1d [{:t 1 :o 1 :h 1 :l 1 :c 1 :v 1}]}}
                     :chart-options {:selected-timeframe :5m}
                     :active-assets {:contexts {}
                                     :loading false}})]
    (subscriptions-runtime/subscribe-active-asset!
     {:store store
      :coin "ETH"
      :log-fn (fn [& _] nil)
      :resolve-market-by-coin-fn (fn [market-by-key coin]
                                   (get market-by-key (str "perp:" coin)))
      :persist-active-asset! (fn [_] nil)
      :persist-active-market-display! (fn [_] nil)
      :subscribe-active-asset-ctx! (fn [_] nil)
      :sync-candle-subscription! (fn [coin interval owner]
                                   (swap! synced-candles conj [coin interval owner]))
      :clear-candle-subscription! (fn [_owner]
                                    (swap! synced-candles conj [:cleared]))
      :fetch-candle-snapshot! (fn [selected-timeframe]
                                (swap! fetched-timeframes conj selected-timeframe))})
    (is (= [["ETH" :5m :active-chart]] @synced-candles))
    (is (= [:5m] @fetched-timeframes))))

(deftest sync-active-candle-subscription-uses-active-asset-and-selected-timeframe-test
  (let [calls (atom [])
        store (atom {:active-asset "BTC"
                     :websocket {:migration-flags {:candle-subscriptions? true}}
                     :chart-options {:selected-timeframe :15m}})]
    (subscriptions-runtime/sync-active-candle-subscription!
     {:store store
      :log-fn (fn [& _] nil)
      :sync-candle-subscription! (fn [coin interval owner]
                                   (swap! calls conj [coin interval owner]))})
    (is (= [["BTC" :15m :active-chart]] @calls))
    (subscriptions-runtime/sync-active-candle-subscription!
     {:store store
     :interval :1h
      :log-fn (fn [& _] nil)
      :sync-candle-subscription! (fn [coin interval owner]
                                   (swap! calls conj [coin interval owner]))})
    (is (= [["BTC" :15m :active-chart]
            ["BTC" :1h :active-chart]]
           @calls))))

(deftest sync-active-candle-subscription-clears-when-candle-migration-disabled-test
  (let [calls (atom [])
        store (atom {:active-asset "BTC"
                     :websocket {:migration-flags {:candle-subscriptions? false}}
                     :chart-options {:selected-timeframe :15m}})]
    (subscriptions-runtime/sync-active-candle-subscription!
     {:store store
      :log-fn (fn [& _] nil)
      :sync-candle-subscription! (fn [& _]
                                   (swap! calls conj :sync))
      :clear-candle-subscription! (fn [owner]
                                    (swap! calls conj [:clear owner]))})
    (is (= [[:clear :active-chart]] @calls))))

(deftest subscribe-orderbook-uses-config-derived-from-selected-mode-test
  (let [normalize-calls (atom [])
        config-calls (atom [])
        subscribe-calls (atom [])
        store (atom {:orderbook-ui {:price-aggregation-by-coin {"BTC" :grouped}}})]
    (subscriptions-runtime/subscribe-orderbook!
     {:store store
      :coin "BTC"
      :log-fn (fn [& _] nil)
      :normalize-mode-fn (fn [mode]
                           (swap! normalize-calls conj mode)
                           :full)
      :mode->subscription-config-fn (fn [mode]
                                      (swap! config-calls conj mode)
                                      {:nSigFigs 5})
      :subscribe-orderbook-fn (fn [coin config]
                                (swap! subscribe-calls conj [coin config]))})
    (is (= [:grouped] @normalize-calls))
    (is (= [:full] @config-calls))
    (is (= [["BTC" {:nSigFigs 5}]] @subscribe-calls))))

(deftest unsubscribe-orderbook-removes-coin-from-projection-test
  (let [unsubscribed-coins (atom [])
        store (atom {:orderbooks {"ETH" {:levels []}
                                  "BTC" {:levels []}}})]
    (subscriptions-runtime/unsubscribe-orderbook!
     {:store store
      :coin "ETH"
      :log-fn (fn [& _] nil)
      :unsubscribe-orderbook-fn (fn [coin]
                                  (swap! unsubscribed-coins conj coin))})
    (is (= ["ETH"] @unsubscribed-coins))
    (is (nil? (get-in @store [:orderbooks "ETH"])))
    (is (some? (get-in @store [:orderbooks "BTC"])))))
