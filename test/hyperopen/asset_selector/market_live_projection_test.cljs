(ns hyperopen.asset-selector.market-live-projection-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.asset-selector.outcome-fixtures :as outcome-fixtures]
            [hyperopen.asset-selector.market-live-projection :as market-live-projection]
            [hyperopen.outcomes.domain.display :as outcome-display]
            [hyperopen.outcomes.domain.quotes :as outcome-quotes]))

(deftest apply-active-asset-ctx-update-patches-perp-market-columns-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :market-type :perp
                :mark 99
                :markRaw "99"
                :change24h 1
                :change24hPct 1
                :volume24h 100
                :fundingRate 0
                :openInterest 0}
        state {:asset-selector {:markets [market]
                                :market-by-key {"perp:BTC" market}
                                :market-index-by-key {"perp:BTC" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "BTC"
                    {:markPx "101.5"
                     :prevDayPx "95"
                     :funding "0.0002"
                     :dayNtlVlm "333"
                     :openInterest "444"})]
    (is (= 101.5 (get-in next-state [:asset-selector :market-by-key "perp:BTC" :mark])))
    (is (= 6.5 (get-in next-state [:asset-selector :market-by-key "perp:BTC" :change24h])))
    (is (< (js/Math.abs (- 6.842105263157895
                           (get-in next-state [:asset-selector :market-by-key "perp:BTC" :change24hPct])))
           1.0e-12))
    (is (= 0.0002 (get-in next-state [:asset-selector :market-by-key "perp:BTC" :fundingRate])))
    (is (= 45066 (get-in next-state [:asset-selector :market-by-key "perp:BTC" :openInterest])))
    (is (= 333 (get-in next-state [:asset-selector :markets 0 :volume24h])))
    (is (= {"perp:BTC" 0} (get-in next-state [:asset-selector :market-index-by-key])))))

