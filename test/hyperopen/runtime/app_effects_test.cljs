(ns hyperopen.runtime.app-effects-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.runtime.app-effects :as app-effects]))

(deftest save-many-applies-all-updates-to-store-test
  (let [store (atom {:wallet {:connected? false}
                     :router {:path "/"}})]
    (app-effects/save-many!
     store
     [[[:wallet :connected?] true]
      [[:router :path] "/trade"]])
    (is (= true (get-in @store [:wallet :connected?])))
    (is (= "/trade" (get-in @store [:router :path])))))

(deftest fetch-candle-snapshot-uses-defaults-and-custom-options-test
  (let [calls (atom [])
        request-fn (fn [coin & {:keys [interval bars]}]
                     (swap! calls conj {:coin coin
                                        :interval interval
                                        :bars bars})
                     (js/Promise.resolve [{:t 1}]))
        store (atom {:active-asset "BTC"})]
    (app-effects/fetch-candle-snapshot!
     {:store store
      :log-fn (fn [& _] nil)
      :request-candle-snapshot-fn request-fn
      :apply-candle-snapshot-success (fn [state coin interval rows]
                                       (assoc-in state [:candles coin interval] rows))
      :apply-candle-snapshot-error (fn [state coin interval err]
                                     (assoc-in state [:candles coin interval :error] (str err)))})
    (app-effects/fetch-candle-snapshot!
     {:store store
      :interval :4h
      :bars 100
      :log-fn (fn [& _] nil)
      :request-candle-snapshot-fn request-fn
      :apply-candle-snapshot-success (fn [state coin interval rows]
                                       (assoc-in state [:candles coin interval] rows))
      :apply-candle-snapshot-error (fn [state coin interval err]
                                     (assoc-in state [:candles coin interval :error] (str err)))})
    (is (= [{:coin "BTC" :interval :1d :bars 330}
            {:coin "BTC" :interval :4h :bars 100}]
           @calls))))

(deftest init-and-reconnect-websocket-effects-forward-runtime-dependencies-test
  (let [store (atom {:websocket {:status :disconnected}})
        init-calls (atom [])
        reconnect-calls (atom 0)]
    (app-effects/init-websocket!
     {:store store
      :ws-url "wss://example.test/ws"
      :log-fn (fn [& _] nil)
      :init-connection! (fn [url]
                          (swap! init-calls conj url))})
    (app-effects/reconnect-websocket!
     {:log-fn (fn [& _] nil)
      :force-reconnect! (fn []
                          (swap! reconnect-calls inc))})
    (is (= ["wss://example.test/ws"] @init-calls))
    (is (= :disconnected (get-in @store [:websocket :status])))
    (is (= 1 @reconnect-calls))))
