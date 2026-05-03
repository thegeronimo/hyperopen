(ns hyperopen.views.account-info.projections.outcomes-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.projections :as projections]))

(def ^:private outcome-market
  {:key "outcome:0"
   :coin "#0"
   :symbol "BTC above 78213 on May 3 at 2:00 AM?"
   :title "BTC above 78213 on May 3 at 2:00 AM?"
   :quote "USDH"
   :market-type :outcome
   :outcome-id 0
   :outcome-sides [{:side-index 0
                    :side-name "Yes"
                    :coin "#0"
                    :asset-id 100000000
                    :mark 0.57042
                    :markRaw "0.57042"}
                   {:side-index 1
                    :side-name "No"
                    :coin "#1"
                    :asset-id 100000001
                    :mark 0.42958
                    :markRaw "0.42958"}]})

(deftest build-outcome-rows-enriches-plus-token-balances-from-outcome-market-metadata-test
  (let [rows (projections/build-outcome-rows
              {:clearinghouse-state {:balances [{:coin "+0"
                                                 :token 100000000
                                                 :hold "0"
                                                 :total "19"
                                                 :entryNtl "11.0271"}
                                                {:coin "HYPE"
                                                 :token 150
                                                 :hold "0"
                                                 :total "2"
                                                 :entryNtl "0"}]}}
              {"outcome:0" outcome-market})
        row (first rows)]
    (is (= 1 (count rows)))
    (is (= "outcome-#0" (:key row)))
    (is (= "BTC above 78213 on May 3 at 2:00 AM?" (:title row)))
    (is (= "outcome:0" (:market-key row)))
    (is (= "+0" (:raw-coin row)))
    (is (= "#0" (:side-coin row)))
    (is (= "Yes" (:side-name row)))
    (is (= "Outcome" (:type-label row)))
    (is (= 19 (:size row)))
    (is (< (js/Math.abs (- 10.83798 (:position-value row))) 0.000001))
    (is (< (js/Math.abs (- 0.5803736842105263 (:entry-price row))) 0.000001))
    (is (= 0.57042 (:mark-price row)))
    (is (< (js/Math.abs (- -0.18912 (:pnl-value row))) 0.000001))
    (is (< (js/Math.abs (- -1.7150474739505477 (:roe-pct row))) 0.000001))))

(deftest build-outcome-rows-supports-hash-coins-and-fallbacks-when-metadata-is-partial-test
  (let [rows (projections/build-outcome-rows
              {:clearinghouse-state {:balances [{:coin "#1"
                                                 :hold "0"
                                                 :total "3"
                                                 :entryNotional "1.8"}
                                                {:coin "+20"
                                                 :hold "0"
                                                 :total "4"
                                                 :entryNtl "2"}]}}
              {"outcome:0" outcome-market})
        [no-row fallback-row] rows]
    (is (= 2 (count rows)))
    (is (= "#1" (:side-coin no-row)))
    (is (= "No" (:side-name no-row)))
    (is (= "BTC above 78213 on May 3 at 2:00 AM?" (:title no-row)))
    (is (= "#20" (:side-coin fallback-row)))
    (is (= "#20" (:title fallback-row)))
    (is (= "Yes" (:side-name fallback-row)))
    (is (nil? (:market-key fallback-row)))
    (is (= 4 (:size fallback-row)))
    (is (= 0 (:mark-price fallback-row)))))

(deftest build-outcome-rows-filters-zero-sides-and-enriches-from-active-market-context-test
  (let [rows (projections/build-outcome-rows
              {:clearinghouse-state {:balances [{:coin "+0"
                                                 :token 100000000
                                                 :hold "0"
                                                 :total "19"
                                                 :entryNtl "11.0271"}
                                                {:coin "+1"
                                                 :token 100000001
                                                 :hold "0"
                                                 :total "0"
                                                 :entryNtl "0"}]}}
              {}
              {:active-market outcome-market
               :active-contexts {"#0" {:mark 0.53210
                                        :markRaw "0.53210"}}})
        [row] rows]
    (is (= 1 (count rows)))
    (is (= "BTC above 78213 on May 3 at 2:00 AM?" (:title row)))
    (is (= "#0" (:side-coin row)))
    (is (= "Yes" (:side-name row)))
    (is (= 19 (:size row)))
    (is (< (js/Math.abs (- 10.1099 (:position-value row))) 0.000001))
    (is (< (js/Math.abs (- 0.5803736842105263 (:entry-price row))) 0.000001))
    (is (= 0.53210 (:mark-price row)))
    (is (< (js/Math.abs (- -0.9172 (:pnl-value row))) 0.000001))))

(deftest build-outcome-rows-uses-spot-asset-contexts-for-side-mark-test
  (let [market-without-marks (update outcome-market
                                     :outcome-sides
                                     (fn [sides]
                                       (mapv #(dissoc % :mark :markRaw) sides)))
        rows (projections/build-outcome-rows
              {:clearinghouse-state {:balances [{:coin "+0"
                                                 :token 100000000
                                                 :hold "0"
                                                 :total "19"
                                                 :entryNtl "11.0271"}]}}
              {}
              {:active-market market-without-marks
               :spot-asset-ctxs [{:coin "#0"
                                  :markPx "0.53210"}]})
        [row] rows]
    (is (= 1 (count rows)))
    (is (= "BTC above 78213 on May 3 at 2:00 AM?" (:title row)))
    (is (= "Yes" (:side-name row)))
    (is (= 0.53210 (:mark-price row)))
    (is (< (js/Math.abs (- 10.1099 (:position-value row))) 0.000001))
    (is (< (js/Math.abs (- -0.9172 (:pnl-value row))) 0.000001))))

(deftest build-outcome-rows-enriches-from-outcome-market-primary-coin-test
  (let [market {:key "outcome:1"
                :coin "#10"
                :title "ETH above 3600 on May 4 at 2:00 AM?"
                :symbol "ETH above 3600 on May 4 at 2:00 AM?"
                :quote "USDH"
                :market-type :outcome
                :outcome-id 1
                :mark 0.42}
        rows (projections/build-outcome-rows
              {:clearinghouse-state {:balances [{:coin "+10"
                                                 :token 100000010
                                                 :hold "0"
                                                 :total "5"
                                                 :entryNtl "2"}]}}
              {"outcome:1" market})
        [row] rows]
    (is (= 1 (count rows)))
    (is (= "ETH above 3600 on May 4 at 2:00 AM?" (:title row)))
    (is (= "outcome:1" (:market-key row)))
    (is (= "#10" (:side-coin row)))
    (is (= "Yes" (:side-name row)))
    (is (= 0.42 (:mark-price row)))
    (is (= 2.1 (:position-value row)))))

(deftest outcome-token-predicate-detects-plus-and-hash-encodings-test
  (is (true? (projections/outcome-token? "+0")))
  (is (true? (projections/outcome-token? "#1")))
  (is (false? (projections/outcome-token? "HYPE")))
  (is (false? (projections/outcome-token? nil))))
