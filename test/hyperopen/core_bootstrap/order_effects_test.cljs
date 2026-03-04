(ns hyperopen.core-bootstrap.order-effects-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.account.context :as account-context]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.api.default :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.core.compat :as core]
            [hyperopen.order.effects :as order-effects]
            [hyperopen.core-bootstrap.test-support.fixtures :as fixtures]))

(def clear-order-feedback-toast-timeout! fixtures/clear-order-feedback-toast-timeout!)

(defn- test-show-toast!
  [store kind message]
  (swap! store assoc-in [:ui :toast] {:kind kind
                                      :message message}))

(defn- test-exchange-response-error
  [resp]
  (or (get-in resp [:response :data])
      (get-in resp [:response :error])
      (:error resp)
      (:status resp)))

(defn- test-runtime-error-message
  [err]
  (or (.-message err) (str err)))

(defn- position-submit-deps
  [dispatched]
  {:dispatch! (fn [_store _evt actions]
                (swap! dispatched conj actions))
   :exchange-response-error test-exchange-response-error
   :runtime-error-message test-runtime-error-message
   :show-toast! test-show-toast!})

(defn- install-account-refresh-mocks!
  [refresh-calls clearinghouse-calls dex-names]
  (let [original-request-open-orders api/request-frontend-open-orders!
        original-request-clearinghouse-state api/request-clearinghouse-state!
        original-ensure-perp-dexs-data api/ensure-perp-dexs-data!]
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
             (js/Promise.resolve (vec dex-names)))))
    (fn restore-account-refresh-mocks! []
      (set! api/request-frontend-open-orders! original-request-open-orders)
      (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
      (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data))))

(deftest api-submit-order-effect-shows-success-toast-and-refreshes-history-and-open-orders-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :order-form {}
                       :order-form-runtime {:submitting? false
                                            :error "old-error"}
                       :ui {:toast nil}})
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
          original-request-clearinghouse-state api/request-clearinghouse-state!
          original-ensure-perp-dexs-data api/ensure-perp-dexs-data!]
      (clear-order-feedback-toast-timeout!)
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
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest api-submit-order-effect-treats-nested-status-errors-as-failures-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :order-form {}
                       :order-form-runtime {:submitting? false
                                            :error nil}
                       :ui {:toast nil}})
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
          original-request-clearinghouse-state api/request-clearinghouse-state!
          original-ensure-perp-dexs-data api/ensure-perp-dexs-data!]
      (clear-order-feedback-toast-timeout!)
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
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest api-submit-order-effect-refreshes-when-submit-response-is-partial-success-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :order-form {}
                       :order-form-runtime {:submitting? false
                                            :error nil}
                       :ui {:toast nil}})
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
          original-request-clearinghouse-state api/request-clearinghouse-state!
          original-ensure-perp-dexs-data api/ensure-perp-dexs-data!]
      (clear-order-feedback-toast-timeout!)
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
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest api-submit-order-effect-runs-pre-submit-actions-before-order-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :order-form {}
                       :order-form-runtime {:submitting? false
                                            :error nil}
                       :ui {:toast nil}})
          submitted-actions (atom [])
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
          original-request-clearinghouse-state api/request-clearinghouse-state!
          original-ensure-perp-dexs-data api/ensure-perp-dexs-data!]
      (clear-order-feedback-toast-timeout!)
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
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest api-submit-order-effect-aborts-when-pre-submit-action-fails-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :order-form {}
                       :order-form-runtime {:submitting? false
                                            :error nil}
                       :ui {:toast nil}})
          submitted-actions (atom [])
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
          original-request-clearinghouse-state api/request-clearinghouse-state!
          original-ensure-perp-dexs-data api/ensure-perp-dexs-data!]
      (clear-order-feedback-toast-timeout!)
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
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest api-cancel-order-effect-shows-success-toast-and-refreshes-open-orders-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :orders {:open-orders [{:order {:coin "BTC" :oid 22}}]
                                :open-orders-snapshot []
                                :open-orders-snapshot-by-dex {}}
                       :ui {:toast nil}})
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          original-cancel-order trading-api/cancel-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
          original-request-clearinghouse-state api/request-clearinghouse-state!
          original-ensure-perp-dexs-data api/ensure-perp-dexs-data!]
      (clear-order-feedback-toast-timeout!)
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
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/cancel-order! original-cancel-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
             (set! api/request-clearinghouse-state! original-request-clearinghouse-state)
             (set! api/ensure-perp-dexs-data! original-ensure-perp-dexs-data)
             (done))))
       0))))

