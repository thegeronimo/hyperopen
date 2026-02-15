(ns hyperopen.views.trading-chart.utils.indicator-view-adapter
  (:require [hyperopen.domain.trading.indicators.math :as imath]))

(def ^:private line-series-meta
  {[:week-52-high-low :high] {:name "52W High" :color "#10b981"}
   [:week-52-high-low :low] {:name "52W Low" :color "#ef4444"}
   [:atr :atr] {:name "ATR" :color "#14b8a6"}
   [:bollinger-bands :upper] {:name "BB Upper" :color "#22c55e"}
   [:bollinger-bands :basis] {:name "BB Basis" :color "#f59e0b"}
   [:bollinger-bands :lower] {:name "BB Lower" :color "#ef4444"}
   [:bollinger-bands-percent-b :percent-b] {:name "%B" :color "#38bdf8"}
   [:bollinger-bands-width :bbw] {:name "BBW" :color "#0ea5e9"}
   [:donchian-channels :upper] {:name "Donchian Upper" :color "#22c55e"}
   [:donchian-channels :middle] {:name "Donchian Mid" :color "#f59e0b"}
   [:donchian-channels :lower] {:name "Donchian Lower" :color "#ef4444"}
   [:price-channel :upper] {:name "Price Ch Upper" :color "#22c55e"}
   [:price-channel :middle] {:name "Price Ch Mid" :color "#f59e0b"}
   [:price-channel :lower] {:name "Price Ch Lower" :color "#ef4444"}
   [:historical-volatility :hv] {:name "HV" :color "#a855f7"}
   [:keltner-channels :upper] {:name "KC Upper" :color "#22c55e"}
   [:keltner-channels :middle] {:name "KC Middle" :color "#f59e0b"}
   [:keltner-channels :lower] {:name "KC Lower" :color "#ef4444"}
   [:moving-average-channel :upper] {:name "MAC Upper" :color "#22c55e"}
   [:moving-average-channel :basis] {:name "MAC Mid" :color "#f59e0b"}
   [:moving-average-channel :lower] {:name "MAC Lower" :color "#ef4444"}
   [:sma :sma] {:name "MA" :color "#38bdf8"}
   [:alma :alma] {:name "ALMA" :color "#f59e0b"}
   [:aroon :aroon-up] {:name "Aroon Up" :color "#22c55e"}
   [:aroon :aroon-down] {:name "Aroon Down" :color "#ef4444"}
   [:adx :adx] {:name "ADX" :color "#a855f7"}
   [:balance-of-power :bop] {:name "BOP" :color "#22c55e"}
   [:advance-decline :ad-bars] {:name "A/D" :color "#06b6d4"}
   [:rate-of-change :roc] {:name "ROC" :color "#22d3ee"}
   [:relative-strength-index :rsi] {:name "RSI" :color "#f97316"}
   [:correlation-coefficient :correlation] {:name "Correlation" :color "#22d3ee"}
   [:true-strength-index :tsi] {:name "TSI" :color "#22d3ee"}
   [:trend-strength-index :trend-si] {:name "Trend SI" :color "#22d3ee"}
   [:trend-strength-index :signal] {:name "Signal" :color "#f97316"}
   [:choppiness-index :chop] {:name "CHOP" :color "#f97316"}
   [:commodity-channel-index :cci] {:name "CCI" :color "#f59e0b"}
   [:macd :macd] {:name "MACD" :color "#38bdf8"}
   [:macd :signal] {:name "Signal" :color "#f59e0b"}
   [:mass-index :mi] {:name "Mass Index" :color "#f59e0b"}
   [:standard-deviation :stddev] {:name "StdDev" :color "#a855f7"}
   [:standard-error :stderr] {:name "StdErr" :color "#22d3ee"}
   [:standard-error-bands :upper] {:name "SE Upper" :color "#22c55e"}
   [:standard-error-bands :center] {:name "SE Mid" :color "#f59e0b"}
   [:standard-error-bands :lower] {:name "SE Lower" :color "#ef4444"}
   [:volatility-close-to-close :vol-cc] {:name "Vol C-C" :color "#22d3ee"}
   [:volatility-index :vol-index] {:name "Vol Index" :color "#f97316"}
   [:volatility-ohlc :vol-ohlc] {:name "Vol OHLC" :color "#22d3ee"}
   [:volatility-zero-trend-close-to-close :vol-zt-cc] {:name "Vol ZT C-C" :color "#a855f7"}
   [:coppock-curve :coppock] {:name "Coppock" :color "#38bdf8"}
   [:fisher-transform :fisher] {:name "Fisher" :color "#f97316"}
   [:fisher-transform :signal] {:name "Signal" :color "#22d3ee"}
   [:majority-rule :majority] {:name "Majority %" :color "#4ade80"}
   [:ratio :ratio] {:name "Ratio" :color "#22d3ee"}
   [:spread :spread] {:name "Spread" :color "#f97316"}
   [:chande-momentum-oscillator :cmo] {:name "CMO" :color "#eab308"}
   [:detrended-price-oscillator :dpo] {:name "DPO" :color "#3b82f6"}
   [:price-oscillator :apo] {:name "APO" :color "#eab308"}
   [:chaikin-money-flow :cmf] {:name "CMF" :color "#22d3ee"}
   [:chaikin-oscillator :chaikin-osc] {:name "Chaikin Osc" :color "#f97316"}
   [:chaikin-oscillator :ad-line] {:name "A/D Line" :color "#6b7280"}
   [:directional-movement :plus-di] {:name "+DI" :color "#22c55e"}
   [:directional-movement :minus-di] {:name "-DI" :color "#ef4444"}
   [:ease-of-movement :eom] {:name "EOM" :color "#06b6d4"}
   [:elders-force-index :efi] {:name "EFI" :color "#e879f9"}
   [:envelopes :upper] {:name "Env Upper" :color "#22c55e"}
   [:envelopes :basis] {:name "Env Basis" :color "#f59e0b"}
   [:envelopes :lower] {:name "Env Lower" :color "#ef4444"}
   [:ichimoku-cloud :tenkan] {:name "Tenkan" :color "#22c55e"}
   [:ichimoku-cloud :kijun] {:name "Kijun" :color "#ef4444"}
   [:ichimoku-cloud :ssa] {:name "Senkou A" :color "#38bdf8"}
   [:ichimoku-cloud :ssb] {:name "Senkou B" :color "#f59e0b"}
   [:ichimoku-cloud :lagging] {:name "Lagging" :color "#a855f7"}
   [:money-flow-index :mfi] {:name "MFI" :color "#14b8a6"}
   [:moving-average-multiple :ma-5] {:name "MA 5" :color "#22c55e"}
   [:moving-average-multiple :ma-10] {:name "MA 10" :color "#38bdf8"}
   [:moving-average-multiple :ma-20] {:name "MA 20" :color "#f59e0b"}
   [:moving-average-multiple :ma-50] {:name "MA 50" :color "#ef4444"}
   [:parabolic-sar :psar] {:name "PSAR" :color "#f97316"}
   [:stochastic :k] {:name "%K" :color "#22c55e"}
   [:stochastic :d] {:name "%D" :color "#ef4444"}
   [:stochastic-rsi :k] {:name "StochRSI %K" :color "#22c55e"}
   [:stochastic-rsi :d] {:name "StochRSI %D" :color "#ef4444"}
   [:supertrend :up] {:name "SuperTrend Up" :color "#22c55e"}
   [:supertrend :down] {:name "SuperTrend Down" :color "#ef4444"}
   [:trix :trix] {:name "TRIX" :color "#f59e0b"}
   [:vortex-indicator :plus] {:name "+VI" :color "#22c55e"}
   [:vortex-indicator :minus] {:name "-VI" :color "#ef4444"}
   [:vwap :vwap] {:name "VWAP" :color "#22d3ee"}
   [:vwma :vwma] {:name "VWMA" :color "#38bdf8"}
   [:williams-r :williams-r] {:name "%R" :color "#e879f9"}
   [:relative-vigor-index :rvi] {:name "RVI" :color "#22d3ee"}
   [:relative-vigor-index :signal] {:name "Signal" :color "#f97316"}
   [:relative-volatility-index :rvi-vol] {:name "RVI" :color "#a855f7"}
   [:smi-ergodic :indicator] {:name "SMI Ergodic" :color "#22d3ee"}
   [:smi-ergodic :signal] {:name "Signal" :color "#f97316"}
   [:ultimate-oscillator :uo] {:name "Ultimate Osc" :color "#a78bfa"}
   [:accumulation-distribution :adl] {:name "A/D" :color "#22d3ee"}
   [:accumulative-swing-index :asi] {:name "ASI" :color "#f97316"}
   [:average-price :ohlc4] {:name "OHLC4" :color "#a3e635"}
   [:median-price :median] {:name "Median" :color "#a3e635"}
   [:typical-price :typical] {:name "Typical Price" :color "#a3e635"}
   [:momentum :momentum] {:name "Momentum" :color "#f97316"}
   [:double-ema :dema] {:name "DEMA" :color "#22d3ee"}
   [:hull-moving-average :hma] {:name "HMA" :color "#f97316"}
   [:moving-average-double :double] {:name "MA Double" :color "#22d3ee"}
   [:moving-average-exponential :ema] {:name "EMA" :color "#38bdf8"}
   [:moving-average-triple :triple] {:name "MA Triple" :color "#22d3ee"}
   [:moving-average-weighted :wma] {:name "WMA" :color "#38bdf8"}
   [:smoothed-moving-average :smma] {:name "SMMA" :color "#22d3ee"}
   [:triple-ema :tema] {:name "TEMA" :color "#22d3ee"}
   [:ema-cross :fast] {:name "EMA Fast" :color "#22c55e"}
   [:ema-cross :slow] {:name "EMA Slow" :color "#ef4444"}
   [:ma-cross :fast] {:name "MA Fast" :color "#22c55e"}
   [:ma-cross :slow] {:name "MA Slow" :color "#ef4444"}
   [:ma-with-ema-cross :ma] {:name "MA" :color "#22c55e"}
   [:ma-with-ema-cross :ema] {:name "EMA" :color "#ef4444"}
   [:least-squares-moving-average :lsma] {:name "LSMA" :color "#22d3ee"}
   [:linear-regression-curve :lrc] {:name "LRC" :color "#60a5fa"}
   [:linear-regression-slope :slope] {:name "LRS" :color "#c084fc"}
   [:on-balance-volume :obv] {:name "OBV" :color "#22c55e"}
   [:price-volume-trend :pvt] {:name "PVT" :color "#06b6d4"}
   [:volume-oscillator :pvo] {:name "PVO" :color "#38bdf8"}
   [:volume-oscillator :signal] {:name "Signal" :color "#f59e0b"}
   [:guppy-multiple-moving-average :ema-short-3] {:name "EMA 3" :color "#22c55e"}
   [:guppy-multiple-moving-average :ema-short-5] {:name "EMA 5" :color "#4ade80"}
   [:guppy-multiple-moving-average :ema-short-8] {:name "EMA 8" :color "#86efac"}
   [:guppy-multiple-moving-average :ema-short-10] {:name "EMA 10" :color "#16a34a"}
   [:guppy-multiple-moving-average :ema-short-12] {:name "EMA 12" :color "#15803d"}
   [:guppy-multiple-moving-average :ema-short-15] {:name "EMA 15" :color "#166534"}
   [:guppy-multiple-moving-average :ema-long-30] {:name "EMA 30" :color "#ef4444"}
   [:guppy-multiple-moving-average :ema-long-35] {:name "EMA 35" :color "#f87171"}
   [:guppy-multiple-moving-average :ema-long-40] {:name "EMA 40" :color "#fca5a5"}
   [:guppy-multiple-moving-average :ema-long-45] {:name "EMA 45" :color "#dc2626"}
   [:guppy-multiple-moving-average :ema-long-50] {:name "EMA 50" :color "#b91c1c"}
   [:guppy-multiple-moving-average :ema-long-60] {:name "EMA 60" :color "#991b1b"}
   [:mcginley-dynamic :mcginley] {:name "McGinley" :color "#f59e0b"}
   [:moving-average-adaptive :kama] {:name "KAMA" :color "#22d3ee"}
   [:moving-average-hamming :hamming-ma] {:name "Hamming MA" :color "#38bdf8"}
   [:williams-alligator :jaw] {:name "Jaw" :color "#3b82f6"}
   [:williams-alligator :teeth] {:name "Teeth" :color "#ef4444"}
   [:williams-alligator :lips] {:name "Lips" :color "#22c55e"}
   [:pivot-points-standard :pp] {:name "PP" :color "#e5e7eb"}
   [:pivot-points-standard :r1] {:name "R1" :color "#22c55e"}
   [:pivot-points-standard :s1] {:name "S1" :color "#ef4444"}
   [:pivot-points-standard :r2] {:name "R2" :color "#16a34a"}
   [:pivot-points-standard :s2] {:name "S2" :color "#dc2626"}
   [:pivot-points-standard :r3] {:name "R3" :color "#15803d"}
   [:pivot-points-standard :s3] {:name "S3" :color "#b91c1c"}
   [:rank-correlation-index :rci] {:name "RCI" :color "#a855f7"}
   [:zig-zag :zig-zag] {:name "Zig Zag" :color "#f97316"}
   [:chaikin-volatility :chv] {:name "CHV" :color "#22d3ee"}
   [:chande-kroll-stop :long-stop] {:name "CK Long" :color "#ef4444"}
   [:chande-kroll-stop :short-stop] {:name "CK Short" :color "#22c55e"}
   [:connors-rsi :connors-rsi] {:name "Connors RSI" :color "#f97316"}
   [:correlation-log :correlation-log] {:name "Corr Log" :color "#a78bfa"}
   [:klinger-oscillator :kvo] {:name "KVO" :color "#22d3ee"}
   [:klinger-oscillator :signal] {:name "Signal" :color "#f97316"}
   [:know-sure-thing :kst] {:name "KST" :color "#22d3ee"}
   [:know-sure-thing :signal] {:name "Signal" :color "#f97316"}})

