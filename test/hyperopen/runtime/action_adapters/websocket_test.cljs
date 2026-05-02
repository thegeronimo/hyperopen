(ns hyperopen.runtime.action-adapters.websocket-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.runtime.action-adapters.websocket :as websocket-adapters]))

(deftest init-websockets-emits-init-effect-test
  (is (= [[:effects/init-websocket]]
         (websocket-adapters/init-websockets {}))))

(deftest subscribe-to-asset-emits-funding-predictability-sync-test
  (is (= [[:effects/subscribe-active-asset "BTC"]
          [:effects/subscribe-orderbook "BTC"]
          [:effects/subscribe-trades "BTC"]
          [:effects/sync-active-asset-funding-predictability "BTC"]]
         (websocket-adapters/subscribe-to-asset {} "BTC"))))

(deftest subscribe-to-asset-emits-outcome-side-book-and-trade-effects-test
  (let [market {:key "outcome:0"
                :coin "#0"
                :market-type :outcome
                :outcome-sides [{:side-index 0 :coin "#0"}
                                {:side-index 1 :coin "#1"}]}
        state {:asset-selector {:market-by-key {"outcome:0" market}}}]
    (is (= [[:effects/subscribe-active-asset "#0"]
            [:effects/subscribe-orderbook "#0"]
            [:effects/subscribe-trades "#0"]
            [:effects/subscribe-orderbook "#1"]
            [:effects/subscribe-trades "#1"]
            [:effects/sync-active-asset-funding-predictability "#0"]]
           (websocket-adapters/subscribe-to-asset state "#0")))))

(deftest subscribe-to-webdata2-emits-address-subscription-test
  (is (= [[:effects/subscribe-webdata2 "0xabc"]]
         (websocket-adapters/subscribe-to-webdata2 {} "0xabc"))))

(deftest refresh-asset-markets-emits-selector-fetch-test
  (is (= [[:effects/fetch-asset-selector-markets]]
         (websocket-adapters/refresh-asset-markets {}))))

(deftest load-user-data-emits-api-load-effect-test
  (is (= [[:effects/api-load-user-data "0xabc"]]
         (websocket-adapters/load-user-data {} "0xabc"))))

(deftest reconnect-websocket-action-emits-effect-test
  (is (= [[:effects/reconnect-websocket]]
         (websocket-adapters/reconnect-websocket-action {}))))