(deftest api-cancel-order-effect-restores-optimistically-hidden-order-on-failure-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :orders {:open-orders [{:order {:coin "BTC" :oid 22}}]
                                :open-orders-snapshot []
                                :open-orders-snapshot-by-dex {}}
                       :ui {:toast nil}})
          original-cancel-order trading-api/cancel-order!]
      (clear-order-feedback-toast-timeout!)
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
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/cancel-order! original-cancel-order)
             (done))))
       0))))

(deftest api-submit-order-effect-surfaces-top-level-exchange-errors-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :order-form {}
                       :order-form-runtime {:submitting? false
                                            :error nil}
                       :ui {:toast nil}})
          original-submit-order trading-api/submit-order!]
      (clear-order-feedback-toast-timeout!)
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
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))

(deftest api-submit-order-effect-handles-runtime-rejections-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :order-form {}
                       :order-form-runtime {:submitting? false
                                            :error nil}
                       :ui {:toast nil}})
          original-submit-order trading-api/submit-order!]
      (clear-order-feedback-toast-timeout!)
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
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))

(deftest api-submit-order-effect-blocks-mutations-while-ghost-mode-active-test
  (let [store (atom {:wallet {:address "0xabc"
                              :agent {:status :ready}}
                     :account-context {:ghost-mode {:active? true
                                                    :address "0x1234567890abcdef1234567890abcdef12345678"}}
                     :order-form {}
                     :order-form-runtime {:submitting? false
                                          :error nil}
                     :ui {:toast nil}})]
    (core/api-submit-order nil store {:action {:type "order"
                                               :orders []
                                               :grouping "na"}})
    (is (= account-context/ghost-mode-read-only-message
           (get-in @store [:order-form-runtime :error])))
    (is (= {:kind :error
            :message account-context/ghost-mode-read-only-message}
           (get-in @store [:ui :toast])))))

(deftest api-cancel-order-effect-blocks-mutations-while-ghost-mode-active-test
  (let [store (atom {:wallet {:address "0xabc"
                              :agent {:status :ready}}
                     :account-context {:ghost-mode {:active? true
                                                    :address "0x1234567890abcdef1234567890abcdef12345678"}}
                     :orders {:cancel-error nil}
                     :ui {:toast nil}})]
    (core/api-cancel-order nil store {:action {:type "cancel"
                                               :cancels [{:a 0 :o 101}]}})
    (is (= account-context/ghost-mode-read-only-message
           (get-in @store [:orders :cancel-error])))
    (is (= {:kind :error
            :message account-context/ghost-mode-read-only-message}
           (get-in @store [:ui :toast])))))