(def ^:private histogram-series-meta
  {[:awesome-oscillator :ao] {:name "AO"
                              :positive-color "#10b981"
                              :negative-color "#ef4444"}
   [:accelerator-oscillator :ac] {:name "AC"
                                   :positive-color "#10b981"
                                   :negative-color "#ef4444"}
   [:smi-ergodic :osc] {:name "Osc"
                        :positive-color "#22c55e"
                        :negative-color "#ef4444"}
   [:chop-zone :chop-zone] {:name "Chop Zone"
                            :positive-color "#22c55e"
                            :negative-color "#ef4444"}
   [:klinger-oscillator :hist] {:name "KVO Hist"
                                 :positive-color "#22c55e"
                                 :negative-color "#ef4444"}
   [:macd :hist] {:name "MACD Hist"
                  :positive-color "#22c55e"
                  :negative-color "#ef4444"}
   [:net-volume :net-volume] {:name "Net Vol"
                              :positive-color "#22c55e"
                              :negative-color "#ef4444"}
   [:volume-oscillator :hist] {:name "PVO Hist"
                               :positive-color "#22c55e"
                               :negative-color "#ef4444"}
   [:volume :volume] {:name "Volume"
                      :positive-color "#22c55e"
                      :negative-color "#ef4444"}})

(def ^:private marker-meta
  {[:williams-fractal :fractal-high] {:position "aboveBar"
                                      :shape "arrowDown"
                                      :color "#22c55e"
                                      :text "▲"
                                      :size 0}
   [:williams-fractal :fractal-low] {:position "belowBar"
                                     :shape "arrowUp"
                                     :color "#ef4444"
                                     :text "▼"
                                     :size 0}})

