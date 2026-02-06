(ns hyperopen.websocket.application.runtime-test
  (:require [cljs.core.async :as async]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [hyperopen.websocket.application.runtime :as runtime]
            [hyperopen.websocket.domain.model :as model]
            [hyperopen.websocket.infrastructure.transport :as infra]))

(defn- reset-stream! [stream]
  (reset! stream {:tier-depth {:market 0 :lossless 0}
                  :metrics {:market-coalesced 0
                            :market-dispatched 0
                            :lossless-dispatched 0
                            :ingress-parse-errors 0}
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

(deftest create-runtime-channels-contract-test
  (let [channels (runtime/create-runtime-channels {:control-buffer-size 2
                                                   :outbound-buffer-size 2
                                                   :ingress-raw-buffer-size 2
                                                   :ingress-decoded-buffer-size 2
                                                   :market-buffer-size 2
                                                   :lossless-buffer-size 2})]
    (testing "Runtime can be created without browser globals"
      (is (contains? channels :control-ch))
      (is (contains? channels :ingress-raw-ch))
      (is (contains? channels :lossless-tier-ch)))))

(deftest handler-router-routes-by-topic-test
  (let [calls (atom [])
        handlers (atom {"trades" #(swap! calls conj %)})
        router (runtime/make-handler-router handlers)]
    (runtime/route-domain-message! router (model/make-domain-message-envelope
                                            {:topic "trades"
                                             :tier :market
                                             :ts 1
                                             :source :test
                                             :socket-id 7
                                             :payload {:channel "trades" :data [1]}}))
    (testing "Router dispatches to handler by domain topic"
      (is (= 1 (count @calls)))
      (is (= "trades" (:channel (first @calls)))))))

(deftest market-coalescing-invariant-test
  (async done
    (let [stream-runtime (atom nil)
          _ (reset-stream! stream-runtime)
          routed (atom [])
          channels (runtime/create-runtime-channels {:control-buffer-size 8
                                                     :outbound-buffer-size 8
                                                     :ingress-raw-buffer-size 8
                                                     :ingress-decoded-buffer-size 8
                                                     :market-buffer-size 8
                                                     :lossless-buffer-size 8})
          scheduler (make-test-scheduler)
          router (reify runtime/IMessageRouter
                   (route-domain-message! [_ envelope]
                     (swap! routed conj envelope)))
          publish-control! (fn [command]
                             (runtime/safe-put! (:control-ch channels) command))
          parse-raw-envelope (fn [{:keys [raw socket-id]}]
                               (let [msg (js->clj (js/JSON.parse raw) :keywordize-keys true)]
                                 {:ok (model/make-domain-message-envelope
                                        {:topic (:channel msg)
                                         :tier :market
                                         :ts (:seq msg)
                                         :source :test
                                         :socket-id socket-id
                                         :payload msg})}))]
      (runtime/start-runtime-loops! {:channels channels
                                     :parse-raw-envelope parse-raw-envelope
                                     :topic->tier (constantly :market)
                                     :router router
                                     :config {:market-coalesce-window-ms 5
                                              :lossless-depth-alert-threshold 100}
                                     :stream-runtime stream-runtime
                                     :scheduler scheduler
                                     :publish-control! publish-control!
                                     :make-command (fn [op] {:op op :ts 0})
                                     :reconnect-if-needed! (fn [] nil)
                                     :drain-queued-messages! (fn [] [])
                                     :desired-subscriptions (atom {})
                                     :dispatch-outbound-message! (fn [_] true)
                                     :command-handlers {}})
      (runtime/safe-put! (:ingress-raw-ch channels) {:raw "{\"channel\":\"trades\",\"seq\":1,\"data\":[{\"coin\":\"BTC\"}]}" :socket-id 1})
      (runtime/safe-put! (:ingress-raw-ch channels) {:raw "{\"channel\":\"trades\",\"seq\":2,\"data\":[{\"coin\":\"BTC\"}]}" :socket-id 1})
      (js/setTimeout
        (fn []
          (is (= 1 (count @routed)))
          (is (= 2 (get-in (first @routed) [:payload :seq])))
          (is (>= (get-in @stream-runtime [:metrics :market-coalesced]) 1))
          (runtime/stop-runtime! channels)
          (done))
        40))))

(deftest lossless-ordering-invariant-test
  (async done
    (let [stream-runtime (atom nil)
          _ (reset-stream! stream-runtime)
          routed (atom [])
          channels (runtime/create-runtime-channels {:control-buffer-size 8
                                                     :outbound-buffer-size 8
                                                     :ingress-raw-buffer-size 8
                                                     :ingress-decoded-buffer-size 8
                                                     :market-buffer-size 8
                                                     :lossless-buffer-size 8})
          scheduler (make-test-scheduler)
          router (reify runtime/IMessageRouter
                   (route-domain-message! [_ envelope]
                     (swap! routed conj (get-in envelope [:payload :seq]))))
          parse-raw-envelope (fn [{:keys [raw socket-id]}]
                               (let [msg (js->clj (js/JSON.parse raw) :keywordize-keys true)]
                                 {:ok (model/make-domain-message-envelope
                                        {:topic (:channel msg)
                                         :tier :lossless
                                         :ts (:seq msg)
                                         :source :test
                                         :socket-id socket-id
                                         :payload msg})}))]
      (runtime/start-runtime-loops! {:channels channels
                                     :parse-raw-envelope parse-raw-envelope
                                     :topic->tier (constantly :lossless)
                                     :router router
                                     :config {:market-coalesce-window-ms 5
                                              :lossless-depth-alert-threshold 100}
                                     :stream-runtime stream-runtime
                                     :scheduler scheduler
                                     :publish-control! (fn [_] true)
                                     :make-command (fn [op] {:op op :ts 0})
                                     :reconnect-if-needed! (fn [] nil)
                                     :drain-queued-messages! (fn [] [])
                                     :desired-subscriptions (atom {})
                                     :dispatch-outbound-message! (fn [_] true)
                                     :command-handlers {}})
      (runtime/safe-put! (:ingress-raw-ch channels) {:raw "{\"channel\":\"userFills\",\"seq\":1,\"data\":[]}" :socket-id 1})
      (runtime/safe-put! (:ingress-raw-ch channels) {:raw "{\"channel\":\"userFills\",\"seq\":2,\"data\":[]}" :socket-id 1})
      (js/setTimeout
        (fn []
          (is (= [1 2] @routed))
          (runtime/stop-runtime! channels)
          (done))
        25))))

