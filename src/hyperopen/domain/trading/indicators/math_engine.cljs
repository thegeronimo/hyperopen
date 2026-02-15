(ns hyperopen.domain.trading.indicators.math-engine
  (:require [hyperopen.domain.trading.indicators.math-adapter :as adapter]))

(defprotocol IIndicatorMathEngine
  (on-balance-volume* [engine close-values volume-values])
  (price-volume-trend* [engine close-values volume-values])
  (percentage-volume-oscillator* [engine volume-values opts])
  (chaikin-money-flow* [engine high-values low-values close-values volume-values opts])
  (chaikin-oscillator* [engine high-values low-values close-values volume-values opts])
  (ease-of-movement* [engine high-values low-values volume-values opts])
  (elders-force-index* [engine close-values volume-values opts])
  (money-flow-index* [engine high-values low-values close-values volume-values opts])
  (absolute-price-oscillator* [engine close-values opts])
  (stochastic* [engine high-values low-values close-values opts])
  (relative-strength-index* [engine close-values opts])
  (trix* [engine close-values opts])
  (williams-r* [engine high-values low-values close-values opts])
  (commodity-channel-index* [engine high-values low-values close-values opts])
  (macd* [engine close-values opts])
  (mass-index* [engine high-values low-values opts])
  (ichimoku-cloud* [engine high-values low-values close-values opts])
  (parabolic-sar* [engine high-values low-values close-values opts])
  (vortex* [engine high-values low-values close-values opts])
  (vwap* [engine close-values volume-values opts])
  (vwma* [engine close-values volume-values opts])
  (dema* [engine close-values opts])
  (ema* [engine close-values opts])
  (tema* [engine close-values opts])
  (rma* [engine close-values opts])
  (moving-linear-regression* [engine period x-values y-values])
  (moving-least-square* [engine period x-values y-values])
  (keltner-channels* [engine high-values low-values close-values opts]))

(defrecord AdapterMathEngine []
  IIndicatorMathEngine
  (on-balance-volume* [_ close-values volume-values]
    (adapter/on-balance-volume close-values volume-values))
  (price-volume-trend* [_ close-values volume-values]
    (adapter/price-volume-trend close-values volume-values))
  (percentage-volume-oscillator* [_ volume-values opts]
    (adapter/percentage-volume-oscillator volume-values opts))
  (chaikin-money-flow* [_ high-values low-values close-values volume-values opts]
    (adapter/chaikin-money-flow high-values low-values close-values volume-values opts))
  (chaikin-oscillator* [_ high-values low-values close-values volume-values opts]
    (adapter/chaikin-oscillator high-values low-values close-values volume-values opts))
  (ease-of-movement* [_ high-values low-values volume-values opts]
    (adapter/ease-of-movement high-values low-values volume-values opts))
  (elders-force-index* [_ close-values volume-values opts]
    (adapter/elders-force-index close-values volume-values opts))
  (money-flow-index* [_ high-values low-values close-values volume-values opts]
    (adapter/money-flow-index high-values low-values close-values volume-values opts))
  (absolute-price-oscillator* [_ close-values opts]
    (adapter/absolute-price-oscillator close-values opts))
  (stochastic* [_ high-values low-values close-values opts]
    (adapter/stochastic high-values low-values close-values opts))
  (relative-strength-index* [_ close-values opts]
    (adapter/relative-strength-index close-values opts))
  (trix* [_ close-values opts]
    (adapter/trix close-values opts))
  (williams-r* [_ high-values low-values close-values opts]
    (adapter/williams-r high-values low-values close-values opts))
  (commodity-channel-index* [_ high-values low-values close-values opts]
    (adapter/commodity-channel-index high-values low-values close-values opts))
  (macd* [_ close-values opts]
    (adapter/macd close-values opts))
  (mass-index* [_ high-values low-values opts]
    (adapter/mass-index high-values low-values opts))
  (ichimoku-cloud* [_ high-values low-values close-values opts]
    (adapter/ichimoku-cloud high-values low-values close-values opts))
  (parabolic-sar* [_ high-values low-values close-values opts]
    (adapter/parabolic-sar high-values low-values close-values opts))
  (vortex* [_ high-values low-values close-values opts]
    (adapter/vortex high-values low-values close-values opts))
  (vwap* [_ close-values volume-values opts]
    (adapter/vwap close-values volume-values opts))
  (vwma* [_ close-values volume-values opts]
    (adapter/vwma close-values volume-values opts))
  (dema* [_ close-values opts]
    (adapter/dema close-values opts))
  (ema* [_ close-values opts]
    (adapter/ema close-values opts))
  (tema* [_ close-values opts]
    (adapter/tema close-values opts))
  (rma* [_ close-values opts]
    (adapter/rma close-values opts))
  (moving-linear-regression* [_ period x-values y-values]
    (adapter/moving-linear-regression period x-values y-values))
  (moving-least-square* [_ period x-values y-values]
    (adapter/moving-least-square period x-values y-values))
  (keltner-channels* [_ high-values low-values close-values opts]
    (adapter/keltner-channels high-values low-values close-values opts)))

