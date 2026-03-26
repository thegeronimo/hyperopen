(ns hyperopen.core-bootstrap.order-effects.submit-refresh-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.core.compat :as core]
            [hyperopen.core-bootstrap.order-effects.test-support :as support]))

(deftest api-submit-order-effect-shows-success-toast-and-refreshes-history-and-open-orders-test
  (async done
    (let [store (atom (support/base-submit-order-store {:order-form-runtime {:submitting? false
                                                                             :error "old-error"}}))
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
          original-request-clearinghouse-state api/request-clearinghouse-state!
          original-ensure-perp-dexs-data api/ensure-perp-dexs-data!]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"})))
      (set! nxr/dispatch
            (fn [_store _evt actions]
              (swap! dispatched conj actions)))
      (set! api/request-frontend-open-orders!
            (fn request-frontend-open-orders-mock
              ([address]
               (request-frontend-open-orders-mock address {}))
              ([address opts]
               (request-frontend-open-orders-mock address (:dex opts) (dissoc opts :dex)))
              ([address dex opts]
               (swap! refresh-calls conj [address dex opts])
               (js/Promise.resolve []))))
      (set! api/request-clearinghouse-state!
            (fn request-clearinghouse-state-mock
              ([address dex]
               (request-clearinghouse-state-mock address dex {}))
              ([address dex opts]
               (swap! clearinghouse-calls conj [address dex opts])
               (js/Promise.resolve {:assetPositions []}))))
      (set! api/ensure-perp-dexs-data!
            (fn ensure-perp-dexs-data-mock
              ([_store]
               (ensure-perp-dexs-data-mock nil {}))
              ([_store _opts]
               (js/Promise.resolve ["dex-a"]))))
      (core/api-submit-order nil store {:action {:type "order"
                                                 :orders []
                                                 :grouping "na"}})
      (js/setTimeout
       (fn []
         (try
           (is (false? (get-in @store [:order-form-runtime :submitting?])))
           (is (nil? (get-in @store [:order-form-runtime :error])))
           (is (= :success (get-in @store [:ui :toast :kind])))
           (is (= "Order submitted."
                  (get-in @store [:ui :toast :message])))
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= 2 (count @refresh-calls)))
           (is (= 2 (count @clearinghouse-calls)))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest api-submit-order-effect-refreshes-dex-open-orders-and-skips-per-dex-clearinghouse-when-ws-snapshot-ready-test
  (async done
    (let [address "0xabc"
          dex "dex-a"
          store (atom (support/base-submit-order-store
                       {:wallet {:address address
                                 :agent {:status :ready}}
                        :websocket {:health {:transport {:state :connected
                                                         :freshness :live}
                                             :streams {["openOrders" nil address nil nil]
                                                       {:topic "openOrders"
                                                        :status :live
                                                        :subscribed? true
                                                        :descriptor {:type "openOrders"
                                                                     :user address}}
                                                       ["webData2" nil address nil nil]
                                                       {:topic "webData2"
                                                        :status :live
                                                        :subscribed? true
                                                        :descriptor {:type "webData2"
                                                                     :user address}}
                                                       ["clearinghouseState" nil address dex nil]
                                                       {:topic "clearinghouseState"
                                                        :status :live
                                                        :subscribed? true
                                                        :descriptor {:type "clearinghouseState"
                                                                     :user address
                                                                     :dex dex}}}}}
                        :perp-dex-clearinghouse {dex {:account-value "1"}}}))
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch
          restore-account-refresh-mocks! (support/install-account-refresh-mocks! refresh-calls
                                                                                 clearinghouse-calls
                                                                                 [dex])]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"})))
      (set! nxr/dispatch
            (fn [_store _evt actions]
              (swap! dispatched conj actions)))
      (core/api-submit-order nil store {:action {:type "order"
                                                 :orders []
                                                 :grouping "na"}})
      (js/setTimeout
       (fn []
         (try
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= [[address dex {:force-refresh? true
                                 :priority :low}]]
                  @refresh-calls))
           (is (= [] @clearinghouse-calls))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (restore-account-refresh-mocks!)
             (done))))
       0))))

(deftest api-submit-order-effect-refreshes-dex-open-orders-for-event-driven-ws-streams-test
  (async done
    (let [address "0xabc"
          store (atom (support/base-submit-order-store
                       {:wallet {:address address
                                 :agent {:status :ready}}
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
                                                                     :user address}}}}}}))
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch
          restore-account-refresh-mocks! (support/install-account-refresh-mocks! refresh-calls
                                                                                 clearinghouse-calls
                                                                                 ["dex-a"])]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"})))
      (set! nxr/dispatch
            (fn [_store _evt actions]
              (swap! dispatched conj actions)))
      (core/api-submit-order nil store {:action {:type "order"
                                                 :orders []
                                                 :grouping "na"}})
      (js/setTimeout
       (fn []
         (try
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= [[address "dex-a" {:force-refresh? true
                                     :priority :low}]]
                  @refresh-calls))
           (is (= [[address "dex-a" {:priority :low}]]
                  @clearinghouse-calls))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (restore-account-refresh-mocks!)
             (done))))
       0))))

(deftest api-submit-order-effect-uses-rest-refresh-when-ws-first-flag-disabled-test
  (async done
    (let [address "0xabc"
          store (atom (support/base-submit-order-store
                       {:wallet {:address address
                                 :agent {:status :ready}}
                        :websocket {:migration-flags {:order-fill-ws-first? false}
                                    :health {:transport {:state :connected
                                                         :freshness :live}
                                             :streams {["openOrders" nil address nil nil]
                                                       {:topic "openOrders"
                                                        :status :live
                                                        :subscribed? true
                                                        :descriptor {:type "openOrders"
                                                                     :user address}}
                                                       ["webData2" nil address nil nil]
                                                       {:topic "webData2"
                                                        :status :live
                                                        :subscribed? true
                                                        :descriptor {:type "webData2"
                                                                     :user address}}}}}}))
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch
          restore-account-refresh-mocks! (support/install-account-refresh-mocks! refresh-calls
                                                                                 clearinghouse-calls
                                                                                 ["dex-a"])]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"})))
      (set! nxr/dispatch
            (fn [_store _evt actions]
              (swap! dispatched conj actions)))
      (core/api-submit-order nil store {:action {:type "order"
                                                 :orders []
                                                 :grouping "na"}})
      (js/setTimeout
       (fn []
         (try
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= [[address nil {:force-refresh? true
                                 :priority :high}]
                   [address "dex-a" {:force-refresh? true
                                     :priority :low}]]
                  @refresh-calls))
           (is (= [[address nil {:priority :high}]
                   [address "dex-a" {:priority :low}]]
                  @clearinghouse-calls))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (restore-account-refresh-mocks!)
             (done))))
       0))))
