(ns hyperopen.websocket.infrastructure.runtime-effects-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.infrastructure.runtime-effects :as runtime-effects]
            [hyperopen.websocket.infrastructure.transport :as infra]))

(defn- next-id! [counter prefix]
  (keyword (str prefix "-" (swap! counter inc))))

(defn- make-context []
  (let [dispatches (atom [])
        router-registrations (atom [])
        router-envelopes (atom [])
        timeout-callbacks (atom {})
        interval-callbacks (atom {})
        cleared-timeouts (atom [])
        cleared-intervals (atom [])
        listener-calls (atom [])
        sent-json (atom [])
        close-calls (atom [])
        created-sockets (atom [])
        timeout-counter (atom 0)
        interval-counter (atom 0)
        hidden-tab? (atom false)
        now-counter (atom 1000)
        parse-result (atom {:ok {:topic "decoded" :payload {:channel "decoded"}}})
        io-state (atom {:sockets {}
                        :timers {}
                        :active-socket-id nil
                        :lifecycle-installed? false
                        :lifecycle-handlers nil})
        window-object (js-obj)
        document-object (js-obj)
        navigator-object (js-obj "onLine" true)
        transport (infra/make-function-transport
                   (fn [_ws-url]
                     (let [socket (js-obj "readyState" infra/ws-ready-state-connecting)]
                       (set! (.-send socket)
                             (fn [payload]
                               (swap! sent-json conj payload)))
                       (set! (.-close socket)
                             (fn [code reason]
                               (swap! close-calls conj [code reason])))
                       (swap! created-sockets conj socket)
                       socket)))
        scheduler (infra/make-function-scheduler
                   {:schedule-timeout-fn (fn [f _ms]
                                           (let [id (next-id! timeout-counter "timeout")]
                                             (swap! timeout-callbacks assoc id f)
                                             id))
                    :clear-timeout-fn (fn [id]
                                        (swap! cleared-timeouts conj id))
                    :schedule-interval-fn (fn [f _ms]
                                            (let [id (next-id! interval-counter "interval")]
                                              (swap! interval-callbacks assoc id f)
                                              id))
                    :clear-interval-fn (fn [id]
                                         (swap! cleared-intervals conj id))
                    :window-object-fn (fn [] window-object)
                    :document-object-fn (fn [] document-object)
                    :navigator-object-fn (fn [] navigator-object)
                    :add-event-listener-fn (fn [target event-name handler]
                                             (swap! listener-calls conj [target event-name handler]))})
        clock (infra/make-function-clock
               (fn []
                 (let [now-ms @now-counter]
                   (swap! now-counter inc)
                   now-ms))
               (constantly 0.5))
        runtime-view-atom (atom nil)
        context {:transport transport
                 :scheduler scheduler
                 :clock clock
                 :io-state io-state
                 :parse-raw-envelope (fn [_]
                                       @parse-result)
                 :hydrate-envelope identity
                 :dispatch! (fn [msg]
                              (swap! dispatches conj msg))
                 :register-router-handler! (fn [topic handler-fn]
                                             (swap! router-registrations conj [topic handler-fn]))
                 :dispatch-envelope! (fn [envelope]
                                       (swap! router-envelopes conj envelope))
                 :runtime-view-atom runtime-view-atom}]
    {:ctx context
     :io-state io-state
     :dispatches dispatches
     :router-registrations router-registrations
     :router-envelopes router-envelopes
     :timeout-callbacks timeout-callbacks
     :interval-callbacks interval-callbacks
     :cleared-timeouts cleared-timeouts
     :cleared-intervals cleared-intervals
     :listener-calls listener-calls
     :sent-json sent-json
     :close-calls close-calls
     :created-sockets created-sockets
     :hidden-tab? hidden-tab?
     :runtime-view-atom runtime-view-atom
     :parse-result parse-result}))

