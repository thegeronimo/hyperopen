(ns hyperopen.websocket.application.runtime-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.websocket.application.runtime :as runtime]
            [hyperopen.websocket.domain.model :as model]
            [hyperopen.websocket.infrastructure.transport :as infra]))

(defn- reset-stream! [stream]
  (reset! stream {:tier-depth {:market 0 :lossless 0}
                  :metrics {:market-coalesced 0
                            :market-dispatched 0
                            :lossless-dispatched 0
                            :ingress-parse-errors 0}
                  :now-ms nil
                  :streams {}
                  :transport {:state :disconnected
                              :online? true
                              :last-recv-at-ms nil
                              :connected-at-ms nil
                              :expected-traffic? false
                              :attempt 0
                              :last-close nil}
                  :market-coalesce {:pending {}
                                    :timer nil}}))

(defn- make-test-scheduler []
  (reify infra/IScheduler
    (schedule-timeout* [_ f ms] (js/setTimeout f ms))
    (clear-timeout* [_ timer-id] (js/clearTimeout timer-id))
    (schedule-interval* [_ f ms] (js/setInterval f ms))
    (clear-interval* [_ timer-id] (js/clearInterval timer-id))
    (window-object* [_] nil)
    (document-object* [_] nil)
    (navigator-object* [_] nil)
    (add-event-listener* [_ _ _ _] nil)
    (online?* [_] true)
    (hidden-tab?* [_] false)))

(defn- make-test-clock []
  (reify infra/IClock
    (now-ms* [_] (.now js/Date))
    (random-value* [_] 0.5)))

(defn- make-test-transport []
  (reify infra/ITransport
    (connect-websocket! [_ _ _] (js-obj "readyState" 0))
    (send-json! [_ _ _] true)
    (close-socket! [_ _ _ _] true)
    (ready-state [_ socket] (.-readyState socket))
    (detach-handlers! [_ _] nil)))

(defn- make-test-runtime
  [{:keys [parse-raw-envelope topic->tier router stream-runtime]
    :or {topic->tier (constantly :lossless)}}]
  (let [runtime-state (atom {:ws-url nil
                             :socket nil
                             :socket-id 0
                             :active-socket-id nil
                             :intentional-close? false
                             :retry-timer nil
                             :watchdog-timer nil})
        connection-state (atom {:status :disconnected
                                :attempt 0
                                :next-retry-at-ms nil
                                :last-close nil
                                :last-activity-at-ms nil
                                :now-ms nil
                                :online? true
                                :transport/state :disconnected
                                :transport/last-recv-at-ms nil
                                :transport/connected-at-ms nil
                                :transport/expected-traffic? false
                                :queue-size 0
                                :ws nil})]
    (runtime/start-runtime!
      {:config {:control-buffer-size 8
                :socket-event-buffer-size 32
                :ingress-decoded-buffer-size 32
                :market-buffer-size 16
                :lossless-buffer-size 16
                :outbound-intent-buffer-size 16
                :metrics-buffer-size 32
                :dead-letter-buffer-size 16
                :lossless-depth-alert-threshold 100
                :max-queue-size 100
                :watchdog-interval-ms 1000
                :stale-visible-ms 45000
                :stale-hidden-ms 180000
                :market-coalesce-window-ms 5}
       :parse-raw-envelope parse-raw-envelope
       :topic->tier topic->tier
       :router router
       :stream-runtime stream-runtime
       :runtime-state runtime-state
       :connection-state connection-state
       :desired-subscriptions (atom {})
       :outbound-queue (atom [])
       :transport (make-test-transport)
       :scheduler (make-test-scheduler)
       :clock (make-test-clock)
       :calculate-retry-delay-ms (fn [_ _ _ _] 10)})))

(deftest create-runtime-channels-contract-test
  (let [channels (runtime/create-runtime-channels {:mailbox-buffer-size 4
                                                   :effects-buffer-size 4
                                                   :metrics-buffer-size 4
                                                   :dead-letter-buffer-size 4})]
    (is (contains? channels :mailbox-ch))
    (is (contains? channels :effects-ch))
    (is (contains? channels :metrics-ch))
    (is (contains? channels :dead-letter-ch))))