(def ^:dynamic *math-engine* (->AdapterMathEngine))

(defn with-math-engine
  [engine f]
  (binding [*math-engine* engine]
    (f)))

(defn on-balance-volume
  [close-values volume-values]
  (on-balance-volume* *math-engine* close-values volume-values))

(defn price-volume-trend
  [close-values volume-values]
  (price-volume-trend* *math-engine* close-values volume-values))

(defn percentage-volume-oscillator
  [volume-values opts]
  (percentage-volume-oscillator* *math-engine* volume-values opts))

(defn chaikin-money-flow
  [high-values low-values close-values volume-values opts]
  (chaikin-money-flow* *math-engine* high-values low-values close-values volume-values opts))

(defn chaikin-oscillator
  [high-values low-values close-values volume-values opts]
  (chaikin-oscillator* *math-engine* high-values low-values close-values volume-values opts))

(defn ease-of-movement
  [high-values low-values volume-values opts]
  (ease-of-movement* *math-engine* high-values low-values volume-values opts))

(defn elders-force-index
  [close-values volume-values opts]
  (elders-force-index* *math-engine* close-values volume-values opts))

(defn money-flow-index
  [high-values low-values close-values volume-values opts]
  (money-flow-index* *math-engine* high-values low-values close-values volume-values opts))

(defn absolute-price-oscillator
  [close-values opts]
  (absolute-price-oscillator* *math-engine* close-values opts))

(defn stochastic
  [high-values low-values close-values opts]
  (stochastic* *math-engine* high-values low-values close-values opts))

(defn relative-strength-index
  [close-values opts]
  (relative-strength-index* *math-engine* close-values opts))

(defn trix
  [close-values opts]
  (trix* *math-engine* close-values opts))

(defn williams-r
  [high-values low-values close-values opts]
  (williams-r* *math-engine* high-values low-values close-values opts))

(defn commodity-channel-index
  [high-values low-values close-values opts]
  (commodity-channel-index* *math-engine* high-values low-values close-values opts))

(defn macd
  [close-values opts]
  (macd* *math-engine* close-values opts))

(defn mass-index
  [high-values low-values opts]
  (mass-index* *math-engine* high-values low-values opts))

(defn ichimoku-cloud
  [high-values low-values close-values opts]
  (ichimoku-cloud* *math-engine* high-values low-values close-values opts))

(defn parabolic-sar
  [high-values low-values close-values opts]
  (parabolic-sar* *math-engine* high-values low-values close-values opts))

(defn vortex
  [high-values low-values close-values opts]
  (vortex* *math-engine* high-values low-values close-values opts))

(defn vwap
  [close-values volume-values opts]
  (vwap* *math-engine* close-values volume-values opts))

(defn vwma
  [close-values volume-values opts]
  (vwma* *math-engine* close-values volume-values opts))

(defn dema
  [close-values opts]
  (dema* *math-engine* close-values opts))

(defn ema
  [close-values opts]
  (ema* *math-engine* close-values opts))

(defn tema
  [close-values opts]
  (tema* *math-engine* close-values opts))

(defn rma
  [close-values opts]
  (rma* *math-engine* close-values opts))

(defn moving-linear-regression
  [period x-values y-values]
  (moving-linear-regression* *math-engine* period x-values y-values))

(defn moving-least-square
  [period x-values y-values]
  (moving-least-square* *math-engine* period x-values y-values))

(defn keltner-channels
  [high-values low-values close-values opts]
  (keltner-channels* *math-engine* high-values low-values close-values opts))
