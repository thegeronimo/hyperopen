(ns hyperopen.views.vaults.detail-vm-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.views.vaults.detail-vm :as detail-vm]))

(defn- approx=
  [expected actual tolerance]
  (and (number? actual)
       (< (js/Math.abs (- expected actual)) tolerance)))

(use-fixtures :each
  (fn [f]
    (detail-vm/reset-vault-detail-vm-cache!)
    (f)
    (detail-vm/reset-vault-detail-vm-cache!)))

(def sample-state
  {:router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}
   :vaults-ui {:detail-tab :about
               :detail-activity-tab :performance-metrics
               :detail-chart-series :pnl
               :detail-returns-benchmark-coins ["BTC"]
               :detail-returns-benchmark-coin "BTC"
               :detail-returns-benchmark-search ""
               :detail-returns-benchmark-suggestions-open? false
               :snapshot-range :month
               :detail-loading? false}
   :asset-selector {:markets [{:coin "BTC"
                               :symbol "BTC"
                               :dex "hl"
                               :market-type :perp
                               :openInterest 1000}]}
   :candles {"BTC" {:1h [[1 0 0 0 100]
                         [2 0 0 0 110]
                         [3 0 0 0 120]]}}
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
                                  :relationship {:type :child
                                                 :parent-address "0x9999999999999999999999999999999999999999"}
                                  :follower-state {:vault-equity 50
                                                   :all-time-pnl 12}}}
            :webdata-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                               {:fills [{:time 3
                                         :coin "BTC"
                                         :side "buy"
                                         :sz "0.5"
                                         :px "101"}
                                        {:time 4
                                         :coin "ETH"
                                         :side "sell"
                                         :sz "1.2"
                                         :px "202"}]
                               :openOrders [{:order {:coin "BTC"
                                                      :side "B"
                                                      :sz "0.1"
                                                      :limitPx "100"
                                                      :timestamp 9}}]
                                :twapStates [{:coin "BTC"
                                              :sz "1.0"
                                              :executedSz "0.2"
                                              :avgPx "101"
                                              :creationTime 12}]
                                :clearinghouseState {:assetPositions [{:position {:coin "BTC"
                                                                                   :szi "0.2"
                                                                                   :entryPx "100"
                                                                                   :positionValue "20"
                                                                                   :unrealizedPnl "1"
                                                                                   :returnOnEquity "0.05"}}
                                                                       {:position {:coin "ETH"
                                                                                   :szi "-1.2"
                                                                                   :entryPx "200"
                                                                                   :positionValue "240"
                                                                                   :unrealizedPnl "-2"
                                                                                   :returnOnEquity "-0.1"}}]}}}
            :fills-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                             [{:time 3
                               :coin "BTC"
                               :side "buy"
                               :sz "0.5"
                               :px "101"}
                              {:time 4
                               :coin "ETH"
                               :side "sell"
                               :sz "1.2"
                               :px "202"}]}
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

