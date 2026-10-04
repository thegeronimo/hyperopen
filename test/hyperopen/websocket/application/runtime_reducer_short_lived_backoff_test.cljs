(ns hyperopen.websocket.application.runtime-reducer-short-lived-backoff-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.application.runtime-reducer :as reducer]
            [hyperopen.websocket.domain.model :as model]))

(defn- run-cycles
  "Open then close socket after socket, each living `lived-ms`. Returns the
   final state and the retry attempt passed to the delay fn on each close."
  [lived-ms cycles]
  (let [attempts (atom [])
        deps {:calculate-retry-delay-ms (fn [attempt _ _ _]
                                          (swap! attempts conj attempt)
                                          500)}
        step (fn [state msg] (:state (reducer/step deps state msg)))
        state (assoc (reducer/initial-runtime-state {})
                     :ws-url "wss://example.test/ws")]
    (loop [state state
           n 0
           t 1000]
      (if (= n cycles)
        {:state state :attempts @attempts}
        (let [socket-id (inc (:socket-id state))
              opened (-> state
                         (assoc :socket-id socket-id :active-socket-id socket-id)
                         (step (model/make-runtime-msg :evt/socket-open t
                                                       {:socket-id socket-id :at-ms t})))
              closed (step opened (model/make-runtime-msg :evt/socket-close (+ t lived-ms)
                                                          {:socket-id socket-id
                                                           :code 1006
                                                           :at-ms (+ t lived-ms)}))]
          (recur (assoc closed :active-socket-id nil) (inc n) (+ t lived-ms 1000)))))))

(deftest short-lived-sockets-keep-backing-off-test
  (testing "a server that drops every fresh socket is retried with growing backoff"
    (let [{:keys [state attempts]} (run-cycles 350 4)]
      (is (= [2 3 4 5] attempts))
      (is (= 4 (:short-lived-streak state)))
      (is (= 0 (:attempt (:state (reducer/step {:calculate-retry-delay-ms (constantly 500)}
                                               (assoc state :active-socket-id 9)
                                               (model/make-runtime-msg :evt/socket-open 99999
                                                                       {:socket-id 9})))))
          "attempt still resets on open, as the TLA model requires")))
  (testing "a connection that stayed up restarts backoff from the base delay"
    (let [{:keys [state attempts]} (run-cycles 60000 3)]
      (is (= [1 1 1] attempts))
      (is (= 0 (:short-lived-streak state))))))
