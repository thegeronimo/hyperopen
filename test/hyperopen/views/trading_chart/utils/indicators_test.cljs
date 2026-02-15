(ns hyperopen.views.trading-chart.utils.indicators-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.trading-chart.utils.indicators :as indicators]))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- sample-candle
  [idx]
  {:time (+ 1700000000 (* idx 86400))
   :open (+ 100 (* idx 0.8))
   :high (+ 101 (* idx 0.9))
   :low (+ 99 (* idx 0.7))
   :close (+ 100.5 (* idx 0.85))
   :volume (+ 1000 (* idx 25))})

(def sample-candles
  (mapv sample-candle (range 60)))

(def fractal-candles
  [{:time 1 :open 8 :high 10 :low 6 :close 9 :volume 100}
   {:time 2 :open 10 :high 12 :low 7 :close 11 :volume 110}
   {:time 3 :open 11 :high 16 :low 9 :close 12 :volume 120}
   {:time 4 :open 12 :high 13 :low 8 :close 10 :volume 115}
   {:time 5 :open 10 :high 11 :low 7 :close 9 :volume 108}
   {:time 6 :open 9 :high 12 :low 6 :close 10 :volume 125}
   {:time 7 :open 10 :high 10 :low 3 :close 8 :volume 130}
   {:time 8 :open 8 :high 13 :low 5 :close 11 :volume 122}
   {:time 9 :open 11 :high 12 :low 6 :close 10 :volume 118}])

(defn- indicator-series-by-id
  [indicator id]
  (some (fn [series]
          (when (= id (:id series))
            series))
        (:series indicator)))

(defn- last-value
  [series]
  (:value (last (:data series))))

(deftest calculate-sma-test
  (let [candles [{:time 1 :close 10}
                 {:time 2 :close 20}
                 {:time 3 :close 30}
                 {:time 4 :close 40}]
        result (vec (indicators/calculate-sma candles 2))]
    (testing "warmup entries are whitespace points"
      (is (= {:time 1} (nth result 0)))
      (is (= {:time 2} (nth result 1))))
    (testing "later entries include SMA values"
      (is (= {:time 3 :value 25} (nth result 2)))
      (is (= {:time 4 :value 35} (nth result 3))))))

(deftest available-indicators-test
  (let [available (indicators/get-available-indicators)
        ids (set (map :id available))
        expected-core-ids #{:week-52-high-low
                            :accelerator-oscillator
                            :accumulation-distribution
                            :accumulative-swing-index
                            :advance-decline
                            :alma
                            :aroon
                            :adx
                            :average-price
                            :atr
                            :awesome-oscillator
                            :balance-of-power
                            :bollinger-bands
                            :sma}
        expected-wave2-ids #{:bollinger-bands-percent-b
                             :bollinger-bands-width
                             :chaikin-money-flow
                             :chaikin-oscillator
                             :commodity-channel-index
                             :donchian-channels
                             :ema-cross
                             :historical-volatility
                             :hull-moving-average
                             :ichimoku-cloud
                             :keltner-channels
                             :macd
                             :money-flow-index
                             :on-balance-volume
                             :parabolic-sar
                             :rate-of-change
                             :relative-strength-index
                             :stochastic-rsi
                             :supertrend
                             :triple-ema
                             :vortex-indicator
                             :vwap
                             :vwma
                             :williams-r}
        expected-wave3-ids #{:chaikin-volatility
                             :chande-kroll-stop
                             :connors-rsi
                             :coppock-curve
                             :correlation-log
                             :correlation-coefficient
                             :fisher-transform
                             :guppy-multiple-moving-average
                             :klinger-oscillator
                             :know-sure-thing
                             :majority-rule
                             :mcginley-dynamic
                             :moving-average-adaptive
                             :moving-average-hamming
                             :pivot-points-standard
                             :rank-correlation-index
                             :relative-vigor-index
                             :relative-volatility-index
                             :smi-ergodic
                             :standard-error
                             :standard-error-bands
                             :trend-strength-index
                             :true-strength-index
                             :ultimate-oscillator
                             :volatility-close-to-close
                             :volatility-index
                             :volatility-ohlc
                             :volatility-zero-trend-close-to-close
                             :volume
                             :williams-alligator
                             :williams-fractal
                             :zig-zag}
        by-id (into {} (map (juxt :id identity) available))]
    (is (every? ids expected-core-ids))
    (is (every? ids expected-wave2-ids))
    (is (every? ids expected-wave3-ids))
    (is (> (count available) 70))
    (is (true? (:supports-period? (get by-id :sma))))
    (is (false? (:supports-period? (get by-id :awesome-oscillator))))
    (is (false? (:supports-period? (get by-id :macd))))
    (is (= (mapv :name available)
           (->> available
                (map :name)
                (sort-by str/lower-case)
                vec)))))

(deftest calculate-indicator-sma-shape-test
  (let [result (indicators/calculate-indicator :sma sample-candles {:period 5})
        series (first (:series result))]
    (is (= :sma (:type result)))
    (is (= :overlay (:pane result)))
    (is (= :line (:series-type series)))
    (is (= (count sample-candles) (count (:data series))))
    (is (nil? (:value (nth (:data series) 4))))
    (is (finite-number? (last-value series)))))

