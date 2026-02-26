(ns hyperopen.core-bootstrap.order-effects-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.core.compat :as core]
            [hyperopen.core-bootstrap.test-support.fixtures :as fixtures]))

(def clear-order-feedback-toast-timeout! fixtures/clear-order-feedback-toast-timeout!)

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
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
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
           (finally
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
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
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
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
           (finally
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
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
          original-submit-order trading-api/submit-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
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
           (finally
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
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
          original-cancel-order trading-api/cancel-order!
          original-dispatch nxr/dispatch
          original-request-open-orders api/request-frontend-open-orders!
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
           (finally
             (clear-order-feedback-toast-timeout!)
             (set! trading-api/cancel-order! original-cancel-order)
             (set! nxr/dispatch original-dispatch)
             (set! api/request-frontend-open-orders! original-request-open-orders)
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