(deftest interpret-effect-socket-connect-and-handler-dispatch-branches-test
  (let [{:keys [ctx io-state dispatches]} (make-context)]
    (runtime-effects/interpret-effect! ctx {:fx/type :fx/socket-connect
                                            :ws-url "wss://example.test/ws"
                                            :socket-id :primary})
    (let [socket (get-in @io-state [:sockets :primary])]
      (is (some? socket))
      (is (= :primary (:active-socket-id @io-state)))
      ((.-onopen socket) #js {})
      ((.-onmessage socket) #js {:data "{\"channel\":\"trades\"}"})
      ((.-onclose socket) #js {:code 1006 :reason "abnormal" :wasClean false})
      ((.-onclose socket) #js {})
      ((.-onerror socket) #js {:message "boom"})
      (is (= [:evt/socket-open
              :evt/socket-message
              :evt/socket-close
              :evt/socket-close
              :evt/socket-error]
             (mapv :msg/type @dispatches)))
      (is (= 1006 (:code (nth @dispatches 2))))
      (is (= 0 (:code (nth @dispatches 3))))
      (is (= "" (:reason (nth @dispatches 3))))
      (is (false? (:was-clean? (nth @dispatches 2)))))))

(deftest interpret-effect-socket-send-close-and-detach-guard-branches-test
  (let [{:keys [ctx io-state sent-json close-calls dispatches]} (make-context)]
    (runtime-effects/interpret-effect! ctx {:fx/type :fx/socket-connect
                                            :ws-url "wss://example.test/ws"
                                            :socket-id :primary})
    (let [socket (get-in @io-state [:sockets :primary])]
      (testing "socket send only writes on open sockets"
        (set! (.-readyState socket) infra/ws-ready-state-open)
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/socket-send
                                                :socket-id :primary
                                                :data {:kind :ping :n 1}})
        (is (= {:kind "ping" :n 1}
               (js->clj (js/JSON.parse (first @sent-json)) :keywordize-keys true)))
        (set! (.-readyState socket) infra/ws-ready-state-connecting)
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/socket-send
                                                :socket-id :primary
                                                :data {:kind :skip}})
        (is (= 1 (count @sent-json))))
      (testing "socket send catches transport send failures"
        (set! (.-readyState socket) infra/ws-ready-state-open)
        (set! (.-send socket)
              (fn [_]
                (throw (js/Error. "send-failed"))))
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/socket-send
                                                :socket-id :primary
                                                :data {:kind :will-fail}})
        (is (= :evt/socket-error (:msg/type (last @dispatches)))))
      (testing "socket send is a no-op for missing sockets"
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/socket-send
                                                :socket-id :missing
                                                :data {:kind :none}})
        (is (= :evt/socket-error (:msg/type (last @dispatches)))))
      (testing "socket close uses defaults and swallows close exceptions"
        (set! (.-close socket)
              (fn [code reason]
                (swap! close-calls conj [code reason])))
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/socket-close
                                                :socket-id :primary
                                                :code 3002
                                                :reason "done"})
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/socket-close
                                                :socket-id :primary})
        (is (= [[3002 "done"]
                [1000 ""]]
               @close-calls))
        (set! (.-close socket)
              (fn [_ _]
                (throw (js/Error. "close-failed"))))
        (is (nil? (runtime-effects/interpret-effect! ctx {:fx/type :fx/socket-close
                                                           :socket-id :primary})))
        (is (nil? (runtime-effects/interpret-effect! ctx {:fx/type :fx/socket-close
                                                           :socket-id :missing}))))
      (testing "detach handlers clears callbacks for existing sockets and no-ops when missing"
        (set! (.-onopen socket) (fn [_] nil))
        (set! (.-onmessage socket) (fn [_] nil))
        (set! (.-onclose socket) (fn [_] nil))
        (set! (.-onerror socket) (fn [_] nil))
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/socket-detach-handlers
                                                :socket-id :primary})
        (is (nil? (.-onopen socket)))
        (is (nil? (.-onmessage socket)))
        (is (nil? (.-onclose socket)))
        (is (nil? (.-onerror socket)))
        (is (nil? (runtime-effects/interpret-effect! ctx {:fx/type :fx/socket-detach-handlers
                                                           :socket-id :missing}))))))
  )