(deftest vault-detail-vm-builds-metrics-relationship-chart-and-activity-test
  (let [vm (detail-vm/vault-detail-vm sample-state)
        strategy-series (first (get-in vm [:chart :series]))]
    (is (= :detail (:kind vm)))
    (is (= "Vault Detail" (:name vm)))
    (is (= "0x1234567890abcdef1234567890abcdef12345678" (:vault-address vm)))
    (is (= :child (get-in vm [:relationship :type])))
    (is (= "0x9999999999999999999999999999999999999999"
           (get-in vm [:relationship :parent-address])))
    (is (= 200 (get-in vm [:metrics :tvl])))
    (is (approx= 34.61538461538461
                 (get-in vm [:metrics :past-month-return])
                 1e-9))
    (is (= 50 (get-in vm [:metrics :your-deposit])))
    (is (= 12 (get-in vm [:metrics :all-time-earned])))
    (is (= false (get-in vm [:vault-transfer :can-open-deposit?])))
    (is (= true (get-in vm [:vault-transfer :can-open-withdraw?])))
    (is (= false (get-in vm [:vault-transfer :open?])))
    (is (= 2 (:followers vm)))
    (is (seq (get-in vm [:chart :points])))
    (is (nil? (get-in vm [:chart :path])))
    (is (nil? (:area-path strategy-series)))
    (is (= "rgba(22, 214, 161, 0.24)" (:area-positive-fill strategy-series)))
    (is (= "rgba(237, 112, 136, 0.24)" (:area-negative-fill strategy-series)))
    (is (number? (:zero-y-ratio strategy-series)))
    (is (= :pnl (get-in vm [:chart :selected-series])))
    (is (= 3 (count (get-in vm [:chart :series-tabs]))))
    (is (= :month (get-in vm [:chart :selected-timeframe])))
    (is (= ["24H" "7D" "30D" "3M" "6M" "1Y" "2Y" "All-time"]
           (mapv :label (get-in vm [:chart :timeframe-options]))))
    (is (seq (get-in vm [:performance-metrics :groups])))
    (is (= :performance-metrics (get-in vm [:activity-tabs 0 :value])))
    (is (= :performance-metrics (:selected-activity-tab vm)))
    (is (= 2 (count (:activity-positions vm))))
    (is (= 1 (count (:activity-open-orders vm))))
    (is (= 2 (count (:activity-fills vm))))
    (is (= 1 (count (:activity-funding-history vm))))
    (is (= 1 (count (:activity-order-history vm))))
    (is (= 1 (count (:activity-deposits-withdrawals vm))))
    (is (= 2 (count (:activity-depositors vm))))
    (is (= 2 (get-in vm [:activity-summary :fill-count])))
    (is (= 1 (get-in vm [:activity-summary :open-order-count])))
    (is (= 2 (get-in vm [:activity-summary :position-count])))))

(deftest vault-detail-vm-builds-open-vault-transfer-modal-model-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        leader-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        state (-> sample-state
                  (assoc-in [:wallet :address] leader-address)
                  (assoc-in [:wallet :agent :status] :ready)
                  (assoc-in [:webdata2 :clearinghouseState :withdrawable] 159.379)
                  (assoc-in [:vaults-ui :vault-transfer-modal]
                            {:open? true
                             :mode :deposit
                             :vault-address vault-address
                             :amount-input "1.5"
                             :withdraw-all? false
                             :submitting? false
                             :error nil})
                  (assoc-in [:vaults :details-by-address vault-address :allow-deposits?] true)
                  (assoc-in [:vaults :details-by-address vault-address :name]
                            "Hyperliquidity Provider (HLP)"))
        vm (detail-vm/vault-detail-vm state)]
    (is (= true (get-in vm [:vault-transfer :open?])))
    (is (= :deposit (get-in vm [:vault-transfer :mode])))
    (is (= true (get-in vm [:vault-transfer :can-open-deposit?])))
    (is (= "Deposit" (get-in vm [:vault-transfer :title])))
    (is (= "Deposit" (get-in vm [:vault-transfer :confirm-label])))
    (is (= "159.37" (get-in vm [:vault-transfer :deposit-max-display])))
    (is (= "159.37" (get-in vm [:vault-transfer :deposit-max-input])))
    (is (= 4 (get-in vm [:vault-transfer :deposit-lockup-days])))
    (is (= "Deposit funds to Hyperliquidity Provider (HLP). The deposit lock-up period is 4 days."
           (get-in vm [:vault-transfer :deposit-lockup-copy])))
    (is (= false (get-in vm [:vault-transfer :submit-disabled?])))
    (is (= true (get-in vm [:vault-transfer :preview-ok?])))))

(deftest vault-detail-vm-uses-follower-lockup-window-for-deposit-copy-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        leader-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        state (-> sample-state
                  (assoc-in [:wallet :address] leader-address)
                  (assoc-in [:wallet :agent :status] :ready)
                  (assoc-in [:webdata2 :clearinghouseState :withdrawable] 50)
                  (assoc-in [:vaults-ui :vault-transfer-modal]
                            {:open? true
                             :mode :deposit
                             :vault-address vault-address
                             :amount-input "1"
                             :withdraw-all? false
                             :submitting? false
                             :error nil})
                  (assoc-in [:vaults :details-by-address vault-address :allow-deposits?] true)
                  (assoc-in [:vaults :details-by-address vault-address :name] "Vault Detail")
                  (assoc-in [:vaults :details-by-address vault-address :follower-state :vault-entry-time-ms] 1000)
                  (assoc-in [:vaults :details-by-address vault-address :follower-state :lockup-until-ms]
                            (+ 1000 (* 2 24 60 60 1000))))
        vm (detail-vm/vault-detail-vm state)]
    (is (= 2 (get-in vm [:vault-transfer :deposit-lockup-days])))
    (is (= "Deposit funds to Vault Detail. The deposit lock-up period is 2 days."
           (get-in vm [:vault-transfer :deposit-lockup-copy])))))