(deftest handler-router-routes-by-topic-test
  (async done
    (let [calls (atom [])
          router (runtime/make-handler-router {:topic->tier (constantly :lossless)
                                               :config {:handler-buffer-size 8}})]
      (runtime/register-topic-handler! router "trades" #(swap! calls conj %))
      (runtime/route-domain-message! router (model/make-domain-message-envelope
                                              {:topic "trades"
                                               :tier :market
                                               :ts 1
                                               :source :test
                                               :socket-id 7
                                               :payload {:channel "trades" :data [1]}}))
      (js/setTimeout
        (fn []
          (is (= 1 (count @calls)))
          (is (= "trades" (:channel (first @calls))))
          (runtime/stop-router! router)
          (done))
        20))))

(deftest market-coalescing-invariant-test
  (async done
    (let [stream-runtime (atom nil)
          _ (reset-stream! stream-runtime)
          routed (atom [])
          router (reify runtime/IMessageRouter
                   (route-domain-message! [_ envelope]
                     (swap! routed conj envelope))
                   (register-topic-handler! [_ _ _] nil)
                   (stop-router! [_] nil))
          parse-raw-envelope (fn [{:keys [raw socket-id]}]
                               (let [msg (js->clj (js/JSON.parse raw) :keywordize-keys true)]
                                 {:ok (model/make-domain-message-envelope
                                        {:topic (:channel msg)
                                         :tier :market
                                         :ts (:seq msg)
                                         :source :test
                                         :socket-id socket-id
                                         :payload msg})}))
          rt (make-test-runtime {:parse-raw-envelope parse-raw-envelope
                                 :topic->tier (constantly :market)
                                 :router router
                                 :stream-runtime stream-runtime})]
      (runtime/publish-command! rt {:op :connection/connect
                                    :ws-url "wss://example.test/ws"})
      (runtime/publish-transport-event! rt {:event/type :socket/open
                                            :socket-id 1})
      (runtime/publish-transport-event! rt {:event/type :socket/message
                                            :socket-id 1
                                            :raw "{\"channel\":\"trades\",\"seq\":1,\"data\":[{\"coin\":\"BTC\"}]}"})
      (runtime/publish-transport-event! rt {:event/type :socket/message
                                            :socket-id 1
                                            :raw "{\"channel\":\"trades\",\"seq\":2,\"data\":[{\"coin\":\"BTC\"}]}"})
      (js/setTimeout
        (fn []
          (is (= 1 (count @routed)))
          (is (= 2 (get-in (first @routed) [:payload :seq])))
          (is (>= (get-in @stream-runtime [:metrics :market-coalesced]) 1))
          (is (= 1 (get-in @stream-runtime [:metrics :market-dispatched])))
          (runtime/stop-runtime! rt)
          (done))
        50))))

(deftest lossless-ordering-invariant-test
  (async done
    (let [stream-runtime (atom nil)
          _ (reset-stream! stream-runtime)
          routed (atom [])
          router (reify runtime/IMessageRouter
                   (route-domain-message! [_ envelope]
                     (swap! routed conj (get-in envelope [:payload :seq])))
                   (register-topic-handler! [_ _ _] nil)
                   (stop-router! [_] nil))
          parse-raw-envelope (fn [{:keys [raw socket-id]}]
                               (let [msg (js->clj (js/JSON.parse raw) :keywordize-keys true)]
                                 {:ok (model/make-domain-message-envelope
                                        {:topic (:channel msg)
                                         :tier :lossless
                                         :ts (:seq msg)
                                         :source :test
                                         :socket-id socket-id
                                         :payload msg})}))
          rt (make-test-runtime {:parse-raw-envelope parse-raw-envelope
                                 :topic->tier (constantly :lossless)
                                 :router router
                                 :stream-runtime stream-runtime})]
      (runtime/publish-command! rt {:op :connection/connect
                                    :ws-url "wss://example.test/ws"})
      (runtime/publish-transport-event! rt {:event/type :socket/open
                                            :socket-id 1})
      (runtime/publish-transport-event! rt {:event/type :socket/message
                                            :socket-id 1
                                            :raw "{\"channel\":\"userFills\",\"seq\":1,\"data\":[]}"})
      (runtime/publish-transport-event! rt {:event/type :socket/message
                                            :socket-id 1
                                            :raw "{\"channel\":\"userFills\",\"seq\":2,\"data\":[]}"})
      (js/setTimeout
        (fn []
          (is (= [1 2] @routed))
          (runtime/stop-runtime! rt)
          (done))
        30))))

(deftest health-tick-event-updates-stream-runtime-now-ms-test
  (async done
    (let [stream-runtime (atom nil)
          _ (reset-stream! stream-runtime)
          router (reify runtime/IMessageRouter
                   (route-domain-message! [_ _] nil)
                   (register-topic-handler! [_ _ _] nil)
                   (stop-router! [_] nil))
          rt (make-test-runtime {:parse-raw-envelope (fn [_] {:error (js/Error. "unused")})
                                 :topic->tier (constantly :lossless)
                                 :router router
                                 :stream-runtime stream-runtime})]
      (runtime/publish-command! rt {:op :connection/connect
                                    :ws-url "wss://example.test/ws"})
      (runtime/publish-transport-event! rt {:event/type :socket/open
                                            :socket-id 1
                                            :ts 100})
      (runtime/publish-transport-event! rt {:event/type :timer/health
                                            :ts 5000
                                            :now-ms 5000})
      (js/setTimeout
        (fn []
          (is (= 5000 (:now-ms @stream-runtime)))
          (runtime/stop-runtime! rt)
          (done))
        30))))