(deftest calculate-indicator-bollinger-bands-multi-series-test
  (let [result (indicators/calculate-indicator :bollinger-bands sample-candles {:period 5 :multiplier 2})
        upper (indicator-series-by-id result :upper)
        basis (indicator-series-by-id result :basis)
        lower (indicator-series-by-id result :lower)
        upper-last (last-value upper)
        basis-last (last-value basis)
        lower-last (last-value lower)]
    (is (= :overlay (:pane result)))
    (is (= 3 (count (:series result))))
    (is (every? some? [upper basis lower]))
    (is (finite-number? upper-last))
    (is (finite-number? basis-last))
    (is (finite-number? lower-last))
    (is (> upper-last basis-last))
    (is (> basis-last lower-last))))

(deftest calculate-indicator-oscillator-histogram-test
  (doseq [indicator-id [:awesome-oscillator :accelerator-oscillator]]
    (let [result (indicators/calculate-indicator indicator-id sample-candles {})
          series (first (:series result))]
      (is (= :separate (:pane result)))
      (is (= :histogram (:series-type series)))
      (is (= (count sample-candles) (count (:data series))))
      (is (finite-number? (last-value series))))))

(deftest calculate-indicator-aroon-lines-test
  (let [result (indicators/calculate-indicator :aroon sample-candles {:period 5})
        up (indicator-series-by-id result :aroon-up)
        down (indicator-series-by-id result :aroon-down)
        up-values (filter finite-number? (map :value (:data up)))
        down-values (filter finite-number? (map :value (:data down)))]
    (is (= :separate (:pane result)))
    (is (= 2 (count (:series result))))
    (is (seq up-values))
    (is (seq down-values))
    (is (every? #(<= 0 % 100) up-values))
    (is (every? #(<= 0 % 100) down-values))))

(deftest calculate-indicator-52-week-high-low-and-average-price-test
  (let [week-result (indicators/calculate-indicator :week-52-high-low sample-candles {:period 1})
        high-series (indicator-series-by-id week-result :high)
        low-series (indicator-series-by-id week-result :low)
        highs (mapv :high sample-candles)
        lows (mapv :low sample-candles)
        expected-last-high (apply max (subvec highs (- (count highs) 8) (count highs)))
        expected-last-low (apply min (subvec lows (- (count lows) 8) (count lows)))
        average-price-result (indicators/calculate-indicator :average-price sample-candles {})
        average-series (first (:series average-price-result))
        first-candle (first sample-candles)
        expected-first-average (/ (+ (:open first-candle)
                                     (:high first-candle)
                                     (:low first-candle)
                                     (:close first-candle))
                                  4)]
    (is (= expected-last-high (last-value high-series)))
    (is (= expected-last-low (last-value low-series)))
    (is (= expected-first-average (:value (first (:data average-series)))))))

(deftest calculate-indicator-advance-decline-proxy-test
  (let [result (indicators/calculate-indicator :advance-decline sample-candles {})
        series (first (:series result))]
    (is (= 59 (last-value series)))))

(deftest calculate-indicator-test
  (is (nil? (indicators/calculate-indicator :unknown sample-candles {}))))

(deftest calculate-indicator-wave2-macd-shape-test
  (let [result (indicators/calculate-indicator :macd sample-candles {})
        series (:series result)
        histogram (first series)
        macd-line (second series)
        signal-line (nth series 2)]
    (is (= :macd (:type result)))
    (is (= :separate (:pane result)))
    (is (= 3 (count series)))
    (is (= :histogram (:series-type histogram)))
    (is (= :line (:series-type macd-line)))
    (is (= :line (:series-type signal-line)))
    (is (finite-number? (last-value macd-line)))
    (is (finite-number? (last-value signal-line)))))

(deftest calculate-indicator-wave2-supertrend-shape-test
  (let [result (indicators/calculate-indicator :supertrend sample-candles {})]
    (is (= :overlay (:pane result)))
    (is (= 2 (count (:series result))))
    (is (some finite-number? (map :value (:data (first (:series result))))))
    (is (some finite-number? (map :value (:data (second (:series result))))))))

(deftest calculate-indicator-wave2-stochastic-rsi-shape-test
  (let [result (indicators/calculate-indicator :stochastic-rsi sample-candles {})
        k-series (first (:series result))
        d-series (second (:series result))]
    (is (= :separate (:pane result)))
    (is (= 2 (count (:series result))))
    (is (= (count sample-candles) (count (:data k-series))))
    (is (= (count sample-candles) (count (:data d-series))))))

(deftest calculate-indicator-wave2-ichimoku-and-vwap-shape-test
  (let [ichimoku (indicators/calculate-indicator :ichimoku-cloud sample-candles {})
        vwap-result (indicators/calculate-indicator :vwap sample-candles {})]
    (is (= :overlay (:pane ichimoku)))
    (is (= 5 (count (:series ichimoku))))
    (is (= :overlay (:pane vwap-result)))
    (is (= 1 (count (:series vwap-result))))
    (is (finite-number? (last-value (first (:series vwap-result)))))))

(deftest calculate-indicator-wave3-shape-test
  (let [gmma (indicators/calculate-indicator :guppy-multiple-moving-average sample-candles {})
        pivots (indicators/calculate-indicator :pivot-points-standard sample-candles {:period 10})
        fisher (indicators/calculate-indicator :fisher-transform sample-candles {})
        smi (indicators/calculate-indicator :smi-ergodic sample-candles {})
        volume (indicators/calculate-indicator :volume sample-candles {})]
    (is (= :overlay (:pane gmma)))
    (is (= 12 (count (:series gmma))))
    (is (= :overlay (:pane pivots)))
    (is (= 7 (count (:series pivots))))
    (is (= :separate (:pane fisher)))
    (is (= 2 (count (:series fisher))))
    (is (= :separate (:pane smi)))
    (is (= 3 (count (:series smi))))
    (is (= :histogram (:series-type (first (:series smi)))))
    (is (= :separate (:pane volume)))
    (is (= :histogram (:series-type (first (:series volume)))))))

(deftest calculate-indicator-wave3-regression-and-williams-shape-test
  (let [stderr-bands (indicators/calculate-indicator :standard-error-bands sample-candles {:period 10 :multiplier 2})
        alligator (indicators/calculate-indicator :williams-alligator sample-candles {})
        fractal (indicators/calculate-indicator :williams-fractal fractal-candles {})
        zig-zag (indicators/calculate-indicator :zig-zag sample-candles {:threshold-percent 3})
        markers (:markers fractal)
        bearish (first (filter #(= "aboveBar" (:position %)) markers))
        bullish (first (filter #(= "belowBar" (:position %)) markers))
        upper (indicator-series-by-id stderr-bands :upper)
        center (indicator-series-by-id stderr-bands :center)
        lower (indicator-series-by-id stderr-bands :lower)]
    (is (= :overlay (:pane stderr-bands)))
    (is (= 3 (count (:series stderr-bands))))
    (is (finite-number? (last-value upper)))
    (is (finite-number? (last-value center)))
    (is (finite-number? (last-value lower)))
    (is (= :overlay (:pane alligator)))
    (is (= 3 (count (:series alligator))))
    (is (= :overlay (:pane fractal)))
    (is (= 0 (count (:series fractal))))
    (is (= 2 (count markers)))
    (is (= "arrowDown" (:shape bearish)))
    (is (= 3 (:time bearish)))
    (is (= "▲" (:text bearish)))
    (is (= "#22c55e" (:color bearish)))
    (is (= 0 (:size bearish)))
    (is (= "arrowUp" (:shape bullish)))
    (is (= 7 (:time bullish)))
    (is (= "▼" (:text bullish)))
    (is (= "#ef4444" (:color bullish)))
    (is (= 0 (:size bullish)))
    (is (= :overlay (:pane zig-zag)))
    (is (= 1 (count (:series zig-zag))))
    (is (some finite-number? (map :value (:data (first (:series zig-zag))))))))

(deftest calculate-indicator-heavy-parity-determinism-test
  (let [candles (mapv sample-candle (range 500))
        cases [[:supertrend {:period 10 :multiplier 3}]
               [:ichimoku-cloud {:short 9 :medium 26 :long 52 :close 26}]
               [:linear-regression-slope {:period 25}]
               [:standard-error-bands {:period 20 :multiplier 2}]]]
    (doseq [[indicator-id params] cases]
      (let [result-a (indicators/calculate-indicator indicator-id candles params)
            result-b (indicators/calculate-indicator indicator-id candles params)]
        (is (some? result-a) (str "missing result for " indicator-id))
        (is (= result-a result-b) (str "non-deterministic result for " indicator-id))))))

(deftest calculate-indicator-heavy-performance-smoke-test
  (let [candles (mapv sample-candle (range 800))
        start-ms (js/Date.now)
        cases [[:supertrend {:period 10 :multiplier 3}]
               [:ichimoku-cloud {:short 9 :medium 26 :long 52 :close 26}]
               [:linear-regression-curve {:period 25}]
               [:standard-error-bands {:period 20 :multiplier 2}]
               [:correlation-log {:period 20}]]]
    (doseq [[indicator-id params] cases]
      (is (some? (indicators/calculate-indicator indicator-id candles params))
          (str "expected heavy indicator result for " indicator-id)))
    (let [elapsed-ms (- (js/Date.now) start-ms)]
      (is (< elapsed-ms 7000)
          (str "heavy indicator suite took too long: " elapsed-ms "ms")))))

(deftest calculate-indicator-invalid-data-fails-closed-test
  (is (nil? (indicators/calculate-indicator :supertrend [1 2 3] {})))
  (is (nil? (indicators/calculate-indicator :stochastic-rsi [nil] {})))
  (is (nil? (indicators/calculate-indicator :macd sample-candles []))))
