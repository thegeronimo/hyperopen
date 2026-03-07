(ns hyperopen.account.surface-service-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.surface-service :as surface-service]))

(def ^:private address
  "0x1111111111111111111111111111111111111111")

(deftest schedule-stream-backed-fallback-skips-live-streams-and-stale-addresses-test
  (let [scheduled-callback (atom nil)
        fetch-calls (atom [])
        store (atom {:wallet {:address address}})]
    (surface-service/schedule-stream-backed-fallback!
     {:store store
      :address address
      :topic "openOrders"
      :fetch-fn (fn [_store fetch-address opts]
                  (swap! fetch-calls conj [fetch-address opts]))
      :opts {:priority :high}
      :startup-stream-backfill-delay-ms 12
      :set-timeout-fn (fn [callback _delay-ms]
                        (reset! scheduled-callback callback)
                        :timeout-id)})
    (is (fn? @scheduled-callback))
    (swap! store assoc-in [:wallet :address]
           "0x2222222222222222222222222222222222222222")
    (@scheduled-callback)
    (is (= [] @fetch-calls))
    (reset! scheduled-callback nil)
    (reset! fetch-calls [])
    (swap! store assoc
           :wallet {:address address}
           :websocket {:health {:transport {:state :connected
                                            :freshness :live}
                                :streams {["openOrders" nil address nil nil]
                                          {:topic "openOrders"
                                           :status :live
                                           :subscribed? true
                                           :descriptor {:type "openOrders"
                                                        :user address}}}}})
    (surface-service/schedule-stream-backed-fallback!
     {:store store
      :address address
      :topic "openOrders"
      :fetch-fn (fn [_store fetch-address opts]
                  (swap! fetch-calls conj [fetch-address opts]))
      :opts {:priority :high}
      :startup-stream-backfill-delay-ms 12
      :set-timeout-fn (fn [callback _delay-ms]
                        (reset! scheduled-callback callback)
                        :timeout-id)})
    (is (nil? @scheduled-callback))
    (is (= [] @fetch-calls))))

(deftest refresh-after-user-fill-respects-stream-and-ready-snapshot-coverage-test
  (async done
    (let [dex "vault"
          refresh-open-orders-calls (atom [])
          refresh-default-clearinghouse-calls (atom [])
          refresh-spot-calls (atom [])
          refresh-perp-dex-calls (atom [])
          sync-calls (atom [])
          store (atom
                 {:wallet {:address address}
                  :router {:path "/trade"}
                  :account-info {:selected-tab :balances}
                  :perp-dex-clearinghouse {dex {:account-value "1"}}
                  :websocket {:health {:transport {:state :connected
                                                   :freshness :live}
                                       :streams {["openOrders" nil address nil nil]
                                                 {:topic "openOrders"
                                                  :status :n-a
                                                  :subscribed? true
                                                  :descriptor {:type "openOrders"
                                                               :user address}}
                                                 ["webData2" nil address nil nil]
                                                 {:topic "webData2"
                                                  :status :n-a
                                                  :subscribed? true
                                                  :descriptor {:type "webData2"
                                                               :user address}}
                                                 ["clearinghouseState" nil address dex nil]
                                                 {:topic "clearinghouseState"
                                                 :status :idle
                                                  :subscribed? true
                                                  :descriptor {:type "clearinghouseState"
                                                               :user address
                                                               :dex dex}}}}}})]
      (surface-service/refresh-after-user-fill!
       {:store store
        :address address
        :ensure-perp-dexs! (fn [_store _opts]
                             (js/Promise.resolve [dex]))
        :sync-perp-dex-clearinghouse-subscriptions! (fn [sync-address dex-names]
                                                      (swap! sync-calls conj [sync-address dex-names]))
        :refresh-open-orders! (fn [_store refresh-address refresh-dex opts]
                                (swap! refresh-open-orders-calls conj [refresh-address refresh-dex opts]))
        :refresh-default-clearinghouse! (fn [_store refresh-address opts]
                                          (swap! refresh-default-clearinghouse-calls conj [refresh-address opts]))
        :refresh-spot-clearinghouse! (fn [_store refresh-address opts]
                                       (swap! refresh-spot-calls conj [refresh-address opts]))
        :refresh-perp-dex-clearinghouse! (fn [_store refresh-address refresh-dex opts]
                                           (swap! refresh-perp-dex-calls conj [refresh-address refresh-dex opts]))})
      (js/setTimeout
       (fn []
         (is (= [] @refresh-open-orders-calls))
         (is (= [] @refresh-default-clearinghouse-calls))
         (is (= [[address {:priority :high}]]
                @refresh-spot-calls))
         (is (= [] @refresh-perp-dex-calls))
         (is (= [[address [dex]]]
                @sync-calls))
         (done))
       0))))