(deftest api-submit-position-tpsl-effect-validates-preconditions-test
  (let [dispatched (atom [])
        deps (position-submit-deps dispatched)
        ghost-mode-store (atom {:wallet {:address "0xabc"
                                         :agent {:status :ready}}
                                :account-context {:ghost-mode {:active? true
                                                               :address "0x1234567890abcdef1234567890abcdef12345678"}}
                                :positions-ui {:tpsl-modal {:submitting? true
                                                            :error nil}}
                                :ui {:toast nil}})
        missing-wallet-store (atom {:wallet {:agent {:status :ready}}
                                    :positions-ui {:tpsl-modal {:submitting? true
                                                                :error nil}}
                                    :ui {:toast nil}})
        not-ready-store (atom {:wallet {:address "0xabc"
                                        :agent {:status :not-ready}}
                               :positions-ui {:tpsl-modal {:submitting? true
                                                           :error nil}}
                               :ui {:toast nil}})]
    (order-effects/api-submit-position-tpsl deps nil ghost-mode-store {:action {:type "order"}})
    (is (= false (get-in @ghost-mode-store [:positions-ui :tpsl-modal :submitting?])))
    (is (= account-context/ghost-mode-read-only-message
           (get-in @ghost-mode-store [:positions-ui :tpsl-modal :error])))
    (is (= {:kind :error
            :message account-context/ghost-mode-read-only-message}
           (get-in @ghost-mode-store [:ui :toast])))

    (order-effects/api-submit-position-tpsl deps nil missing-wallet-store {:action {:type "order"}})
    (is (= false (get-in @missing-wallet-store [:positions-ui :tpsl-modal :submitting?])))
    (is (= "Connect your wallet before submitting."
           (get-in @missing-wallet-store [:positions-ui :tpsl-modal :error])))
    (is (= {:kind :error
            :message "Connect your wallet before submitting."}
           (get-in @missing-wallet-store [:ui :toast])))

    (order-effects/api-submit-position-tpsl deps nil not-ready-store {:action {:type "order"}})
    (is (= false (get-in @not-ready-store [:positions-ui :tpsl-modal :submitting?])))
    (is (= "Enable trading before submitting orders."
           (get-in @not-ready-store [:positions-ui :tpsl-modal :error])))
    (is (= {:kind :error
            :message "Enable trading before submitting orders."}
           (get-in @not-ready-store [:ui :toast])))
    (is (= [] @dispatched))))

(deftest api-submit-position-tpsl-effect-success-resets-modal-and-refreshes-surfaces-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :positions-ui {:tpsl-modal {:submitting? true
                                                   :error "old-error"}}
                       :ui {:toast nil}})
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          deps (position-submit-deps dispatched)
          restore-refresh-mocks! (install-account-refresh-mocks! refresh-calls clearinghouse-calls [])
          original-submit-order trading-api/submit-order!]
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"})))
      (order-effects/api-submit-position-tpsl deps nil store {:action {:type "order"}})
      (js/setTimeout
       (fn []
         (try
           (is (= (position-tpsl/default-modal-state)
                  (get-in @store [:positions-ui :tpsl-modal])))
           (is (= {:kind :success
                   :message "TP/SL orders submitted."}
                  (get-in @store [:ui :toast])))
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= 1 (count @refresh-calls)))
           (is (= 1 (count @clearinghouse-calls)))
           (finally
             (restore-refresh-mocks!)
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))

