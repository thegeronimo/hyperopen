(ns hyperopen.domain.trading.indicators.math-adapter-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.domain.trading.indicators.math-adapter :as math-adapter]
            ["indicatorts" :refer [apo cci cmf cmo dema ema emv fi ichimokuCloud kc macd mfi mi movingLeastSquare movingLinearRegressionUsingLeastSquare obv psar pvo rma rsi stoch tema trix vortex vpt vwap vwma willr]
             :rename {apo js-apo
                      cci js-cci
                      cmf js-cmf
                      cmo js-cmo
                      dema js-dema
                      ema js-ema
                      emv js-emv
                      fi js-fi
                      ichimokuCloud js-ichimoku-cloud
                      kc js-kc
                      macd js-macd
                      mfi js-mfi
                      mi js-mi
                      movingLeastSquare js-moving-least-square
                      movingLinearRegressionUsingLeastSquare js-moving-linear-regression
                      obv js-obv
                      psar js-psar
                      pvo js-pvo
                      rma js-rma
                      rsi js-rsi
                      stoch js-stoch
                      tema js-tema
                      trix js-trix
                      vortex js-vortex
                      vpt js-vpt
                      vwap js-vwap
                      vwma js-vwma
                      willr js-willr}]))

(defn- sample-candle
  [idx]
  (let [idxf (double idx)
        base (+ 100 (* idxf 0.75))
        oscillation (* 1.8 (js/Math.sin (/ idxf 4)))]
    {:time (+ 1700000000 (* idx 60))
     :open (+ base oscillation)
     :high (+ base 2.4 oscillation)
     :low (- (+ base oscillation) 2.1)
     :close (+ base (* 0.6 oscillation))
     :volume (+ 1200 (* idx 13))}))

(def ^:private sample-candles
  (mapv sample-candle (range 120)))

(def ^:private highs (mapv :high sample-candles))
(def ^:private lows (mapv :low sample-candles))
(def ^:private closes (mapv :close sample-candles))
(def ^:private volumes (mapv :volume sample-candles))
(def ^:private x-values (vec (range (count sample-candles))))

(defn- js->kw
  [value]
  (js->clj value :keywordize-keys true))

(defn- canonicalize-nan
  [value]
  (cond
    (number? value) (if (js/isNaN value) ::nan value)
    (map? value) (into {}
                       (map (fn [[k v]]
                              [k (canonicalize-nan v)]))
                       value)
    (vector? value) (mapv canonicalize-nan value)
    (sequential? value) (mapv canonicalize-nan value)
    :else value))

(defn- parity=
  [expected actual]
  (= (canonicalize-nan expected)
     (canonicalize-nan actual)))

