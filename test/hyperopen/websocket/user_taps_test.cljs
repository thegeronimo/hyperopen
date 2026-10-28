(ns hyperopen.websocket.user-taps-test
  "Raw evidence taps that `hyperopen.websocket.user/init!` wires into the
  userFills and openOrders handlers."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.platform :as platform]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.user :as user-ws]))

(defn- make-store []
  (atom {:orders {:fills []}
         :wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
         :ui {:toast nil :toasts []}}))

(deftest init-passes-raw-taps-to-the-fills-and-open-orders-handlers-test
  (let [active-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        stale-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        store (make-store)
        handlers (atom {})
        fills-calls (atom [])
        orders-calls (atom [])]
    (with-redefs [ws-client/register-handler!
                  (fn [message-type handler-fn]
                    (swap! handlers assoc message-type handler-fn)
                    true)
                  platform/now-ms (fn [] 777)]
      (user-ws/init! store {:on-raw-user-fills (fn [s msg at] (swap! fills-calls conj [s msg at]))
                            :on-raw-open-orders (fn [s msg at] (swap! orders-calls conj [s msg at]))})
      (let [empty-snapshot {:channel "userFills"
                            :data {:user active-address :isSnapshot true :fills []}}
            stale-fills {:channel "userFills"
                         :user stale-address
                         :data {:isSnapshot false :fills [{:tid 3 :coin "BTC" :time 3000}]}}
            open-orders {:channel "openOrders"
                         :user stale-address
                         :data {:orders [{:oid 11}]}}]
        ((get @handlers "userFills") empty-snapshot)
        ((get @handlers "userFills") stale-fills)
        ((get @handlers "openOrders") open-orders)
        ((get @handlers "userFundings") {:channel "userFundings"
                                         :data {:user active-address :fundings []}})
        (testing "the fills tap sees every raw userFills message, including other users'"
          (is (= 2 (count @fills-calls)))
          (is (identical? empty-snapshot (second (first @fills-calls))))
          (is (identical? stale-fills (second (second @fills-calls))))
          (is (every? #(identical? store (first %)) @fills-calls))
          (is (every? #(= 777 (nth % 2)) @fills-calls)))
        (testing "the open-orders tap sees only openOrders messages"
          (is (= 1 (count @orders-calls)))
          (is (identical? open-orders (second (first @orders-calls)))))
        (testing "the display path still ignores other users' messages"
          (is (empty? (get-in @store [:orders :fills])))
          (is (nil? (get-in @store [:orders :open-orders]))))))))

(deftest init-without-taps-registers-the-same-channels-test
  (let [plain (atom #{})
        tapped (atom #{})]
    (with-redefs [ws-client/register-handler! (fn [message-type _] (swap! plain conj message-type) true)]
      (user-ws/init! (make-store)))
    (with-redefs [ws-client/register-handler! (fn [message-type _] (swap! tapped conj message-type) true)]
      (user-ws/init! (make-store) {}))
    (is (= @plain @tapped))
    (is (contains? @plain "userFills"))
    (is (contains? @plain "openOrders"))))