(deftest vault-detail-vm-flags-invalid-vault-addresses-test
  (let [vm (detail-vm/vault-detail-vm (assoc-in sample-state [:router :path] "/vaults/not-an-address"))]
    (is (= :detail (:kind vm)))
    (is (true? (:invalid-address? vm)))))

(deftest vault-detail-vm-uses-month-portfolio-return-and-normalizes-depositor-count-test
  (let [state (-> sample-state
                  (assoc-in [:vaults :details-by-address "0x1234567890abcdef1234567890abcdef12345678" :apr] 0.21)
                  (assoc-in [:vaults :details-by-address "0x1234567890abcdef1234567890abcdef12345678" :portfolio :month]
                            {:accountValueHistory [[1 100] [2 110]]
                             :pnlHistory [[1 0] [2 10]]})
                  (assoc-in [:vaults :details-by-address "0x1234567890abcdef1234567890abcdef12345678" :followers] [])
                  (assoc-in [:vaults :details-by-address "0x1234567890abcdef1234567890abcdef12345678" :followers-count] 137)
                  (assoc-in [:vaults :merged-index-rows 0 :snapshot-by-key :month] [0.0 0.19]))
        vm (detail-vm/vault-detail-vm state)]
    (is (approx= 10
                 (get-in vm [:metrics :past-month-return])
                 1e-9))
    (is (= 21 (get-in vm [:metrics :apr])))
    (is (= 137 (:followers vm)))
    (is (= 137
           (some->> (:activity-tabs vm)
                    (filter #(= :depositors (:value %)))
                    first
                    :count)))))

(deftest vault-detail-vm-leaves-month-return-missing-when-summary-and-snapshot-are-both-absent-test
  (let [state (-> sample-state
                  (assoc-in [:vaults :details-by-address "0x1234567890abcdef1234567890abcdef12345678" :apr] 0.21)
                  (assoc-in [:vaults :details-by-address "0x1234567890abcdef1234567890abcdef12345678" :portfolio] {})
                  (assoc-in [:vaults :merged-index-rows 0 :snapshot-by-key] {:all-time [0.5]}))
        vm (detail-vm/vault-detail-vm state)]
    (is (nil? (get-in vm [:metrics :past-month-return])))
    (is (= 21 (get-in vm [:metrics :apr])))))

(deftest vault-detail-vm-uses-viewer-scoped-vault-details-for-account-metrics-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        viewer-address "0xffffffffffffffffffffffffffffffffffffffff"
        state (-> sample-state
                  (assoc-in [:wallet :address] "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                  (assoc-in [:account-context :spectate-mode] {:active? true
                                                               :address viewer-address})
                  (assoc-in [:vaults :user-equity-by-address] {})
                  (update-in [:vaults :details-by-address vault-address] dissoc :follower-state)
                  (assoc-in [:vaults :viewer-details-by-address vault-address viewer-address]
                            {:follower-state {:user viewer-address
                                              :vault-equity 88
                                              :all-time-pnl 13
                                              :vault-entry-time-ms 1000
                                              :lockup-until-ms (+ 1000 (* 3 24 60 60 1000))}}))
        vm (detail-vm/vault-detail-vm state)]
    (is (= 88 (get-in vm [:metrics :your-deposit])))
    (is (= 13 (get-in vm [:metrics :all-time-earned])))
    (is (= 3 (get-in vm [:vault-transfer :deposit-lockup-days])))))

