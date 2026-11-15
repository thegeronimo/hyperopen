(ns hyperopen.websocket.application.runtime-tla-trace-fixtures
  (:require [hyperopen.websocket.application.runtime-reducer :as reducer]
            [hyperopen.websocket.domain.model :as model]))

(def test-config
  {:max-queue-size 3
   :watchdog-interval-ms 10000
   :health-tick-interval-ms 1000
   :transport-live-threshold-ms 10000
   :stale-threshold-ms {"l2Book" 5000
                        "trades" 10000}
   :stale-visible-ms 45000
   :stale-hidden-ms 180000
   :market-coalesce-window-ms 5})

(defn initial-state
  []
  (reducer/initial-runtime-state test-config))

(defn recording
  [{:keys [initial-state messages]}]
  {:session {:metadata {:initial-state initial-state}}
   :events (mapv (fn [idx msg]
                   {:seq (inc idx)
                    :kind :runtime/msg
                    :at-ms (:ts msg)
                    :payload {:msg-type (:msg/type msg)
                              :msg msg}})
                 (range)
                 messages)})

(def stale-socket-trace
  {:initial-state (-> (initial-state)
                      (assoc :status :connected
                             :active-socket-id 2
                             :online? true))
   :messages [(model/make-runtime-msg :evt/decoded-envelope
                                      100
                                      {:socket-id 1
                                       :recv-at-ms 100
                                       :envelope {:topic "trades"
                                                  :tier :market
                                                  :ts 100
                                                  :payload {:channel "trades"
                                                            :seq 9
                                                            :data [{:coin "BTC"}]}}})
              (model/make-runtime-msg :evt/parse-error
                                      101
                                      {:socket-id 1
                                       :raw "{}"
                                       :error (js/Error. "stale")})]})

(def replay-order-state
  (let [sub-a {:type "trades" :coin "BTC"}
        sub-b {:type "l2Book" :coin "ETH"}
        sub-c {:type "openOrders" :user "0xabc"}
        desired {(model/subscription-key sub-a) sub-a
                 (model/subscription-key sub-b) sub-b
                 (model/subscription-key sub-c) sub-c}]
    (-> (initial-state)
        (assoc :status :connecting
               :online? true
               :socket-id 10
               :active-socket-id 10
               :desired-subscriptions desired
               :queue [{:op "queued" :id 1}
                       {:op "queued" :id 2}]))))

(def replay-order-trace
  {:initial-state replay-order-state
   :messages [(model/make-runtime-msg :evt/socket-open
                                      700
                                      {:socket-id 10
                                       :at-ms 700})]})

(def browser-startup-recording
  {:session {:metadata {:initial-state (initial-state)
                        :capture-source :hyperopen-debug-flight-recording
                        :capture-date "2026-03-27"}}
   :events [{:seq 1
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/init-connection
                       :msg (model/make-runtime-msg :cmd/init-connection
                                                    1774626671971
                                                    {:ws-url "wss://api.hyperliquid.xyz/ws"})}}
            {:seq 2
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671971
                                                    {:topic "activeAssetCtx"})}}
            {:seq 3
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671971
                                                    {:topic "candle"})}}
            {:seq 4
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671971
                                                    {:topic "l2Book"})}}
            {:seq 5
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671971
                                                    {:topic "trades"})}}
            {:seq 6
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671971
                                                    {:topic "openOrders"})}}
            {:seq 7
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671971
                                                    {:topic "twapStates"})}}
            {:seq 8
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671971
                                                    {:topic "userFills"})}}
            {:seq 9
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671971
                                                    {:topic "userFundings"})}}
            {:seq 10
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671971
                                                    {:topic "userTwapHistory"})}}
            {:seq 11
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671971
                                                    {:topic "userTwapSliceFills"})}}
            {:seq 12
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671971
                                                    {:topic "userNonFundingLedgerUpdates"})}}
            {:seq 13
             :kind :runtime/msg
             :at-ms 1774626671971
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671971
                                                    {:topic "clearinghouseState"})}}
            {:seq 14
             :kind :runtime/msg
             :at-ms 1774626671972
             :payload {:msg-type :cmd/register-handler
                       :msg (model/make-runtime-msg :cmd/register-handler
                                                    1774626671972
                                                    {:topic "webData2"})}}
            {:seq 15
             :kind :runtime/msg
             :at-ms 1774626671974
             :payload {:msg-type :cmd/send-message
                       :msg (model/make-runtime-msg :cmd/send-message
                                                    1774626671974
                                                    {:data {:method "subscribe"
                                                            :subscription {:type "activeAssetCtx"
                                                                           :coin "BTC"}}})}}
            {:seq 16
             :kind :runtime/msg
             :at-ms 1774626671974
             :payload {:msg-type :cmd/send-message
                       :msg (model/make-runtime-msg :cmd/send-message
                                                    1774626671974
                                                    {:data {:method "subscribe"
                                                            :subscription {:type "l2Book"
                                                                           :coin "BTC"}}})}}
            {:seq 17
             :kind :runtime/msg
             :at-ms 1774626671974
             :payload {:msg-type :cmd/send-message
                       :msg (model/make-runtime-msg :cmd/send-message
                                                    1774626671974
                                                    {:data {:method "subscribe"
                                                            :subscription {:type "trades"
                                                                           :coin "BTC"}}})}}
            {:seq 18
             :kind :runtime/msg
             :at-ms 1774626672380
             :payload {:msg-type :evt/socket-open
                       :msg (model/make-runtime-msg :evt/socket-open
                                                    1774626672380
                                                    {:socket-id 1
                                                     :at-ms 1774626672380})}}]})

