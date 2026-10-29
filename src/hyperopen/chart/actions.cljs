(ns hyperopen.chart.actions
  (:require [clojure.string :as str]
            [hyperopen.account.chart-fill-history :as chart-fill-history]
            [hyperopen.account.context :as account-context]
            [hyperopen.websocket.migration-flags :as migration-flags]))

(def ^:private default-chart-backfill-bars
  330)

(defn- normalized-non-empty-string
  [value]
  (when (string? value)
    (let [value* (str/trim value)]
      (when (seq value*)
        value*))))

(defn- positive-int-or-default
  [value fallback]
  (let [parsed (cond
                 (integer? value) value
                 (number? value) value
                 (string? value) (js/parseInt value 10)
                 :else js/NaN)]
    (if (and (number? parsed)
             (js/isFinite parsed)
             (pos? parsed))
      (js/Math.floor parsed)
      fallback)))

(defn- positive-int-value
  [value]
  (let [parsed (cond
                 (integer? value) value
                 (number? value) value
                 (string? value) (js/Number value)
                 :else js/NaN)]
    (when (and (number? parsed)
               (js/isFinite parsed)
               (pos? parsed))
      (js/Math.floor parsed))))

(defn- chart-dropdown-visibility-path-values
  [open-dropdown]
  [[[:chart-options :timeframes-dropdown-visible] (= open-dropdown :timeframes)]
   [[:chart-options :chart-type-dropdown-visible] (= open-dropdown :chart-type)]
   [[:chart-options :indicators-dropdown-visible] (= open-dropdown :indicators)]])

(defn- chart-dropdown-projection-effect
  ([open-dropdown]
   (chart-dropdown-projection-effect open-dropdown []))
  ([open-dropdown extra-path-values]
   [:effects/save-many (into (vec extra-path-values)
                             (chart-dropdown-visibility-path-values open-dropdown))]))

(defn toggle-timeframes-dropdown
  [state]
  (let [current-visible (boolean (get-in state [:chart-options :timeframes-dropdown-visible]))
        open-dropdown (when-not current-visible :timeframes)]
    [(chart-dropdown-projection-effect open-dropdown)]))

(defn select-chart-timeframe
  [state timeframe]
  (cond-> [(chart-dropdown-projection-effect nil [[[:chart-options :selected-timeframe] timeframe]])
           [:effects/local-storage-set "chart-timeframe" (name timeframe)]
           [:effects/sync-active-candle-subscription :interval timeframe]]
    (migration-flags/should-fetch-candle-snapshot? state
                                                   (:active-asset state)
                                                   timeframe)
    (conj [:effects/fetch-candle-snapshot :interval timeframe])))

(defn request-older-chart-fills
  "Fetch the window of the account's fills just older than the history the
  chart has covered. No-op unless the history belongs to the effective
  account, nothing is in flight, older fills may exist and `:end-ms` is older
  than the covered history."
  [state payload]
  (let [address (account-context/effective-account-address state)
        history (chart-fill-history/history-for state address)
        covered-from (:covered-from-ms history)
        end-ms (positive-int-value (:end-ms payload))]
    (if (and history
             end-ms
             (number? covered-from)
             (< end-ms covered-from)
             (not (:exhausted? history))
             (nil? (:loading-end-ms history)))
      [[:effects/fetch-older-chart-fills {:address (:address history) :end-ms end-ms}]]
      [])))

(defn request-chart-candle-backfill
  [state payload]
  (let [active-asset (normalized-non-empty-string (:active-asset state))
        requested-coin (normalized-non-empty-string (:coin payload))
        interval (or (:interval payload)
                     (get-in state [:chart-options :selected-timeframe])
                     :1d)
        bars (positive-int-or-default (:bars payload)
                                      default-chart-backfill-bars)
        end-time-ms (positive-int-value (:end-time-ms payload))]
    (if (and active-asset
             requested-coin
             (= active-asset requested-coin)
             (keyword? interval)
             end-time-ms)
      [[:effects/fetch-candle-snapshot
        :coin requested-coin
        :interval interval
        :bars bars
        :end-time-ms end-time-ms]]
      [])))

(defn toggle-chart-type-dropdown
  [state]
  (let [current-visible (boolean (get-in state [:chart-options :chart-type-dropdown-visible]))
        open-dropdown (when-not current-visible :chart-type)]
    [(chart-dropdown-projection-effect open-dropdown)]))

(defn select-chart-type
  [state chart-type]
  [(chart-dropdown-projection-effect nil [[[:chart-options :selected-chart-type] chart-type]])
   [:effects/local-storage-set "chart-type" (name chart-type)]])

(defn toggle-indicators-dropdown
  [state]
  (let [current-visible (boolean (get-in state [:chart-options :indicators-dropdown-visible]))
        open-dropdown (when-not current-visible :indicators)]
    (cond-> [(chart-dropdown-projection-effect open-dropdown
                                               [[[:chart-options :indicators-search-term] ""]])]
      open-dropdown
      (conj [:effects/load-trading-indicators-module]))))

(defn update-indicators-search
  [_state value]
  [[:effects/save
    [:chart-options :indicators-search-term]
    (if (string? value)
      value
      (str (or value "")))]])
