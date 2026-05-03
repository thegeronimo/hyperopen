(ns hyperopen.views.account-info.vm-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.vm :as vm]))

(defn- base-orders []
  {:open-orders []
   :open-orders-snapshot []
   :open-orders-snapshot-by-dex {}
   :order-history []})

(deftest account-info-vm-projects-global-state-into-tab-view-model-test
  (let [state {:account-info {:selected-tab :trade-history
                              :balances-coin-search "btc"
                              :trade-history {:sort {:column "Time" :direction :desc}
                                              :coin-search "liq"}
                              :open-orders {:coin-search "nv"}
                              :funding-history {:page-size 25}
                              :order-history {:status-filter :all
                                              :coin-search "nv"}}
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
    (is (= "btc" (:balances-coin-search view-model)))
    (is (= :all (get-in view-model [:positions-state :direction-filter])))
    (is (= "" (get-in view-model [:positions-state :coin-search])))
    (is (false? (get-in view-model [:positions-state :filter-open?])))
    (is (= :all (get-in view-model [:open-orders-state :direction-filter])))
    (is (= "nv" (get-in view-model [:open-orders-state :coin-search])))
    (is (false? (get-in view-model [:open-orders-state :filter-open?])))
    (is (= {"xyz:NVDA" {:coin "xyz:NVDA"
                        :symbol "NVDA/USDC"}}
           (get-in view-model [:open-orders-state :market-by-key])))
    (is (= "liq" (get-in view-model [:trade-history-state :coin-search])))
    (is (= {"xyz:NVDA" {:coin "xyz:NVDA"
                        :symbol "NVDA/USDC"}}
           (get-in view-model [:trade-history-state :market-by-key])))
    (is (= {"xyz:NVDA" {:coin "xyz:NVDA"
                        :symbol "NVDA/USDC"}}
           (get-in view-model [:order-history-state :market-by-key])))
    (is (= "nv" (get-in view-model [:order-history-state :coin-search])))
    (is (= 1 (get-in view-model [:tab-counts :open-orders])))))

(deftest account-info-vm-keeps-positions-filter-state-when-present-test
  (let [state {:account-info {:selected-tab :positions
                              :positions {:direction-filter :short
                                          :coin-search "eth"
                                          :filter-open? true}}
               :webdata2 {:clearinghouseState {:assetPositions []}}
               :orders (base-orders)
               :spot {:meta nil
                      :clearinghouse-state nil}
               :account {:mode :classic}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)]
    (is (= :short (get-in view-model [:positions-state :direction-filter])))
    (is (= "eth" (get-in view-model [:positions-state :coin-search])))
    (is (true? (get-in view-model [:positions-state :filter-open?])))))

(deftest account-info-vm-projects-inspected-account-read-only-flags-into-shared-tab-state-test
  (let [trader "0x1111111111111111111111111111111111111111"
        spectate-address "0x2222222222222222222222222222222222222222"
        base-state {:account-info {:selected-tab :positions}
                    :webdata2 {:clearinghouseState {:assetPositions []}}
                    :orders (base-orders)
                    :spot {:meta nil
                           :clearinghouse-state nil}
                    :account {:mode :classic}
                    :perp-dex-clearinghouse {}}
        read-only-cases [{:label "trader route"
                          :state (assoc base-state
                                        :router {:path (str "/portfolio/trader/" trader)})
                          :expected-message account-context/trader-portfolio-read-only-message}
                         {:label "spectate mode"
                          :state (assoc base-state
                                        :account-context {:spectate-mode {:active? true
                                                                          :address spectate-address}})
                          :expected-message account-context/spectate-mode-read-only-message}]]
    (doseq [{:keys [label state expected-message]} read-only-cases]
      (let [view-model (vm/account-info-vm state)]
        (is (true? (:read-only? view-model)) label)
        (is (= expected-message
               (:read-only-message view-model))
            label)
        (is (true? (get-in view-model [:positions-state :read-only?])) label)
        (is (true? (get-in view-model [:open-orders-state :read-only?])) label)
        (is (true? (get-in view-model [:twap-state :read-only?])) label)))))

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
      (is (= {:balances 1 :positions 1 :open-orders 1} @calls))

      (vm/reset-account-info-vm-cache!)
      (vm/account-info-vm (assoc-in base-state [:account-info :selected-tab] :open-orders))
      (is (= {:balances 1 :positions 1 :open-orders 2} @calls)))))

