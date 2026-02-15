(ns hyperopen.domain.trading.indicators.catalog.trend)

(def trend-indicator-definitions
  [{:id :alma
    :name "Arnaud Legoux Moving Average"
    :short-name "ALMA"
    :description "Gaussian-weighted moving average"
    :supports-period? true
    :default-period 9
    :min-period 2
    :max-period 200
    :default-config {:period 9
                     :offset 0.85
                     :sigma 6}}
   {:id :aroon
    :name "Aroon"
    :short-name "Aroon"
    :description "Aroon Up and Aroon Down lines"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}
   {:id :adx
    :name "Average Directional Index"
    :short-name "ADX"
    :description "Trend strength from directional movement"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14
                     :smoothing 14}}
   {:id :sma
    :name "Moving Average"
    :short-name "MA"
    :description "Simple moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20}}
   {:id :double-ema
    :name "Double EMA"
    :short-name "DEMA"
    :description "Double exponential moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
}
   {:id :hull-moving-average
    :name "Hull Moving Average"
    :short-name "HMA"
    :description "Weighted moving average with reduced lag"
    :supports-period? true
    :default-period 21
    :min-period 2
    :max-period 400
    :default-config {:period 21}
}
   {:id :moving-average-double
    :name "Moving Average Double"
    :short-name "MA Double"
    :description "Alias of DEMA"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
}
   {:id :moving-average-exponential
    :name "Moving Average Exponential"
    :short-name "EMA"
    :description "Exponential moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
}
   {:id :moving-average-triple
    :name "Moving Average Triple"
    :short-name "MA Triple"
    :description "Alias of TEMA"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
}
   {:id :moving-average-weighted
    :name "Moving Average Weighted"
    :short-name "WMA"
    :description "Linearly weighted moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
}
   {:id :smoothed-moving-average
    :name "Smoothed Moving Average"
    :short-name "SMMA"
    :description "Rolling moving average (RMA)"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 400
    :default-config {:period 14}
}
   {:id :triple-ema
    :name "Triple EMA"
    :short-name "TEMA"
    :description "Triple exponential moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
}
   {:id :ema-cross
    :name "EMA Cross"
    :short-name "EMA X"
    :description "Fast and slow EMA crossover lines"
    :supports-period? false
    :default-config {:fast 12
                     :slow 26}
}
   {:id :ma-cross
    :name "MA Cross"
    :short-name "MA X"
    :description "Fast and slow simple moving average crossover"
    :supports-period? false
    :default-config {:fast 9
                     :slow 21}
}
   {:id :ma-with-ema-cross
    :name "MA with EMA Cross"
    :short-name "MA/EMA X"
    :description "Simple moving average crossed with exponential moving average"
    :supports-period? false
    :default-config {:ma-period 20
                     :ema-period 50}
}
   {:id :least-squares-moving-average
    :name "Least Squares Moving Average"
    :short-name "LSMA"
    :description "Moving linear-regression line"
    :supports-period? true
    :default-period 25
    :min-period 2
    :max-period 400
    :default-config {:period 25}
}
   {:id :linear-regression-curve
    :name "Linear Regression Curve"
    :short-name "LRC"
    :description "Moving least-squares regression curve"
    :supports-period? true
    :default-period 25
    :min-period 2
    :max-period 400
    :default-config {:period 25}
}
   {:id :linear-regression-slope
    :name "Linear Regression Slope"
    :short-name "LRS"
    :description "Slope of moving linear regression"
    :supports-period? true
    :default-period 25
    :min-period 2
    :max-period 400
    :default-config {:period 25}
}
   {:id :directional-movement
    :name "Directional Movement"
    :short-name "DMI"
    :description "+DI and -DI directional strength lines"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
}
   {:id :envelopes
    :name "Envelopes"
    :short-name "ENV"
    :description "SMA with percentage envelope bands"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :percent 0.025}
}
   {:id :ichimoku-cloud
    :name "Ichimoku Cloud"
    :short-name "ICHI"
    :description "Tenkan, Kijun, Senkou spans, and lagging span"
    :supports-period? false
    :default-config {:short 9
                     :medium 26
                     :long 52
                     :close 26}
}
   {:id :moving-average-multiple
    :name "Moving Average Multiple"
    :short-name "MA Multi"
    :description "Multiple moving averages (5, 10, 20, 50)"
    :supports-period? false
    :default-config {:periods [5 10 20 50]}
}
   {:id :parabolic-sar
    :name "Parabolic SAR"
    :short-name "PSAR"
    :description "Trend-following stop and reverse points"
    :supports-period? false
    :default-config {:step 0.02
                     :max 0.2}
}
   {:id :supertrend
    :name "SuperTrend"
    :short-name "SuperTrend"
    :description "ATR-based trend-following overlay"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 200
    :default-config {:period 10
                     :multiplier 3}
}
   {:id :vortex-indicator
    :name "Vortex Indicator"
    :short-name "VI"
    :description "+VI and -VI trend oscillators"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
}
   {:id :vwap
    :name "VWAP"
    :short-name "VWAP"
    :description "Volume weighted average price"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
}
   {:id :vwma
    :name "VWMA"
    :short-name "VWMA"
    :description "Volume weighted moving average"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
}
   {:id :guppy-multiple-moving-average
    :name "Guppy Multiple Moving Average"
    :short-name "GMMA"
    :description "Short and long EMA ribbon"
    :supports-period? false
    :default-config {}
}
   {:id :mcginley-dynamic
    :name "McGinley Dynamic"
    :short-name "MGD"
    :description "Adaptive moving average with speed correction"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 400
    :default-config {:period 14}
}
   {:id :moving-average-adaptive
    :name "Moving Average Adaptive"
    :short-name "KAMA"
    :description "Kaufman Adaptive Moving Average"
    :supports-period? true
    :default-period 10
    :min-period 2
    :max-period 400
    :default-config {:period 10
                     :fast 2
                     :slow 30}
}
   {:id :moving-average-hamming
    :name "Moving Average Hamming"
    :short-name "HAMMA"
    :description "Moving average with Hamming window weights"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
}
   {:id :williams-alligator
    :name "Williams Alligator"
    :short-name "Alligator"
    :description "Three smoothed moving averages with offsets"
    :supports-period? false
    :default-config {:jaw-period 13
                     :jaw-shift 8
                     :teeth-period 8
                     :teeth-shift 5
                     :lips-period 5
                     :lips-shift 3}
}])

