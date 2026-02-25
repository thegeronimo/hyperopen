(ns hyperopen.core-bootstrap.websocket-diagnostics-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.core.compat :as core]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.websocket.client :as ws-client]))

(deftest websocket-diagnostics-ui-actions-emit-deterministic-effects-test
  (is (= [[:effects/save-many [[[:websocket-ui :diagnostics-open?] true]
                               [[:websocket-ui :reveal-sensitive?] false]
                               [[:websocket-ui :copy-status] nil]]]
          [:effects/refresh-websocket-health]]
         (core/toggle-ws-diagnostics {:websocket-ui {:diagnostics-open? false}})))
  (is (= [[:effects/save-many [[[:websocket-ui :diagnostics-open?] false]
                               [[:websocket-ui :reveal-sensitive?] false]
                               [[:websocket-ui :copy-status] nil]]]]
         (core/toggle-ws-diagnostics {:websocket-ui {:diagnostics-open? true}})))
  (is (= [[:effects/save-many [[[:websocket-ui :diagnostics-open?] false]
                               [[:websocket-ui :reveal-sensitive?] false]
                               [[:websocket-ui :copy-status] nil]]]]
         (core/close-ws-diagnostics {})))
  (is (= [[:effects/save [:websocket-ui :reveal-sensitive?] false]]
         (core/toggle-ws-diagnostics-sensitive {:websocket-ui {:reveal-sensitive? true}})))
  (is (= [[:effects/confirm-ws-diagnostics-reveal]]
         (core/toggle-ws-diagnostics-sensitive {:websocket-ui {:reveal-sensitive? false}})))
  (is (= [[:effects/save-many [[[:websocket-ui :diagnostics-open?] false]
                               [[:websocket-ui :reveal-sensitive?] false]
                               [[:websocket-ui :copy-status] nil]]]
          [:effects/save [:websocket-ui :reconnect-cooldown-until-ms] 7000]
          [:effects/reconnect-websocket]]
         (core/ws-diagnostics-reconnect-now {:websocket-ui {:diagnostics-open? true}
                                             :websocket {:health {:generated-at-ms 2000
                                                                  :transport {:state :connected}}}})))
  (is (= [[:effects/copy-websocket-diagnostics]]
         (core/ws-diagnostics-copy {})))
  (is (= [[:effects/save [:websocket-ui :show-surface-freshness-cues?] true]]
         (core/set-show-surface-freshness-cues {} true)))
  (is (= [[:effects/save [:websocket-ui :show-surface-freshness-cues?] false]]
         (core/set-show-surface-freshness-cues {} nil)))
  (is (= [[:effects/save [:websocket-ui :show-surface-freshness-cues?] true]]
         (core/toggle-show-surface-freshness-cues
           {:websocket-ui {:show-surface-freshness-cues? false}})))
  (is (= [[:effects/save [:websocket-ui :show-surface-freshness-cues?] false]]
         (core/toggle-show-surface-freshness-cues
           {:websocket-ui {:show-surface-freshness-cues? true}})))
  (is (= [[:effects/ws-reset-subscriptions {:group :market_data :source :manual}]]
         (core/ws-diagnostics-reset-market-subscriptions
           {:websocket-ui {:reset-in-progress? false}
            :websocket {:health {:generated-at-ms 2000
                                 :transport {:state :connected}}}})))
  (is (= [[:effects/ws-reset-subscriptions {:group :orders_oms :source :manual}]]
         (core/ws-diagnostics-reset-orders-subscriptions
           {:websocket-ui {:reset-in-progress? false}
            :websocket {:health {:generated-at-ms 2000
                                 :transport {:state :connected}}}})))
  (is (= [[:effects/ws-reset-subscriptions {:group :all :source :manual}]]
         (core/ws-diagnostics-reset-all-subscriptions
           {:websocket-ui {:reset-in-progress? false}
            :websocket {:health {:generated-at-ms 2000
                                 :transport {:state :connected}}}}))))

