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
        fetch-fn (fn [store & {:keys [interval bars]}]
                   (swap! calls conj {:store store
                                      :interval interval
                                      :bars bars}))
        store (atom {})]
    (app-effects/fetch-candle-snapshot!
     {:store store
      :log-fn (fn [& _] nil)
      :fetch-candle-snapshot-fn fetch-fn})
    (app-effects/fetch-candle-snapshot!
     {:store store
      :interval :4h
      :bars 100
      :log-fn (fn [& _] nil)
      :fetch-candle-snapshot-fn fetch-fn})
    (is (= [{:store store :interval :1d :bars 330}
            {:store store :interval :4h :bars 100}]
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
    (is (= :connecting (get-in @store [:websocket :status])))
    (is (= 1 @reconnect-calls))))