(deftest vault-detail-vm-selects-account-value-series-when-user-selects-it-test
  (let [state (assoc-in sample-state [:vaults-ui :detail-chart-series] :account-value)
        vm (detail-vm/vault-detail-vm state)
        strategy-series (first (get-in vm [:chart :series]))]
    (is (= :account-value (get-in vm [:chart :selected-series])))
    (is (= "#f7931a" (:stroke strategy-series)))
    (is (nil? (:area-path strategy-series)))
    (is (= "rgba(247, 147, 26, 0.24)" (:area-fill strategy-series)))))

(deftest vault-detail-vm-builds-returns-chart-series-and-benchmark-performance-columns-test
  (let [state (assoc-in sample-state [:vaults-ui :detail-chart-series] :returns)
        vm (detail-vm/vault-detail-vm state)
        strategy-series (first (get-in vm [:chart :series]))]
    (is (= :returns (get-in vm [:chart :selected-series])))
    (is (= :returns (get-in vm [:chart :axis-kind])))
    (is (= 2 (count (get-in vm [:chart :series]))))
    (is (nil? (:area-path strategy-series)))
    (is (seq (get-in vm [:chart :points])))
    (is (= [0 15.38 34.62]
           (mapv :value (get-in vm [:chart :points]))))
    (is (= ["BTC"] (get-in vm [:performance-metrics :benchmark-coins])))
    (is (= "BTC (HL PERP)" (get-in vm [:performance-metrics :benchmark-label])))
    (is (seq (get-in vm [:performance-metrics :groups])))))

(deftest vault-detail-vm-reports-background-benchmark-sync-while-market-history-is-missing-test
  (let [state (-> sample-state
                  (assoc-in [:vaults-ui :detail-chart-series] :returns)
                  (assoc-in [:candles "BTC" :1h] []))
        vm (detail-vm/vault-detail-vm state)
        background-status (:background-status vm)]
    (is (true? (:visible? background-status)))
    (is (= "Vault analytics are still syncing" (:title background-status)))
    (is (= "The chart is ready. The remaining analytics will fill in automatically."
           (:detail background-status)))
    (is (= [{:id :benchmark-history
             :label "Benchmark history"}]
           (:items background-status)))
    (is (true? (get-in vm [:performance-metrics :loading?])))))

(deftest vault-detail-vm-limits-vault-benchmark-candidates-to-top-100-by-tvl-test
  (let [address-for (fn [idx]
                      (let [suffix (str idx)
                            zero-count (max 0 (- 40 (count suffix)))]
                        (str "0x"
                             (apply str (repeat zero-count "0"))
                             suffix)))
        top-address (address-for 105)
        cutoff-address (address-for 6)
        excluded-address (address-for 5)
        vault-rows (mapv (fn [idx]
                           {:name (str "Vault " idx)
                            :vault-address (address-for idx)
                            :relationship {:type :normal}
                            :tvl idx})
                         (range 1 106))
        state (-> sample-state
                  (assoc-in [:vaults-ui :detail-returns-benchmark-coins]
                            [(str "vault:" excluded-address)])
                  (assoc-in [:vaults-ui :detail-returns-benchmark-coin]
                            (str "vault:" excluded-address))
                  (assoc-in [:vaults-ui :detail-returns-benchmark-search] "vault")
                  (assoc-in [:vaults :merged-index-rows]
                            (conj vault-rows
                                  {:name "Child Vault"
                                   :vault-address "0x3333333333333333333333333333333333333333"
                                   :relationship {:type :child}
                                   :tvl 1000})))
        vm (detail-vm/vault-detail-vm state)
        benchmark-selector (get-in vm [:chart :returns-benchmark])
        candidates (->> (:candidates benchmark-selector)
                        (filter (fn [{:keys [value]}]
                                  (str/starts-with? value "vault:")))
                        vec)]
    (is (= 100 (count candidates)))
    (is (= (str "vault:" top-address)
           (some-> candidates first :value)))
    (is (= (str "vault:" cutoff-address)
           (some-> candidates last :value)))
    (is (not-any? #(= (str "vault:" excluded-address) (:value %))
                  candidates))
    (is (= [] (:selected-coins benchmark-selector)))))

