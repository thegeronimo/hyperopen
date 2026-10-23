(ns hyperopen.views.trading-chart.utils.chart-trades-model-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.trading-chart.utils.chart-trades-model :as model]))

(def ^:private day 86400)

;; Daily candles opening at t0, t0+1d, ... (seconds).
(def ^:private t0 1700006400)

(def ^:private candles
  (mapv (fn [i]
          {:time (+ t0 (* i day))
           :open 10 :high (+ 12 i) :low (- 9 (* 0.1 i)) :close 11})
        (range 6)))

(defn- at
  "Milliseconds `hours` into candle `i`."
  [i hours]
  (* 1000 (+ t0 (* i day) (* hours 3600))))

(defn- build
  ([fills] (build fills {}))
  ([fills opts]
   (model/build-chart-trades (merge {:active-asset "HYPE"
                                     :fills fills
                                     :market-by-key {}
                                     :candle-data candles
                                     :perp? true}
                                    opts))))

(deftest snaps-fills-to-the-candle-at-or-before-the-fill-time-test
  (let [{:keys [groups]} (build [{:coin "HYPE" :tid 1 :side "B" :px "10" :sz "1" :time (at 2 0)}
                                 {:coin "HYPE" :tid 2 :side "A" :px "11" :sz "1" :time (at 3 23.9)}])]
    (testing "exactly on a candle open"
      (is (= (+ t0 (* 2 day)) (:time (first groups)))))
    (testing "mid-candle snaps back to that candle's open"
      (is (= (+ t0 (* 3 day)) (:time (second groups)))))))

(deftest drops-fills-before-the-first-loaded-candle-and-snaps-late-fills-to-the-last-candle-test
  (let [{:keys [groups]} (build [{:coin "HYPE" :tid 1 :side "B" :px "10" :sz "1" :time (at -1 0)}
                                 {:coin "HYPE" :tid 2 :side "B" :px "10" :sz "1" :time (at 9 0)}])]
    (is (= 1 (count groups)))
    (is (= (:time (last candles)) (:time (first groups))))))

(deftest merges-same-side-fills-in-a-candle-with-a-size-weighted-average-test
  (let [{:keys [groups groups-by-id]}
        (build [{:coin "HYPE" :tid 1 :side "B" :px "10" :sz "1" :time (at 1 1) :fee "0.1" :crossed true}
                {:coin "HYPE" :tid 2 :side "B" :px "13" :sz "2" :time (at 1 2) :fee "0.2" :crossed false}
                {:coin "HYPE" :tid 3 :side "B" :px "11" :sz "1" :time (at 1 3) :fee "-0.05"
                 :startPosition "3"}])
        group (first groups)]
    (is (= 1 (count groups)))
    (is (= 3 (:count group)))
    (is (= 4 (:size group)))
    ;; (10*1 + 13*2 + 11*1) / 4
    (is (= 11.75 (:avg-price group)))
    (is (= 47 (:notional group)))
    (is (< (js/Math.abs (- 0.25 (:fees group))) 1e-9))
    (is (= 4 (:position-after group)) "latest fill's startPosition + its signed size")
    (is (= [false true true] (mapv :maker? (:fills group))))
    (is (= (str (+ t0 day) "-buy") (:id group)))
    (is (= group (get groups-by-id (:id group))))
    (is (= (:high (nth candles 1)) (:candle-high group)))
    (is (= (:low (nth candles 1)) (:candle-low group)))))

(deftest keeps-buys-sells-and-liquidations-in-one-candle-as-separate-groups-test
  (let [{:keys [groups]}
        (build [{:coin "HYPE" :tid 1 :side "A" :px "11" :sz "1" :time (at 2 5)}
                {:coin "HYPE" :tid 2 :side "B" :px "10" :sz "1" :time (at 2 1)}
                {:coin "HYPE" :tid 3 :side "A" :px "9" :sz "1" :time (at 2 9)
                 :liquidation {:markPx "9" :method "market"}}])]
    (is (= [:buy :sell :liquidation] (mapv :side-kind groups)))))

