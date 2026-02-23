(ns hyperopen.views.account-info.vm-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
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
    (is (empty? (:open-orders view-model)))
    (is (= {"xyz:NVDA" {:coin "xyz:NVDA"
                        :symbol "NVDA/USDC"}}
           (get-in view-model [:trade-history-state :market-by-key])))
    (is (= {"xyz:NVDA" {:coin "xyz:NVDA"
                        :symbol "NVDA/USDC"}}
           (get-in view-model [:order-history-state :market-by-key])))
    (is (= 1 (get-in view-model [:tab-counts :open-orders])))))

(deftest account-info-vm-computes-heavy-derivations-only-for-selected-tab-test
  (let [base-state {:account-info {}
                    :webdata2 {:fills [{:tid 1}]}
                    :orders {:open-orders [{:coin "ETH" :oid 11}]
                             :open-orders-snapshot []
                             :open-orders-snapshot-by-dex {}}
                    :spot {:meta nil
                           :clearinghouse-state nil}
                    :account {:mode :classic}
                    :perp-dex-clearinghouse {}}
        calls (atom {:balances 0 :positions 0 :open-orders 0})]
    (binding [derived-cache/*build-balance-rows* (fn [_webdata2 _spot-data _account _market-by-key]
                                                   (swap! calls update :balances inc)
                                                   [])
              derived-cache/*collect-positions* (fn [_webdata2 _perp-dex-states]
                                                 (swap! calls update :positions inc)
                                                 [])
              derived-cache/*normalized-open-orders* (fn [_orders _snapshot _snapshot-by-dex _pending-cancel-oids]
                                                      (swap! calls update :open-orders inc)
                                                      [])]
      (vm/reset-account-info-vm-cache!)
      (vm/account-info-vm (assoc-in base-state [:account-info :selected-tab] :trade-history))
      (is (= {:balances 0 :positions 0 :open-orders 0} @calls))

      (vm/reset-account-info-vm-cache!)
      (vm/account-info-vm (assoc-in base-state [:account-info :selected-tab] :balances))
      (is (= {:balances 1 :positions 0 :open-orders 0} @calls))

      (vm/reset-account-info-vm-cache!)
      (vm/account-info-vm (assoc-in base-state [:account-info :selected-tab] :positions))
      (is (= {:balances 1 :positions 1 :open-orders 0} @calls))

      (vm/reset-account-info-vm-cache!)
      (vm/account-info-vm (assoc-in base-state [:account-info :selected-tab] :open-orders))
      (is (= {:balances 1 :positions 1 :open-orders 1} @calls)))))

(deftest account-info-vm-memoizes-selected-tab-derived-rows-by-input-identity-test
  (let [state {:account-info {:selected-tab :open-orders}
               :webdata2 {}
               :orders {:open-orders [{:coin "ETH" :oid 11}]
                        :open-orders-snapshot []
                        :open-orders-snapshot-by-dex {}}
               :spot {:meta nil
                      :clearinghouse-state nil}
               :account {:mode :classic}
               :perp-dex-clearinghouse {}}
        normalize-calls (atom 0)]
    (binding [derived-cache/*normalized-open-orders* (fn [_orders _snapshot _snapshot-by-dex _pending-cancel-oids]
                                                        (swap! normalize-calls inc)
                                                        [{:coin "ETH" :oid 11}])]
      (vm/reset-account-info-vm-cache!)
      (vm/account-info-vm state)
      (vm/account-info-vm state)
      (is (= 1 @normalize-calls))

      ;; Sort-state churn should not invalidate derived row identity cache.
      (vm/account-info-vm (assoc-in state [:account-info :open-orders-sort :direction] :asc))
      (is (= 1 @normalize-calls))

      ;; Input identity change should invalidate cache.
      (vm/account-info-vm (assoc state :orders (assoc (:orders state) :open-orders [{:coin "ETH" :oid 12}])))
      (is (= 2 @normalize-calls)))))

(deftest account-info-vm-derives-freshness-cues-from-websocket-health-test
  (let [state {:account-info {:selected-tab :open-orders}
               :webdata2 {}
               :orders (base-orders)
               :spot {:meta nil
                      :clearinghouse-state nil}
               :perp-dex-clearinghouse {}
               :websocket-ui {:show-surface-freshness-cues? true}
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

(deftest account-info-vm-hides-freshness-cues-when-toggle-disabled-test
  (let [state {:account-info {:selected-tab :open-orders}
               :webdata2 {}
               :orders (base-orders)
               :spot {:meta nil
                      :clearinghouse-state nil}
               :perp-dex-clearinghouse {}
               :websocket-ui {:show-surface-freshness-cues? false}
               :wallet {:address "0xabc"}
               :websocket-health {:generated-at-ms 20000
                                  :streams {["openOrders" nil "0xabc" nil nil]
                                            {:topic "openOrders"
                                             :status :delayed
                                             :subscribed? true
                                             :last-payload-at-ms 8000
                                             :stale-threshold-ms 5000}}}}
        view-model (vm/account-info-vm state)]
    (is (nil? (:freshness-cues view-model)))))

(deftest account-info-vm-builds-balance-pnl-from-asset-selector-spot-prices-when-webdata2-spot-ctxs-missing-test
  (let [state {:account-info {:selected-tab :balances}
               :webdata2 {}
               :orders (base-orders)
               :account {:mode :classic}
               :asset-selector {:market-by-key {"spot:MEOW/USDC" {:coin "MEOW/USDC"
                                                                   :mark 0.02}}}
               :spot {:meta {:tokens [{:index 0 :name "USDC" :weiDecimals 6}
                                      {:index 1 :name "MEOW" :weiDecimals 6}]
                             :universe [{:name "MEOW/USDC"
                                         :tokens [1 0]
                                         :index 0}]}
                      :clearinghouse-state {:balances [{:coin "MEOW"
                                                        :token 1
                                                        :hold "0.0"
                                                        :total "2.0"
                                                        :entryNtl "0.03"}]}}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)
        meow-row (some #(when (= "MEOW" (:coin %)) %) (:balance-rows view-model))]
    (is (some? meow-row))
    (is (< (js/Math.abs (- 0.04 (:usdc-value meow-row))) 0.000001))
    (is (< (js/Math.abs (- 0.01 (:pnl-value meow-row))) 0.000001))
    (is (< (js/Math.abs (- 33.3333333333 (:pnl-pct meow-row))) 0.000001))))

(deftest account-info-vm-builds-balance-pnl-when-entry-notional-uses-alternate-key-test
  (let [state {:account-info {:selected-tab :balances}
               :webdata2 {}
               :orders (base-orders)
               :account {:mode :classic}
               :asset-selector {:market-by-key {"spot:MEOW/USDC" {:coin "MEOW/USDC"
                                                                   :mark 0.02}}}
               :spot {:meta {:tokens [{:index 0 :name "USDC" :weiDecimals 6}
                                      {:index 1 :name "MEOW" :weiDecimals 6}]
                             :universe [{:name "MEOW/USDC"
                                         :tokens [1 0]
                                         :index 0}]}
                      :clearinghouse-state {:balances [{:coin "MEOW"
                                                        :token "1"
                                                        :hold "0.0"
                                                        :total "2.0"
                                                        :entryNotional "0.03"}]}}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)
        meow-row (some #(when (= "MEOW" (:coin %)) %) (:balance-rows view-model))]
    (is (some? meow-row))
    (is (< (js/Math.abs (- 0.01 (:pnl-value meow-row))) 0.000001))
    (is (< (js/Math.abs (- 33.3333333333 (:pnl-pct meow-row))) 0.000001))))

(deftest account-info-vm-builds-balance-pnl-from-market-base-coin-when-spot-meta-missing-test
  (let [state {:account-info {:selected-tab :balances}
               :webdata2 {}
               :orders (base-orders)
               :account {:mode :classic}
               :asset-selector {:market-by-key {"spot:@123" {:market-type :spot
                                                             :coin "@123"
                                                             :symbol "MEOW/USDC"
                                                             :base "MEOW"
                                                             :quote "USDC"
                                                             :mark 0.02
                                                             :szDecimals 6}}}
               :spot {:meta nil
                      :clearinghouse-state {:balances [{:coin "MEOW"
                                                        :hold "0.0"
                                                        :total "2.0"
                                                        :entryNtl "0.03"}]}}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)
        meow-row (some #(when (= "MEOW" (:coin %)) %) (:balance-rows view-model))]
    (is (some? meow-row))
    (is (= 6 (:amount-decimals meow-row)))
    (is (< (js/Math.abs (- 0.04 (:usdc-value meow-row))) 0.000001))
    (is (< (js/Math.abs (- 0.01 (:pnl-value meow-row))) 0.000001))
    (is (< (js/Math.abs (- 33.3333333333 (:pnl-pct meow-row))) 0.000001))))