(deftest account-info-vm-attaches-position-tpsl-trigger-prices-from-open-orders-test
  (let [state {:account-info {:selected-tab :positions}
               :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "HYPE"
                                                                             :szi "1.0"
                                                                             :entryPx "10"
                                                                             :positionValue "10"
                                                                             :unrealizedPnl "0.5"
                                                                             :returnOnEquity "0.1"
                                                                             :liquidationPx "5"
                                                                             :marginUsed "1"
                                                                             :cumFunding {:allTime "0"}}}]}}
               :orders {:open-orders [{:coin "HYPE"
                                       :oid 101
                                       :orderType "Take Profit Market"
                                       :isTrigger true
                                       :triggerPx "11.5"
                                       :isPositionTpsl true
                                       :timestamp 1700000001000}
                                      {:coin "HYPE"
                                       :oid 102
                                       :orderType "Stop Market"
                                       :isTrigger true
                                       :triggerPx "9.5"
                                       :isPositionTpsl true
                                       :timestamp 1700000000000}]
                        :open-orders-snapshot []
                        :open-orders-snapshot-by-dex {}}
               :spot {:meta nil
                      :clearinghouse-state nil}
               :account {:mode :classic}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)
        row (first (:positions view-model))]
    (is (= "11.5" (:position-tp-trigger-px row)))
    (is (= "9.5" (:position-sl-trigger-px row)))))

(deftest account-info-vm-attaches-reduce-only-tpsl-triggers-even-when-is-position-flag-missing-test
  (let [state {:account-info {:selected-tab :positions}
               :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "BERA"
                                                                             :szi "169.3"
                                                                             :entryPx "0.59014"
                                                                             :positionValue "99.80"
                                                                             :unrealizedPnl "0.11"
                                                                             :returnOnEquity "0.005"
                                                                             :liquidationPx "1.63655604"
                                                                             :marginUsed "19.96"
                                                                             :cumFunding {:allTime "0"}}}]}}
               :orders {:open-orders [{:coin "BERA"
                                       :oid 201
                                       :orderType "Take Profit Market"
                                       :isTrigger true
                                       :triggerPx "0.57831"
                                       :reduceOnly true
                                       :isPositionTpsl false
                                       :timestamp 1700000001000}]
                        :open-orders-snapshot []
                        :open-orders-snapshot-by-dex {}}
               :spot {:meta nil
                      :clearinghouse-state nil}
               :account {:mode :classic}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)
        row (first (:positions view-model))]
    (is (= "0.57831" (:position-tp-trigger-px row)))
    (is (nil? (:position-sl-trigger-px row)))))

(deftest account-info-vm-attaches-market-mark-price-to-positions-when-row-mark-missing-test
  (let [state {:account-info {:selected-tab :positions}
               :asset-selector {:market-by-key {"perp:xyz:GOLD" {:key "perp:xyz:GOLD"
                                                                  :coin "xyz:GOLD"
                                                                  :mark 5354.2}}}
               :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "xyz:GOLD"
                                                                             :szi "0.0185"
                                                                             :entryPx "5382.40"
                                                                             :positionValue "99.06"
                                                                             :unrealizedPnl "-0.51"
                                                                             :returnOnEquity "-0.103"
                                                                             :liquidationPx "4407.8"
                                                                             :marginUsed "19.15"
                                                                             :cumFunding {:allTime "-0.03"}}}]}}
               :orders (base-orders)
               :spot {:meta nil
                      :clearinghouse-state nil}
               :account {:mode :classic}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)
        row (first (:positions view-model))]
    (is (= 5354.2 (get-in row [:position :markPx])))))

