(ns hyperopen.websocket.active-asset-ctx-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.market-projection-runtime :as market-runtime]))

(defn- reset-active-asset-ctx-state!
  []
  (reset! active-ctx/active-asset-ctx-state {:subscriptions #{}
                                              :owners-by-coin {}
                                              :coins-by-owner {}
                                              :contexts {}}))

(deftest create-active-asset-data-handler-coalesces-burst-updates-per-frame-test
  (reset-active-asset-ctx-state!)
  (market-runtime/reset-market-projection-runtime!)
  (try
    (let [store (atom {:active-assets {:contexts {}
                                       :loading true}
                      :asset-selector {:markets [{:key "perp:BTC"
                                                  :coin "BTC"
                                                  :symbol "BTC-USDC"
                                                  :market-type :perp
                                                  :mark 99
                                                  :markRaw "99"
                                                  :fundingRate 0.0
                                                  :openInterest 0}]
                                       :market-by-key {"perp:BTC" {:key "perp:BTC"
                                                                    :coin "BTC"
                                                                    :symbol "BTC-USDC"
                                                                    :market-type :perp
                                                                    :mark 99
                                                                    :markRaw "99"
                                                                    :fundingRate 0.0
                                                                    :openInterest 0}}
                                       :market-index-by-key {"perp:BTC" 0}}})
          store-write-count (atom 0)
          schedule-count (atom 0)
          scheduled-callback (atom nil)
          watch-key ::store-write-counter
          payload-a {:channel "activeAssetCtx"
                     :data {:coin "BTC"
                            :ctx {:markPx "100.0"
                                  :oraclePx "99.9"
                                  :prevDayPx "95.0"
                                  :funding "0.0001"
                                  :dayNtlVlm "111"
                                  :openInterest "222"}}}
          payload-b {:channel "activeAssetCtx"
                     :data {:coin "BTC"
                            :ctx {:markPx "101.5"
                                  :oraclePx "100.4"
                                  :prevDayPx "95.0"
                                  :funding "0.0002"
                                  :dayNtlVlm "333"
                                  :openInterest "444"}}}]
      (add-watch store watch-key
                 (fn [_ _ old-state new-state]
                   (when (not= old-state new-state)
                     (swap! store-write-count inc))))
      (with-redefs [platform/request-animation-frame! (fn [f]
                                                        (swap! schedule-count inc)
                                                        (reset! scheduled-callback f)
                                                        :raf-id)]
        (let [handler (active-ctx/create-active-asset-data-handler store)]
          (handler payload-a)
          (handler payload-b)
          (is (= 1 @schedule-count))
          (is (= 0 @store-write-count))
          (@scheduled-callback 16)
          (is (= 1 @store-write-count))
          (is (= false (get-in @store [:active-assets :loading])))
          (is (= 101.5 (get-in @store [:active-assets :contexts "BTC" :mark])))
          (is (= 6.5 (get-in @store [:active-assets :contexts "BTC" :change24h])))
          (is (= 0.02 (get-in @store [:active-assets :contexts "BTC" :fundingRate])))
          (is (= 101.5 (get-in @store [:asset-selector :market-by-key "perp:BTC" :mark])))
          (is (= 0.0002 (get-in @store [:asset-selector :market-by-key "perp:BTC" :fundingRate])))
          (is (= 45066 (get-in @store [:asset-selector :market-by-key "perp:BTC" :openInterest])))
          (is (= {"perp:BTC" 0} (get-in @store [:asset-selector :market-index-by-key])))))
      (remove-watch store watch-key))
    (finally
      (reset-active-asset-ctx-state!)
      (market-runtime/reset-market-projection-runtime!))))