(def force-reconnect-trace
  {:initial-state (-> (initial-state)
                      (assoc :status :connected
                             :ws-url "wss://example.test/ws"
                             :online? true
                             :socket-id 5
                             :active-socket-id 5
                             :market-flush-active? true)
                      (assoc-in [:market-coalesce :pending ["activeAssetCtx" "BTC"]]
                                {:topic "activeAssetCtx"
                                 :tier :market
                                 :ts 12
                                 :payload {:channel "activeAssetCtx"
                                           :coin "BTC"}}))
   :messages [(model/make-runtime-msg :cmd/force-reconnect 600)]})

(def seq-gap-resubscribe-trace
  {:initial-state (-> (initial-state)
                      (assoc :status :connected
                             :active-socket-id 1
                             :online? true)
                      (assoc-in [:transport :connected-at-ms] 10))
   :messages [(model/make-runtime-msg :cmd/send-message
                                      100
                                      {:data {:method "subscribe"
                                              :subscription {:type "trades" :coin "BTC"}}})
              (model/make-runtime-msg :evt/decoded-envelope
                                      110
                                      {:recv-at-ms 110
                                       :envelope {:topic "trades"
                                                  :tier :market
                                                  :ts 110
                                                  :payload {:channel "trades"
                                                            :seq 1
                                                            :data [{:coin "BTC"}]}}})
              (model/make-runtime-msg :evt/decoded-envelope
                                      130
                                      {:recv-at-ms 130
                                       :envelope {:topic "trades"
                                                  :tier :market
                                                  :ts 130
                                                  :payload {:channel "trades"
                                                            :seq 5
                                                            :data [{:coin "BTC"}]}}})
              (model/make-runtime-msg :cmd/send-message
                                      140
                                      {:data {:method "subscribe"
                                              :subscription {:type "trades" :coin "BTC"}}})]})

(def intentional-close-trace
  {:initial-state (-> (initial-state)
                      (assoc :status :connected
                             :active-socket-id 7
                             :intentional-close? true))
   :messages [(model/make-runtime-msg :evt/socket-close
                                      100
                                      {:socket-id 7
                                       :code 1000
                                       :reason "Intentional disconnect"
                                       :was-clean? true
                                       :at-ms 100})]})

(def expected-replay-order
  (vec
   (concat
    (->> (vals (:desired-subscriptions replay-order-state))
         (sort-by model/subscription-key)
         (map (fn [subscription]
                {:method "subscribe"
                 :subscription subscription})))
    (:queue replay-order-state))))

(def browser-startup-subscriptions
  [{:type "activeAssetCtx" :coin "BTC"}
   {:type "l2Book" :coin "BTC"}
   {:type "trades" :coin "BTC"}])

(def expected-browser-startup-replay-order
  (vec
   (concat
    (->> browser-startup-subscriptions
         (sort-by model/subscription-key)
         (map (fn [subscription]
                {:method "subscribe"
                 :subscription subscription})))
    (mapv (fn [subscription]
            {:method "subscribe"
             :subscription subscription})
          browser-startup-subscriptions))))
