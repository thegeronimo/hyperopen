(ns hyperopen.api.endpoints.orders-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.endpoints.orders :as orders]))

(deftest request-frontend-open-orders-builds-default-and-dex-specific-bodies-test
  (let [calls (atom [])
        post-info! (fn [body opts]
                     (swap! calls conj [body opts])
                     (js/Promise.resolve []))]
    (orders/request-frontend-open-orders! post-info! "0xabc" nil {})
    (orders/request-frontend-open-orders! post-info! "0xabc" "vault" {:priority :low})
    (is (= [{"type" "frontendOpenOrders"
             "user" "0xabc"}
            {"type" "frontendOpenOrders"
             "user" "0xabc"
             "dex" "vault"}]
           (mapv first @calls)))
    (is (= [{:priority :high}
            {:priority :low}]
           (mapv second @calls)))))

(deftest request-user-fills-enforces-aggregate-by-time-test
  (let [calls (atom [])
        post-info! (fn [body opts]
                     (swap! calls conj [body opts])
                     (js/Promise.resolve []))]
    (orders/request-user-fills! post-info! "0xabc" {})
    (is (= {"type" "userFills"
            "user" "0xabc"
            "aggregateByTime" true}
           (ffirst @calls)))
    (is (= {:priority :high}
           (second (first @calls))))))

(deftest request-historical-orders-normalizes-wrapped-and-flat-payloads-test
  (async done
    (let [post-info! (fn [_body _opts]
                       (js/Promise.resolve {:orders [{:coin "SOL" :oid 9}
                                                    {:order {:coin "BTC" :oid 1}}]}))]
      (-> (orders/request-historical-orders! post-info! "0xabc" {})
          (.then (fn [rows]
                   (is (= 2 (count rows)))
                   (is (= "SOL" (get-in rows [0 :order :coin])))
                   (is (= "BTC" (get-in rows [1 :order :coin])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-historical-orders-skips-network-when-address-missing-test
  (async done
    (let [called? (atom false)
          post-info! (fn [_body _opts]
                       (reset! called? true)
                       (js/Promise.resolve []))]
      (-> (orders/request-historical-orders! post-info! nil {})
          (.then (fn [rows]
                   (is (= [] rows))
                   (is (false? @called?))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