(deftest owner-scoped-subscriptions-unsubscribe-only-after-last-owner-test
  (reset-active-asset-ctx-state!)
  (let [messages (atom [])]
    (with-redefs [ws-client/send-message! (fn [message]
                                            (swap! messages conj message)
                                            true)]
      (active-ctx/subscribe-active-asset-ctx! "BTC")
      (active-ctx/subscribe-active-asset-ctx! "BTC" :asset-selector)
      (active-ctx/unsubscribe-active-asset-ctx! "BTC" :asset-selector)
      (active-ctx/unsubscribe-active-asset-ctx! "BTC"))
    (is (= [{:method "subscribe"
             :subscription {:type "activeAssetCtx"
                            :coin "BTC"}}
            {:method "unsubscribe"
             :subscription {:type "activeAssetCtx"
                            :coin "BTC"}}]
           @messages))
    (is (= #{} (active-ctx/get-subscribed-coins-by-owner :asset-selector)))
    (is (empty? (active-ctx/get-subscriptions)))
    (is (nil? (get-in @active-ctx/active-asset-ctx-state [:contexts "BTC"])))))

(deftest create-active-asset-data-handler-accepts-active-spot-asset-ctx-test
  (reset-active-asset-ctx-state!)
  (market-runtime/reset-market-projection-runtime!)
  (try
    (let [store (atom {:active-assets {:contexts {}
                                       :loading true}
                      :asset-selector {:markets [{:key "outcome:0"
                                                  :coin "#0"
                                                  :market-type :outcome}]
                                       :market-by-key {"outcome:0" {:key "outcome:0"
                                                                    :coin "#0"
                                                                    :market-type :outcome}}
                                       :market-index-by-key {"outcome:0" 0}}})
          scheduled-callback (atom nil)
          payload {:channel "activeSpotAssetCtx"
                   :data {:coin "#0"
                          :ctx {:markPx "0.65012"
                                :prevDayPx "0.55"
                                :dayNtlVlm "415908.3249700002"
                                :circulatingSupply "204692.0"}}}]
      (with-redefs [platform/request-animation-frame! (fn [f]
                                                        (reset! scheduled-callback f)
                                                        :raf-id)]
        ((active-ctx/create-active-asset-data-handler store) payload)
        (@scheduled-callback 16)
        (is (= false (get-in @store [:active-assets :loading])))
        (is (= 0.65012 (get-in @store [:active-assets :contexts "#0" :mark])))
        (is (= 204692 (get-in @store [:active-assets :contexts "#0" :openInterest])))
        (is (= 204692 (get-in @store [:asset-selector :market-by-key "outcome:0" :openInterest])))
        (is (nil? (get-in @store [:active-assets :contexts "#0" :fundingRate])))))
    (finally
      (reset-active-asset-ctx-state!)
      (market-runtime/reset-market-projection-runtime!))))

(deftest unsubscribe-active-asset-ctx-updates-local-state-atomically-test
  (reset-active-asset-ctx-state!)
  (try
    (reset! active-ctx/active-asset-ctx-state {:subscriptions #{"BTC"}
                                                :owners-by-coin {"BTC" #{:active-asset}}
                                                :coins-by-owner {:active-asset #{"BTC"}}
                                                :contexts {"BTC" {:markPx "100"}}})
    (let [write-count (atom 0)
          watch-key ::active-asset-write-counter]
      (add-watch active-ctx/active-asset-ctx-state watch-key
                 (fn [_ _ old-state new-state]
                   (when (not= old-state new-state)
                     (swap! write-count inc))))
      (with-redefs [ws-client/send-message! (fn [_] true)]
        (active-ctx/unsubscribe-active-asset-ctx! "BTC"))
      (is (= 1 @write-count))
      (is (empty? (:subscriptions @active-ctx/active-asset-ctx-state)))
      (is (empty? (:owners-by-coin @active-ctx/active-asset-ctx-state)))
      (is (empty? (:coins-by-owner @active-ctx/active-asset-ctx-state)))
      (is (nil? (get-in @active-ctx/active-asset-ctx-state [:contexts "BTC"])))
      (remove-watch active-ctx/active-asset-ctx-state watch-key))
    (finally
      (reset-active-asset-ctx-state!))))
