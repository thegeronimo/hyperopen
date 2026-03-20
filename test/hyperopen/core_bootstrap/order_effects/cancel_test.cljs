(ns hyperopen.core-bootstrap.order-effects.cancel-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.default :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.core.compat :as core]
            [hyperopen.core-bootstrap.order-effects.test-support :as support]))

(deftest api-cancel-order-effect-shows-success-toast-and-refreshes-open-orders-test
  (async done
    (let [store (atom (support/base-cancel-order-store))
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-cancel-order trading-api/cancel-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
          original-request-clearinghouse-state api/request-clearinghouse-state!
          original-ensure-perp-dexs-data api/ensure-perp-dexs-data!]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/cancel-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"
                                   :response {:type "cancel"
                                              :data {:statuses ["success"]}}})))
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
      (core/api-cancel-order nil store {:action {:type "cancel"
                                                 :cancels [{:a 0 :o 22}]}})
      (is (= #{22}
             (get-in @store [:orders :pending-cancel-oids])))
      (js/setTimeout
       (fn []
         (try
           (is (nil? (get-in @store [:orders :cancel-error])))
           (is (nil? (get-in @store [:orders :pending-cancel-oids])))
           (is (= []
                  (get-in @store [:orders :open-orders])))
           (is (= :success
                  (get-in @store [:ui :toast :kind])))
           (is (= "Order canceled."
                  (get-in @store [:ui :toast :message])))
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= 2 (count @refresh-calls)))
           (is (= 2 (count @clearinghouse-calls)))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/cancel-order! original-cancel-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest api-cancel-order-effect-restores-optimistically-hidden-order-on-failure-test
  (async done
    (let [store (atom (support/base-cancel-order-store
                       {:orders {:open-orders [{:order {:coin "BTC" :oid 22}}]
                                 :open-orders-snapshot []
                                 :open-orders-snapshot-by-dex {}}}))
          original-cancel-order trading-api/cancel-order!]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/cancel-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "error"
                                   :response {:type "error"
                                              :data "cancel failed"}})))
      (core/api-cancel-order nil store {:action {:type "cancel"
                                                 :cancels [{:a 0 :o 22}]}})
      (is (= #{22}
             (get-in @store [:orders :pending-cancel-oids])))
      (js/setTimeout
       (fn []
         (try
           (is (str/includes? (or (get-in @store [:orders :cancel-error]) "")
                              "cancel failed"))
           (is (nil? (get-in @store [:orders :pending-cancel-oids])))
           (is (= [22]
                  (->> (get-in @store [:orders :open-orders])
                       (mapv #(get-in % [:order :oid])))))
           (is (= :error (get-in @store [:ui :toast :kind])))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/cancel-order! original-cancel-order)
             (done))))
       0))))

(deftest api-cancel-order-effect-prunes-only-successful-orders-on-partial-batch-failure-test
  (async done
    (let [store (atom (support/base-cancel-order-store
                       {:orders {:open-orders [{:order {:coin "BTC" :oid 22}}
                                               {:order {:coin "ETH" :oid 23}}
                                               {:order {:coin "SOL" :oid 24}}]
                                 :open-orders-snapshot []
                                 :open-orders-snapshot-by-dex {}}}))
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-cancel-order trading-api/cancel-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
          original-request-clearinghouse-state api/request-clearinghouse-state!
          original-ensure-perp-dexs-data api/ensure-perp-dexs-data!]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/cancel-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"
                                   :response {:type "cancel"
                                              :data {:statuses ["success"
                                                                {:error "too late"}
                                                                "success"]}}})))
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
      (core/api-cancel-order nil store {:action {:type "cancel"
                                                 :cancels [{:a 0 :o 22}
                                                           {:a 1 :o 23}
                                                           {:a 2 :o 24}]}})
      (is (= #{22 23 24}
             (get-in @store [:orders :pending-cancel-oids])))
      (js/setTimeout
       (fn []
         (try
           (is (= "Order 2: too late"
                  (get-in @store [:orders :cancel-error])))
           (is (nil? (get-in @store [:orders :pending-cancel-oids])))
           (is (= [23]
                  (->> (get-in @store [:orders :open-orders])
                       (mapv #(get-in % [:order :oid])))))
           (is (= :error
                  (get-in @store [:ui :toast :kind])))
           (is (str/includes? (get-in @store [:ui :toast :message] "")
                              "partially failed"))
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= 2 (count @refresh-calls)))
           (is (= 2 (count @clearinghouse-calls)))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/cancel-order! original-cancel-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest api-cancel-order-effect-shows-twap-success-toast-and-refreshes-account-surfaces-test
  (async done
    (let [store (atom (support/base-cancel-order-store
                       {:orders {:cancel-error "old-error"}}))
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-cancel-order trading-api/cancel-order!
          original-dispatch nxr/dispatch
          restore-account-refresh-mocks! (support/install-account-refresh-mocks! refresh-calls
                                                                                 clearinghouse-calls
                                                                                 ["dex-a"])]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/cancel-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"
                                   :response {:type "twapCancel"
                                              :data {:status "success"}}})))
      (set! nxr/dispatch
            (fn [_store _evt actions]
              (swap! dispatched conj actions)))
      (core/api-cancel-order nil store support/twap-cancel-request)
      (js/setTimeout
       (fn []
         (try
           (is (nil? (get-in @store [:orders :cancel-error])))
           (is (= :success
                  (get-in @store [:ui :toast :kind])))
           (is (= "TWAP terminated."
                  (get-in @store [:ui :toast :message])))
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= 2 (count @refresh-calls)))
           (is (= 2 (count @clearinghouse-calls)))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/cancel-order! original-cancel-order)
             (set! nxr/dispatch original-dispatch)
             (restore-account-refresh-mocks!)
             (done))))
       0))))