(deftest vault-detail-vm-builds-vault-benchmark-series-and-performance-columns-test
  (let [peer-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        peer-ref (str "vault:" peer-address)
        benchmark-summary {:summary-id :peer-vault-month}
        base-returns-history-rows portfolio-metrics/returns-history-rows
        state (-> sample-state
                  (assoc-in [:vaults-ui :detail-chart-series] :returns)
                  (assoc-in [:vaults-ui :detail-returns-benchmark-coins] [peer-ref])
                  (assoc-in [:vaults-ui :detail-returns-benchmark-coin] peer-ref)
                  (assoc-in [:vaults :benchmark-details-by-address peer-address :portfolio :month]
                            benchmark-summary)
                  (assoc-in [:vaults :merged-index-rows]
                            [{:name "Vault Detail"
                              :vault-address "0x1234567890abcdef1234567890abcdef12345678"
                              :leader "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                              :tvl 200
                              :apr 0.2}
                             {:name "Peer Vault"
                              :vault-address peer-address
                              :relationship {:type :normal}
                              :tvl 180}]))]
    (with-redefs [portfolio-metrics/returns-history-rows
                  (fn [state summary scope]
                    (if (= summary benchmark-summary)
                      [[1 0] [2 8] [3 12]]
                      (base-returns-history-rows state summary scope)))]
      (let [vm (detail-vm/vault-detail-vm state)
            benchmark-series (second (get-in vm [:chart :series]))]
        (is (= "Peer Vault (VAULT)" (:label benchmark-series)))
        (is (= [0 8 12]
               (mapv :value (:points benchmark-series))))
        (is (= [peer-ref]
               (get-in vm [:performance-metrics :benchmark-coins])))
        (is (= "Peer Vault (VAULT)"
               (get-in vm [:performance-metrics :benchmark-label])))))))

(deftest vault-detail-vm-derives-one-year-window-from-all-time-when-range-slice-missing-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        end-time-ms 1738306800000
        old-time-ms 1702006800000
        inside-window-start-ms 1712386800000
        state (-> sample-state
                  (assoc-in [:vaults-ui :snapshot-range] :one-year)
                  (assoc-in [:vaults-ui :detail-chart-series] :pnl)
                  (assoc-in [:vaults :details-by-address vault-address :portfolio]
                            {:all-time {:accountValueHistory [[old-time-ms 100]
                                                              [inside-window-start-ms 120]
                                                              [end-time-ms 150]]
                                        :pnlHistory [[old-time-ms 0]
                                                     [inside-window-start-ms 10]
                                                     [end-time-ms 20]]}}))
        vm (detail-vm/vault-detail-vm state)]
    (is (= :one-year (get-in vm [:chart :selected-timeframe])))
    (is (= [0 10]
           (mapv :value (get-in vm [:chart :points]))))
    (is (= [inside-window-start-ms end-time-ms]
           (mapv :time-ms (get-in vm [:chart :points]))))))

(deftest vault-detail-vm-aggregates-component-history-and-accepts-channel-shaped-sources-test
  (let [child-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        state (-> sample-state
                  (assoc-in [:vaults :details-by-address "0x1234567890abcdef1234567890abcdef12345678" :relationship]
                            {:type :parent
                             :child-addresses [child-address]})
                  (assoc-in [:vaults :fills-by-vault child-address]
                            [{:time 8
                              :coin "SOL"
                              :side "buy"
                              :sz "1.0"
                              :px "30"}])
                  (assoc-in [:vaults :funding-history-by-vault child-address]
                            [{:time 9
                              :coin "SOL"
                              :fundingRate 0.002
                              :szi 1.0
                              :usdc 1.5}])
                  (assoc-in [:vaults :order-history-by-vault child-address]
                            [{:order {:coin "SOL"
                                      :side "B"
                                      :origSz "1.0"
                                      :limitPx "31"
                                      :orderType "Limit"
                                      :timestamp 10}
                              :status "filled"
                              :statusTimestamp 11}])
                  (assoc-in [:vaults :webdata-by-vault "0x1234567890abcdef1234567890abcdef12345678" :openOrders]
                            {:orders [{:order {:coin "BTC"
                                               :side "B"
                                               :sz "0.1"
                                               :limitPx "100"
                                               :timestamp 9}}]})
                  (assoc-in [:vaults :webdata-by-vault "0x1234567890abcdef1234567890abcdef12345678" :twapStates]
                            {:states [{:coin "BTC"
                                       :sz "1.0"
                                       :executedSz "0.2"
                                       :avgPx "101"
                                       :creationTime 12}]})
                  (assoc-in [:vaults :ledger-updates-by-vault "0x1234567890abcdef1234567890abcdef12345678"]
                            {:nonFundingLedgerUpdates [{:time 12
                                                       :hash "0xabc"
                                                       :delta {:type "vaultDeposit"
                                                               :vault "0x1234567890abcdef1234567890abcdef12345678"
                                                               :usdc "10.0"}}]}))
        vm (detail-vm/vault-detail-vm state)]
    (is (= 3 (count (:activity-fills vm))))
    (is (= 2 (count (:activity-funding-history vm))))
    (is (= 2 (count (:activity-order-history vm))))
    (is (= 1 (count (:activity-open-orders vm))))
    (is (= 1 (count (:activity-twaps vm))))
    (is (= 1 (count (:activity-deposits-withdrawals vm))))
    (is (= 3 (get-in vm [:activity-summary :fill-count])))))

