(ns hyperopen.api.endpoints.orders-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.api.endpoints.orders :as orders]))

(deftest request-frontend-open-orders-builds-default-and-dex-specific-bodies-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls [])]
    (orders/request-frontend-open-orders! post-info! "0xabc" nil {})
    (orders/request-frontend-open-orders! post-info! "0xabc" "" {})
    (orders/request-frontend-open-orders! post-info! "0xabc" "vault" {:priority :low})
    (is (= [{"type" "frontendOpenOrders"
             "user" "0xabc"}
            {"type" "frontendOpenOrders"
             "user" "0xabc"}
            {"type" "frontendOpenOrders"
             "user" "0xabc"
             "dex" "vault"}]
           (mapv first @calls)))
    (is (= [{:priority :high
             :dedupe-key [:frontend-open-orders "0xabc" nil]
             :cache-ttl-ms 2500}
            {:priority :high
             :dedupe-key [:frontend-open-orders "0xabc" nil]
             :cache-ttl-ms 2500}
            {:priority :low
             :dedupe-key [:frontend-open-orders "0xabc" "vault"]
             :cache-ttl-ms 2500}]
           (mapv second @calls)))))

(deftest request-user-fills-enforces-aggregate-by-time-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls [])]
    (orders/request-user-fills! post-info! "0xabc" {})
    (is (= {"type" "userFills"
            "user" "0xabc"
            "aggregateByTime" true}
           (ffirst @calls)))
    (is (= {:priority :high
            :dedupe-key [:user-fills "0xabc"]
            :cache-ttl-ms 5000}
           (second (first @calls))))))

(deftest request-historical-orders-normalizes-wrapped-and-flat-payloads-test
  (async done
    (let [post-info! (api-stubs/post-info-stub {:orders [{:coin "SOL" :oid 9}
                                                          {:order {:coin "BTC" :oid 1}}]})]
      (-> (orders/request-historical-orders! post-info! "0xabc" {})
          (.then (fn [rows]
                   (is (= 2 (count rows)))
                   (is (= "SOL" (get-in rows [0 :order :coin])))
                   (is (= "BTC" (get-in rows [1 :order :coin])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-historical-orders-applies-dedupe-and-ttl-policy-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls {:orders []})]
    (orders/request-historical-orders! post-info! "0xAbC" {:priority :low})
    (is (= {"type" "historicalOrders"
            "user" "0xAbC"}
           (ffirst @calls)))
    (is (= {:priority :low
            :dedupe-key [:historical-orders "0xabc"]
            :cache-ttl-ms 5000}
           (second (first @calls))))))

(deftest request-historical-orders-normalizes-sequential-payload-and-filters-invalid-rows-test
  (async done
    (let [post-info! (api-stubs/post-info-stub [nil
                                                {:coin "SOL" :oid 9}
                                                "invalid-row"
                                                {:order {:coin "BTC" :oid 1}}])]
      (-> (orders/request-historical-orders! post-info! "0xabc" {})
          (.then (fn [rows]
                   (is (= 2 (count rows)))
                   (is (= "SOL" (get-in rows [0 :order :coin])))
                   (is (= "BTC" (get-in rows [1 :order :coin])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-historical-orders-supports-alternative-map-payload-keys-test
  (async done
    (let [run-request (fn [payload]
                        (orders/request-historical-orders!
                         (api-stubs/post-info-stub payload)
                         "0xabc"
                         {}))]
      (-> (js/Promise.all
           #js [(run-request {:historicalOrders [{:coin "ETH" :oid 2}]})
                (run-request {:data [{:coin "DOGE" :oid 3}]})
                (run-request {:data {:unexpected true}})
                (run-request "not-a-map-or-seq")])
          (.then (fn [results]
                   (let [results* (vec (array-seq results))]
                     (is (= "ETH" (get-in results* [0 0 :order :coin])))
                     (is (= "DOGE" (get-in results* [1 0 :order :coin])))
                     (is (= [] (nth results* 2)))
                     (is (= [] (nth results* 3))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-historical-orders-skips-network-when-address-missing-test
  (async done
    (let [called? (atom false)
          post-info! (api-stubs/post-info-stub
                      (fn [_body _opts]
                        (reset! called? true)
                        []))]
      (-> (orders/request-historical-orders! post-info! nil {})
          (.then (fn [rows]
                   (is (= [] rows))
                   (is (false? @called?))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
