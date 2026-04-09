(ns hyperopen.core-bootstrap.order-effects.submit-failures-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.default :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.core.compat :as core]
            [hyperopen.order.effects :as order-effects]
            [hyperopen.schema.order-request-contracts :as order-request-contracts]
            [hyperopen.core-bootstrap.order-effects.test-support :as support]))

(deftest api-submit-order-effect-treats-nested-status-errors-as-failures-test
  (async done
    (let [store (atom (support/base-submit-order-store))
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
              (js/Promise.resolve {:status "ok"
                                   :response {:type "order"
                                              :data {:statuses [{:error "IocCancelRejected"}]}}})))
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
           (is (str/includes? (or (get-in @store [:order-form-runtime :error]) "")
                              "IocCancelRejected"))
           (is (= :error (get-in @store [:ui :toast :kind])))
           (is (str/includes? (or (get-in @store [:ui :toast :message]) "")
                              "Order placement failed"))
           (is (str/includes? (or (get-in @store [:ui :toast :message]) "")
                              "IocCancelRejected"))
           (is (= [] @dispatched))
           (is (= [] @refresh-calls))
           (is (= [] @clearinghouse-calls))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest api-submit-order-effect-refreshes-when-submit-response-is-partial-success-test
  (async done
    (let [store (atom (support/base-submit-order-store))
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
              (js/Promise.resolve {:status "ok"
                                   :response {:type "order"
                                              :data {:statuses [{:filled {:oid 123 :totalSz "1" :avgPx "100"}}
                                                                {:error "IocCancelRejected"}]}}})))
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
           (is (str/includes? (or (get-in @store [:order-form-runtime :error]) "")
                              "IocCancelRejected"))
           (is (= :error (get-in @store [:ui :toast :kind])))
           (is (str/includes? (or (get-in @store [:ui :toast :message]) "")
                              "partially failed"))
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

(deftest api-submit-order-effect-runs-pre-submit-actions-before-order-test
  (async done
    (let [store (atom (support/base-submit-order-store))
          submitted-actions (atom [])
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
            (fn [_store _address action]
              (swap! submitted-actions conj action)
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
               (js/Promise.resolve []))))
      (is (order-request-contracts/order-request-valid?
           (let [order (array-map :a 0
                                  :b true
                                  :p "100"
                                  :s "1"
                                  :r false
                                  :t (array-map :limit (array-map :tif "Gtc")))]
             (array-map :action (array-map :type "order"
                                           :orders [order]
                                           :grouping "na")
                        :asset-idx 0
                        :orders [order]
                        :pre-actions [(array-map :type "updateLeverage"
                                                 :asset 0
                                                 :isCross true
                                                 :leverage 20)]))))
      (core/api-submit-order nil
                             store
                             {:pre-actions [{:type "updateLeverage"
                                             :asset 0
                                             :isCross true
                                             :leverage 20}]
                              :action {:type "order"
                                       :orders []
                                       :grouping "na"}})
      (js/setTimeout
       (fn []
         (try
           (is (= [{:type "updateLeverage"
                    :asset 0
                    :isCross true
                    :leverage 20}
                   {:type "order"
                    :orders []
                    :grouping "na"}]
                  @submitted-actions))
           (is (false? (get-in @store [:order-form-runtime :submitting?])))
           (is (nil? (get-in @store [:order-form-runtime :error])))
           (is (= :success (get-in @store [:ui :toast :kind])))
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= 1 (count @refresh-calls)))
           (is (= 1 (count @clearinghouse-calls)))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest api-submit-order-effect-aborts-when-pre-submit-action-fails-test
  (async done
    (let [store (atom (support/base-submit-order-store))
          submitted-actions (atom [])
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
            (fn [_store _address action]
              (swap! submitted-actions conj action)
              (js/Promise.resolve {:status "error"
                                   :response {:type "error"
                                              :data "leverage rejected"}})))
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
               (js/Promise.resolve []))))
      (core/api-submit-order nil
                             store
                             {:pre-actions [{:type "updateLeverage"
                                             :asset 0
                                             :isCross true
                                             :leverage 20}]
                              :action {:type "order"
                                       :orders []
                                       :grouping "na"}})
      (js/setTimeout
       (fn []
         (try
           (is (= 1 (count @submitted-actions)))
           (is (= "updateLeverage" (:type (first @submitted-actions))))
           (is (false? (get-in @store [:order-form-runtime :submitting?])))
           (is (str/includes? (or (get-in @store [:order-form-runtime :error]) "")
                              "leverage rejected"))
           (is (= :error (get-in @store [:ui :toast :kind])))
           (is (str/includes? (or (get-in @store [:ui :toast :message]) "")
                              "Margin mode update failed"))
           (is (= [] @dispatched))
           (is (= [] @refresh-calls))
           (is (= [] @clearinghouse-calls))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest api-submit-order-effect-surfaces-top-level-exchange-errors-test
  (async done
    (let [store (atom (support/base-submit-order-store))
          original-submit-order trading-api/submit-order!]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "error"
                                   :response {:type "error"
                                              :data "invalid tick"}})))
      (core/api-submit-order nil store {:action {:type "order"
                                                 :orders []
                                                 :grouping "na"}})
      (js/setTimeout
       (fn []
         (try
           (is (false? (get-in @store [:order-form-runtime :submitting?])))
           (is (str/includes? (or (get-in @store [:order-form-runtime :error]) "")
                              "invalid tick"))
           (is (= :error (get-in @store [:ui :toast :kind])))
           (is (str/includes? (or (get-in @store [:ui :toast :message]) "")
                              "Order placement failed"))
           (is (str/includes? (or (get-in @store [:ui :toast :message]) "")
                              "invalid tick"))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))

(deftest api-submit-order-effect-opens-enable-trading-recovery-modal-for-missing-agent-wallet-test
  (async done
    (let [store (atom (support/base-submit-order-store))
          original-submit-order trading-api/submit-order!]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/submit-order!
            (fn [store _address _action]
              (swap! store update-in [:wallet :agent] merge
                     {:status :error
                      :error "Agent wallet not recognized by Hyperliquid. Enable Trading again."})
              (js/Promise.resolve
               {:status "err"
                :error "Agent wallet not recognized by Hyperliquid. Enable Trading again."})))
      (core/api-submit-order nil store {:action {:type "order"
                                                 :orders []
                                                 :grouping "na"}})
      (js/setTimeout
       (fn []
         (try
           (is (false? (get-in @store [:order-form-runtime :submitting?])))
           (is (nil? (get-in @store [:order-form-runtime :error])))
           (is (= :error (get-in @store [:wallet :agent :status])))
           (is (= "Agent wallet not recognized by Hyperliquid. Enable Trading again."
                  (get-in @store [:wallet :agent :error])))
           (is (true? (get-in @store [:wallet :agent :recovery-modal-open?])))
           (is (nil? (get-in @store [:ui :toast])))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))

(deftest api-submit-order-effect-opens-enable-trading-recovery-modal-when-agent-not-ready-locally-test
  (let [store (atom {:wallet {:address "0xabc"
                              :agent {:status :not-ready}}
                     :order-form-runtime {:submitting? false
                                          :error "old-error"}
                     :ui {:toast nil}})]
    (core/api-submit-order {:dispatch! (fn [_store _evt _actions])
                            :exchange-response-error support/test-exchange-response-error
                            :runtime-error-message support/test-runtime-error-message
                            :show-toast! support/test-show-toast!}
                           nil
                           store
                           {:action {:type "order"
                                     :orders []
                                     :grouping "na"}})
    (is (false? (get-in @store [:order-form-runtime :submitting?])))
    (is (nil? (get-in @store [:order-form-runtime :error])))
    (is (= "Enable trading before submitting orders."
           (get-in @store [:wallet :agent :error])))
    (is (true? (get-in @store [:wallet :agent :recovery-modal-open?])))
    (is (nil? (get-in @store [:ui :toast])))))

(deftest api-submit-order-effect-dispatches-unlock-when-agent-is-locked-locally-test
  (let [store (atom {:wallet {:address "0xabc"
                              :agent {:status :locked}}
                     :order-form-runtime {:submitting? false
                                          :error "old-error"}
                     :ui {:toast nil}})
        dispatched (atom [])]
    (order-effects/api-submit-order {:dispatch! (fn [_store _evt actions]
                                                  (swap! dispatched conj actions))
                                     :exchange-response-error support/test-exchange-response-error
                                     :runtime-error-message support/test-runtime-error-message
                                     :show-toast! support/test-show-toast!}
                                    nil
                                    store
                                    {:action {:type "order"
                                              :orders []
                                              :grouping "na"}})
    (is (false? (get-in @store [:order-form-runtime :submitting?])))
    (is (nil? (get-in @store [:order-form-runtime :error])))
    (is (= [[[:actions/unlock-agent-trading]]]
           @dispatched))
    (is (nil? (get-in @store [:ui :toast])))))

(deftest api-submit-order-effect-handles-runtime-rejections-test
  (async done
    (let [store (atom (support/base-submit-order-store))
          original-submit-order trading-api/submit-order!]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.reject (js/Error. "network timeout"))))
      (core/api-submit-order nil store {:action {:type "order"
                                                 :orders []
                                                 :grouping "na"}})
      (js/setTimeout
       (fn []
         (try
           (is (false? (get-in @store [:order-form-runtime :submitting?])))
           (is (str/includes? (or (get-in @store [:order-form-runtime :error]) "")
                              "network timeout"))
           (is (= :error (get-in @store [:ui :toast :kind])))
           (is (str/includes? (or (get-in @store [:ui :toast :message]) "")
                              "Order placement failed: network timeout"))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))

(deftest api-submit-order-effect-blocks-mutations-while-spectate-mode-active-test
  (let [store (atom (support/base-submit-order-store
                    {:account-context {:spectate-mode {:active? true
                                                        :address "0x1234567890abcdef1234567890abcdef12345678"}}}))]
    (core/api-submit-order nil store {:action {:type "order"
                                               :orders []
                                               :grouping "na"}})
    (is (= account-context/spectate-mode-read-only-message
           (get-in @store [:order-form-runtime :error])))
    (is (= {:kind :error
            :message account-context/spectate-mode-read-only-message}
           (get-in @store [:ui :toast])))))
