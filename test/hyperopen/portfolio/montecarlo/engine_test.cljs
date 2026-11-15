(ns hyperopen.portfolio.montecarlo.engine-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.metrics.returns :as returns]
            [hyperopen.portfolio.montecarlo.engine :as engine]))

(defn- approx= [a b tol]
  (< (js/Math.abs (- a b)) tol))

(defn- constant-returns [r n]
  (vec (repeat n r)))

(defn- varied-returns [n]
  (mapv (fn [i] (* 0.02 (- (js/Math.sin i) 0.05))) (range n)))

(deftest mulberry32-is-deterministic-and-in-unit-interval-test
  (let [a (engine/mulberry32 7)
        b (engine/mulberry32 7)
        xs (vec (repeatedly 200 a))
        ys (vec (repeatedly 200 b))]
    (is (= xs ys) "same seed yields the same stream")
    (is (every? (fn [x] (and (<= 0 x) (< x 1))) xs) "values lie in [0, 1)")
    (is (not= xs (vec (repeatedly 200 (engine/mulberry32 8))))
        "a different seed yields a different stream")))

(deftest percentile-interpolates-test
  (let [s (vec (range 0 11))]
    (is (= 0 (engine/percentile s 0)))
    (is (= 10 (engine/percentile s 100)))
    (is (= 5 (engine/percentile s 50)))
    (is (approx= 2.5 (engine/percentile s 25) 1e-12)))
  (is (= 0 (engine/percentile [] 50)) "empty collection is safe"))

(deftest histogram-counts-sum-to-n-test
  (let [h (engine/histogram [0 1 2 3 4 5 6 7 8 9] 5)]
    (is (= 5 (count (:counts h))))
    (is (= 10 (reduce + (:counts h))) "every value falls in exactly one bin")
    (is (= 0 (:min h)))
    (is (= 9 (:max h))))
  (let [h (engine/histogram [] 8)]
    (is (= 8 (count (:counts h))))
    (is (= 0 (reduce + (:counts h))) "empty input is safe")))

(defn- interval [simple dt-years]
  {:simple-return simple
   :log-return (js/Math.log (+ 1 simple))
   :dt-years dt-years})

(defn- varied-intervals [n]
  (mapv (fn [i]
          (interval (* 0.05 (- (js/Math.sin i) 0.1))
                    (/ (+ 5 (mod (* 7 i) 23)) 365.0)))
        (range n)))

(deftest engine-annualizes-cagr-by-elapsed-time-not-point-count-test
  (let [per (- (js/Math.pow 2 (/ 1.0 20)) 1)
        ivals (mapv (fn [_] (interval per 0.1)) (range 20))
        res (engine/run {:intervals ivals :sims 200 :method :shuffle :seed 5 :start-equity 1})]
    (is (approx= 2.0 (get-in res [:meta :total-years]) 1e-9)
        "elapsed time is 2 years, not 20/365")
    (is (approx= 0.41421 (get-in res [:cagr :p50]) 1e-3)
        "CAGR is the ~2-year annualized rate, not a point-count inflation")
    (is (approx= (returns/interval-cagr ivals) (get-in res [:cagr :p50]) 1e-6)
        "engine CAGR equals the tearsheet interval-cagr")))

(deftest engine-sharpe-vol-cagr-match-tearsheet-irregular-test
  (let [ivals (varied-intervals 80)
        res (engine/run {:intervals ivals :sims 600 :method :shuffle :seed 13 :start-equity 1})
        exp-cagr (returns/interval-cagr ivals)
        exp-vol (returns/volatility-ann-irregular ivals)
        exp-sharpe (returns/sharpe-irregular ivals 0)]
    (is (approx= exp-cagr (get-in res [:cagr :p50]) 1e-6) "CAGR matches interval-cagr")
    (is (approx= exp-vol (get-in res [:vol :p50]) 1e-6) "vol matches volatility-ann-irregular")
    (is (number? exp-sharpe))
    (is (< (js/Math.abs (- exp-sharpe (get-in res [:sharpe :median])))
           (* 0.15 (js/Math.abs exp-sharpe)))
        "shuffle Sharpe median ≈ tearsheet sharpe-irregular (leave-one-out narrows to it)")))

