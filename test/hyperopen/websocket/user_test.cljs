(ns hyperopen.websocket.user-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.api.default :as api]
            [hyperopen.api.market-metadata.facade :as market-metadata]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.user :as user-ws]))

(defn- make-store []
  (atom {:orders {:fills []
                  :fundings []
                  :fundings-raw []
                  :ledger []}
         :ui {:toast nil
              :toasts []}
         :account-info {:funding-history {:filters {:coin-set #{}
                                                   :start-time-ms 0
                                                   :end-time-ms 9999999999999}}}}))

(deftest init-handlers-parse-nested-user-channel-payloads-test
  (let [store (make-store)
        handlers (atom {})]
    (with-redefs [ws-client/register-handler!
                  (fn [message-type handler-fn]
                    (swap! handlers assoc message-type handler-fn)
                    true)]
      (user-ws/init! store)
      (testing "userFills snapshot uses nested :data :fills"
        ((get @handlers "userFills")
         {:channel "userFills"
          :data {:isSnapshot true
                 :fills [{:tid 1 :coin "BTC" :time 1000}]}})
        (is (= [{:tid 1 :coin "BTC" :time 1000}]
               (get-in @store [:orders :fills])))
        (is (nil? (get-in @store [:ui :toast]))))
      (testing "userFundings snapshot normalizes nested :data :fundings"
        ((get @handlers "userFundings")
         {:channel "userFundings"
          :data {:isSnapshot true
                 :fundings [{:time 1000
                             :coin "HYPE"
                             :usdc "-1.2500"
                             :szi "-250.5"
                             :fundingRate "0.00045"}]}})
        (is (= 1 (count (get-in @store [:orders :fundings-raw]))))
        (is (= :short (get-in @store [:orders :fundings-raw 0 :position-side])))
        (is (= "HYPE" (get-in @store [:orders :fundings 0 :coin]))))
      (testing "userNonFundingLedgerUpdates snapshot uses nested :data :nonFundingLedgerUpdates"
        ((get @handlers "userNonFundingLedgerUpdates")
         {:channel "userNonFundingLedgerUpdates"
          :data {:isSnapshot true
                 :nonFundingLedgerUpdates [{:time 1000 :coin "USDC" :delta "5.0"}]}})
        (is (= [{:time 1000 :coin "USDC" :delta "5.0"}]
               (get-in @store [:orders :ledger])))))))

(deftest user-channel-incrementals-append-and-dedupe-test
  (let [store (make-store)
        handlers (atom {})]
    (with-redefs [ws-client/register-handler!
                  (fn [message-type handler-fn]
                    (swap! handlers assoc message-type handler-fn)
                    true)
                  runtime-state/runtime (atom (runtime-state/default-runtime-state))
                  platform/set-timeout! (fn [_ _] 1234)
                  platform/clear-timeout! (fn [_] nil)]
      (user-ws/init! store)
      ((get @handlers "userFills")
       {:channel "userFills"
        :data {:isSnapshot true
               :fills [{:tid 1 :coin "BTC" :time 1000}]}})
      ((get @handlers "userFills")
       {:channel "userFills"
        :data {:isSnapshot false
               :fills [{:tid 2
                        :coin "ETH"
                        :side "A"
                        :sz "0.0473"
                        :px "2132.4"
                        :time 2000}]}})
      (is (= [2 1] (mapv :tid (get-in @store [:orders :fills]))))
      (is (= :success (get-in @store [:ui :toast :kind])))
      (is (= "Sold 0.0473 ETH"
             (get-in @store [:ui :toast :headline])))
      (is (= "At average price of $2,132.4"
             (get-in @store [:ui :toast :subline])))
      (is (= 1 (count (get-in @store [:ui :toasts]))))
      (swap! store assoc :ui {:toast nil
                              :toasts []})
      ((get @handlers "userFills")
       {:channel "userFills"
        :data {:isSnapshot false
               :fills [{:tid 2
                        :coin "ETH"
                        :side "A"
                        :sz "0.0473"
                        :px "2132.4"
                        :time 2000}]}})
      (is (= [2 1] (mapv :tid (get-in @store [:orders :fills]))))
      (is (nil? (get-in @store [:ui :toast])))
      ((get @handlers "userFundings")
       {:channel "userFundings"
        :data {:isSnapshot true
               :fundings [{:time 1000
                           :coin "BTC"
                           :usdc "1.0"
                           :szi "3.0"
                           :fundingRate "0.0001"}]}})
      ((get @handlers "userFundings")
       {:channel "userFundings"
        :data {:isSnapshot false
               :fundings [{:time 1000
                           :coin "BTC"
                           :usdc "1.0"
                           :szi "3.0"
                           :fundingRate "0.0001"}
                          {:time 2000
                           :coin "BTC"
                           :usdc "2.0"
                           :szi "4.0"
                           :fundingRate "0.0002"}]}})
      (is (= 2 (count (get-in @store [:orders :fundings-raw]))))
      (is (= [2000 1000] (mapv :time-ms (get-in @store [:orders :fundings-raw]))))
      ((get @handlers "userNonFundingLedgerUpdates")
       {:channel "userNonFundingLedgerUpdates"
        :data {:isSnapshot true
               :nonFundingLedgerUpdates [{:time 1000 :coin "USDC" :delta "1.0"}]}})
      ((get @handlers "userNonFundingLedgerUpdates")
       {:channel "userNonFundingLedgerUpdates"
        :data {:isSnapshot false
               :nonFundingLedgerUpdates [{:time 2000 :coin "USDC" :delta "2.0"}]}})
      (is (= ["2.0" "1.0"] (mapv :delta (get-in @store [:orders :ledger])))))))