(deftest apply-active-asset-ctx-update-patches-only-targeted-selector-index-test
  (let [btc-market {:key "perp:BTC"
                    :coin "BTC"
                    :market-type :perp
                    :mark 100}
        eth-market {:key "perp:ETH"
                    :coin "ETH"
                    :market-type :perp
                    :mark 200}
        state {:asset-selector {:markets [btc-market eth-market]
                                :market-by-key {"perp:BTC" btc-market
                                                "perp:ETH" eth-market}
                                :market-index-by-key {"perp:BTC" 0
                                                      "perp:ETH" 1}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "BTC"
                    {:markPx "101"
                     :prevDayPx "100"})]
    (is (= 101 (get-in next-state [:asset-selector :markets 0 :mark])))
    (is (identical? eth-market (get-in next-state [:asset-selector :markets 1])))
    (is (not (identical? btc-market (get-in next-state [:asset-selector :markets 0]))))))

(deftest apply-active-asset-ctx-update-keeps-spot-funding-and-open-interest-empty-test
  (let [spot-market {:key "spot:PURR/USDC"
                     :coin "PURR/USDC"
                     :symbol "PURR/USDC"
                     :market-type :spot
                     :mark 0.5
                     :markRaw "0.5"
                     :fundingRate nil
                     :openInterest nil}
        state {:asset-selector {:markets [spot-market]
                                :market-by-key {"spot:PURR/USDC" spot-market}
                                :market-index-by-key {"spot:PURR/USDC" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "PURR/USDC"
                    {:markPx "0.75"
                     :prevDayPx "0.5"
                     :funding "0.0004"
                     :openInterest "100"
                     :dayNtlVlm "88"})]
    (is (= 0.75 (get-in next-state [:asset-selector :market-by-key "spot:PURR/USDC" :mark])))
    (is (= 50 (get-in next-state [:asset-selector :market-by-key "spot:PURR/USDC" :change24hPct])))
    (is (nil? (get-in next-state [:asset-selector :market-by-key "spot:PURR/USDC" :fundingRate])))
    (is (nil? (get-in next-state [:asset-selector :market-by-key "spot:PURR/USDC" :openInterest]))))

  (testing "unknown coins are a no-op"
    (let [state {:asset-selector {:markets []
                                  :market-by-key {}
                                  :market-index-by-key {}}}]
      (is (= state
             (market-live-projection/apply-active-asset-ctx-update
              state
              "BTC"
              {:markPx "1"}))))))

(deftest apply-active-asset-ctx-update-refreshes-outcome-open-interest-from-active-context-test
  (let [outcome-market {:key "outcome:0"
                        :coin "#0"
                        :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                        :market-type :outcome
                        :mark 0.58
                        :markRaw "0.58"
                        :openInterest 537233
                        :fundingRate nil
                        :outcome-sides [{:coin "#0"
                                         :side-index 0}
                                        {:coin "#1"
                                         :side-index 1}]}
        state {:asset-selector {:markets [outcome-market]
                                :market-by-key {"outcome:0" outcome-market}
                                :market-index-by-key {"outcome:0" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "#0"
                    {:markPx "0.6"
                     :prevDayPx "0.5"
                     :openInterest "276502"
                     :dayNtlVlm "1200"})]
    (is (= 0.6 (get-in next-state [:asset-selector :market-by-key "outcome:0" :mark])))
    (is (= 0.6 (get-in next-state [:asset-selector :market-by-key "outcome:0" :outcome-sides 0 :mark])))
    ;; Outcome contexts carry supply, not a perp openInterest: the event's
    ;; interest is recomputed from its contracts, never taken from this field.
    (is (= 537233 (get-in next-state [:asset-selector :market-by-key "outcome:0" :openInterest])))
    (is (nil? (get-in next-state [:asset-selector :market-by-key "outcome:0" :fundingRate])))))

(deftest apply-active-asset-ctx-update-patches-outcome-side-without-clobbering-primary-side-test
  (let [outcome-market {:key "outcome:0"
                        :coin "#0"
                        :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                        :market-type :outcome
                        :mark 0.58
                        :markRaw "0.58"
                        :openInterest 537233
                        :fundingRate nil
                        :outcome-sides [{:coin "#0"
                                         :side-index 0
                                         :mark 0.58
                                         :markRaw "0.58"}
                                        {:coin "#1"
                                         :side-index 1
                                         :mark 0.42
                                         :markRaw "0.42"}]}
        state {:asset-selector {:markets [outcome-market]
                                :market-by-key {"outcome:0" outcome-market}
                                :market-index-by-key {"outcome:0" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "#1"
                    {:markPx "0.46790"
                     :prevDayPx "0.45"
                     :openInterest "276502"
                     :dayNtlVlm "1200"})]
    (is (= 0.58 (get-in next-state [:asset-selector :market-by-key "outcome:0" :mark])))
    (is (= "0.58" (get-in next-state [:asset-selector :market-by-key "outcome:0" :markRaw])))
    (is (= 0.46790 (get-in next-state [:asset-selector :market-by-key "outcome:0" :outcome-sides 1 :mark])))
    (is (= "0.46790" (get-in next-state [:asset-selector :market-by-key "outcome:0" :outcome-sides 1 :markRaw])))
    (is (= 0.46790 (get-in next-state [:asset-selector :markets 0 :outcome-sides 1 :mark])))))

(deftest apply-active-asset-ctx-update-hydrates-outcome-open-interest-from-circulating-supply-test
  (let [outcome-market {:key "outcome:0"
                        :coin "#0"
                        :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                        :market-type :outcome
                        :mark 0.58
                        :outcome-sides [{:coin "#0"
                                         :side-index 0}
                                        {:coin "#1"
                                         :side-index 1}]}
        state {:asset-selector {:markets [outcome-market]
                                :market-by-key {"outcome:0" outcome-market}
                                :market-index-by-key {"outcome:0" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "#0"
                    {:markPx "0.63796"
                     :prevDayPx "0.55"
                     :circulatingSupply "567696.0"
                     :dayNtlVlm "1403439.9898999992"})]
    (is (= 0.63796 (get-in next-state [:asset-selector :market-by-key "outcome:0" :mark])))
    (is (= 567696.0 (get-in next-state [:asset-selector :market-by-key "outcome:0" :openInterest])))
    (is (= 567696.0 (get-in next-state [:asset-selector :markets 0 :openInterest])))))

(deftest apply-active-asset-ctx-update-refreshes-grouped-question-option-side-test
  (let [range-market (->> (markets/build-outcome-markets outcome-fixtures/live-outcome-meta
                                                          outcome-fixtures/live-outcome-ctxs)
                          (filter #(= "question:30" (:key %)))
                          first)
        state {:asset-selector {:markets [range-market]
                                :market-by-key {"question:30" range-market}
                                :market-index-by-key {"question:30" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "#1620"
                    {:markPx "0.333"
                     :prevDayPx "0.300"
                     :circulatingSupply "1200"
                     :dayNtlVlm "44"})]
    (is (= 0.333
           (get-in next-state [:asset-selector :market-by-key "question:30" :question-options 1 :mark])))
    (is (= 0.333
           (get-in next-state [:asset-selector :market-by-key "question:30" :question-options 1 :sides 0 :mark])))
    (is (= "#1620"
           (get-in next-state [:asset-selector :market-by-key "question:30" :question-options 1 :sides 0 :coin])))
    (is (= "Below $61,044 —  *  $61,044 – $63,535 —  *  Above $63,535 —"
           (get-in next-state [:asset-selector :market-by-key "question:30" :outcome-summary])))
    (is (= 0.333
           (get-in next-state [:asset-selector :markets 0 :question-options 1 :mark])))))

(deftest apply-active-asset-ctx-update-keeps-championship-summary-bounded-test
  (let [world-cup (->> (markets/build-outcome-markets outcome-fixtures/live-outcome-meta
                                                       outcome-fixtures/live-outcome-ctxs)
                       (filter #(= "question:32" (:key %)))
                       first)
        state {:asset-selector {:markets [world-cup]
                                :market-by-key {"question:32" world-cup}
                                :market-index-by-key {"question:32" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "#2120"
                    {:markPx "0.181"
                     :prevDayPx "0.166"
                     :circulatingSupply "32000"
                     :dayNtlVlm "2600"})]
    (is (= 0.181
           (get-in next-state [:asset-selector :market-by-key "question:32" :question-options 4 :mark])))
    (is (= 3 (count (str/split (get-in next-state [:asset-selector :market-by-key "question:32" :outcome-summary])
                               #"  \*  "))))
    (is (= (get-in next-state [:asset-selector :market-by-key "question:32" :outcome-summary])
           (get-in next-state [:asset-selector :markets 0 :outcome-summary])))))

(deftest live-ticks-recompute-event-totals-test
  (let [world-cup (->> (markets/build-outcome-markets outcome-fixtures/live-outcome-meta
                                                       outcome-fixtures/live-outcome-ctxs)
                       (filter #(= "question:32" (:key %)))
                       first)
        state {:asset-selector {:markets [world-cup]
                                :market-by-key {"question:32" world-cup}
                                :market-index-by-key {"question:32" 0}}}
        before (:volume24h world-cup)
        coin (get-in world-cup [:question-options 4 :yes-coin])
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    coin
                    {:markPx "0.181" :prevDayPx "0.166" :circulatingSupply "32000"
                     :dayNtlVlm "2600" :dayBaseVlm "9000"})
        after (get-in next-state [:asset-selector :market-by-key "question:32"])]
    (is (pos? before))
    (is (> (:volume24h after) 9000) "one contract's tick does not overwrite the event total")
    (is (= (:volume24h after)
           (reduce + (keep #(outcome-display/contract-shares (:sides %))
                           (:question-options after)))))
    (is (= 9000 (get-in after [:question-options 4 :sides 0 :baseVolume24h])))))

(deftest seed-prev-day-zero-is-not-a-24h-reference-test
  (let [outcome-market {:key "outcome:0" :coin "#0" :market-type :outcome :mark 0.5
                        :change24h 0.1 :change24hPct 25
                        :outcome-sides [{:coin "#0" :side-index 0 :change24h 0.1 :change24hPct 25}
                                        {:coin "#1" :side-index 1}]}
        state {:asset-selector {:markets [outcome-market]
                                :market-by-key {"outcome:0" outcome-market}
                                :market-index-by-key {"outcome:0" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state "#0" {:markPx "0.5" :prevDayPx "0.0" :midPx "0.5"})]
    (is (nil? (get-in next-state [:asset-selector :market-by-key "outcome:0" :change24h])))
    (is (nil? (get-in next-state [:asset-selector :market-by-key "outcome:0" :outcome-sides 0 :change24h])))))

(deftest top-of-book-updates-feed-the-chance-rule-test
  (let [outcome-market {:key "outcome:0" :coin "#0" :market-type :outcome
                        :outcome-sides [{:coin "#0" :side-index 0 :prevDayRaw "0.4"}
                                        {:coin "#1" :side-index 1 :prevDayRaw "0.6"}]}
        state {:asset-selector {:markets [outcome-market]
                                :market-by-key {"outcome:0" outcome-market}
                                :market-index-by-key {"outcome:0" 0}}}
        next-state (market-live-projection/apply-outcome-top-of-book-update state "#0" {:bid "0.41" :ask "0.43"})
        side (get-in next-state [:asset-selector :market-by-key "outcome:0" :outcome-sides 0])]
    (is (= {:bid 0.41 :ask 0.43} (select-keys side [:bid :ask])))
    (is (= side (get-in next-state [:asset-selector :markets 0 :outcome-sides 0])))
    (is (= :live (:state (outcome-quotes/side-quote side))))
    (testing "an emptied side is written as nil: No market"
      (let [emptied (market-live-projection/apply-outcome-top-of-book-update next-state "#0" {:bid nil :ask "0.43"})]
        (is (= :no-market (:state (outcome-quotes/side-quote
                                   (get-in emptied [:asset-selector :market-by-key "outcome:0" :outcome-sides 0])))))))))

(deftest top-of-book-mirrors-onto-the-sibling-and-the-active-market-test
  (let [option-a {:outcome-id 1 :sides [{:coin "#10" :side-index 0 :prevDayRaw "0.4"}
                                        {:coin "#11" :side-index 1 :prevDayRaw "0.6"}]}
        option-b {:outcome-id 2 :sides [{:coin "#20" :side-index 0 :prevDayRaw "0.3"}
                                        {:coin "#21" :side-index 1 :prevDayRaw "0.7"}]}
        market {:key "question:1" :coin "#10" :market-type :outcome
                :outcome-subscription-coins ["#10" "#11" "#20" "#21"]
                :outcome-sides (:sides option-a)
                :question-options [option-a option-b]}
        state {:active-asset "#10"
               :active-market market
               :asset-selector {:markets [market]
                                :market-by-key {"question:1" market}
                                :market-index-by-key {"question:1" 0}}}
        next-state (market-live-projection/apply-outcome-top-of-book-update state "#20" {:bid "0.31" :ask "0.33"})
        catalog (get-in next-state [:asset-selector :market-by-key "question:1"])]
    (testing "side 1 is side 0 mirrored: bid1 = 1 - ask0, ask1 = 1 - bid0"
      (let [[s0 s1] (get-in catalog [:question-options 1 :sides])]
        (is (= {:bid 0.31 :ask 0.33} (select-keys s0 [:bid :ask])))
        (is (< (js/Math.abs (- 0.67 (:bid s1))) 1e-9))
        (is (< (js/Math.abs (- 0.69 (:ask s1))) 1e-9))))
    (testing "the ticket's answer rows (active market) read the same book"
      (is (= (get-in catalog [:question-options 1])
             (get-in next-state [:active-market :question-options 1])))
      (is (= :live (:state (outcome-quotes/side-quote
                            (get-in next-state [:active-market :question-options 1 :sides 0]))))))
    (testing "other contracts are untouched"
      (is (not (contains? (get-in next-state [:active-market :question-options 0 :sides 0]) :bid))))))
