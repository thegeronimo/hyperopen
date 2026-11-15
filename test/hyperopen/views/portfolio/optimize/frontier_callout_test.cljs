(ns hyperopen.views.portfolio.optimize.frontier-callout-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.optimize.frontier-callout :as frontier-callout]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-nodes collect-strings node-attr node-by-role text-node]]))

(deftest frontier-callout-wraps-long-title-within-card-test
  (let [callout (frontier-callout/callout
                 {:bounds {:width 520 :height 240}
                  :data-role "portfolio-optimizer-frontier-callout-long-title"
                  :label "[ Systemic Strategies ] Hyperliquid Vault"
                  :point {:x 120 :y 72}
                  :rows [{:label "Expected Return" :value "18.38%"}
                         {:label "Volatility" :value "23.74%"}
                         {:label "Sharpe" :value "0.774"}
                         {:label "Target Weight" :value "0.47%"}]})
        rect (first (collect-nodes callout #(= :rect (first %))))
        title-lines (collect-nodes callout #(= :tspan (first %)))
        first-metric (text-node callout "Expected Return")]
    (is (= 2 (count title-lines)))
    (is (every? #(<= (count (first (collect-strings %))) 26) title-lines))
    (is (< 113 (node-attr rect :height))
        "Wrapped title should grow the callout instead of overlapping metric rows.")
    (is (= 52 (node-attr first-metric :y)))))

(deftest blended-portfolio-callout-renders-designer-allocation-card-test
  (let [callout (frontier-callout/callout
                 {:bounds {:width 560 :height 340}
                  :data-role "portfolio-optimizer-frontier-callout-target"
                  :label "Target"
                  :variant :blended
                  :point {:x 120 :y 70}
                  :rows (frontier-callout/point-rows
                         {:expected-return 0.146
                          :volatility 0.12
                          :sharpe 0.84})
                  :allocations {:rows [{:label "BTC" :weight 0.24 :value "24.0%"}
                                       {:label "USDC" :weight 0.22 :value "22.0%"}
                                       {:label "ETH" :weight 0.18 :value "18.0%"}
                                       {:label "SOL" :weight 0.165 :value "16.5%"}
                                       {:label "HYPE" :weight 0.115 :value "11.5%"}
                                       {:label "kPEPE" :weight 0.04 :value "4.0%"}
                                       {:label "DOGE" :weight 0.025 :value "2.5%"}
                                       {:label "LINK" :weight 0.015 :value "1.5%"}]}})
        card (first (collect-nodes callout #(and (= :rect (first %))
                                                 (= "portfolio-optimizer-frontier-callout-card"
                                                    (node-attr % :data-role)))))
        first-segment (node-by-role callout
                                    "portfolio-optimizer-frontier-callout-allocation-segment-0")
        first-dot (node-by-role callout
                                "portfolio-optimizer-frontier-callout-allocation-dot-0")
        strings (set (collect-strings callout))]
    (is (= 224 (node-attr card :width)))
    (is (= 0 (node-attr card :rx)))
    (is (contains? strings "TARGET"))
    (is (contains? strings "PORTFOLIO"))
    (is (contains? strings "μ · return"))
    (is (contains? strings "σ · vol"))
    (is (contains? strings "IMPLIED ALLOCATION"))
    (is (contains? strings "BTC"))
    (is (contains? strings "USDC"))
    (is (contains? strings "ETH"))
    (is (contains? strings "SOL"))
    (is (contains? strings "HYPE"))
    (is (contains? strings "kPEPE"))
    (is (contains? strings "+2 more"))
    (is (not (contains? strings "Expected Return")))
    (is (not (contains? strings "DOGE")))
    (is (= 8
           (count (collect-nodes callout
                                 #(and (= :rect (first %))
                                       (some? (node-attr % :data-allocation-segment)))))))
    (is (= "#e2b84f" (node-attr first-segment :fill)))
    (is (= "#e2b84f" (node-attr first-dot :fill)))))

(deftest blended-portfolio-callout-renders-allocation-symbols-test
  (let [callout (frontier-callout/callout
                 {:bounds {:width 560 :height 340}
                  :data-role "portfolio-optimizer-frontier-callout-target"
                  :label "Target"
                  :variant :blended
                  :point {:x 120 :y 70}
                  :rows (frontier-callout/point-rows
                         {:expected-return 0.146
                          :volatility 0.12
                          :sharpe 0.84})
                  :allocations {:rows [{:label "BTC"
                                         :instrument-id "perp:BTC"
                                         :weight 0.54
                                         :value "54.0%"}
                                        {:label "Hyperliquidity Provider (HLP)"
                                         :instrument-id "vault:0x1111111111111111111111111111111111111111"
                                         :weight 0.46
                                         :value "46.0%"}]}})
        btc-symbol (node-by-role
                    callout
                    "portfolio-optimizer-frontier-callout-allocation-symbol-0")
        vault-diamond (node-by-role
                       callout
                       "portfolio-optimizer-frontier-callout-allocation-vault-diamond-1")
        first-dot (node-by-role
                   callout
                   "portfolio-optimizer-frontier-callout-allocation-dot-0")]
    (is (= "https://app.hyperliquid.xyz/coins/BTC.svg"
           (node-attr btc-symbol :href)))
    (is (some? vault-diamond))
    (is (nil? first-dot))))

(deftest blended-portfolio-callout-renders-namespaced-market-icons-test
  (let [callout (frontier-callout/callout
                 {:bounds {:width 560 :height 340}
                  :data-role "portfolio-optimizer-frontier-callout-target"
                  :label "Target"
                  :variant :blended
                  :point {:x 120 :y 70}
                  :rows (frontier-callout/point-rows
                         {:expected-return 0.146
                          :volatility 0.12
                          :sharpe 0.84})
                  :allocations {:rows [{:label "GOLD"
                                         :instrument-id "hl:hip3:xyz:GOLD"
                                         :weight 0.54
                                         :value "54.0%"}
                                        {:label "AAPL"
                                         :instrument-id "hl:hip3:xyz:AAPL"
                                         :weight 0.46
                                         :value "46.0%"}]}})
        gold-symbol (node-by-role
                     callout
                     "portfolio-optimizer-frontier-callout-allocation-symbol-0")
        aapl-symbol (node-by-role
                     callout
                     "portfolio-optimizer-frontier-callout-allocation-symbol-1")]
    (is (= "https://app.hyperliquid.xyz/coins/xyz:GOLD.svg"
           (node-attr gold-symbol :href)))
    (is (= "https://app.hyperliquid.xyz/coins/xyz:AAPL.svg"
           (node-attr aapl-symbol :href)))))

(deftest point-rows-render-exposure-as-multipliers-test
  (let [rows (frontier-callout/point-rows
              {:expected-return 0.146
               :volatility 0.12
               :sharpe 0.84}
              {:exposure {:gross 1.84
                          :net 0.92}})
        value-by-label (into {} (map (juxt :label :value) rows))]
    (is (= "1.84x" (get value-by-label "Gross Exposure")))
    (is (= "0.92x" (get value-by-label "Net Exposure")))))

(deftest blended-portfolio-callout-compacts-long-allocation-label-test
  (let [callout (frontier-callout/callout
                 {:bounds {:width 560 :height 340}
                  :data-role "portfolio-optimizer-frontier-callout-target"
                  :label "Target"
                  :variant :blended
                  :point {:x 120 :y 70}
                  :rows (frontier-callout/point-rows
                         {:expected-return 0.3214
                          :volatility 0.5981
                          :sharpe 0.542})
                  :allocations {:rows [{:label "Hyperliquidity Provider (HLP)"
                                         :weight 0.5
                                         :value "50.0%"}
                                        {:label "Growi HF"
                                         :weight 0.464
                                         :value "46.4%"}
                                        {:label "BTC"
                                         :weight 0.036
                                         :value "3.6%"}]}})
        long-label (node-by-role
                    callout
                    "portfolio-optimizer-frontier-callout-allocation-label-0")
        value-node (text-node callout "50.0%")
        strings (set (collect-strings callout))]
    (is (= "Hyperliquidity... (HLP)"
           (first (collect-strings long-label))))
    (is (= "Hyperliquidity Provider (HLP)"
           (node-attr long-label :data-full-label)))
    (is (contains? strings "50.0%"))
    (is (= "end" (node-attr value-node :text-anchor)))))

(deftest point-rows-append-horizon-vols-behind-a-divider-test
  (let [rows (frontier-callout/point-rows
              {:expected-return 18.6606
               :volatility 4.1182}
              {:horizons {:daily 0.2155565
                          :weekly 0.5703081
                          :monthly 1.1806513}})
        value-by-label (into {} (map (juxt :label :value) rows))
        divider-index (first (keep-indexed (fn [idx row]
                                             (when (:divider? row) idx))
                                           rows))]
    (is (= 3 divider-index)
        "The divider sits after the optimizer outputs (return/vol/Sharpe).")
    (is (= "±21.56%" (get value-by-label "Daily 1σ")))
    (is (= "±57.03%" (get value-by-label "Weekly 1σ")))
    (is (= "±118.07%" (get value-by-label "Monthly 1σ")))
    (is (nil? (some :divider? (frontier-callout/point-rows
                               {:expected-return 0.1 :volatility 0.2}))))))

(deftest callout-renders-divider-rows-as-hairlines-test
  (let [callout (frontier-callout/callout
                 {:bounds {:width 560 :height 340}
                  :data-role "portfolio-optimizer-frontier-callout-target"
                  :label "Target"
                  :variant :blended
                  :point {:x 120 :y 70}
                  :rows (frontier-callout/point-rows
                         {:expected-return 0.146 :volatility 0.12 :sharpe 0.84}
                         {:horizons {:daily 0.0062810
                                     :weekly 0.0166182
                                     :monthly 0.0344029}})
                  :allocations {:rows [{:label "BTC" :weight 1 :value "100.0%"}]}})
        strings (set (collect-strings callout))
        aria (frontier-callout/aria-label
              "Target"
              (frontier-callout/point-rows
               {:expected-return 0.146 :volatility 0.12 :sharpe 0.84}
               {:horizons {:daily 0.0062810
                           :weekly 0.0166182
                           :monthly 0.0344029}}))]
    (is (contains? strings "Daily 1σ"))
    (is (contains? strings "±0.63%"))
    (is (not (re-find #",\s*," aria)))
    (is (re-find #"Daily 1σ ±0.63%" aria))))
