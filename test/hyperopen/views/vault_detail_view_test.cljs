(ns hyperopen.views.vault-detail-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.vault-detail-view :as vault-detail-view]))

(defn- node-children [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(def sample-state
  {:router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}
   :vaults-ui {:detail-tab :about
               :detail-activity-tab :positions
               :detail-chart-series :pnl
               :snapshot-range :month
               :detail-loading? false}
   :vaults {:errors {:details-by-address {}
                     :webdata-by-vault {}
                     :fills-by-vault {}
                     :funding-history-by-vault {}
                     :order-history-by-vault {}
                     :ledger-updates-by-vault {}}
            :loading {:fills-by-vault {}
                      :funding-history-by-vault {}
                      :order-history-by-vault {}
                      :ledger-updates-by-vault {}}
            :details-by-address {"0x1234567890abcdef1234567890abcdef12345678"
                                 {:name "Vault Detail"
                                  :leader "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                  :description "Sample vault"
                                  :portfolio {:month {:accountValueHistory [[1 10] [2 11] [3 15]]
                                                      :pnlHistory [[1 -1] [2 0.5] [3 2.5]]}}
                                  :followers [{:user "0xf1"} {:user "0xf2"}]
                                  :leader-commission 0.15
                                  :relationship {:type :parent
                                                 :child-addresses ["0x9999999999999999999999999999999999999999"]}
                                  :follower-state {:vault-equity 50
                                                   :all-time-pnl 12}}}
            :webdata-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                               {:openOrders [{:order {:coin "BTC"
                                                      :side "B"
                                                      :sz "0.1"
                                                      :limitPx "100"
                                                      :timestamp 9}}]
                                :twapStates [{:coin "BTC"
                                              :sz "1.0"
                                              :executedSz "0.1"
                                              :avgPx "101"
                                              :creationTime 20}]
                                :clearinghouseState {:assetPositions [{:position {:coin "BTC"
                                                                                   :szi "0.2"
                                                                                   :entryPx "100"
                                                                                   :positionValue "20"
                                                                                   :unrealizedPnl "1"
                                                                                   :returnOnEquity "0.05"}}]}}}
            :fills-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                             [{:time 3
                               :coin "BTC"
                               :side "buy"
                               :sz "0.5"
                               :px "101"}]}
            :funding-history-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                                       [{:time-ms 5
                                         :coin "BTC"
                                         :fundingRate 0.001
                                         :positionSize 3
                                         :payment -4.2}]}
            :order-history-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                                     [{:order {:coin "BTC"
                                               :side "B"
                                               :origSz "1.0"
                                               :limitPx "99"
                                               :orderType "Limit"
                                               :timestamp 10}
                                       :status "filled"
                                       :statusTimestamp 11}]}
            :ledger-updates-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                                      [{:time 12
                                        :hash "0xabc"
                                        :delta {:type "vaultDeposit"
                                                :vault "0x1234567890abcdef1234567890abcdef12345678"
                                                :usdc "10.0"}}]}
            :user-equity-by-address {"0x1234567890abcdef1234567890abcdef12345678"
                                     {:equity 50}}
            :merged-index-rows [{:name "Vault Detail"
                                 :vault-address "0x1234567890abcdef1234567890abcdef12345678"
                                 :leader "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                 :tvl 200
                                 :apr 0.2
                                 :snapshot-by-key {:month [0.1 0.2]
                                                   :all-time [0.5]}}]}})

(deftest vault-detail-view-renders-hero-metrics-tabs-and-activity-test
  (let [view (vault-detail-view/vault-detail-view sample-state)
        root (find-first-node view #(= "vault-detail-root" (get-in % [1 :data-parity-id])))
        detail-tab-button (find-first-node view
                                           #(= [[:actions/set-vault-detail-tab :vault-performance]]
                                               (get-in % [1 :on :click])))
        chart-tab-button (find-first-node view
                                          #(= [[:actions/set-vault-detail-chart-series :account-value]]
                                              (get-in % [1 :on :click])))
        activity-tab-button (find-first-node view
                                             #(= [[:actions/set-vault-detail-activity-tab :open-orders]]
                                                 (get-in % [1 :on :click])))
        text (set (collect-strings view))]
    (is (some? root))
    (is (some? detail-tab-button))
    (is (some? chart-tab-button))
    (is (some? activity-tab-button))
    (is (contains? text "Vault Detail"))
    (is (contains? text "Past Month Return"))
    (is (contains? text "Open Orders (1)"))
    (is (contains? text "Funding History (1)"))))

(deftest vault-detail-view-shows-invalid-message-when-route-address-is-invalid-test
  (let [view (vault-detail-view/vault-detail-view (assoc-in sample-state [:router :path] "/vaults/not-an-address"))
        text (set (collect-strings view))]
    (is (contains? text "Invalid vault address."))))

(deftest vault-detail-view-formats-large-activity-tab-counts-test
  (let [open-order {:order {:coin "BTC"
                            :side "B"
                            :sz "0.1"
                            :limitPx "100"}}
        state (assoc-in sample-state
                        [:vaults :webdata-by-vault "0x1234567890abcdef1234567890abcdef12345678" :openOrders]
                        (mapv (fn [idx]
                                (assoc-in open-order [:order :timestamp] idx))
                              (range 101)))
        view (vault-detail-view/vault-detail-view state)
        text (set (collect-strings view))]
    (is (contains? text "Open Orders (100+)"))))