(deftest user-fills-incrementals-stack-toasts-and-group-summaries-test
  (let [store (make-store)
        handlers (atom {})]
    (with-redefs [ws-client/register-handler!
                  (fn [message-type handler-fn]
                    (swap! handlers assoc message-type handler-fn)
                    true)
                  runtime-state/runtime (atom (runtime-state/default-runtime-state))
                  platform/set-timeout! (fn [_ _] 1234)
                  platform/clear-timeout! (fn [_] nil)]
      (user-ws/init! store)
      ((get @handlers "userFills")
       {:channel "userFills"
        :data {:isSnapshot false
               :fills [{:tid 10
                        :coin "HYPE"
                        :side "B"
                        :sz "4.00"
                        :px "31.00"
                        :time 1000}
                       {:tid 11
                        :coin "HYPE"
                        :side "B"
                        :sz "2.00"
                        :px "33.00"
                        :time 1001}]}})
      (is (= "Bought 6 HYPE"
             (get-in @store [:ui :toast :headline])))
      (is (= "At average price of $31.66667"
             (get-in @store [:ui :toast :subline])))
      (is (= 1 (count (get-in @store [:ui :toasts]))))

      ((get @handlers "userFills")
       {:channel "userFills"
        :data {:isSnapshot false
               :fills [{:tid 12
                        :coin "SOL"
                        :side "A"
                        :sz "1.25"
                        :px "90.79"
                        :time 1002}]}})
      (is (= "Sold 1.25 SOL"
             (get-in @store [:ui :toast :headline])))
      (is (= 2 (count (get-in @store [:ui :toasts]))))
      (is (= ["Bought 6 HYPE"
              "Sold 1.25 SOL"]
             (mapv :headline (get-in @store [:ui :toasts])))))))

(deftest user-ledger-incremental-triggers-account-surface-refresh-test
  (let [store (doto (make-store)
                (swap! assoc :wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                       :account-context {:ghost-mode {:active? true
                                                      :address "0xdddddddddddddddddddddddddddddddddddddddd"}}))
        handlers (atom {})
        scheduled-refresh (atom nil)
        open-orders-addresses (atom [])
        perp-clearinghouse-addresses (atom [])
        spot-clearinghouse-addresses (atom [])]
    (with-redefs [ws-client/register-handler!
                  (fn [message-type handler-fn]
                    (swap! handlers assoc message-type handler-fn)
                    true)
                  platform/set-timeout! (fn [callback _ms]
                                          (reset! scheduled-refresh callback)
                                          1234)
                  platform/clear-timeout! (fn [_] nil)
                  api/request-frontend-open-orders! (fn
                                                      ([_address]
                                                       (swap! open-orders-addresses conj _address)
                                                       (js/Promise.resolve []))
                                                      ([_address _opts]
                                                       (swap! open-orders-addresses conj _address)
                                                       (js/Promise.resolve []))
                                                      ([_address _dex _opts]
                                                       (swap! open-orders-addresses conj _address)
                                                       (js/Promise.resolve [])))
                  api/request-clearinghouse-state! (fn
                                                     ([_address _dex]
                                                      (swap! perp-clearinghouse-addresses conj _address)
                                                      (js/Promise.resolve {}))
                                                     ([_address _dex _opts]
                                                      (swap! perp-clearinghouse-addresses conj _address)
                                                      (js/Promise.resolve {})))
                  api/request-spot-clearinghouse-state! (fn
                                                          ([_address]
                                                           (swap! spot-clearinghouse-addresses conj _address)
                                                           (js/Promise.resolve {}))
                                                          ([_address _opts]
                                                           (swap! spot-clearinghouse-addresses conj _address)
                                                           (js/Promise.resolve {})))
                  market-metadata/ensure-and-apply-perp-dex-metadata! (fn [_deps _opts]
                                                                        (js/Promise.resolve []))]
      (user-ws/init! store)
      ((get @handlers "userNonFundingLedgerUpdates")
       {:channel "userNonFundingLedgerUpdates"
        :data {:isSnapshot false
               :nonFundingLedgerUpdates [{:time 2000 :coin "USDC" :delta "2.0"}]}})
      (is (fn? @scheduled-refresh))
      (@scheduled-refresh)
      (is (= ["0xdddddddddddddddddddddddddddddddddddddddd"]
             @open-orders-addresses))
      (is (= ["0xdddddddddddddddddddddddddddddddddddddddd"]
             @perp-clearinghouse-addresses))
      (is (= ["0xdddddddddddddddddddddddddddddddddddddddd"]
             @spot-clearinghouse-addresses)))))

