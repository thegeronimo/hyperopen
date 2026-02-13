(ns hyperopen.views.account-info.vm-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.vm :as vm]))

(defn- base-orders []
  {:open-orders []
   :open-orders-snapshot []
   :open-orders-snapshot-by-dex {}
   :order-history []})

(deftest account-info-vm-projects-global-state-into-tab-view-model-test
  (let [state {:account-info {:selected-tab :trade-history
                              :trade-history {:sort {:column "Time" :direction :desc}}
                              :funding-history {:page-size 25}
                              :order-history {:status-filter :all}}
               :asset-selector {:market-by-key {"xyz:NVDA" {:coin "xyz:NVDA"
                                                            :symbol "NVDA/USDC"}}}
               :webdata2 {:fills [{:tid 7 :coin "xyz:NVDA"}]
                          :fundings [{:id "f-1" :coin "xyz:NVDA"}]
                          :fundings-raw [{:coin "xyz:NVDA"}]}
               :orders {:open-orders [{:coin "xyz:NVDA"
                                       :oid 42
                                       :side "B"
                                       :sz "1.0"
                                       :limitPx "10.0"
                                       :timestamp 1700000000000}]
                        :open-orders-snapshot []
                        :open-orders-snapshot-by-dex {}
                        :order-history [{:status "open"
                                         :statusTimestamp 1700000000000
                                         :order {:coin "xyz:NVDA"
                                                 :oid 9}}]}
               :spot {:meta nil
                      :clearinghouse-state nil}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)]
    (is (= :trade-history (:selected-tab view-model)))
    (is (= 1 (count (:trade-history-rows view-model))))
    (is (= 1 (count (:funding-history-rows view-model))))
    (is (= 1 (count (:order-history-rows view-model))))
    (is (= 1 (count (:open-orders view-model))))
    (is (= {"xyz:NVDA" {:coin "xyz:NVDA"
                        :symbol "NVDA/USDC"}}
           (get-in view-model [:trade-history-state :market-by-key])))
    (is (= {"xyz:NVDA" {:coin "xyz:NVDA"
                        :symbol "NVDA/USDC"}}
           (get-in view-model [:order-history-state :market-by-key])))
    (is (= 1 (get-in view-model [:tab-counts :open-orders])))))

(deftest account-info-vm-derives-freshness-cues-from-websocket-health-test
  (let [state {:account-info {:selected-tab :open-orders}
               :webdata2 {}
               :orders (base-orders)
               :spot {:meta nil
                      :clearinghouse-state nil}
               :perp-dex-clearinghouse {}
               :wallet {:address "0xabc"}
               :websocket-health {:generated-at-ms 20000
                                  :streams {["openOrders" nil "0xabc" nil nil]
                                            {:topic "openOrders"
                                             :status :delayed
                                             :subscribed? true
                                             :last-payload-at-ms 8000
                                             :stale-threshold-ms 5000}
                                            ["webData2" nil "0xabc" nil nil]
                                            {:topic "webData2"
                                             :status :n-a
                                             :subscribed? true
                                             :last-payload-at-ms 17000}}}}
        view-model (vm/account-info-vm state)]
    (is (= :warning (get-in view-model [:freshness-cues :open-orders :tone])))
    (is (str/includes? (get-in view-model [:freshness-cues :open-orders :text]) "Stale 12s"))
    (is (str/includes? (get-in view-model [:freshness-cues :positions :text]) "Last update 3s ago"))))