(deftest size-steps-are-relative-to-the-median-group-notional-test
  (let [{:keys [groups median-notional]}
        (build [{:coin "HYPE" :tid 1 :side "B" :px "10" :sz "1" :time (at 0 1)}
                {:coin "HYPE" :tid 2 :side "B" :px "10" :sz "4" :time (at 1 1)}
                {:coin "HYPE" :tid 3 :side "B" :px "10" :sz "5" :time (at 2 1)}
                {:coin "HYPE" :tid 4 :side "B" :px "10" :sz "20" :time (at 3 1)}])]
    ;; notionals 10, 40, 50, 200 -> median 45
    (is (= 45 median-notional))
    (is (= [:small :typical :typical :large] (mapv :size-step groups)))))

(deftest dedupes-fills-by-trade-id-but-not-by-order-id-test
  (let [{:keys [groups]}
        (build [{:coin "HYPE" :tid 7 :oid 1 :side "B" :px "10" :sz "1" :time (at 1 1)}
                {:coin "HYPE" :tid 7 :oid 1 :side "B" :px "10" :sz "1" :time (at 1 1)}
                {:coin "HYPE" :oid 2 :side "B" :px "10" :sz "1" :time (at 1 2)}
                {:coin "HYPE" :oid 2 :side "B" :px "10.5" :sz "1" :time (at 1 3)}])]
    (is (= 3 (:count (first groups))))))

(deftest ignores-other-assets-and-undrawable-rows-test
  (let [{:keys [groups]}
        (build [{:coin "BTC" :tid 1 :side "B" :px "10" :sz "1" :time (at 1 1)}
                {:coin "HYPE" :tid 2 :side "B" :px "0" :sz "1" :time (at 1 1)}
                {:coin "HYPE" :tid 3 :side "?" :px "10" :sz "1" :time (at 1 1)}
                {:coin "HYPE" :tid 4 :side "B" :px "10" :sz "0" :time (at 1 1)}
                "not-a-map"])]
    (is (= [] groups))))

(deftest matches-spot-fills-to-the-spot-pair-but-not-the-same-base-perp-test
  (let [market-by-key {"spot:@107" {:coin "@107" :symbol "HYPE/USDC" :base "HYPE" :market-type :spot}
                       "perp:HYPE" {:coin "HYPE" :symbol "HYPE-USDC" :base "HYPE" :market-type :perp}}
        {:keys [groups ribbon]}
        (build [{:coin "@107" :tid 1 :side "B" :px "40" :sz "2" :time (at 1 1) :startPosition "0"
                 :fee "0.002" :feeToken "HYPE"}
                {:coin "HYPE" :tid 2 :side "B" :px "40" :sz "5" :time (at 1 2) :startPosition "0"}]
               {:active-asset "@107" :perp? false :market-by-key market-by-key})]
    (is (= 1 (count groups)))
    (is (= 2 (:size (first groups))) "the HYPE perp fill is not drawn on the spot chart")
    (is (= "HYPE" (:fee-token (first groups))) "spot buy fees are in the base token")
    (is (= [] ribbon) "no ribbon for spot")))

(deftest orders-same-millisecond-fills-by-execution-not-by-trade-id-text-test
  ;; one buy sweeping three makers: tids 9, 10, 11 sort "10" < "11" < "9" as text
  (let [{:keys [groups ribbon]}
        (build [{:coin "HYPE" :tid 11 :side "B" :px "10.2" :sz "1" :time (at 1 1) :startPosition "2"}
                {:coin "HYPE" :tid 9 :side "B" :px "10" :sz "1" :time (at 1 1) :startPosition "0"}
                {:coin "HYPE" :tid 10 :side "B" :px "10.1" :sz "1" :time (at 1 1) :startPosition "1"}
                ;; same-ms row without a tid must not crash the ordering
                {:coin "HYPE" :side "A" :px "10" :sz "0.5" :time (at 1 1) :startPosition "3"}])
        buy (first groups)]
    (is (= 3 (:position-after buy)))
    (is (= [10 10.1 10.2] (mapv :price (:fills buy))))
    (is (= {:direction :long :size 3 :open-ended? true}
           (select-keys (last ribbon) [:direction :size :open-ended?])))))

