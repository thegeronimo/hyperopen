(ns hyperopen.chart.settings)

(def ^:private chart-timeframes
  #{:1m :3m :5m :15m :30m :1h :2h :4h :8h :12h :1d :3d :1w :1M})

(def ^:private chart-types
  #{:area :bar :baseline :candlestick :histogram :line})

(defn- load-chart-option
  [ls-key default valid-set]
  (let [v (keyword (or (js/localStorage.getItem ls-key) (name default)))]
    (if (contains? valid-set v) v default)))

(defn- serialize-indicators
  [indicators]
  (into {}
        (map (fn [[k v]] [(name k) v]))
        (or indicators {})))

(defn- load-indicators
  []
  (try
    (let [raw (js/localStorage.getItem "chart-active-indicators")]
      (if (seq raw)
        (let [parsed (js->clj (js/JSON.parse raw) :keywordize-keys true)]
          (if (map? parsed) parsed {}))
        {}))
    (catch :default _
      {})))

(defn restore-chart-options!
  [store]
  (let [timeframe (load-chart-option "chart-timeframe" :1d chart-timeframes)
        chart-type (load-chart-option "chart-type" :candlestick chart-types)
        indicators (load-indicators)]
    (swap! store update-in [:chart-options] merge
           {:selected-timeframe timeframe
            :selected-chart-type chart-type
            :active-indicators indicators})))

(defn add-indicator
  [state indicator-type params]
  (let [current-indicators (get-in state [:chart-options :active-indicators] {})
        new-indicators (assoc current-indicators indicator-type params)]
    [[:effects/save [:chart-options :active-indicators] new-indicators]
     [:effects/local-storage-set-json "chart-active-indicators" (serialize-indicators new-indicators)]]))

(defn remove-indicator
  [state indicator-type]
  (let [current-indicators (get-in state [:chart-options :active-indicators] {})
        new-indicators (dissoc current-indicators indicator-type)]
    [[:effects/save [:chart-options :active-indicators] new-indicators]
     [:effects/local-storage-set-json "chart-active-indicators" (serialize-indicators new-indicators)]]))

(defn update-indicator-period
  [state indicator-type period-value]
  (let [current-indicators (get-in state [:chart-options :active-indicators] {})
        period (js/parseInt period-value)
        updated-indicators (assoc-in current-indicators [indicator-type :period] period)]
    [[:effects/save [:chart-options :active-indicators] updated-indicators]
     [:effects/local-storage-set-json "chart-active-indicators" (serialize-indicators updated-indicators)]]))