(deftest user-ledger-refresh-skips-open-orders-and-default-clearinghouse-when-ws-live-test
  (let [effective-address "0xdddddddddddddddddddddddddddddddddddddddd"
        store (doto (make-store)
                (swap! assoc :wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                       :account-context {:ghost-mode {:active? true
                                                      :address effective-address}}
                       :websocket {:health {:transport {:state :connected
                                                        :freshness :live}
                                            :streams {["openOrders" nil effective-address nil nil]
                                                      {:topic "openOrders"
                                                       :status :live
                                                       :subscribed? true
                                                       :descriptor {:type "openOrders"
                                                                    :user effective-address}}
                                                      ["webData2" nil effective-address nil nil]
                                                      {:topic "webData2"
                                                       :status :live
                                                       :subscribed? true
                                                       :descriptor {:type "webData2"
                                                                    :user effective-address}}}}}))
        handlers (atom {})
        scheduled-refresh (atom nil)
        open-orders-addresses (atom [])
        perp-clearinghouse-addresses (atom [])
        spot-clearinghouse-addresses (atom [])]
    (with-redefs [ws-client/register-handler!
                  (fn [message-type handler-fn]
                    (swap! handlers assoc message-type handler-fn)
                    true)
                  platform/set-timeout! (fn [callback _ms]
                                          (reset! scheduled-refresh callback)
                                          1234)
                  platform/clear-timeout! (fn [_] nil)
                  api/request-frontend-open-orders! (fn
                                                      ([_address]
                                                       (swap! open-orders-addresses conj _address)
                                                       (js/Promise.resolve []))
                                                      ([_address _opts]
                                                       (swap! open-orders-addresses conj _address)
                                                       (js/Promise.resolve []))
                                                      ([_address _dex _opts]
                                                       (swap! open-orders-addresses conj _address)
                                                       (js/Promise.resolve [])))
                  api/request-clearinghouse-state! (fn
                                                     ([_address _dex]
                                                      (swap! perp-clearinghouse-addresses conj _address)
                                                      (js/Promise.resolve {}))
                                                     ([_address _dex _opts]
                                                      (swap! perp-clearinghouse-addresses conj _address)
                                                      (js/Promise.resolve {})))
                  api/request-spot-clearinghouse-state! (fn
                                                          ([_address]
                                                           (swap! spot-clearinghouse-addresses conj _address)
                                                           (js/Promise.resolve {}))
                                                          ([_address _opts]
                                                           (swap! spot-clearinghouse-addresses conj _address)
                                                           (js/Promise.resolve {})))
                  market-metadata/ensure-and-apply-perp-dex-metadata! (fn [_deps _opts]
                                                                        (js/Promise.resolve []))]
      (user-ws/init! store)
      ((get @handlers "userNonFundingLedgerUpdates")
       {:channel "userNonFundingLedgerUpdates"
        :data {:isSnapshot false
               :nonFundingLedgerUpdates [{:time 2000 :coin "USDC" :delta "2.0"}]}})
      (is (fn? @scheduled-refresh))
      (@scheduled-refresh)
      (is (= [] @open-orders-addresses))
      ;; Default clearinghouse snapshot is skipped when webData2 stream is live.
      (is (= [] @perp-clearinghouse-addresses))
      (is (= [effective-address]
             @spot-clearinghouse-addresses)))))