(deftest liquidation-groups-keep-the-side-of-the-forced-trade-test
  (let [{:keys [groups]}
        (build [{:coin "HYPE" :tid 1 :side "A" :px "9" :sz "2" :time (at 2 1) :startPosition "2"
                 :liquidation {:markPx "9"}}])]
    (is (= :liquidation (:side-kind (first groups))))
    (is (= -1 (:sign (first groups))))))

(deftest ribbon-handles-a-flip-from-long-to-short-in-one-fill-test
  (let [{:keys [ribbon]}
        (build [{:coin "HYPE" :tid 1 :side "B" :px "10" :sz "2" :time (at 1 1) :startPosition "0"}
                {:coin "HYPE" :tid 2 :side "A" :px "11" :sz "5" :time (at 3 1) :startPosition "2"}])]
    (is (= [{:from-time (+ t0 day) :to-time (+ t0 (* 3 day)) :direction :long :size 2 :open-ended? false}
            {:from-time (+ t0 (* 3 day)) :to-time (:time (last candles)) :direction :short :size 3
             :open-ended? true}]
           ribbon))))

(deftest empty-inputs-produce-empty-output-test
  (is (= [] (:groups (build []))))
  (is (= [] (:groups (build [{:coin "HYPE" :tid 1 :side "B" :px "1" :sz "1" :time (at 1 1)}]
                            {:candle-data []})))))

(deftest ribbon-tracks-long-flat-short-flat-from-start-positions-test
  (let [{:keys [ribbon]}
        (build [{:coin "HYPE" :tid 1 :side "B" :px "10" :sz "2" :time (at 1 1) :startPosition "0"}
                {:coin "HYPE" :tid 2 :side "A" :px "11" :sz "2" :time (at 2 1) :startPosition "2"}
                {:coin "HYPE" :tid 3 :side "A" :px "11" :sz "3" :time (at 3 1) :startPosition "0"}
                {:coin "HYPE" :tid 4 :side "B" :px "10" :sz "3" :time (at 4 1) :startPosition "-3"}])]
    (is (= [{:from-time (+ t0 day) :to-time (+ t0 (* 2 day)) :direction :long :size 2 :open-ended? false}
            {:from-time (+ t0 (* 3 day)) :to-time (+ t0 (* 4 day)) :direction :short :size 3 :open-ended? false}]
           ribbon))))

(deftest ribbon-includes-a-leading-position-and-an-open-ended-tail-test
  (let [{:keys [ribbon]}
        (build [{:coin "HYPE" :tid 1 :side "B" :px "10" :sz "1" :time (at 2 1) :startPosition "5"}
                {:coin "HYPE" :tid 2 :side "B" :px "10" :sz "1" :time (at 2 3) :startPosition "6"}])]
    (is (= [{:from-time t0 :to-time (+ t0 (* 2 day)) :direction :long :size 5 :open-ended? false}
            {:from-time (+ t0 (* 2 day)) :to-time (:time (last candles)) :direction :long :size 7
             :open-ended? true}]
           ribbon)
        "the zero-width segment between two fills in one candle is dropped")))

(deftest ribbon-merges-adjacent-identical-segments-test
  (let [{:keys [ribbon]}
        (build [{:coin "HYPE" :tid 1 :side "B" :px "10" :sz "1" :time (at 1 1) :startPosition "0"}
                ;; a buy and a sell in candle 2 net to the same position
                {:coin "HYPE" :tid 2 :side "B" :px "10" :sz "1" :time (at 2 1) :startPosition "1"}
                {:coin "HYPE" :tid 3 :side "A" :px "10" :sz "1" :time (at 2 2) :startPosition "2"}])]
    (is (= [{:from-time (+ t0 day) :to-time (:time (last candles)) :direction :long :size 1
             :open-ended? true}]
           ribbon))))