(deftest websocket-diagnostics-reconnect-guard-prevents-duplicate-reconnect-test
  (is (= []
         (core/ws-diagnostics-reconnect-now {:websocket-ui {:reconnect-cooldown-until-ms 9000}
                                             :websocket {:health {:generated-at-ms 5000
                                                                  :transport {:state :connected}}}})))
  (is (= []
         (core/ws-diagnostics-reconnect-now {:websocket-ui {:reconnect-cooldown-until-ms nil}
                                             :websocket {:health {:generated-at-ms 5000
                                                                  :transport {:state :reconnecting}}}}))))

(deftest websocket-diagnostics-reset-guard-prevents-duplicate-or-unsafe-reset-test
  (is (= []
         (core/ws-diagnostics-reset-market-subscriptions
           {:websocket-ui {:reset-in-progress? true}
            :websocket {:health {:generated-at-ms 5000
                                 :transport {:state :connected}}}})))
  (is (= []
         (core/ws-diagnostics-reset-orders-subscriptions
           {:websocket-ui {:reset-cooldown-until-ms 9000}
            :websocket {:health {:generated-at-ms 5000
                                 :transport {:state :connected}}}})))
  (is (= []
         (core/ws-diagnostics-reset-all-subscriptions
           {:websocket-ui {:reset-cooldown-until-ms nil}
            :websocket {:health {:generated-at-ms 5000
                                 :transport {:state :reconnecting}}}}))))


(deftest ws-reset-subscriptions-effect-targets-group-and-avoids-duplicates-test
  (let [address "0x1234567890abcdef1234567890abcdef12345678"
        sends (atom [])
        live-health {:generated-at-ms 1700000000000
                     :transport {:state :connected
                                 :freshness :live}
                     :streams {["trades" "BTC" nil nil nil]
                               {:group :market_data
                                :topic "trades"
                                :subscribed? true
                                :descriptor {:type "trades" :coin "BTC"}}
                               ["dup-trades" "BTC" nil nil nil]
                               {:group :market_data
                                :topic "trades"
                                :subscribed? true
                                :descriptor {:type "trades" :coin "BTC"}}
                               ["openOrders" nil address nil nil]
                               {:group :orders_oms
                                :topic "openOrders"
                                :subscribed? true
                                :descriptor {:type "openOrders" :user address}}}}
        store (atom {:websocket {:health {:generated-at-ms 1
                                          :transport {:state :connected}
                                          :streams {}}}
                     :websocket-ui {:reset-in-progress? false
                                    :reset-cooldown-until-ms nil
                                    :reset-counts {:market_data 0 :orders_oms 0 :all 0}
                                    :diagnostics-timeline []}})]
    (with-redefs [ws-client/get-health-snapshot (fn [] live-health)
                  ws-client/send-message! (fn [payload]
                                            (swap! sends conj payload)
                                            true)]
      (core/ws-reset-subscriptions nil store {:group :market_data :source :manual})
      (is (= [{:method "unsubscribe" :subscription {:type "trades" :coin "BTC"}}
              {:method "subscribe" :subscription {:type "trades" :coin "BTC"}}]
             @sends))
      (is (false? (get-in @store [:websocket-ui :reset-in-progress?])))
      (is (= 1 (get-in @store [:websocket-ui :reset-counts :market_data])))
      (is (number? (get-in @store [:websocket-ui :reset-cooldown-until-ms])))
      (is (= :reset-market
             (get-in @store [:websocket-ui :diagnostics-timeline 0 :event]))))))

(deftest ws-reset-subscriptions-effect-uses-current-snapshot-and-noops-when-unsafe-test
  (let [sends (atom [])
        stale-health {:generated-at-ms 1000
                      :transport {:state :connected}
                      :streams {["trades" "ETH" nil nil nil]
                                {:group :market_data
                                 :topic "trades"
                                 :subscribed? true
                                 :descriptor {:type "trades" :coin "ETH"}}}}
        reconnecting-health {:generated-at-ms 2000
                             :transport {:state :reconnecting}
                             :streams {["trades" "BTC" nil nil nil]
                                       {:group :market_data
                                        :topic "trades"
                                        :subscribed? true
                                        :descriptor {:type "trades" :coin "BTC"}}}}
        store (atom {:websocket {:health stale-health}
                     :websocket-ui {:reset-in-progress? false
                                    :reset-cooldown-until-ms nil
                                    :reset-counts {:market_data 0 :orders_oms 0 :all 0}
                                    :diagnostics-timeline []}})]
    (with-redefs [ws-client/get-health-snapshot (fn [] reconnecting-health)
                  ws-client/send-message! (fn [payload]
                                            (swap! sends conj payload)
                                            true)]
      (core/ws-reset-subscriptions nil store {:group :market_data :source :manual})
      (is (empty? @sends))
      (is (= 0 (get-in @store [:websocket-ui :reset-counts :market_data]))))))

