(ns hyperopen.chart.settings
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.utils.parse :as parse-utils]))

(def ^:private chart-timeframes
  #{:1m :3m :5m :15m :30m :1h :2h :4h :8h :12h :1d :3d :1w :1M})

(def ^:private chart-types
  #{:area
    :bar
    :baseline
    :candlestick
    :columns
    :heikin-ashi
    :high-low
    :hlc-area
    :hollow-candles
    :line
    :line-with-markers
    :step-line})

(def ^:private chart-type-aliases
  {:histogram :columns})

(def ^:private chart-volume-visible-storage-key
  "chart-volume-visible")

(defn- load-chart-option
  [ls-key default valid-set]
  (let [v (keyword (or (platform/local-storage-get ls-key) (name default)))]
    (if (contains? valid-set v) v default)))

(defn- normalize-chart-type
  [chart-type]
  (get chart-type-aliases chart-type chart-type))

(defn- load-chart-type
  []
  (normalize-chart-type (load-chart-option "chart-type" :candlestick (into chart-types (keys chart-type-aliases)))))

(defn- serialize-indicators
  [indicators]
  (into {}
        (map (fn [[k v]] [(name k) v]))
        (or indicators {})))

(defn- load-indicators
  []
  (try
    (let [raw (platform/local-storage-get "chart-active-indicators")]
      (if (seq raw)
        (let [parsed (js->clj (js/JSON.parse raw) :keywordize-keys true)]
          (if (map? parsed) parsed {}))
        {}))
    (catch :default _
      {})))

(defn- parse-bool
  [value default]
  (cond
    (boolean? value) value
    (nil? value) default
    :else (let [text (some-> value str str/trim str/lower-case)]
            (cond
              (= text "true") true
              (= text "false") false
              :else default))))

(defn- load-volume-visible?
  []
  (parse-bool (platform/local-storage-get chart-volume-visible-storage-key) true))

(defn- save-volume-visible-effects
  [visible?]
  [[:effects/save [:chart-options :volume-visible?] visible?]
   [:effects/local-storage-set chart-volume-visible-storage-key (if visible? "true" "false")]])

(defn restore-chart-options!
  [store]
  (let [timeframe (load-chart-option "chart-timeframe" :1d chart-timeframes)
        chart-type (load-chart-type)
        indicators (load-indicators)
        volume-visible? (load-volume-visible?)]
    (swap! store update-in [:chart-options] merge
           {:selected-timeframe timeframe
            :selected-chart-type chart-type
            :volume-visible? volume-visible?
            :active-indicators indicators})))

(defn add-indicator
  [state indicator-type params]
  (let [current-indicators (get-in state [:chart-options :active-indicators] {})
        new-indicators (assoc current-indicators indicator-type params)]
    [[:effects/save [:chart-options :active-indicators] new-indicators]
     [:effects/local-storage-set-json "chart-active-indicators" (serialize-indicators new-indicators)]
     [:effects/load-trading-indicators-module]]))

(defn remove-indicator
  [state indicator-type]
  (let [current-indicators (get-in state [:chart-options :active-indicators] {})
        new-indicators (dissoc current-indicators indicator-type)]
    [[:effects/save [:chart-options :active-indicators] new-indicators]
     [:effects/local-storage-set-json "chart-active-indicators" (serialize-indicators new-indicators)]]))

(defn update-indicator-period
  [state indicator-type period-value]
  (let [current-indicators (get-in state [:chart-options :active-indicators] {})
        parsed-period (parse-utils/parse-localized-int-value period-value
                                                             (get-in state [:ui :locale]))
        current-period (get-in current-indicators [indicator-type :period])
        period (if (nil? parsed-period)
                 current-period
                 parsed-period)
        updated-indicators (assoc-in current-indicators [indicator-type :period] period)]
    [[:effects/save [:chart-options :active-indicators] updated-indicators]
     [:effects/local-storage-set-json "chart-active-indicators" (serialize-indicators updated-indicators)]
     [:effects/load-trading-indicators-module]]))

(defn show-volume-indicator
  [_state]
  (save-volume-visible-effects true))

(defn hide-volume-indicator
  [_state]
  (save-volume-visible-effects false))