(deftest user-ledger-refresh-forces-rest-refresh-when-ws-first-flag-disabled-test
  (let [effective-address "0xdddddddddddddddddddddddddddddddddddddddd"
        store (doto (make-store)
                (swap! assoc :wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                       :account-context {:ghost-mode {:active? true
                                                      :address effective-address}}
                       :websocket {:migration-flags {:order-fill-ws-first? false}
                                   :health {:transport {:state :connected
                                                        :freshness :live}
                                            :streams {["openOrders" nil effective-address nil nil]
                                                      {:topic "openOrders"
                                                       :status :live
                                                       :subscribed? true
                                                       :descriptor {:type "openOrders"
                                                                    :user effective-address}}
                                                      ["webData2" nil effective-address nil nil]
                                                      {:topic "webData2"
                                                       :status :live
                                                       :subscribed? true
                                                       :descriptor {:type "webData2"
                                                                    :user effective-address}}}}}))
        handlers (atom {})
        scheduled-refresh (atom nil)
        open-orders-addresses (atom [])
        perp-clearinghouse-addresses (atom [])
        spot-clearinghouse-addresses (atom [])]
    (with-redefs [ws-client/register-handler!
                  (fn [message-type handler-fn]
                    (swap! handlers assoc message-type handler-fn)
                    true)
                  platform/set-timeout! (fn [callback _ms]
                                          (reset! scheduled-refresh callback)
                                          1234)
                  platform/clear-timeout! (fn [_] nil)
                  api/request-frontend-open-orders! (fn
                                                      ([_address]
                                                       (swap! open-orders-addresses conj _address)
                                                       (js/Promise.resolve []))
                                                      ([_address _opts]
                                                       (swap! open-orders-addresses conj _address)
                                                       (js/Promise.resolve []))
                                                      ([_address _dex _opts]
                                                       (swap! open-orders-addresses conj _address)
                                                       (js/Promise.resolve [])))
                  api/request-clearinghouse-state! (fn
                                                     ([_address _dex]
                                                      (swap! perp-clearinghouse-addresses conj _address)
                                                      (js/Promise.resolve {}))
                                                     ([_address _dex _opts]
                                                      (swap! perp-clearinghouse-addresses conj _address)
                                                      (js/Promise.resolve {})))
                  api/request-spot-clearinghouse-state! (fn
                                                          ([_address]
                                                           (swap! spot-clearinghouse-addresses conj _address)
                                                           (js/Promise.resolve {}))
                                                          ([_address _opts]
                                                           (swap! spot-clearinghouse-addresses conj _address)
                                                           (js/Promise.resolve {})))
                  market-metadata/ensure-and-apply-perp-dex-metadata! (fn [_deps _opts]
                                                                        (js/Promise.resolve []))]
      (user-ws/init! store)
      ((get @handlers "userNonFundingLedgerUpdates")
       {:channel "userNonFundingLedgerUpdates"
        :data {:isSnapshot false
               :nonFundingLedgerUpdates [{:time 2000 :coin "USDC" :delta "2.0"}]}})
      (is (fn? @scheduled-refresh))
      (@scheduled-refresh)
      (is (= [effective-address]
             @open-orders-addresses))
      ;; Flag disabled should restore default clearinghouse snapshot fetch.
      (is (= [effective-address]
             @perp-clearinghouse-addresses))
      (is (= [effective-address]
             @spot-clearinghouse-addresses)))))

(deftest user-handlers-ignore-messages-for-inactive-address-test
  (let [active-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        stale-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        store (doto (make-store)
                (swap! assoc :wallet {:address active-address}))
        handlers (atom {})]
    (with-redefs [ws-client/register-handler!
                  (fn [message-type handler-fn]
                    (swap! handlers assoc message-type handler-fn)
                    true)]
      (user-ws/init! store)
      ((get @handlers "userFills")
       {:channel "userFills"
        :user stale-address
        :data {:isSnapshot true
               :fills [{:tid 1 :coin "BTC" :time 1000}]}})
      (is (empty? (get-in @store [:orders :fills])))
      ((get @handlers "openOrders")
       {:channel "openOrders"
        :user stale-address
        :data {:orders [{:oid 11}]}})
      (is (nil? (get-in @store [:orders :open-orders])))
      ((get @handlers "userFills")
       {:channel "userFills"
        :user active-address
        :data {:isSnapshot true
               :fills [{:tid 2 :coin "ETH" :time 2000}]}})
      (is (= [2] (mapv :tid (get-in @store [:orders :fills]))))
      ((get @handlers "openOrders")
       {:channel "openOrders"
        :user active-address
        :data {:orders [{:oid 22}]}})
      (is (= {:orders [{:oid 22}]}
             (get-in @store [:orders :open-orders]))))))