(deftest vault-detail-vm-accepts-injected-now-ms-for-deterministic-twap-runtime-test
  (let [state (assoc-in sample-state
                        [:vaults :webdata-by-vault "0x1234567890abcdef1234567890abcdef12345678" :twapStates]
                        [{:coin "BTC"
                          :sz "1.0"
                          :executedSz "0.2"
                          :avgPx "101"
                          :creationTime 12
                          :startTime 1000
                          :durationMs 10800000}])
        vm (detail-vm/vault-detail-vm state {:now-ms 3661000})
        twap (first (:activity-twaps vm))]
    (is (= 3660000 (:running-ms twap)))
    (is (= 10800000 (:total-ms twap)))
    (is (= "1h 1m / 3h 0m" (:running-label twap)))))

(deftest vault-detail-vm-applies-direction-filter-to-supported-activity-tabs-test
  (let [state (assoc-in sample-state [:vaults-ui :detail-activity-direction-filter] :short)
        vm (detail-vm/vault-detail-vm state)]
    (is (= :short (:activity-direction-filter vm)))
    (is (= 1 (count (:activity-positions vm))))
    (is (= "ETH" (get-in vm [:activity-positions 0 :coin])))
    (is (= 0 (count (:activity-open-orders vm))))))

(deftest vault-detail-vm-applies-per-tab-sort-state-for-activity-rows-test
  (let [state (assoc-in sample-state
                        [:vaults-ui :detail-activity-sort-by-tab]
                        {:positions {:column :coin
                                     :direction :asc}})
        vm (detail-vm/vault-detail-vm state)]
    (is (= :coin (get-in vm [:activity-sort-state-by-tab :positions :column])))
    (is (= :asc (get-in vm [:activity-sort-state-by-tab :positions :direction])))
    (is (= "BTC" (get-in vm [:activity-positions 0 :coin])))
    (is (= "ETH" (get-in vm [:activity-positions 1 :coin])))))

(deftest vault-detail-vm-skips-heavy-derivations-on-unrelated-state-writes-test
  (detail-vm/vault-detail-vm sample-state)
  (let [summary-cache @#'detail-vm/summary-cache
        chart-series-cache @#'detail-vm/chart-series-data-cache
        benchmark-cache @#'detail-vm/benchmark-points-cache
        metrics-cache @#'detail-vm/performance-metrics-cache]
    (detail-vm/vault-detail-vm (assoc sample-state :toast {:id 1}))
    (is (identical? (:summary summary-cache)
                    (:summary @#'detail-vm/summary-cache)))
    (is (identical? (:series-by-key chart-series-cache)
                    (:series-by-key @#'detail-vm/chart-series-data-cache)))
    (is (identical? (:benchmark-points-by-coin benchmark-cache)
                    (:benchmark-points-by-coin @#'detail-vm/benchmark-points-cache)))
    (is (identical? (:model metrics-cache)
                    (:model @#'detail-vm/performance-metrics-cache)))))