(deftest copy-websocket-diagnostics-redacts-sensitive-fields-test
  (async done
    (let [address "0x1234567890abcdef1234567890abcdef12345678"
          written (atom nil)
          navigator-prop "navigator"
          original-navigator-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis navigator-prop)
          fake-clipboard #js {:writeText (fn [payload]
                                           (reset! written payload)
                                           (js/Promise.resolve true))}
          fake-navigator #js {:clipboard fake-clipboard}
          health {:generated-at-ms 1700000000000
                  :transport {:state :connected
                              :freshness :live
                              :last-close {:code 1000
                                           :reason "ok"}}
                  :groups {:orders_oms {:worst-status :offline}
                           :market_data {:worst-status :idle}
                           :account {:worst-status :n-a}}
                  :streams {["openOrders" nil address nil nil]
                            {:group :orders_oms
                             :topic "openOrders"
                             :status :offline
                             :last-payload-at-ms 1699999999000
                             :stale-threshold-ms nil
                             :descriptor {:type "openOrders"
                                          :user address
                                          :token "secret-token"
                                          :meta {:authorization "Bearer token"
                                                 :entries [{:address address}]}}}}}
          store (atom {:websocket {:health health}
                       :websocket-ui {:copy-status nil}})]
      (js/Object.defineProperty js/globalThis navigator-prop
                                #js {:value fake-navigator
                                     :configurable true})
      (core/copy-websocket-diagnostics nil store)
      (js/setTimeout
        (fn []
          (try
            (is (string? @written))
            (is (not (str/includes? @written address)))
            (is (str/includes? @written "<redacted>"))
            (is (= :success (get-in @store [:websocket-ui :copy-status :kind])))
            (is (= "Copied (redacted)"
                   (get-in @store [:websocket-ui :copy-status :message])))
            (let [decoded (js->clj (js/JSON.parse @written) :keywordize-keys true)]
              (is (= runtime-state/app-version (get-in decoded [:app :version])))
              (is (map? (:counters decoded)))
              (is (= "<redacted>" (get-in decoded [:streams 0 :descriptor :user])))
              (is (= "<redacted>" (get-in decoded [:streams 0 :descriptor :token])))
              (is (= "<redacted>" (get-in decoded [:streams 0 :descriptor :meta :authorization])))
              (is (= "<redacted>" (get-in decoded [:streams 0 :descriptor :meta :entries 0 :address]))))
            (finally
              (if original-navigator-descriptor
                (js/Object.defineProperty js/globalThis navigator-prop original-navigator-descriptor)
                (js/Reflect.deleteProperty js/globalThis navigator-prop))
              (done))))
        0))))

(deftest copy-websocket-diagnostics-fallback-status-when-clipboard-unavailable-test
  (let [navigator-prop "navigator"
        original-navigator-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis navigator-prop)
        store (atom {:websocket {:health {:generated-at-ms 1700000000000
                                          :transport {:state :connected
                                                      :freshness :live}
                                          :groups {:orders_oms {:worst-status :idle}
                                                   :market_data {:worst-status :live}
                                                   :account {:worst-status :n-a}}
                                          :streams {["openOrders" nil "0x1234567890abcdef1234567890abcdef12345678" nil nil]
                                                    {:group :orders_oms
                                                     :topic "openOrders"
                                                     :status :n-a
                                                     :descriptor {:type "openOrders"
                                                                  :user "0x1234567890abcdef1234567890abcdef12345678"}}}}}
                     :websocket-ui {:copy-status nil}})]
    (js/Object.defineProperty js/globalThis navigator-prop
                              #js {:value #js {}
                                   :configurable true})
    (try
      (core/copy-websocket-diagnostics nil store)
      (let [status (get-in @store [:websocket-ui :copy-status])]
        (is (= :error (:kind status)))
        (is (str/includes? (:message status) "Couldn't access clipboard"))
        (is (string? (:fallback-json status)))
        (is (str/includes? (:fallback-json status) "<redacted>")))
      (finally
        (if original-navigator-descriptor
          (js/Object.defineProperty js/globalThis navigator-prop original-navigator-descriptor)
          (js/Reflect.deleteProperty js/globalThis navigator-prop))))))