(deftest api-submit-position-tpsl-effect-handles-runtime-errors-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :positions-ui {:tpsl-modal {:submitting? true
                                                   :error nil}}
                       :ui {:toast nil}})
          deps (position-submit-deps (atom []))
          original-submit-order trading-api/submit-order!]
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.reject (js/Error. "rpc timeout"))))
      (order-effects/api-submit-position-tpsl deps nil store {:action {:type "order"}})
      (js/setTimeout
       (fn []
         (try
           (is (= false (get-in @store [:positions-ui :tpsl-modal :submitting?])))
           (is (= "rpc timeout" (get-in @store [:positions-ui :tpsl-modal :error])))
           (is (= {:kind :error
                   :message "Order placement failed: rpc timeout"}
                  (get-in @store [:ui :toast])))
           (finally
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))

(deftest api-submit-position-margin-effect-validates-preconditions-test
  (let [dispatched (atom [])
        deps (position-submit-deps dispatched)
        ghost-mode-store (atom {:wallet {:address "0xabc"
                                         :agent {:status :ready}}
                                :account-context {:ghost-mode {:active? true
                                                               :address "0x1234567890abcdef1234567890abcdef12345678"}}
                                :positions-ui {:margin-modal {:submitting? true
                                                              :error nil}}
                                :ui {:toast nil}})
        missing-wallet-store (atom {:wallet {:agent {:status :ready}}
                                    :positions-ui {:margin-modal {:submitting? true
                                                                  :error nil}}
                                    :ui {:toast nil}})
        not-ready-store (atom {:wallet {:address "0xabc"
                                        :agent {:status :not-ready}}
                               :positions-ui {:margin-modal {:submitting? true
                                                             :error nil}}
                               :ui {:toast nil}})]
    (order-effects/api-submit-position-margin deps nil ghost-mode-store {:action {:type "updateIsolatedMargin"}})
    (is (= false (get-in @ghost-mode-store [:positions-ui :margin-modal :submitting?])))
    (is (= account-context/ghost-mode-read-only-message
           (get-in @ghost-mode-store [:positions-ui :margin-modal :error])))
    (is (= {:kind :error
            :message account-context/ghost-mode-read-only-message}
           (get-in @ghost-mode-store [:ui :toast])))

    (order-effects/api-submit-position-margin deps nil missing-wallet-store {:action {:type "updateIsolatedMargin"}})
    (is (= false (get-in @missing-wallet-store [:positions-ui :margin-modal :submitting?])))
    (is (= "Connect your wallet before updating margin."
           (get-in @missing-wallet-store [:positions-ui :margin-modal :error])))
    (is (= {:kind :error
            :message "Connect your wallet before updating margin."}
           (get-in @missing-wallet-store [:ui :toast])))

    (order-effects/api-submit-position-margin deps nil not-ready-store {:action {:type "updateIsolatedMargin"}})
    (is (= false (get-in @not-ready-store [:positions-ui :margin-modal :submitting?])))
    (is (= "Enable trading before updating margin."
           (get-in @not-ready-store [:positions-ui :margin-modal :error])))
    (is (= {:kind :error
            :message "Enable trading before updating margin."}
           (get-in @not-ready-store [:ui :toast])))
    (is (= [] @dispatched))))

(deftest api-submit-position-margin-effect-success-resets-modal-and-refreshes-surfaces-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :positions-ui {:margin-modal {:submitting? true
                                                     :error "old-error"}}
                       :ui {:toast nil}})
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          deps (position-submit-deps dispatched)
          restore-refresh-mocks! (install-account-refresh-mocks! refresh-calls clearinghouse-calls [])
          original-submit-order trading-api/submit-order!]
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"})))
      (order-effects/api-submit-position-margin deps nil store {:action {:type "updateIsolatedMargin"}})
      (js/setTimeout
       (fn []
         (try
           (is (= (position-margin/default-modal-state)
                  (get-in @store [:positions-ui :margin-modal])))
           (is (= {:kind :success
                   :message "Margin updated."}
                  (get-in @store [:ui :toast])))
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= 1 (count @refresh-calls)))
           (is (= 1 (count @clearinghouse-calls)))
           (finally
             (restore-refresh-mocks!)
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))

(deftest api-submit-position-margin-effect-surfaces-exchange-errors-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :positions-ui {:margin-modal {:submitting? true
                                                     :error nil}}
                       :ui {:toast nil}})
          deps (position-submit-deps (atom []))
          original-submit-order trading-api/submit-order!]
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "error"
                                   :response {:type "error"
                                              :data "margin rejected"}})))
      (order-effects/api-submit-position-margin deps nil store {:action {:type "updateIsolatedMargin"}})
      (js/setTimeout
       (fn []
         (try
           (is (= false (get-in @store [:positions-ui :margin-modal :submitting?])))
           (is (= "margin rejected" (get-in @store [:positions-ui :margin-modal :error])))
           (is (= {:kind :error
                   :message "Margin update failed: margin rejected"}
                  (get-in @store [:ui :toast])))
           (finally
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))

(deftest api-submit-position-margin-effect-handles-runtime-errors-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready}}
                       :positions-ui {:margin-modal {:submitting? true
                                                     :error nil}}
                       :ui {:toast nil}})
          deps (position-submit-deps (atom []))
          original-submit-order trading-api/submit-order!]
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.reject (js/Error. "transport failure"))))
      (order-effects/api-submit-position-margin deps nil store {:action {:type "updateIsolatedMargin"}})
      (js/setTimeout
       (fn []
         (try
           (is (= false (get-in @store [:positions-ui :margin-modal :submitting?])))
           (is (= "transport failure" (get-in @store [:positions-ui :margin-modal :error])))
           (is (= {:kind :error
                   :message "Margin update failed: transport failure"}
                  (get-in @store [:ui :toast])))
           (finally
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))
