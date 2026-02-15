(ns hyperopen.domain.trading.indicators.catalog.volatility)

(def volatility-indicator-definitions
  [{:id :week-52-high-low
    :name "52 Week High/Low"
    :short-name "52W H/L"
    :description "Rolling 52-week high and low levels"
    :supports-period? true
    :default-period 52
    :min-period 1
    :max-period 260
    :default-config {:period 52}}
   {:id :atr
    :name "Average True Range"
    :short-name "ATR"
    :description "Wilder average true range"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}
   {:id :bollinger-bands
    :name "Bollinger Bands"
    :short-name "BOLL"
    :description "Upper, basis, and lower volatility bands"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20
                     :multiplier 2}}
   {:id :bollinger-bands-percent-b
    :name "Bollinger Bands %B"
    :short-name "%B"
    :description "Position of price within Bollinger Bands"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20
                     :multiplier 2}
}
   {:id :bollinger-bands-width
    :name "Bollinger Bands Width"
    :short-name "BBW"
    :description "Normalized width of Bollinger Bands"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20
                     :multiplier 2}
}
   {:id :donchian-channels
    :name "Donchian Channels"
    :short-name "DONCH"
    :description "Highest-high and lowest-low rolling channels"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
}
   {:id :price-channel
    :name "Price Channel"
    :short-name "PChannel"
    :description "Highest-high and lowest-low channel"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
}
   {:id :historical-volatility
    :name "Historical Volatility"
    :short-name "HV"
    :description "Annualized volatility from log close-to-close returns"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :annualization 365}
}
   {:id :keltner-channels
    :name "Keltner Channels"
    :short-name "KC"
    :description "EMA centerline with ATR-based bands"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20}
}
   {:id :moving-average-channel
    :name "Moving Average Channel"
    :short-name "MA Channel"
    :description "SMA center with standard-deviation channel"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :multiplier 1.5}
}
   {:id :standard-deviation
    :name "Standard Deviation"
    :short-name "StdDev"
    :description "Rolling standard deviation of close"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20}
}
   {:id :standard-error
    :name "Standard Error"
    :short-name "StdErr"
    :description "Standard error of rolling linear regression"
    :supports-period? true
    :default-period 20
    :min-period 3
    :max-period 400
    :default-config {:period 20}
}
   {:id :standard-error-bands
    :name "Standard Error Bands"
    :short-name "SE Bands"
    :description "Linear-regression centerline plus/minus standard error"
    :supports-period? true
    :default-period 20
    :min-period 3
    :max-period 400
    :default-config {:period 20
                     :multiplier 2}
}
   {:id :volatility-close-to-close
    :name "Volatility Close-to-Close"
    :short-name "Vol C-C"
    :description "Annualized volatility of log close returns"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :annualization 365}
}
   {:id :volatility-index
    :name "Volatility Index"
    :short-name "VolIdx"
    :description "ATR as percent of close"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 400
    :default-config {:period 14}
}
   {:id :volatility-ohlc
    :name "Volatility O-H-L-C"
    :short-name "Vol OHLC"
    :description "Annualized Garman-Klass volatility"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :annualization 365}
}
   {:id :volatility-zero-trend-close-to-close
    :name "Volatility Zero Trend Close-to-Close"
    :short-name "Vol ZT C-C"
    :description "Detrended annualized close-to-close volatility"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 400
    :default-config {:period 20
                     :annualization 365}
}])