(deftest histogram-collapses-near-equal-values-test
  (let [v (mapv (fn [i] (+ 13.8 (* i 1e-14))) (range 100))
        h (engine/histogram v 34)]
    (is (:degenerate? h) "near-equal values are treated as a single value")
    (is (= 100 (reduce + (:counts h))) "all values are still counted")
    (is (= 100 (nth (:counts h) (quot 34 2))) "they pile into the centre bin"))
  (let [h (engine/histogram [0 1 2 3 4 5 6 7 8 9] 5)]
    (is (not (:degenerate? h)) "a genuinely spread metric is not degenerate")))

(deftest histogram-clips-to-percentile-domain-test
  (let [body (mapv (fn [i] (/ i 100.0)) (range 100))
        v (conj body 100.0)
        sorted (vec (sort v))
        lo (engine/percentile sorted 1)
        hi (engine/percentile sorted 99)
        full (engine/histogram v 34)
        clipped (engine/histogram v 34 {:domain [lo hi]})]
    (is (= 101 (reduce + (:counts full))) "all values counted (unclipped)")
    (is (= 101 (reduce + (:counts clipped))) "clamped values are still counted")
    (is (not (:overflow-hi? full)) "no domain ⇒ no overflow flag")
    (is (:overflow-hi? clipped) "the +100 outlier is clamped into the last bin and flagged")
    (is (< (:max clipped) (:data-max clipped)) "the displayed max is the clip bound, below the true max")
    (is (> (count (filter pos? (:counts clipped))) 5) "clipping spreads the body across many bins")
    (is (<= (count (filter pos? (:counts full))) 2) "without clipping the body collapses into ~one bin")))

(deftest run-is-deterministic-by-seed-test
  (let [returns (varied-returns 250)
        opts {:returns returns :sims 500 :horizon 90 :seed 123 :start-equity 1000}
        a (engine/run opts)
        b (engine/run opts)]
    (is (= (:band a) (:band b)) "identical inputs produce identical bands")
    (is (= (:goal-prob a) (:goal-prob b)))
    (is (= (:bust-prob a) (:bust-prob b)))
    (is (= (get-in a [:terminal :median]) (get-in b [:terminal :median])))))

(deftest run-with-different-seed-differs-test
  (let [returns (varied-returns 250)
        a (engine/run {:returns returns :sims 400 :horizon 60 :seed 1})
        b (engine/run {:returns returns :sims 400 :horizon 60 :seed 2})]
    (is (not= (:band a) (:band b)) "a different seed reshuffles the paths")))

(deftest constant-positive-returns-guarantee-goal-and-no-bust-test
  (let [returns (constant-returns 0.01 252)
        res (engine/run {:returns returns :sims 300 :horizon 90
                         :goal 0.5 :bust -0.3 :start-equity 1})
        expected-terminal (dec (js/Math.pow 1.01 90))]
    (is (= 1 (:goal-prob res)) "all paths reach the goal")
    (is (= 0 (:bust-prob res)) "no path busts")
    (is (approx= expected-terminal (get-in res [:terminal :median]) 1e-9))
    (is (= 252 (get-in res [:meta :sample-size])))))

(deftest percentile-bands-are-monotonic-test
  (let [res (engine/run {:returns (varied-returns 252) :sims 600 :horizon 120 :seed 9})
        {:keys [p5 p25 p50 p75 p95]} (:band res)]
    (doseq [i (range (count p50))]
      (is (<= (nth p5 i) (nth p25 i) (nth p50 i) (nth p75 i) (nth p95 i))
          (str "band ordering holds at grid point " i)))
    (is (<= (get-in res [:terminal :p5])
            (get-in res [:terminal :p50])
            (get-in res [:terminal :p95]))
        "terminal distribution percentiles are ordered")))