(deftest account-info-vm-derives-twap-tab-rows-and-state-test
  (let [state {:account-info {:selected-tab :twap
                              :twap {:selected-subtab :history}
                              :trade-history {:coin-search "eth"}}
               :orders {:twap-states [[17 {:coin "BTC"
                                           :side "B"
                                           :sz "1.0"
                                           :executedSz "0.4"
                                           :executedNtl "40.0"
                                           :minutes 30
                                           :timestamp 1700000000000
                                           :reduceOnly false}]]
                        :twap-history [{:time 1700001000
                                        :status {:status "finished"}
                                        :state {:coin "BTC"
                                                :side "B"
                                                :sz "1.0"
                                                :executedSz "1.0"
                                                :executedNtl "100.0"
                                                :minutes 30
                                                :timestamp 1700000000000
                                                :reduceOnly false
                                                :randomize true}}]
                        :twap-slice-fills [{:tid 9
                                            :coin "BTC"
                                            :time 1700002000000
                                            :px "100.0"
                                            :sz "0.1"}]}
               :spot {:meta nil
                      :clearinghouse-state nil}
               :account {:mode :classic}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)]
    (is (= :history (get-in view-model [:twap-state :selected-subtab])))
    (is (= 1 (count (:twap-active-rows view-model))))
    (is (= 1 (count (:twap-history-rows view-model))))
    (is (= 1 (count (:twap-fill-rows view-model))))
    (is (= :all (get-in view-model [:twap-fill-state :direction-filter])))
    (is (= "" (get-in view-model [:twap-fill-state :coin-search])))
    (is (= 1 (get-in view-model [:tab-counts :twap])))))

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

(deftest account-info-vm-carries-market-metadata-for-selected-open-orders-tab-test
  (let [state {:account-info {:selected-tab :open-orders}
               :asset-selector {:market-by-key {"spot:@107" {:coin "@107"
                                                             :market-type :spot
                                                             :symbol "AAPL/USDC"
                                                             :base "AAPL"
                                                             :quote "USDC"}}}
               :webdata2 {}
               :orders {:open-orders [{:coin "@107"
                                       :oid 42
                                       :side "B"
                                       :sz "1.0"
                                       :limitPx "10.0"
                                       :timestamp 1700000000000}]
                        :open-orders-snapshot []
                        :open-orders-snapshot-by-dex {}}
               :spot {:meta nil
                      :clearinghouse-state nil}
               :account {:mode :classic}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)
        order-row (first (:open-orders view-model))]
    (is (= :open-orders (:selected-tab view-model)))
    (is (= 1 (count (:open-orders view-model))))
    (is (= "@107" (:coin order-row)))
    (is (= "42" (:oid order-row)))
    (is (= "AAPL"
           (get-in view-model [:open-orders-state :market-by-key "spot:@107" :base])))))

(deftest account-info-vm-projects-order-cancel-error-into-open-orders-state-test
  (let [state {:account-info {:selected-tab :open-orders}
               :webdata2 {}
               :orders {:open-orders [{:coin "BTC"
                                       :oid 42
                                       :side "B"
                                       :sz "1.0"
                                       :limitPx "10.0"
                                       :timestamp 1700000000000}]
                        :open-orders-snapshot []
                        :open-orders-snapshot-by-dex {}
                        :cancel-error "Missing asset or order id."}
               :spot {:meta nil
                      :clearinghouse-state nil}
               :account {:mode :classic}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)]
    (is (= "Missing asset or order id."
           (get-in view-model [:open-orders-state :cancel-error])))))

