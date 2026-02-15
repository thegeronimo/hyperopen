(ns hyperopen.domain.trading.indicators.catalog.oscillators)

(def oscillator-indicator-definitions
  [{:id :accelerator-oscillator
    :name "Accelerator Oscillator"
    :short-name "AC"
    :description "Awesome Oscillator minus its 5-period simple moving average"
    :supports-period? false
    :default-config {}}
   {:id :advance-decline
    :name "Advance/Decline"
    :short-name "A/D (Bars)"
    :description "Single-instrument proxy using cumulative up/down bar count"
    :supports-period? false
    :default-config {}}
   {:id :awesome-oscillator
    :name "Awesome Oscillator"
    :short-name "AO"
    :description "5-period SMA minus 34-period SMA of median price"
    :supports-period? false
    :default-config {}}
   {:id :balance-of-power
    :name "Balance of Power"
    :short-name "BOP"
    :description "(Close - Open) / (High - Low)"
    :supports-period? false
    :default-config {}}
   {:id :rate-of-change
    :name "Rate Of Change"
    :short-name "ROC"
    :description "Percent change over n periods"
    :supports-period? true
    :default-period 9
    :min-period 1
    :max-period 400
    :default-config {:period 9}}
   {:id :momentum
    :name "Momentum"
    :short-name "Momentum"
    :description "Price change over n periods"
    :supports-period? true
    :default-period 10
    :min-period 1
    :max-period 400
    :default-config {:period 10}
}
   {:id :chande-momentum-oscillator
    :name "Chande Momentum Oscillator"
    :short-name "CMO"
    :description "Momentum oscillator using rolling gains and losses"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
}
   {:id :detrended-price-oscillator
    :name "Detrended Price Oscillator"
    :short-name "DPO"
    :description "Price minus displaced moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20}
}
   {:id :price-oscillator
    :name "Price Oscillator"
    :short-name "APO"
    :description "Absolute difference between fast and slow EMA"
    :supports-period? false
    :default-config {:fast 12
                     :slow 26}
}
   {:id :stochastic
    :name "Stochastic"
    :short-name "Stoch"
    :description "%K and %D stochastic oscillator"
    :supports-period? false
    :default-config {:kPeriod 14
                     :dPeriod 3}
}
   {:id :stochastic-rsi
    :name "Stochastic RSI"
    :short-name "Stoch RSI"
    :description "Stochastic oscillator applied to RSI"
    :supports-period? false
    :default-config {:rsiPeriod 14
                     :stochPeriod 14
                     :kSmoothing 3
                     :dSmoothing 3}
}
   {:id :trix
    :name "TRIX"
    :short-name "TRIX"
    :description "Triple-smoothed EMA rate of change"
    :supports-period? true
    :default-period 15
    :min-period 2
    :max-period 400
    :default-config {:period 15}
}
   {:id :williams-r
    :name "Williams %R"
    :short-name "%R"
    :description "Overbought and oversold momentum oscillator"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
}
   {:id :choppiness-index
    :name "Choppiness Index"
    :short-name "CHOP"
    :description "Log-scaled range efficiency oscillator"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
}
   {:id :commodity-channel-index
    :name "Commodity Channel Index"
    :short-name "CCI"
    :description "Typical-price deviation from moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20}
}
   {:id :macd
    :name "MACD"
    :short-name "MACD"
    :description "MACD line, signal line, and histogram"
    :supports-period? false
    :default-config {:fast 12
                     :slow 26
                     :signal 9}
}
   {:id :mass-index
    :name "Mass Index"
    :short-name "MI"
    :description "Range expansion/contraction trend reversal indicator"
    :supports-period? false
    :default-config {:emaPeriod 9
                     :miPeriod 25}
}
   {:id :relative-strength-index
    :name "Relative Strength Index"
    :short-name "RSI"
    :description "Momentum oscillator of gains vs losses"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
}
   {:id :correlation-coefficient
    :name "Correlation Coefficient"
    :short-name "Corr"
    :description "Rolling Pearson correlation of price and time"
    :supports-period? true
    :default-period 20
    :min-period 3
    :max-period 400
    :default-config {:period 20}
}
   {:id :correlation-log
    :name "Correlation - Log"
    :short-name "Corr Log"
    :description "Rolling Pearson correlation of log price and time"
    :supports-period? true
    :default-period 20
    :min-period 3
    :max-period 400
    :default-config {:period 20}
}
   {:id :true-strength-index
    :name "True Strength Index"
    :short-name "TSI"
    :description "Double-smoothed momentum oscillator"
    :supports-period? false
    :default-config {:short 13
                     :long 25}
}
   {:id :trend-strength-index
    :name "Trend Strength Index"
    :short-name "TrendSI"
    :description "Absolute TSI with smoothing"
    :supports-period? false
    :default-config {:short 13
                     :long 25
                     :signal 13}
}
   {:id :smi-ergodic
    :name "SMI Ergodic Indicator/Oscillator"
    :short-name "SMI Ergodic"
    :description "TSI-derived indicator, signal, and oscillator"
    :supports-period? false
    :default-config {:short 13
                     :long 25
                     :signal 13}
}
   {:id :ultimate-oscillator
    :name "Ultimate Oscillator"
    :short-name "UO"
    :description "Weighted multi-timeframe buying pressure oscillator"
    :supports-period? false
    :default-config {:short 7
                     :medium 14
                     :long 28}
}
   {:id :connors-rsi
    :name "Connors RSI"
    :short-name "CRSI"
    :description "Average of short RSI, streak RSI, and percent-rank"
    :supports-period? false
    :default-config {:rsi-period 3
                     :streak-period 2
                     :rank-period 100}
}
   {:id :chop-zone
    :name "Chop Zone"
    :short-name "CZ"
    :description "Trend-angle zone derived from EMA slope"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
}
   {:id :klinger-oscillator
    :name "Klinger Oscillator"
    :short-name "KVO"
    :description "Volume-force EMA oscillator"
    :supports-period? false
    :default-config {:fast 34
                     :slow 55
                     :signal 13}
}
   {:id :know-sure-thing
    :name "Know Sure Thing"
    :short-name "KST"
    :description "Weighted sum of smoothed rate-of-change"
    :supports-period? false
    :default-config {:roc1 10
                     :roc2 15
                     :roc3 20
                     :roc4 30
                     :sma1 10
                     :sma2 10
                     :sma3 10
                     :sma4 15
                     :signal 9}
}
   {:id :relative-vigor-index
    :name "Relative Vigor Index"
    :short-name "RVI"
    :description "Smoothed ratio of close-open to high-low"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10}
}
   {:id :relative-volatility-index
    :name "Relative Volatility Index"
    :short-name "RVI Vol"
    :description "RSI-style oscillator over volatility"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
}
   {:id :spread
    :name "Spread"
    :short-name "Spread"
    :description "Single-stream proxy: close minus close n bars ago"
    :supports-period? true
    :default-period 1
    :min-period 1
    :max-period 400
    :default-config {:period 1}
}
   {:id :ratio
    :name "Ratio"
    :short-name "Ratio"
    :description "Single-stream proxy: close divided by close n bars ago"
    :supports-period? true
    :default-period 1
    :min-period 1
    :max-period 400
    :default-config {:period 1}
}
   {:id :majority-rule
    :name "Majority Rule"
    :short-name "Majority"
    :description "Percent of bars closing above SMA"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 400
    :default-config {:period 14}
}
   {:id :fisher-transform
    :name "Fisher Transform"
    :short-name "Fisher"
    :description "Fisher transform of normalized median price"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10}
}
   {:id :coppock-curve
    :name "Coppock Curve"
    :short-name "COPP"
    :description "WMA of summed long and short ROC"
    :supports-period? false
    :default-config {:long-roc 14
                     :short-roc 11
                     :wma-period 10}
}
   {:id :chaikin-volatility
    :name "Chaikin Volatility"
    :short-name "CHV"
    :description "Rate-of-change of EMA high-low range"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10
                     :roc-period 10}
}
   {:id :chande-kroll-stop
    :name "Chande Kroll Stop"
    :short-name "CKS"
    :description "ATR-based long and short stop lines"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10
                     :atr-period 10
                     :multiplier 1.0}
}
   ])

