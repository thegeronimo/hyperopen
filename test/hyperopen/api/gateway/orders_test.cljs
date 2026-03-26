(ns hyperopen.api.gateway.orders-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.gateway.orders :as orders-gateway]))

(deftest request-frontend-open-orders-normalizes-dex-and-opts-test
  (async done
    (let [calls (atom [])
          deps {:post-info! (fn [body opts]
                              (swap! calls conj {:body body :opts opts})
                              (js/Promise.resolve []))}]
      (-> (orders-gateway/request-frontend-open-orders!
           deps
           "0xabc"
           {:priority :low})
          (.then (fn []
                   (is (= {"type" "frontendOpenOrders"
                           "user" "0xabc"}
                          (get-in @calls [0 :body])))
                   (is (= {:priority :low
                           :dedupe-key [:frontend-open-orders "0xabc" nil]
                           :cache-ttl-ms 2500}
                          (get-in @calls [0 :opts])))
                   (orders-gateway/request-frontend-open-orders!
                    deps
                    "0xabc"
                    {:dex "dex-a"
                     :priority :high})))
          (.then (fn []
                   (is (= {"type" "frontendOpenOrders"
                           "user" "0xabc"
                           "dex" "dex-a"}
                          (get-in @calls [1 :body])))
                   (is (= {:priority :high
                           :dedupe-key [:frontend-open-orders "0xabc" "dex-a"]
                           :cache-ttl-ms 2500}
                          (get-in @calls [1 :opts])))
                   (orders-gateway/request-frontend-open-orders!
                    deps
                    "0xabc"
                    "dex-b"
                    {:priority :low})))
          (.then (fn []
                   (is (= {"type" "frontendOpenOrders"
                           "user" "0xabc"
                           "dex" "dex-b"}
                          (get-in @calls [2 :body])))
                   (is (= {:priority :low
                           :dedupe-key [:frontend-open-orders "0xabc" "dex-b"]
                           :cache-ttl-ms 2500}
                          (get-in @calls [2 :opts])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-historical-orders-normalizes-response-shape-test
  (async done
    (let [deps {:log-fn (fn [& _] nil)
                :post-info! (fn [_body _opts]
                              (js/Promise.resolve [{:coin "BTC" :oid 1}
                                                   {:order {:coin "ETH" :oid 2}}]))}]
      (-> (orders-gateway/fetch-historical-orders! deps "0xabc" {:priority :high})
          (.then (fn [rows]
                   (is (= 2 (count rows)))
                   (is (= "BTC" (get-in rows [0 :order :coin])))
                   (is (= "ETH" (get-in rows [1 :order :coin])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-historical-orders-delegates-to-request-data-wrapper-test
  (async done
    (let [called (atom nil)
          deps {:request-historical-orders-data! (fn [address opts]
                                                   (reset! called [address opts])
                                                   (js/Promise.resolve []))}]
      (-> (orders-gateway/request-historical-orders! deps "0xabc" {:priority :high})
          (.then (fn [_]
                   (is (= ["0xabc" {:priority :high}] @called))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-historical-orders-supports-legacy-fetch-dependency-test
  (async done
    (let [called (atom nil)
          deps {:fetch-historical-orders! (fn [store address opts]
                                            (reset! called [store address opts])
                                            (js/Promise.resolve []))}]
      (-> (orders-gateway/request-historical-orders! deps "0xabc" {:priority :high})
          (.then (fn [_]
                   (is (= [nil "0xabc" {:priority :high}] @called))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
