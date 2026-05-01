(ns hyperopen.api.default-orders-test
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.api.default :as api]))

(use-fixtures
  :each
  {:before (fn []
             (api/reset-request-runtime!))
   :after (fn []
            (api/reset-request-runtime!))})

(deftest fetch-historical-orders-sends-request-and-normalizes-vector-payload-test
  (async done
    (let [calls (atom [])
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([body _opts]
               (swap! calls conj body)
               (js/Promise.resolve [{:order {:coin "BTC" :oid 1}}
                                    {:coin "ETH" :oid 2}]))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-historical-orders! (atom {}) "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {:priority :high})
          (.then (fn [rows]
                   (is (= {"type" "historicalOrders" "user" "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                          (first @calls)))
                   (is (= 2 (count rows)))
                   (is (= "BTC" (get-in rows [0 :order :coin])))
                   (is (= "ETH" (get-in rows [1 :order :coin])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

(deftest fetch-historical-orders-supports-wrapped-payload-keys-test
  (async done
    (let [original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([_body _opts]
               (js/Promise.resolve {:orders [{:order {:coin "SOL" :oid 9}}]}))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-historical-orders! (atom {}) "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {})
          (.then (fn [rows]
                   (is (= 1 (count rows)))
                   (is (= "SOL" (get-in rows [0 :order :coin])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

(deftest fetch-user-fills-sends-aggregate-request-and-preserves-liquidation-fields-test
  (async done
    (let [store (atom {:orders {}})
          calls (atom [])
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([body _opts]
               (swap! calls conj body)
               (js/Promise.resolve [{:tid 1
                                     :coin "PUMP"
                                     :side "A"
                                     :dir "Market Order Liquidation: Close Long"
                                     :liquidation {:markPx "0.001780"
                                                   :method "market"}
                                     :time 1700000000000}]))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-fills! store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          (.then (fn [rows]
                   (let [request-body (first @calls)
                         stored-row (first (get-in @store [:orders :fills]))]
                     (is (= {"type" "userFills"
                             "user" "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                             "aggregateByTime" true}
                            request-body))
                     (is (= "Market Order Liquidation: Close Long" (:dir stored-row)))
                     (is (= "0.001780" (get-in stored-row [:liquidation :markPx])))
                     (is (= "market" (get-in stored-row [:liquidation :method])))
                     (is (= rows (get-in @store [:orders :fills])))
                     (done))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))