(deftest sync-websocket-health-fingerprint-updates-when-time-bucket-advances-test
  (async done
    (let [original-runtime-view @ws-client/runtime-view
          original-health-projection-state @ws-client/websocket-health-projection-state
          store (atom {:websocket {:health {}}
                       :websocket-ui {:diagnostics-open? false}})]
      (reset! ws-client/runtime-view
              {:active-socket-id nil
               :connection {:status :connected
                            :attempt 0
                            :next-retry-at-ms nil
                            :last-close nil
                            :last-activity-at-ms 100
                            :now-ms 1000
                            :online? true
                            :transport/state :connected
                            :transport/last-recv-at-ms 900
                            :transport/connected-at-ms 900
                            :transport/expected-traffic? false
                            :transport/freshness :live
                            :queue-size 0
                            :ws nil}
               :stream {:tier-depth {:market 0 :lossless 0}
                        :metrics {:market-coalesced 0
                                  :market-dispatched 0
                                  :lossless-dispatched 0
                                  :ingress-parse-errors 0}
                        :now-ms 1000
                        :streams {}
                        :transport {:state :connected
                                    :online? true
                                    :last-recv-at-ms 900
                                    :connected-at-ms 900
                                    :expected-traffic? false
                                    :freshness :live
                                    :attempt 0
                                    :last-close nil}
                        :market-coalesce {:pending {}
                                          :timer nil}}})
      (reset! ws-client/websocket-health-projection-state {:fingerprint nil
                                                           :writes 0})
      (core/sync-websocket-health! store :force? true)
      (js/setTimeout
        (fn []
          (is (= 1000 (get-in @store [:websocket :health :generated-at-ms])))
          (is (= 1 (:writes @ws-client/websocket-health-projection-state)))
          (swap! ws-client/runtime-view assoc-in [:stream :now-ms] 2000)
          (core/sync-websocket-health! store)
          (js/setTimeout
            (fn []
              (is (= 2000 (get-in @store [:websocket :health :generated-at-ms])))
              (is (= 2 (:writes @ws-client/websocket-health-projection-state)))
              (swap! ws-client/runtime-view assoc-in [:connection :transport/freshness] :delayed)
              (core/sync-websocket-health! store)
              (js/setTimeout
                (fn []
                  (try
                    (is (= 3 (:writes @ws-client/websocket-health-projection-state)))
                    (finally
                      (reset! ws-client/runtime-view original-runtime-view)
                      (reset! ws-client/websocket-health-projection-state original-health-projection-state)
                      (done))))
                0))
            0))
        0))))