(deftest grid-times-and-day-zero-anchor-test
  (let [res (engine/run {:returns (constant-returns 0.003 100)
                         :sims 50 :horizon 90 :start-equity 1000})]
    (is (= 0 (first (:times res))))
    (is (= 90 (peek (:times res))))
    (is (= 91 (count (:times res))))
    (is (= 1000 (first (get-in res [:band :p50]))) "day 0 anchors to start equity")))

(deftest draw-paths-are-capped-test
  (let [res (engine/run {:returns (constant-returns 0.001 50) :sims 1000 :horizon 30})]
    (is (= 220 (count (:draw-paths res))) "kept paths are capped at 220")
    (is (= 31 (alength (first (:draw-paths res)))) "each path has horizon+1 points")))

(deftest empty-returns-is-safe-test
  (let [res (engine/run {:returns [] :sims 100 :horizon 30})]
    (is (= 0 (:goal-prob res)))
    (is (= 0 (:bust-prob res)))
    (is (= 0 (get-in res [:meta :sample-size])))
    (is (= [] (:draw-paths res)))))

(deftest bootstrap-clamps-horizon-to-sample-size-test
  (let [res (engine/run {:returns (varied-returns 100) :sims 200 :horizon 365 :seed 3})]
    (is (= 100 (get-in res [:meta :horizon])) "horizon clamps down to the sample size")
    (is (= 100 (peek (:times res))))
    (is (= :bootstrap (get-in res [:meta :method])) "bootstrap is the default method"))
  (let [res (engine/run {:returns (varied-returns 250) :sims 50 :horizon 90 :seed 3})]
    (is (= 90 (get-in res [:meta :horizon])) "a horizon within history is left alone")))

(deftest shuffle-pins-terminal-and-varies-drawdown-test
  (let [returns (varied-returns 120)
        res (engine/run {:returns returns :sims 400 :horizon 999
                         :method :shuffle :seed 7 :start-equity 1})
        term (:terminal res)
        dd (:maxdd res)
        cagr (:cagr res)
        sharpe (:sharpe res)]
    (is (= :shuffle (get-in res [:meta :method])))
    (is (= 120 (get-in res [:meta :horizon])) "shuffle length is the history length, not the horizon arg")
    (is (= 120 (peek (:times res))))
    (is (approx= (:p5 term) (:p95 term) 1e-9) "terminal P5 equals terminal P95")
    (is (approx= (:min term) (:max term) 1e-9) "terminal min equals terminal max")
    (is (< (:std term) 1e-9) "terminal value does not vary across orderings")
    (is (< (:std cagr) 1e-9) "CAGR is fixed (terminal is shuffle-invariant), matching QuantStats montecarlo_cagr")
    (is (> (:std dd) 0) "max drawdown genuinely varies across orderings")
    (is (> (:std sharpe) 0) "Sharpe varies via QuantStats' per-path day-drop (leave-one-out)")))

(deftest shuffle-band-converges-at-the-final-day-test
  (let [res (engine/run {:returns (varied-returns 150) :sims 500
                         :method :shuffle :seed 11})
        {:keys [p5 p50 p95]} (:band res)
        last-i (dec (count p50))]
    (is (approx= (nth p5 last-i) (nth p95 last-i) 1e-9)
        "all paths meet at the same realized endpoint")
    (is (approx= (nth p50 last-i) (nth p95 last-i) 1e-9))))

(deftest shuffle-is-deterministic-by-seed-test
  (let [returns (varied-returns 120)
        opts {:returns returns :sims 300 :method :shuffle :seed 21}
        a (engine/run opts)
        b (engine/run opts)
        c (engine/run (assoc opts :seed 22))]
    (is (= (:band a) (:band b)) "same seed reshuffles identically")
    (is (= (get-in a [:maxdd :median]) (get-in b [:maxdd :median])))
    (is (not= (:band a) (:band c)) "a different seed yields different orderings")))
