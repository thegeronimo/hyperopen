(ns hyperopen.portfolio.montecarlo.engine
  "Pure, deterministic Monte Carlo engine for the portfolio forecast surface.

  Two methods share one result shape, selected by `:method`:

    :shuffle   — the faithful QuantStats method (`quantstats/_montecarlo.py`).
                 Each simulation is a random *reordering* (permutation, no
                 replacement) of the realized returns, so the simulation length
                 equals the history length and there is no forecast horizon.
                 Reordering cannot change the product of (1+r), so every path
                 ends at the *same* realized terminal value; only the path —
                 hence the drawdown — varies. Sim 0 is the original order,
                 matching QuantStats's unshuffled first column.

    :bootstrap — draws the realized returns at random *with replacement* over a
                 forecast horizon, thousands of times, to map the range of
                 forward outcomes the same return distribution could produce.
                 The horizon is clamped to the sample size so a forecast can
                 never compound more days than were actually observed.

  Both preserve the empirical return distribution while breaking time-ordering,
  isolating luck from skill across drawdowns, Sharpe, and terminal value.

  Determinism: randomness comes from a seeded `mulberry32` generator, so a given
  set of inputs always produces the same result. The namespace performs no I/O
  and touches no DOM, so it is worker-safe and unit-testable. The hot simulation
  loop uses `js/Float64Array` and array access for speed; only the small,
  capped set of drawn paths and the per-grid percentile bands are materialized
  as ClojureScript vectors for the view layer.")

(defn mulberry32
  "Return a zero-argument function producing a deterministic stream of uniform
  floats in [0, 1), seeded by integer `seed`."
  [seed]
  (let [state (atom (bit-or 0 seed))]
    (fn []
      (let [a (bit-or 0 (+ @state 0x6D2B79F5))]
        (reset! state a)
        (let [t (js/Math.imul (bit-xor a (unsigned-bit-shift-right a 15))
                              (bit-or 1 a))
              t (bit-xor (+ t (js/Math.imul (bit-xor t (unsigned-bit-shift-right t 7))
                                            (bit-or 61 t)))
                         t)]
          (/ (unsigned-bit-shift-right (bit-xor t (unsigned-bit-shift-right t 14)) 0)
             4294967296))))))

(defn- f64->vec
  "Copy a (typed) array into a ClojureScript vector of numbers. Avoids relying on
  `vec`/`array-seq` semantics over `js/Float64Array`, which vary by build."
  [arr]
  (let [n (alength arr)]
    (loop [i 0 acc (transient [])]
      (if (< i n)
        (recur (inc i) (conj! acc (aget arr i)))
        (persistent! acc)))))

(defn percentile
  "Linear-interpolated percentile `p` (0..100) over an already-sorted numeric
  collection `sorted`. Returns 0 for an empty collection."
  [sorted p]
  (let [n (count sorted)]
    (if (zero? n)
      0
      (let [idx (* (/ p 100) (dec n))
            lo (js/Math.floor idx)
            hi (js/Math.ceil idx)]
        (if (== lo hi)
          (nth sorted lo)
          (let [a (nth sorted lo)
                b (nth sorted hi)]
            (+ a (* (- b a) (- idx lo)))))))))

(defn dist-stats
  "Summarize a numeric collection `xs` into the distribution map the view needs:
  min/max/mean/median/std and the p5..p95 percentiles, plus `:raw` (the original
  values, for histograms) and `:sorted`."
  [xs]
  (let [v (vec xs)
        n (count v)
        sorted (vec (sort v))
        sum (reduce + 0 v)
        mean (if (zero? n) 0 (/ sum n))
        variance (if (zero? n)
                   0
                   (/ (reduce (fn [acc x]
                                (let [d (- x mean)]
                                  (+ acc (* d d))))
                              0 v)
                      n))]
    {:min (if (zero? n) 0 (first sorted))
     :max (if (zero? n) 0 (peek sorted))
     :mean mean
     :median (percentile sorted 50)
     :std (js/Math.sqrt variance)
     :p5 (percentile sorted 5)
     :p10 (percentile sorted 10)
     :p25 (percentile sorted 25)
     :p50 (percentile sorted 50)
     :p75 (percentile sorted 75)
     :p90 (percentile sorted 90)
     :p95 (percentile sorted 95)
     :raw v
     :sorted sorted}))

(defn histogram
  "Bin numeric collection `xs` into `bins` equal-width buckets over an optional
  clip `:domain [lo hi]` (default: the data's own min/max). Values outside the
  domain are clamped into the edge bins, so the counts still sum to the input
  size; `:overflow-lo?`/`:overflow-hi?` flag whether any were. Clipping the
  domain to a percentile band keeps a few extreme outliers (e.g. one wildly
  compounded bootstrap path) from stretching a linear axis and crushing the body
  of a heavy-tailed distribution into a single bar.

  Returns `{:counts [...] :min :max :data-min :data-max :span :degenerate?
  :overflow-lo? :overflow-hi? :bin-width}` where `:min`/`:max` are the displayed
  domain and `:data-min`/`:data-max` are the true extremes."
  ([xs bins] (histogram xs bins nil))
  ([xs bins {:keys [domain]}]
   (let [v (vec xs)]
     (if (empty? v)
       {:counts (vec (repeat bins 0)) :min 0 :max 0 :data-min 0 :data-max 0
        :span 1 :degenerate? true :overflow-lo? false :overflow-hi? false
        :bin-width (/ 1 bins)}
       (let [data-min (reduce min v)
             data-max (reduce max v)
             dlo (when (and domain (number? (first domain))) (first domain))
             dhi (when (and domain (number? (second domain))) (second domain))
             [lo hi] (if (and dlo dhi (< dlo dhi)) [dlo dhi] [data-min data-max])
             degenerate? (<= (- hi lo)
                             (* 1e-9 (max 1 (js/Math.abs lo) (js/Math.abs hi))))
             span (if (or degenerate? (<= (- hi lo) 0)) 1 (- hi lo))
             counts (js/Array. bins)]
         (dotimes [i bins] (aset counts i 0))
         (doseq [x v]
           (let [b (if degenerate?
                     (quot bins 2)
                     (let [b (js/Math.floor (* (/ (- x lo) span) bins))]
                       (cond (>= b bins) (dec bins)
                             (< b 0) 0
                             :else b)))]
             (aset counts b (inc (aget counts b)))))
         {:counts (vec counts)
          :min lo
          :max hi
          :data-min data-min
          :data-max data-max
          :span span
          :degenerate? degenerate?
          :overflow-lo? (boolean (and (not degenerate?) (< data-min lo)))
          :overflow-hi? (boolean (and (not degenerate?) (> data-max hi)))
          :bin-width (/ span bins)})))))

(defn- grid-indices
  "Sampled time grid (day indices 0..H) used to bound the memory of the
  percentile-band columns. Mirrors the prototype's GRID = min(H, 160)."
  [^number H]
  (let [grid (min H 160)
        out (js/Array.)]
    (dotimes [g (inc grid)]
      (.push out (js/Math.round (* (/ g grid) H))))
    out))

(defn- ->interval-arrays
  "Normalize the engine's input into parallel typed arrays of simple returns,
  log returns and interval durations in years.

  Preferred input is `:intervals` — the irregular history the tearsheet uses,
  maps of `{:simple-return :log-return :dt-years}` from
  `hyperopen.portfolio.metrics.history/cumulative-rows->irregular-intervals`, so
  each step carries its real elapsed time. A legacy `:returns` seq of simple
  returns is also accepted and treated as one trading day per point
  (`dt-years = 1/365`), preserving the old daily behavior for any such caller.
  Steps with a non-positive growth factor or duration are dropped."
  [intervals returns]
  (let [day-years (/ 1.0 365.0)
        src (cond
              (seq intervals)
              (mapv (fn [{:keys [simple-return log-return dt-years]}]
                      (let [s (when (number? simple-return) simple-return)
                            d (when (and (number? dt-years) (pos? dt-years)) dt-years)
                            l (cond
                                (number? log-return) log-return
                                (and (number? s) (> (+ 1 s) 0)) (js/Math.log (+ 1 s))
                                :else nil)]
                        (when (and s d l) [s l d])))
                    intervals)

              (seq returns)
              (mapv (fn [r]
                      (when (and (number? r) (> (+ 1 r) 0))
                        [r (js/Math.log (+ 1 r)) day-years]))
                    returns)

              :else [])
        rows (filterv some? src)
        n (count rows)
        simp (js/Float64Array. n)
        logr (js/Float64Array. n)
        dt (js/Float64Array. n)]
    (dotimes [i n]
      (let [row (nth rows i)]
        (aset simp i (nth row 0))
        (aset logr i (nth row 1))
        (aset dt i (nth row 2))))
    {:simp simp :logr logr :dt dt :m n}))

(defn- identity-indices
  "An `Int32Array` of the identity permutation `0..n-1` (the original order)."
  [n]
  (let [arr (js/Int32Array. n)]
    (dotimes [i n] (aset arr i i))
    arr))

(defn- shuffled-indices
  "A deterministic Fisher–Yates permutation of `0..n-1`, drawing from `rng` (a
  `mulberry32` instance). Used by the QuantStats shuffle method to reorder the
  realized returns without replacement."
  [rng n]
  (let [arr (identity-indices n)]
    (loop [i (dec n)]
      (when (pos? i)
        (let [j (bit-or 0 (* (rng) (inc i)))
              tmp (aget arr i)]
          (aset arr i (aget arr j))
          (aset arr j tmp)
          (recur (dec i)))))
    arr))

(defn run
  "Run the Monte Carlo simulation.

  Options:
    :intervals        irregular history as maps {:simple-return :log-return
                      :dt-years} (from metrics.history/cumulative-rows->irregular-intervals);
                      each step carries its real elapsed time, so Sharpe/vol/CAGR
                      are annualized by elapsed time, matching the tearsheet
    :returns          legacy alternative: simple returns, treated as daily points
    :method           :bootstrap (default, with replacement over a horizon) or
                      :shuffle (QuantStats permutation; length = sample size)
    :sims             number of simulated paths (default 1000)
    :horizon          number of resampled steps for :bootstrap (the caller derives
                      it from a calendar span); ignored for :shuffle (uses the full
                      history); clamped to the sample size
    :bust             drawdown threshold counted as a bust, negative (default -0.3)
    :goal             total-return threshold counted as a goal (default 0.5)
    :seed             RNG seed (default 42)
    :start-equity     starting equity for ending-value scaling (default 1)
    :rf               annual risk-free rate for Sharpe (default 0)

  Returns a map with `:meta` (including `:total-years`, `:ppy-eff`,
  `:span-years`), `:draw-paths`, `:band`, `:terminal`/`:maxdd`/`:sharpe`/
  `:cagr`/`:vol` distribution maps, and `:bust-prob`/`:goal-prob`."
  [{:keys [intervals returns sims horizon bust goal seed start-equity rf method]
    :or {sims 1000 horizon 90 bust -0.3 goal 0.5 seed 42 start-equity 1
         rf 0 method :bootstrap}}]
  (let [{:keys [simp logr dt m]} (->interval-arrays intervals returns)
        shuffle? (= method :shuffle)
        realized-years (loop [i 0 acc 0.0]
                         (if (< i m) (recur (inc i) (+ acc (aget dt i))) acc))
        ppy-eff (if (pos? realized-years) (/ m realized-years) 0)
        rf-log (if (and (number? rf) (> (+ 1 rf) 0)) (js/Math.log (+ 1 rf)) 0)
        H (cond
            (zero? m) horizon
            shuffle? m
            :else (min horizon m))
        span-years (cond
                     (zero? m) 0
                     shuffle? realized-years
                     :else (* H (/ realized-years m)))
        rng (mulberry32 seed)
        grid-idx (grid-indices H)
        grid-n (.-length grid-idx)
        cols (vec (repeatedly grid-n #(js/Float64Array. sims)))
        draw-cap (min sims 220)
        terminal (js/Float64Array. sims)
        maxdd (js/Float64Array. sims)
        sharpe (js/Float64Array. sims)
        cagr (js/Float64Array. sims)
        vol (js/Float64Array. sims)
        draw-paths (transient [])
        busts (volatile! 0)
        goals (volatile! 0)]
    (if (zero? m)
      {:meta {:sims sims :horizon H :bust bust :goal goal :seed seed :method method
              :start-equity start-equity :rf rf :sample-size 0
              :total-years 0 :ppy-eff 0 :span-years 0}
       :draw-paths [] :times (vec grid-idx)
       :band {:p5 [] :p25 [] :p50 [] :p75 [] :p95 []}
       :terminal (dist-stats []) :maxdd (dist-stats [])
       :sharpe (dist-stats []) :cagr (dist-stats []) :vol (dist-stats [])
       :bust-prob 0 :goal-prob 0}
      (do
        (dotimes [s sims]
          (let [keep-path? (< s draw-cap)
                path (when keep-path? (js/Float64Array. (inc H)))
                perm (when shuffle?
                       (if (zero? s) (identity-indices m) (shuffled-indices rng m)))]
            (when keep-path? (aset path 0 start-equity))
            (let [gp0 (if (== (aget grid-idx 0) 0)
                        (do (aset (nth cols 0) s start-equity) 1)
                        0)
                  result
                  (loop [t 1 equity 1.0 peak 1.0 worst-dd 0.0
                         acc-a 0.0 acc-b 0.0 acc-c 0.0 gp gp0]
                    (if (> t H)
                      [equity worst-dd acc-a acc-b acc-c]
                      (let [idx (if shuffle?
                                  (aget perm (dec t))
                                  (bit-or 0 (* (rng) m)))
                            r (aget simp idx)
                            lg (aget logr idx)
                            d (aget dt idx)
                            equity* (* equity (+ 1 r))
                            peak* (if (> equity* peak) equity* peak)
                            dd (- (/ equity* peak*) 1)
                            worst* (if (< dd worst-dd) dd worst-dd)
                            scaled (* equity* start-equity)]
                        (when keep-path? (aset path t scaled))
                        (let [gp* (loop [gp gp]
                                    (if (and (< gp grid-n)
                                             (== (aget grid-idx gp) t))
                                      (do (aset (nth cols gp) s scaled)
                                          (recur (inc gp)))
                                      gp))]
                          (recur (inc t) equity* peak* worst*
                                 (+ acc-a (/ (* lg lg) d)) (+ acc-b lg) (+ acc-c d) gp*)))))
                  [equity worst-dd acc-a acc-b acc-c] result
                  drift (if (pos? acc-c) (/ acc-b acc-c) 0)
                  var-rate (if (> H 1)
                             (max 0 (/ (- acc-a (/ (* acc-b acc-b) acc-c)) (dec H)))
                             0)
                  vol* (js/Math.sqrt var-rate)
                  cagr* (- (js/Math.exp drift) 1)
                  sharpe-full (if (pos? vol*) (/ (- drift rf-log) vol*) 0)
                  sharpe-val (if (and shuffle? (> H 1))
                               (let [i0 (aget perm 0)
                                     a0 (aget logr i0)
                                     d0 (aget dt i0)
                                     a' (- acc-a (/ (* a0 a0) d0))
                                     b' (- acc-b a0)
                                     c' (- acc-c d0)
                                     k' (dec H)
                                     drift' (if (pos? c') (/ b' c') 0)
                                     var' (if (> k' 1)
                                            (max 0 (/ (- a' (/ (* b' b') c')) (dec k')))
                                            0)
                                     vol' (js/Math.sqrt var')]
                                 (if (pos? vol') (/ (- drift' rf-log) vol') 0))
                               sharpe-full)]
              (aset terminal s (- equity 1))
              (aset maxdd s worst-dd)
              (aset sharpe s sharpe-val)
              (aset cagr s cagr*)
              (aset vol s vol*)
              (when (<= worst-dd bust) (vswap! busts inc))
              (when (>= (- equity 1) goal) (vswap! goals inc))
              (when keep-path? (conj! draw-paths path)))))
        (let [band (reduce
                    (fn [acc g]
                      (let [sorted (f64->vec (.sort (.slice (nth cols g))
                                                    (fn [a b] (- a b))))]
                        (-> acc
                            (update :p5 conj (percentile sorted 5))
                            (update :p25 conj (percentile sorted 25))
                            (update :p50 conj (percentile sorted 50))
                            (update :p75 conj (percentile sorted 75))
                            (update :p95 conj (percentile sorted 95)))))
                    {:p5 [] :p25 [] :p50 [] :p75 [] :p95 []}
                    (range grid-n))]
          {:meta {:sims sims :horizon H :bust bust :goal goal :seed seed :method method
                  :start-equity start-equity :rf rf :sample-size m
                  :total-years realized-years :ppy-eff ppy-eff :span-years span-years}
           :draw-paths (persistent! draw-paths)
           :band band
           :times (vec grid-idx)
           :terminal (dist-stats (f64->vec terminal))
           :maxdd (dist-stats (f64->vec maxdd))
           :sharpe (dist-stats (f64->vec sharpe))
           :cagr (dist-stats (f64->vec cagr))
           :vol (dist-stats (f64->vec vol))
           :bust-prob (/ @busts sims)
           :goal-prob (/ @goals sims)})))))
