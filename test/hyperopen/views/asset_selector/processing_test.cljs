(ns hyperopen.views.asset-selector.processing-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.asset-selector.outcome-fixtures :as outcome-fixtures]
            [hyperopen.asset-selector.settings :as settings]
            [hyperopen.views.asset-selector.processing :as processing]
            [hyperopen.views.asset-selector.test-support :as support]))

(deftest filter-and-sort-assets-test
  (testing "strict search filters by prefix"
    (let [results (processing/filter-and-sort-assets support/sample-markets "bt" :name :asc #{} false true :all)]
      (is (= 1 (count results)))
      (is (= "BTC-USDC" (:symbol (first results))))))

  (testing "favorites-only filter"
    (let [results (processing/filter-and-sort-assets support/sample-markets "" :name :asc #{"perp:BTC"} true false :all)]
      (is (= 1 (count results)))
      (is (= "perp:BTC" (:key (first results))))))

  (testing "tab filter for spot"
    (let [results (processing/filter-and-sort-assets support/sample-markets "" :name :asc #{} false false :spot)]
      (is (= 1 (count results)))
      (is (= :spot (:market-type (first results))))))

  (testing "outcome tab and search use question metadata"
    (let [outcome {:key "outcome:0"
                   :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                   :title "BTC above 78213 on May 3 at 2:00 AM?"
                   :coin "#0"
                   :base "BTC"
                   :underlying "BTC"
                   :period "1d"
                   :market-type :outcome
                   :category :outcome
                   :mark 0.58
                   :volume24h 180824
                   :openInterest 254722
                   :expiry-ms 1777788000000}
          results (processing/filter-and-sort-assets (conj support/sample-markets outcome)
                                                     "above 78213"
                                                     :volume
                                                     :desc
                                                     #{}
                                                     false
                                                     false
                                                     :outcome)]
      (is (= ["outcome:0"] (mapv :key results)))))

  (testing "outcome category chips filter by template category; legacy period tabs act as Crypto"
    (let [assets [{:key "outcome:0" :symbol "Bitcoin above $75,000" :coin "#0" :market-type :outcome
                   :category :outcome :outcome-category :crypto :volume24h 1}
                  {:key "outcome:1" :symbol "S&P 500 above 7,376" :coin "#10" :market-type :outcome
                   :category :outcome :outcome-category :stocks :volume24h 2}
                  {:key "outcome:2" :symbol "Anthropic IPO" :coin "#20" :market-type :outcome
                   :category :outcome :outcome-category :companies :volume24h 3}]]
      (doseq [tab [:outcome-crypto :outcome-1d :outcome-15m]]
        (is (= ["outcome:0"]
               (mapv :key (processing/filter-and-sort-assets assets "" :volume :desc #{} false false tab)))))
      (is (= ["outcome:1"]
             (mapv :key (processing/filter-and-sort-assets assets "" :volume :desc #{} false false :outcome-stocks))))
      (is (= ["outcome:2"]
             (mapv :key (processing/filter-and-sort-assets assets "" :volume :desc #{} false false :outcome-companies))))))

  (testing "outcome category subfilters include economics and sports grouped rows"
    (let [assets (markets/build-outcome-markets outcome-fixtures/live-outcome-meta
                                                outcome-fixtures/live-outcome-ctxs)]
      ;; Outcome volume is shares traded, each contract counted once.
      (is (= ["outcome:104" "question:19"]
             (mapv :key (processing/filter-and-sort-assets assets "" :volume :desc #{} false false :outcome-economics))))
      (is (= ["outcome:142" "question:32" "outcome:141"]
             (mapv :key (processing/filter-and-sort-assets assets "" :volume :desc #{} false false :outcome-sports))))
      (is (= ["question:32"]
             (mapv :key (processing/filter-and-sort-assets assets "France" :volume :desc #{} false false :outcome-sports))))
      (is (= ["outcome:159" "question:30"]
             (mapv :key (processing/filter-and-sort-assets assets "BTC" :volume :desc #{} false false :outcome-1d))))))

  (testing "outcome chips are the template categories on every chain"
    (is (= [:outcome :outcome-sports :outcome-crypto :outcome-stocks :outcome-economics :outcome-companies]
           (settings/outcome-tabs {:hyperliquid-chain :mainnet})))
    (is (= (settings/outcome-tabs {:hyperliquid-chain :mainnet})
           (settings/outcome-tabs {:hyperliquid-chain :testnet}))))

  (testing "hip3 tab strict mode parity: strict off shows full HIP3 set, strict on applies eligibility"
    (let [assets [{:key "perp:xyz:USA500"
                   :symbol "USA500-USDT"
                   :coin "xyz:USA500"
                   :base "USA500"
                   :market-type :perp
                   :category :tradfi
                   :hip3? true
                   :hip3-eligible? true}
                  {:key "perp:xyz:ILLQ"
                   :symbol "ILLQ-USDC"
                   :coin "xyz:ILLQ"
                   :base "ILLQ"
                   :market-type :perp
                   :category :tradfi
                   :hip3? true
                   :hip3-eligible? false}
                  {:key "perp:xyz:LEGACY"
                   :symbol "LEGACY-USDC"
                   :coin "xyz:LEGACY"
                   :base "LEGACY"
                   :market-type :perp
                   :category :tradfi
                   :hip3? true}
                  {:key "perp:BTC"
                   :symbol "BTC-USDC"
                   :coin "BTC"
                   :base "BTC"
                   :market-type :perp
                   :category :crypto
                   :hip3? false}]
          strict-off-results (processing/filter-and-sort-assets assets "" :name :asc #{} false false :hip3)
          strict-on-results (processing/filter-and-sort-assets assets "" :name :asc #{} false true :hip3)
          perps-strict-on-results (processing/filter-and-sort-assets assets "" :name :asc #{} false true :perps)]
      (is (= ["perp:xyz:ILLQ" "perp:xyz:LEGACY" "perp:xyz:USA500"]
             (mapv :key strict-off-results)))
      (is (= ["perp:xyz:LEGACY" "perp:xyz:USA500"]
             (mapv :key strict-on-results)))
      (is (= ["perp:BTC" "perp:xyz:LEGACY" "perp:xyz:USA500"]
             (mapv :key perps-strict-on-results))))))

(deftest filter-and-sort-assets-preserves-cache-order-when-sort-values-missing-test
  (let [cached-markets [{:key "spot:AAA/USDC"
                         :symbol "AAA/USDC"
                         :coin "AAA/USDC"
                         :base "AAA"
                         :market-type :spot
                         :cache-order 2}
                        {:key "spot:BBB/USDC"
                         :symbol "BBB/USDC"
                         :coin "BBB/USDC"
                         :base "BBB"
                         :market-type :spot
                         :cache-order 0}
                        {:key "spot:CCC/USDC"
                         :symbol "CCC/USDC"
                         :coin "CCC/USDC"
                         :base "CCC"
                         :market-type :spot
                         :cache-order 1}]
        results (processing/filter-and-sort-assets cached-markets "" :volume :desc #{} false false :all)]
    (is (= ["BBB/USDC" "CCC/USDC" "AAA/USDC"]
           (mapv :symbol results)))))

(deftest processed-assets-returns-cached-result-when-input-identities-match-test
  (processing/reset-processed-assets-cache!)
  (let [favorites #{}
        first-result (processing/processed-assets support/sample-markets "" :volume :desc favorites false false :all)
        second-result (processing/processed-assets support/sample-markets "" :volume :desc favorites false false :all)
        changed-result (processing/processed-assets support/sample-markets "btc" :volume :desc favorites false false :all)]
    (is (identical? first-result second-result))
    (is (not (identical? second-result changed-result)))))

(deftest processed-assets-keeps-stable-order-across-live-market-churn-test
  (processing/reset-processed-assets-cache!)
  (let [favorites #{}
        initial-results (processing/processed-assets support/sample-markets "" :volume :desc favorites false false :all)
        live-markets [{:key "perp:BTC"
                       :symbol "BTC-USDC"
                       :coin "BTC"
                       :base "BTC"
                       :market-type :perp
                       :category :crypto
                       :hip3? false
                       :mark 99
                       :volume24h 999
                       :change24hPct 8}
                      {:key "perp:xyz:GOLD"
                       :symbol "GOLD-USDC"
                       :coin "xyz:GOLD"
                       :base "GOLD"
                       :market-type :perp
                       :category :tradfi
                       :hip3? true
                       :hip3-eligible? true
                       :mark 1
                       :volume24h 1
                       :change24hPct -4}
                      {:key "spot:PURR/USDC"
                       :symbol "PURR/USDC"
                       :coin "PURR/USDC"
                       :base "PURR"
                       :market-type :spot
                       :category :spot
                       :hip3? false
                       :mark 0.75
                       :volume24h 3
                       :change24hPct 2}]
        live-market-by-key (into {} (map (juxt :key identity) live-markets))
        live-results (processing/processed-assets live-markets live-market-by-key "" :volume :desc favorites false false :all)]
    (is (= (mapv :key initial-results)
           (mapv :key live-results)))
    (is (= 99
           (some->> live-results
                    (filter #(= "perp:BTC" (:key %)))
                    first
                    :mark)))
    (is (= 0.75
           (some->> live-results
                    (filter #(= "spot:PURR/USDC" (:key %)))
                    first
                    :mark)))))

(deftest processed-assets-invalidates-cache-when-outcome-metadata-changes-test
  (processing/reset-processed-assets-cache!)
  (let [favorites #{}
        initial [{:key "outcome:0"
                  :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                  :title "BTC above 78213 on May 3 at 2:00 AM?"
                  :coin "#0"
                  :base "BTC"
                  :market-type :outcome
                  :category :outcome
                  :period "1d"
                  :expiry-ms 1777788000000
                  :mark 0.58
                  :volume24h 4}]
        changed [(assoc (first initial)
                        :title "BTC above 80000 on May 3 at 2:00 AM?"
                        :symbol "BTC above 80000 on May 3 at 2:00 AM?"
                        :target-price "80000")]
        _ (processing/processed-assets initial "" :volume :desc favorites false false :outcome)
        changed-result (processing/processed-assets changed "" :volume :desc favorites false false :outcome)]
    (is (= "80000"
           (:target-price (first changed-result))))))

(deftest processed-assets-invalidates-cache-when-question-options-change-test
  (processing/reset-processed-assets-cache!)
  (let [favorites #{}
        initial [(first (markets/build-outcome-markets outcome-fixtures/live-outcome-meta
                                                       outcome-fixtures/live-outcome-ctxs))]
        changed [(-> (first initial)
                     (assoc-in [:question-options 0 :label] "Under 4.3%")
                     (assoc :outcome-summary "Under 4.3% 61%  *  Exactly 4.3% 37%  *  Above 4.3% 6%"))]
        _ (processing/processed-assets initial "" :volume :desc favorites false false :outcome)
        changed-result (processing/processed-assets changed "" :volume :desc favorites false false :outcome)]
    (is (= "Under 4.3%"
           (get-in changed-result [0 :question-options 0 :label])))))
