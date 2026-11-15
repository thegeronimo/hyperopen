(ns hyperopen.margin-rec.paths-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.margin-rec.paths :as paths]
            [hyperopen.margin-rec.tiers :as tiers]))

(defn- geometric-candles
  "Candles where every bar multiplies the close by `step` and wicks span
  [low-mult, high-mult] of the previous close. Deterministic and degenerate:
  every bootstrap block is identical."
  [n step low-mult high-mult]
  (loop [i 0
         c 100.0
         rows []]
    (if (> i n)
      rows
      (recur (inc i)
             (* c step)
             (conj rows {:t (* i 3600000)
                         :o c
                         :c c
                         :l (* c low-mult)
                         :h (* c high-mult)})))))

(deftest mulberry32-determinism
  (let [a (paths/mulberry32 42)
        b (paths/mulberry32 42)
        c (paths/mulberry32 43)]
    (is (= (vec (repeatedly 5 a)) (vec (repeatedly 5 b))))
    (is (not= (vec (repeatedly 5 (paths/mulberry32 42)))
              (vec (repeatedly 5 c))))))

(deftest prepare-bars-parses-and-clamps
  (let [bars (paths/prepare-bars [{:t 0 :o "100" :c "100" :l "99" :h "101"}
                                  {:t 3600000 :o "100" :c "99" :l "98" :h "100.5"}
                                  {:t 7200000 :o "99" :c "100" :l "98.9" :h "100.2"}])]
    (is (= 2 (:n bars)))
    (is (< (js/Math.abs (- (aget (:rets bars) 0) (js/Math.log 0.99))) 1e-12))
    (is (< (js/Math.abs (- (aget (:lows bars) 0) (js/Math.log 0.98))) 1e-12))
    (testing "wick excursions bracket the close return"
      (dotimes [i (:n bars)]
        (is (<= (aget (:lows bars) i) (aget (:rets bars) i)))
        (is (>= (aget (:highs bars) i) (aget (:rets bars) i))))))
  (testing "insufficient rows"
    (is (nil? (paths/prepare-bars [{:t 0 :c "100" :l "99" :h "101"}])))
    (is (nil? (paths/prepare-bars [])))))

(deftest degenerate-required-equity-is-exact
  (let [step 0.99
        low-mult 0.985
        m 0.02
        bars (paths/prepare-bars (geometric-candles 80 step low-mult 1.001))
        mm-fn (tiers/maintenance-fn (tiers/flat-rate-schedule m))
        horizon 3
        expected (reduce
                  (fn [req t]
                    (let [close (* 100 (js/Math.pow step (inc t)))
                          ext (* close low-mult)]
                      (-> req
                          (max (- (mm-fn ext) (- ext 100)))
                          (max (- (mm-fn close) (- close 100))))))
                  (mm-fn 100)
                  (range horizon))
        sorted (paths/simulate-required-sync
                bars
                {:q 1 :p0 100 :mm-fn mm-fn :horizon-bars horizon
                 :seed 7 :rho 1 :block-len 24 :stress-prob 0}
                64)]
    (is (< (js/Math.abs (- (aget sorted 0) expected)) 1e-9))
    (is (< (js/Math.abs (- (aget sorted 63) expected)) 1e-9))))

(deftest batch-slicing-is-invariant
  (let [bars (paths/prepare-bars (geometric-candles 200 0.999 0.99 1.01))
        params {:q -2 :p0 100
                :mm-fn (tiers/maintenance-fn (tiers/flat-schedule 10))
                :horizon-bars 48 :seed 123 :rho 1.1
                :block-len 24 :stress-prob 0.15
                :stress-starts (paths/stress-starts (:rets bars) 24)}
        whole (paths/sort-required [(paths/simulate-batch bars params 0 32)])
        sliced (paths/sort-required [(paths/simulate-batch bars params 0 10)
                                     (paths/simulate-batch bars params 10 10)
                                     (paths/simulate-batch bars params 20 12)])]
    (is (= (vec whole) (vec sliced)))))

(deftest long-short-symmetry
  (let [n 120
        rows (map (fn [i]
                    {:t (* i 3600000) :o 100 :c 100 :l 99 :h (/ 100 0.99)})
                  (range n))
        bars (paths/prepare-bars rows)
        mm-fn (tiers/maintenance-fn (tiers/flat-rate-schedule 0.02))
        base {:p0 100 :mm-fn mm-fn :horizon-bars 24 :seed 9 :rho 1
              :block-len 24 :stress-prob 0}
        long-sorted (paths/simulate-required-sync bars (assoc base :q 1) 256)
        short-sorted (paths/simulate-required-sync bars (assoc base :q -1) 256)]
    (dotimes [i 256]
      (is (>= (+ (aget short-sorted i) 1e-12) (aget long-sorted i))))))

(deftest sorted-distribution-helpers
  (let [sorted (paths/sort-required [(js/Float64Array. #js [3 1 4 2])])]
    (is (= [1 2 3 4] (vec sorted)))
    (is (= 0.5 (paths/prob-above sorted 2.5)))
    (is (= 0.25 (paths/prob-above sorted 3)))
    (is (= 0 (paths/prob-above sorted 9)))
    (is (= 1 (paths/prob-above sorted 0)))
    (is (= 1 (paths/quantile-of-sorted sorted 0)))
    (is (= 4 (paths/quantile-of-sorted sorted 1)))
    (is (= 2.5 (paths/quantile-of-sorted sorted 0.5)))))

(deftest stress-starts-rank-blocks
  (let [n 100
        rows (map (fn [i]
                    (let [c (if (<= 40 i 47) (+ 100 (* 8 (js/Math.pow -1 i))) 100)]
                      {:t (* i 3600000) :o 100 :c c :l (- c 0.1) :h (+ c 0.1)}))
                  (range n))
        bars (paths/prepare-bars rows)
        starts (set (paths/stress-starts (:rets bars) 8))]
    (testing "top-decile blocks overlap the volatile stretch"
      (is (pos? (count starts)))
      (is (some #(<= 33 % 47) starts)))))