(deftest interpret-effect-timer-and-lifecycle-branches-test
  (let [{:keys [ctx io-state timeout-callbacks interval-callbacks cleared-timeouts cleared-intervals listener-calls dispatches hidden-tab?]}
        (make-context)]
    (testing "set-timeout clears existing timer, stores new timer, and dispatches on fire"
      (swap! io-state assoc-in [:timers :retry] :old-timeout)
      (runtime-effects/interpret-effect! ctx {:fx/type :fx/timer-set-timeout
                                              :timer-key :retry
                                              :ms 5
                                              :msg {:msg/type :evt/timer-retry-fired}})
      (is (= [:old-timeout] @cleared-timeouts))
      (let [timer-id (get-in @io-state [:timers :retry])]
        ((get @timeout-callbacks timer-id))
        (is (nil? (get-in @io-state [:timers :retry])))
        (is (= :evt/timer-retry-fired (:msg/type (last @dispatches))))))
    (testing "set-interval clears existing timer and dispatches repeatedly"
      (swap! io-state assoc-in [:timers :health] :old-interval)
      (runtime-effects/interpret-effect! ctx {:fx/type :fx/timer-set-interval
                                              :timer-key :health
                                              :ms 10
                                              :msg {:msg/type :evt/timer-health-tick}})
      (is (= [:old-interval] @cleared-intervals))
      (let [timer-id (get-in @io-state [:timers :health])]
        ((get @interval-callbacks timer-id))
        (is (= :evt/timer-health-tick (:msg/type (last @dispatches))))
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/timer-clear-interval
                                                :timer-key :health})
        (is (nil? (get-in @io-state [:timers :health])))))
    (testing "clear-timeout no-ops cleanly when timer key is absent"
      (is (nil? (runtime-effects/interpret-effect! ctx {:fx/type :fx/timer-clear-timeout
                                                         :timer-key :missing}))))
    (testing "lifecycle listeners install once and visibility dispatch depends on hidden-tab?"
      (with-redefs [infra/hidden-tab?* (fn [_]
                                         @hidden-tab?)]
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/lifecycle-install-listeners})
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/lifecycle-install-listeners})
        (is (true? (:lifecycle-installed? @io-state)))
        (is (= 4 (count @listener-calls)))
        (let [{:keys [focus online offline visibility]} (:lifecycle-handlers @io-state)
              dispatch-count-before (count @dispatches)]
          (focus #js {})
          (online #js {})
          (offline #js {})
          (reset! hidden-tab? true)
          (visibility #js {})
          (reset! hidden-tab? false)
          (visibility #js {})
          (is (= [:evt/lifecycle-focus
                  :evt/lifecycle-online
                  :evt/lifecycle-offline
                  :evt/lifecycle-hidden
                  :evt/lifecycle-visible]
                 (->> @dispatches
                      (drop dispatch-count-before)
                      (mapv :msg/type)))))))))

(deftest interpret-effect-router-parse-projection-and-telemetry-branches-test
  (let [{:keys [ctx io-state router-registrations router-envelopes dispatches parse-result runtime-view-atom]}
        (make-context)]
    (testing "router registration and envelope dispatch delegate to injected collaborators"
      (runtime-effects/interpret-effect! ctx {:fx/type :fx/router-register-handler
                                              :topic "trades"
                                              :handler-fn (fn [_] nil)})
      (runtime-effects/interpret-effect! ctx {:fx/type :fx/router-dispatch-envelope
                                              :envelope {:topic "trades" :payload {:channel "trades"}}})
      (is (= 1 (count @router-registrations)))
      (is (= [{:topic "trades" :payload {:channel "trades"}}]
             @router-envelopes)))
    (testing "router dispatch hydrates envelope when collaborator is provided"
      (runtime-effects/interpret-effect! (assoc ctx :hydrate-envelope (fn [envelope]
                                                                         (assoc-in envelope [:payload :hydrated?] true)))
                                         {:fx/type :fx/router-dispatch-envelope
                                          :envelope {:topic "trades" :payload {:channel "trades"}}})
      (is (= true (get-in (last @router-envelopes) [:payload :hydrated?]))))
    (testing "parse-raw-message dispatches decoded envelopes and parse errors"
      (reset! parse-result {:ok {:topic "decoded" :payload {:channel "decoded"}}})
      (runtime-effects/interpret-effect! ctx {:fx/type :fx/parse-raw-message
                                              :raw "{}"
                                              :socket-id :primary
                                              :recv-at-ms 222})
      (reset! parse-result {:error (js/Error. "parse-failed")})
      (runtime-effects/interpret-effect! ctx {:fx/type :fx/parse-raw-message
                                              :raw "bad-json"
                                              :socket-id :primary})
      (is (= [:evt/decoded-envelope :evt/parse-error]
             (->> @dispatches
                  (take-last 2)
                  (mapv :msg/type))))
      (is (= :primary (:socket-id (nth @dispatches (- (count @dispatches) 2)))))
      (is (= 222 (:ts (nth @dispatches (- (count @dispatches) 2)))))
      (is (= :primary (:socket-id (last @dispatches))))
      (is (number? (:ts (last @dispatches)))))
    (testing "projection effects refresh public runtime atoms"
      (let [socket (js-obj "readyState" infra/ws-ready-state-open)
            runtime-view-watch-count (atom 0)]
        (add-watch runtime-view-atom ::runtime-view-watch
                   (fn [_ _ _ _]
                     (swap! runtime-view-watch-count inc)))
        (swap! io-state assoc-in [:sockets :active] socket)
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/project-runtime-view
                                                :runtime-view {:connection {:status :connected}
                                                               :active-socket-id :active
                                                               :stream {:metrics {:market-coalesced 2}
                                                                        :tier-depth {:market 1}
                                                                        :market-coalesce {:pending {}}
                                                                        :now-ms 999
                                                                        :health-fingerprint {:transport/state :connected}
                                                                        :streams {:trades {:status :healthy}}
                                                                        :transport {:state :connected}}}
                                                :projection-fingerprint {:connection {:status :connected
                                                                                      :active-socket-id :active}
                                                                         :stream {:metrics {:market-coalesced 2}
                                                                                  :health-fingerprint {:transport/state :connected}}}})
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/project-runtime-view
                                                :runtime-view {:connection {:status :connected}
                                                               :active-socket-id :active
                                                               :stream {:metrics {:market-coalesced 2}
                                                                        :tier-depth {:market 1}
                                                                        :market-coalesce {:pending {}}
                                                                        :now-ms 1000
                                                                        :health-fingerprint {:transport/state :connected}
                                                                        :streams {:trades {:status :healthy}}
                                                                        :transport {:state :connected}}}
                                                :projection-fingerprint {:connection {:status :connected
                                                                                      :active-socket-id :active}
                                                                         :stream {:metrics {:market-coalesced 2}
                                                                                  :health-fingerprint {:transport/state :connected}}}})
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/project-runtime-view
                                                :runtime-view {:connection {:status :reconnecting}
                                                               :active-socket-id :active
                                                               :stream {:metrics {:market-coalesced 2}
                                                                        :tier-depth {:market 1}
                                                                        :market-coalesce {:pending {}}
                                                                        :now-ms 1000
                                                                        :health-fingerprint {:transport/state :connected}
                                                                        :streams {:trades {:status :healthy}}
                                                                        :transport {:state :connected}}}
                                                :projection-fingerprint {:connection {:status :reconnecting
                                                                                      :active-socket-id :active}
                                                                         :stream {:metrics {:market-coalesced 2}
                                                                                  :health-fingerprint {:transport/state :connected}}}})
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/project-runtime-view
                                                :runtime-view {:connection {:status :reconnecting}
                                                               :active-socket-id :active
                                                               :stream {:metrics {:market-coalesced 3}
                                                                        :tier-depth {:market 1}
                                                                        :market-coalesce {:pending {}}
                                                                        :now-ms 1001
                                                                        :health-fingerprint {:transport/state :connected}
                                                                        :streams {:trades {:status :healthy}}
                                                                        :transport {:state :connected}}}
                                                :projection-fingerprint {:connection {:status :reconnecting
                                                                                      :active-socket-id :active}
                                                                         :stream {:metrics {:market-coalesced 3}
                                                                                  :health-fingerprint {:transport/state :connected}}}})
        (is (= 3 @runtime-view-watch-count))
        (is (= {:connection {:status :reconnecting
                             :ws socket}
                :active-socket-id :active
                :stream {:metrics {:market-coalesced 3}
                         :tier-depth {:market 1}
                         :market-coalesce {:pending {}}
                         :now-ms 1001
                         :health-fingerprint {:transport/state :connected}
                         :streams {:trades {:status :healthy}}
                         :transport {:state :connected}}}
               @runtime-view-atom))
        (remove-watch runtime-view-atom ::runtime-view-watch)))
    (testing "log and dead-letter effects emit telemetry with default level and formatted error"
      (with-redefs [telemetry/dev-enabled? (constantly true)]
        (telemetry/clear-events!)
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/log
                                                :message "runtime-log"
                                                :error (js/Error. "oops")})
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/log
                                                :level :warn
                                                :message "runtime-warn"})
        (runtime-effects/interpret-effect! ctx {:fx/type :fx/dead-letter
                                                :reason :unknown})
        (let [events (telemetry/events)]
          (is (= :websocket/runtime-log (:event (nth events 0))))
          (is (= :info (:level (nth events 0))))
          (is (string? (:error (nth events 0))))
          (is (= :warn (:level (nth events 1))))
          (is (= :websocket/dead-letter (:event (nth events 2)))))
        (telemetry/clear-events!)))
    (testing "unknown effects return nil"
      (is (nil? (runtime-effects/interpret-effect! ctx {:fx/type :fx/unknown}))))))