(deftest sync-websocket-health-auto-recover-is-flagged-and-cooldown-protected-test
  (let [dispatches (atom [])
        flag-prop "ENABLE_WS_AUTO_RECOVER"
        original-flag-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis flag-prop)
        health {:generated-at-ms 1700000000000
                :transport {:state :connected
                            :freshness :live
                            :expected-traffic? true
                            :attempt 0}
                :groups {:orders_oms {:worst-status :idle}
                         :market_data {:worst-status :delayed}
                         :account {:worst-status :idle}}
                :streams {["l2Book" "BTC" nil nil nil]
                          {:group :market_data
                           :topic "l2Book"
                           :status :delayed
                           :subscribed? true
                           :last-payload-at-ms (- 1700000000000 45000)
                           :stale-threshold-ms 5000
                           :descriptor {:type "l2Book" :coin "BTC"}}}}
        store (atom {:websocket {:health {}}
                     :websocket-ui {:reset-in-progress? false
                                    :auto-recover-cooldown-until-ms nil
                                    :auto-recover-count 0
                                    :diagnostics-timeline []}})]
    (reset! ws-client/websocket-health-projection-state {:fingerprint nil
                                                         :writes 0})
    (js/Object.defineProperty js/globalThis flag-prop
                              #js {:value true
                                   :configurable true})
    (try
      (with-redefs [ws-client/get-health-snapshot (fn [] health)
                    nxr/dispatch (fn [_ _ effects]
                                   (swap! dispatches conj effects))]
        (core/sync-websocket-health! store)
        (core/sync-websocket-health! store)
        (is (= 1 (count @dispatches)))
        (is (= [[:actions/ws-diagnostics-reset-market-subscriptions :auto-recover]]
               (first @dispatches)))
        (is (= 1 (get-in @store [:websocket-ui :auto-recover-count])))
        (is (number? (get-in @store [:websocket-ui :auto-recover-cooldown-until-ms]))))
      (finally
        (if original-flag-descriptor
          (js/Object.defineProperty js/globalThis flag-prop original-flag-descriptor)
          (js/Reflect.deleteProperty js/globalThis flag-prop))))))

(deftest sync-websocket-health-auto-recover-skips-unsupported-states-test
  (let [dispatches (atom [])
        flag-prop "ENABLE_WS_AUTO_RECOVER"
        original-flag-descriptor (js/Object.getOwnPropertyDescriptor js/globalThis flag-prop)
        offline-health {:generated-at-ms 1700000000000
                        :transport {:state :disconnected
                                    :freshness :offline
                                    :expected-traffic? true}
                        :groups {:orders_oms {:worst-status :idle}
                                 :market_data {:worst-status :offline}
                                 :account {:worst-status :idle}}
                        :streams {["l2Book" "BTC" nil nil nil]
                                  {:group :market_data
                                   :topic "l2Book"
                                   :status :delayed
                                   :subscribed? true
                                   :last-payload-at-ms (- 1700000000000 45000)
                                   :stale-threshold-ms 5000
                                   :descriptor {:type "l2Book" :coin "BTC"}}}}
        event-driven-health {:generated-at-ms 1700000001000
                             :transport {:state :connected
                                         :freshness :live
                                         :expected-traffic? false}
                             :groups {:orders_oms {:worst-status :n-a}
                                      :market_data {:worst-status :idle}
                                      :account {:worst-status :n-a}}
                             :streams {["openOrders" nil "0xabc" nil nil]
                                       {:group :orders_oms
                                        :topic "openOrders"
                                        :status :n-a
                                        :subscribed? true
                                        :last-payload-at-ms 1700000000500
                                        :stale-threshold-ms nil
                                        :descriptor {:type "openOrders" :user "0xabc"}}}}
        store (atom {:websocket {:health {}}
                     :websocket-ui {:reset-in-progress? false
                                    :auto-recover-cooldown-until-ms nil
                                    :auto-recover-count 0
                                    :diagnostics-timeline []}})]
    (reset! ws-client/websocket-health-projection-state {:fingerprint nil
                                                         :writes 0})
    (js/Object.defineProperty js/globalThis flag-prop
                              #js {:value true
                                   :configurable true})
    (try
      (with-redefs [nxr/dispatch (fn [_ _ effects]
                                   (swap! dispatches conj effects))]
        (with-redefs [ws-client/get-health-snapshot (fn [] offline-health)]
          (core/sync-websocket-health! store))
        (with-redefs [ws-client/get-health-snapshot (fn [] event-driven-health)]
          (core/sync-websocket-health! store)))
      (is (empty? @dispatches))
      (is (= 0 (get-in @store [:websocket-ui :auto-recover-count])))
      (finally
        (if original-flag-descriptor
          (js/Object.defineProperty js/globalThis flag-prop original-flag-descriptor)
          (js/Reflect.deleteProperty js/globalThis flag-prop))))))
