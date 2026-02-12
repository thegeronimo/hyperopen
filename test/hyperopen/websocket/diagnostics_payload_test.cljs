(ns hyperopen.websocket.diagnostics-payload-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.websocket.diagnostics-payload :as diagnostics-payload]))

(deftest diagnostics-stream-rows-sorts-deterministically-test
  (let [health {:streams {["trades" "ETH" nil nil nil]
                          {:group :market_data
                           :topic "trades"
                           :status :live}
                          ["openOrders" nil "0xabc" nil nil]
                          {:group :orders_oms
                           :topic "openOrders"
                           :status :live}
                          ["l2Book" "BTC" nil nil nil]
                          {:group :market_data
                           :topic "l2Book"
                           :status :live}}}
        rows (diagnostics-payload/diagnostics-stream-rows health)]
    (is (= [:market_data :market_data :orders_oms]
           (mapv :group rows)))
    (is (= ["l2Book" "trades" "openOrders"]
           (mapv :topic rows)))))

(deftest diagnostics-copy-payload-merges-counter-defaults-test
  (let [state {:websocket-ui {:reconnect-count 2
                              :reset-counts {:orders_oms 3}
                              :auto-recover-count 1
                              :diagnostics-timeline [{:event :connected :at-ms 1}]}}
        health {:generated-at-ms 1700000000000
                :transport {:state :connected}
                :groups {:market_data {:worst-status :live}}
                :streams {}}
        payload (diagnostics-payload/diagnostics-copy-payload state health "0.1.0")]
    (is (= {:market_data 0 :orders_oms 3 :all 0}
           (get-in payload [:counters :reset-counts])))
    (is (= 2 (get-in payload [:counters :reconnect-count])))
    (is (= 1 (get-in payload [:counters :auto-recover-count])))
    (is (= [{:event :connected :at-ms 1}]
           (:timeline payload)))
    (is (= "0.1.0" (get-in payload [:app :version])))))

(deftest copy-status-helpers-return-expected-shapes-test
  (let [health {:generated-at-ms 1700000000000}
        error-status (diagnostics-payload/copy-error-status health "{json}")
        success-status (diagnostics-payload/copy-success-status health)]
    (is (= 1700000000000 (:at-ms error-status)))
    (is (= :error (:kind error-status)))
    (is (= "{json}" (:fallback-json error-status)))
    (is (= :success (:kind success-status)))
    (is (= "Copied (redacted)" (:message success-status)))))