(deftest account-info-vm-derives-freshness-cues-from-websocket-health-test
  (let [state {:account-info {:selected-tab :open-orders}
               :webdata2 {}
               :orders (base-orders)
               :spot {:meta nil
                      :clearinghouse-state nil}
               :perp-dex-clearinghouse {}
               :websocket-ui {:show-surface-freshness-cues? true}
               :wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
               :account-context {:spectate-mode {:active? true
                                              :address "0xdddddddddddddddddddddddddddddddddddddddd"}}
               :websocket {:health {:generated-at-ms 20000
                                    :streams {["openOrders" nil "0xdddddddddddddddddddddddddddddddddddddddd" nil nil]
                                              {:topic "openOrders"
                                               :status :delayed
                                               :subscribed? true
                                               :last-payload-at-ms 8000
                                               :stale-threshold-ms 5000}
                                              ["webData2" nil "0xdddddddddddddddddddddddddddddddddddddddd" nil nil]
                                              {:topic "webData2"
                                               :status :n-a
                                               :subscribed? true
                                               :last-payload-at-ms 17000}}}}}
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
               :wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
               :websocket {:health {:generated-at-ms 20000
                                    :streams {["openOrders" nil "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" nil nil]
                                              {:topic "openOrders"
                                               :status :delayed
                                               :subscribed? true
                                               :last-payload-at-ms 8000
                                               :stale-threshold-ms 5000}}}}}
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

(deftest account-info-vm-projects-outcome-balances-into-outcomes-tab-and-count-test
  (let [outcome-market {:key "outcome:0"
                        :coin "#0"
                        :title "BTC above 78213 on May 3 at 2:00 AM?"
                        :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                        :quote "USDH"
                        :market-type :outcome
                        :outcome-id 0
                        :outcome-sides [{:side-index 0
                                         :side-name "Yes"
                                         :coin "#0"
                                         :mark 0.57042}]}
        state {:account-info {:selected-tab :outcomes}
               :asset-selector {:market-by-key {"outcome:0" outcome-market}}
               :webdata2 {}
               :orders (base-orders)
               :account {:mode :classic}
               :spot {:meta nil
                      :clearinghouse-state {:balances [{:coin "+0"
                                                        :hold "0"
                                                        :total "19"
                                                        :entryNtl "11.0271"}
                                                       {:coin "HYPE"
                                                        :hold "0"
                                                        :total "2"
                                                        :entryNtl "0"}]}}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)
        [outcome-row] (:outcomes view-model)]
    (is (= :outcomes (:selected-tab view-model)))
    (is (= 1 (get-in view-model [:tab-counts :outcomes])))
    (is (= 1 (count (:outcomes view-model))))
    (is (= "BTC above 78213 on May 3 at 2:00 AM?" (:title outcome-row)))
    (is (= "Yes" (:side-name outcome-row)))))

(deftest account-info-vm-enriches-outcome-rows-from-active-market-and-contexts-test
  (let [outcome-market {:key "outcome:0"
                        :coin "#0"
                        :title "BTC above 78213 on May 3 at 2:00 AM?"
                        :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                        :quote "USDH"
                        :market-type :outcome
                        :outcome-id 0
                        :outcome-sides [{:side-index 0
                                         :side-name "Yes"
                                         :coin "#0"}
                                        {:side-index 1
                                         :side-name "No"
                                         :coin "#1"}]}
        state {:account-info {:selected-tab :outcomes}
               :active-market outcome-market
               :active-assets {:contexts {"#0" {:mark 0.53210
                                                 :markRaw "0.53210"}}}
               :asset-selector {:market-by-key {}}
               :webdata2 {}
               :orders (base-orders)
               :account {:mode :classic}
               :spot {:meta nil
                      :clearinghouse-state {:balances [{:coin "+0"
                                                        :token 100000000
                                                        :hold "0"
                                                        :total "19"
                                                        :entryNtl "11.0271"}
                                                       {:coin "+1"
                                                        :token 100000001
                                                        :hold "0"
                                                        :total "0"
                                                        :entryNtl "0"}]}}
               :perp-dex-clearinghouse {}}
        view-model (vm/account-info-vm state)
        [outcome-row] (:outcomes view-model)]
    (is (= 1 (get-in view-model [:tab-counts :outcomes])))
    (is (= 1 (count (:outcomes view-model))))
    (is (= "BTC above 78213 on May 3 at 2:00 AM?" (:title outcome-row)))
    (is (= "Yes" (:side-name outcome-row)))
    (is (= 0.53210 (:mark-price outcome-row)))
    (is (< (js/Math.abs (- 10.1099 (:position-value outcome-row))) 0.000001))
    (is (< (js/Math.abs (- -0.9172 (:pnl-value outcome-row))) 0.000001))))
