(ns hyperopen.domain.trading.indicators.catalog.flow)

(def flow-indicator-definitions
  [{:id :accumulation-distribution
    :name "Accumulation/Distribution"
    :short-name "A/D"
    :description "Cumulative money flow volume line"
    :supports-period? false
    :default-config {}
}
   {:id :accumulative-swing-index
    :name "Accumulative Swing Index"
    :short-name "ASI"
    :description "Wilder swing index accumulated over time"
    :supports-period? false
    :default-config {}
}
   {:id :volume
    :name "Volume"
    :short-name "VOL"
    :description "Raw traded volume"
    :supports-period? false
    :default-config {}
}
   {:id :net-volume
    :name "Net Volume"
    :short-name "Net Vol"
    :description "Signed per-bar volume based on price direction"
    :supports-period? false
    :default-config {}
}
   {:id :on-balance-volume
    :name "On Balance Volume"
    :short-name "OBV"
    :description "Cumulative signed volume"
    :supports-period? false
    :default-config {}
}
   {:id :price-volume-trend
    :name "Price Volume Trend"
    :short-name "PVT"
    :description "Cumulative volume scaled by price change"
    :supports-period? false
    :default-config {}
}
   {:id :volume-oscillator
    :name "Volume Oscillator"
    :short-name "PVO"
    :description "Percentage volume oscillator"
    :supports-period? false
    :default-config {:fast 12
                     :slow 26
                     :signal 9}
}
   {:id :chaikin-money-flow
    :name "Chaikin Money Flow"
    :short-name "CMF"
    :description "Volume-weighted accumulation/distribution over a period"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20}
}
   {:id :chaikin-oscillator
    :name "Chaikin Oscillator"
    :short-name "CHO"
    :description "EMA difference of accumulation/distribution"
    :supports-period? false
    :default-config {:fast 3
                     :slow 10}
}
   {:id :ease-of-movement
    :name "Ease Of Movement"
    :short-name "EOM"
    :description "Volume-adjusted distance moved"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
}
   {:id :elders-force-index
    :name "Elder's Force Index"
    :short-name "EFI"
    :description "EMA of price change multiplied by volume"
    :supports-period? true
    :default-period 13
    :min-period 2
    :max-period 200
    :default-config {:period 13}
}
   {:id :money-flow-index
    :name "Money Flow Index"
    :short-name "MFI"
    :description "Volume-weighted RSI-style oscillator"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}
}])