(deftest api-cancel-order-effect-surfaces-twap-string-status-errors-test
  (async done
    (let [store (atom (support/base-cancel-order-store
                       {:orders {:cancel-error nil}}))
          original-cancel-order trading-api/cancel-order!]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/cancel-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"
                                   :response {:type "twapCancel"
                                              :data {:statuses [" already stopped "]}}})))
      (core/api-cancel-order nil store support/twap-cancel-request)
      (js/setTimeout
       (fn []
         (try
           (is (= "already stopped"
                  (get-in @store [:orders :cancel-error])))
           (is (= :error
                  (get-in @store [:ui :toast :kind])))
           (is (= "TWAP termination failed: already stopped"
                  (get-in @store [:ui :toast :message])))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/cancel-order! original-cancel-order)
             (done))))
       0))))

(deftest api-cancel-order-effect-surfaces-twap-error-map-messages-test
  (async done
    (let [store (atom (support/base-cancel-order-store
                       {:orders {:cancel-error nil}}))
          original-cancel-order trading-api/cancel-order!]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/cancel-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"
                                   :response {:type "twapCancel"
                                              :data {:status {:error {:message "twap missing"}}}}})))
      (core/api-cancel-order nil store support/twap-cancel-request)
      (js/setTimeout
       (fn []
         (try
           (is (= "twap missing"
                  (get-in @store [:orders :cancel-error])))
           (is (= :error
                  (get-in @store [:ui :toast :kind])))
           (is (= "TWAP termination failed: twap missing"
                  (get-in @store [:ui :toast :message])))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/cancel-order! original-cancel-order)
             (done))))
       0))))

(deftest api-cancel-order-effect-ignores-blank-twap-error-values-test
  (async done
    (let [store (atom (support/base-cancel-order-store
                       {:orders {:cancel-error "stale"}}))
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-cancel-order trading-api/cancel-order!
          original-dispatch nxr/dispatch
          restore-account-refresh-mocks! (support/install-account-refresh-mocks! refresh-calls
                                                                                 clearinghouse-calls
                                                                                 ["dex-a"])]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/cancel-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"
                                   :response {:type "twapCancel"
                                              :data {:status {:error "   "}}}})))
      (set! nxr/dispatch
            (fn [_store _evt actions]
              (swap! dispatched conj actions)))
      (core/api-cancel-order nil store support/twap-cancel-request)
      (js/setTimeout
       (fn []
         (try
           (is (nil? (get-in @store [:orders :cancel-error])))
           (is (= :success
                  (get-in @store [:ui :toast :kind])))
           (is (= "TWAP terminated."
                  (get-in @store [:ui :toast :message])))
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= 2 (count @refresh-calls)))
           (is (= 2 (count @clearinghouse-calls)))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/cancel-order! original-cancel-order)
             (set! nxr/dispatch original-dispatch)
             (restore-account-refresh-mocks!)
             (done))))
       0))))

(deftest api-cancel-order-effect-blocks-mutations-while-spectate-mode-active-test
  (let [store (atom (support/base-cancel-order-store
                    {:account-context {:spectate-mode {:active? true
                                                        :address "0x1234567890abcdef1234567890abcdef12345678"}}}))]
    (core/api-cancel-order nil store {:action {:type "cancel"
                                               :cancels [{:a 0 :o 101}]}})
    (is (= account-context/spectate-mode-read-only-message
           (get-in @store [:orders :cancel-error])))
    (is (= {:kind :error
            :message account-context/spectate-mode-read-only-message}
           (get-in @store [:ui :toast])))))