(defn point
  [time value]
  (if (imath/finite-number? value)
    {:time time :value value}
    {:time time}))

(defn points-from-values
  [time-values indicator-values]
  (mapv point time-values indicator-values))

(defn- line-meta
  [indicator-type series-id]
  (merge {:name (name series-id)
          :color "#38bdf8"
          :line-width 2}
         (get line-series-meta [indicator-type series-id])))

(defn line-series
  [indicator-type series-id time-values indicator-values]
  (let [{:keys [name color line-width]} (line-meta indicator-type series-id)]
    {:id series-id
     :name name
     :series-type :line
     :color color
     :line-width line-width
     :data (points-from-values time-values indicator-values)}))

(defn- histogram-point
  [positive-color negative-color time value]
  (if (imath/finite-number? value)
    {:time time
     :value value
     :color (if (neg? value) negative-color positive-color)}
    {:time time}))

(defn histogram-series
  [indicator-type series-id time-values indicator-values]
  (let [{:keys [name positive-color negative-color]}
        (merge {:name (name series-id)
                :positive-color "#10b981"
                :negative-color "#ef4444"}
               (get histogram-series-meta [indicator-type series-id]))]
    {:id series-id
     :name name
     :series-type :histogram
     :data (mapv (fn [time value]
                   (histogram-point positive-color negative-color time value))
                 time-values
                 indicator-values)}))

(defn indicator-result
  ([indicator-type pane series]
   {:type indicator-type
    :pane pane
    :series series})
  ([indicator-type pane series markers]
   (cond-> {:type indicator-type
            :pane pane
            :series series}
     (seq markers) (assoc :markers markers))))

(defn- project-series
  [indicator-type time-values {:keys [id series-type values]}]
  (case series-type
    :histogram (histogram-series indicator-type id time-values values)
    :line (line-series indicator-type id time-values values)
    nil))

(defn- project-marker
  [indicator-type marker]
  (let [kind (:kind marker)]
    (if (keyword? kind)
      (if-let [style (get marker-meta [indicator-type kind])]
        (merge {:id (:id marker)
                :time (:time marker)}
               style)
        marker)
      marker)))

(defn project-domain-indicator
  [data {:keys [type pane series markers]}]
  (let [time-values (imath/times data)
        projected-series (->> series
                              (keep (fn [series-def]
                                      (project-series type time-values series-def)))
                              vec)
        projected-markers (when (seq markers)
                            (mapv #(project-marker type %) markers))]
    (indicator-result type pane projected-series projected-markers)))