(deftest indicatorts-series-adapter-parity-test
  (let [cases [[:on-balance-volume
                (vec (js-obv (into-array closes) (into-array volumes)))
                (vec (math-adapter/on-balance-volume closes volumes))]
               [:price-volume-trend
                (vec (js-vpt (into-array closes) (into-array volumes)))
                (vec (math-adapter/price-volume-trend closes volumes))]
               [:chaikin-money-flow
                (vec (js-cmf (into-array highs)
                             (into-array lows)
                             (into-array closes)
                             (into-array volumes)
                             #js {:period 20}))
                (vec (math-adapter/chaikin-money-flow highs lows closes volumes {:period 20}))]
               [:ease-of-movement
                (vec (js-emv (into-array highs)
                             (into-array lows)
                             (into-array volumes)
                             #js {:period 14}))
                (vec (math-adapter/ease-of-movement highs lows volumes {:period 14}))]
               [:elders-force-index
                (vec (js-fi (into-array closes)
                            (into-array volumes)
                            #js {:period 13}))
                (vec (math-adapter/elders-force-index closes volumes {:period 13}))]
               [:money-flow-index
                (vec (js-mfi (into-array highs)
                             (into-array lows)
                             (into-array closes)
                             (into-array volumes)
                             #js {:period 14}))
                (vec (math-adapter/money-flow-index highs lows closes volumes {:period 14}))]
               [:absolute-price-oscillator
                (vec (js-apo (into-array closes) #js {:fast 12 :slow 26}))
                (vec (math-adapter/absolute-price-oscillator closes {:fast 12 :slow 26}))]
               [:relative-strength-index
                (vec (js-rsi (into-array closes) #js {:period 14}))
                (vec (math-adapter/relative-strength-index closes {:period 14}))]
               [:trix
                (vec (js-trix (into-array closes) #js {:period 15}))
                (vec (math-adapter/trix closes {:period 15}))]
               [:williams-r
                (vec (js-willr (into-array highs)
                               (into-array lows)
                               (into-array closes)
                               #js {:period 14}))
                (vec (math-adapter/williams-r highs lows closes {:period 14}))]
               [:commodity-channel-index
                (vec (js-cci (into-array highs)
                             (into-array lows)
                             (into-array closes)
                             #js {:period 20}))
                (vec (math-adapter/commodity-channel-index highs lows closes {:period 20}))]
               [:mass-index
                (vec (js-mi (into-array highs)
                            (into-array lows)
                            #js {:emaPeriod 9 :miPeriod 25}))
                (vec (math-adapter/mass-index highs lows {:ema-period 9 :mi-period 25}))]
               [:vwap
                (vec (js-vwap (into-array closes)
                              (into-array volumes)
                              #js {:period 20}))
                (vec (math-adapter/vwap closes volumes {:period 20}))]
               [:vwma
                (vec (js-vwma (into-array closes)
                              (into-array volumes)
                              #js {:period 20}))
                (vec (math-adapter/vwma closes volumes {:period 20}))]
               [:dema
                (vec (js-dema (into-array closes) #js {:period 20}))
                (vec (math-adapter/dema closes {:period 20}))]
               [:ema
                (vec (js-ema (into-array closes) #js {:period 20}))
                (vec (math-adapter/ema closes {:period 20}))]
               [:tema
                (vec (js-tema (into-array closes) #js {:period 20}))
                (vec (math-adapter/tema closes {:period 20}))]
               [:rma
                (vec (js-rma (into-array closes) #js {:period 14}))
                (vec (math-adapter/rma closes {:period 14}))]
               [:moving-linear-regression
                (vec (js-moving-linear-regression 25 (into-array x-values) (into-array closes)))
                (vec (math-adapter/moving-linear-regression 25 x-values closes))]]]
    (doseq [[label expected actual] cases]
      (is (parity= expected actual)
          (str "series parity mismatch for " label)))))

(deftest indicatorts-map-adapter-parity-test
  (let [cases [[:percentage-volume-oscillator
                (js->kw (js-pvo (into-array volumes)
                                #js {:fast 12 :slow 26 :signal 9}))
                (math-adapter/percentage-volume-oscillator volumes {:fast 12 :slow 26 :signal 9})]
               [:chaikin-oscillator
                (js->kw (js-cmo (into-array highs)
                                (into-array lows)
                                (into-array closes)
                                (into-array volumes)
                                #js {:fast 3 :slow 10}))
                (math-adapter/chaikin-oscillator highs lows closes volumes {:fast 3 :slow 10})]
               [:stochastic
                (js->kw (js-stoch (into-array highs)
                                  (into-array lows)
                                  (into-array closes)
                                  #js {:kPeriod 14 :dPeriod 3}))
                (math-adapter/stochastic highs lows closes {:k-period 14 :d-period 3})]
               [:macd
                (js->kw (js-macd (into-array closes)
                                 #js {:fast 12 :slow 26 :signal 9}))
                (math-adapter/macd closes {:fast 12 :slow 26 :signal 9})]
               [:ichimoku-cloud
                (js->kw (js-ichimoku-cloud (into-array highs)
                                           (into-array lows)
                                           (into-array closes)
                                           #js {:short 9 :medium 26 :long 52 :close 26}))
                (math-adapter/ichimoku-cloud highs lows closes {:short 9 :medium 26 :long 52 :close 26})]
               [:parabolic-sar
                (js->kw (js-psar (into-array highs)
                                 (into-array lows)
                                 (into-array closes)
                                 #js {:step 0.02 :max 0.2}))
                (math-adapter/parabolic-sar highs lows closes {:step 0.02 :max-value 0.2})]
               [:vortex
                (js->kw (js-vortex (into-array highs)
                                   (into-array lows)
                                   (into-array closes)
                                   #js {:period 14}))
                (math-adapter/vortex highs lows closes {:period 14})]
               [:moving-least-square
                (js->kw (js-moving-least-square 25
                                                (into-array x-values)
                                                (into-array closes)))
                (math-adapter/moving-least-square 25 x-values closes)]
               [:keltner-channels
                (js->kw (js-kc (into-array highs)
                               (into-array lows)
                               (into-array closes)
                               #js {:period 20}))
                (math-adapter/keltner-channels highs lows closes {:period 20})]]]
    (doseq [[label expected actual] cases]
      (is (parity= expected actual)
          (str "map parity mismatch for " label)))))
