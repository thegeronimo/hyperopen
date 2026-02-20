(ns hyperopen.views.trading-chart.utils.chart-interop.legend-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.legend :as legend]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]))

(deftest create-legend-supports-business-day-crosshair-time-with-injected-document-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        crosshair-handler* (atom nil)
        chart #js {:subscribeCrosshairMove (fn [handler]
                                             (reset! crosshair-handler* handler))
                   :unsubscribeCrosshairMove (fn [_] nil)}
        legend-meta {:symbol "BTC"
                     :timeframe-label "1D"
                     :venue "TestVenue"
                     :candle-data [{:time {:year 2026 :month 2 :day 15}
                                    :open 99 :high 101 :low 98 :close 100}
                                   {:time {:year 2026 :month 2 :day 16}
                                    :open 100 :high 103 :low 99 :close 102}]}
        legend-control (chart-interop/create-legend!
                        container
                        chart
                        legend-meta
                        {:document document
                         :format-price (fn [price] (str "P" price))
                         :format-delta (fn [delta] (str "D" delta))
                         :format-pct (fn [pct] (str "Q" (.toFixed pct 1)))})]
    (is (fn? @crosshair-handler*))
    (@crosshair-handler* #js {:time #js {:year 2026 :month 2 :day 16}})
    (let [text (str/join " " (fake-dom/collect-text-content (aget (.-children container) 0)))]
      (is (str/includes? text "BTC · 1D · TestVenue"))
      (is (str/includes? text "P100"))
      (is (str/includes? text "P103"))
      (is (str/includes? text "P99"))
      (is (str/includes? text "P102"))
      (is (str/includes? text "D2"))
      (is (str/includes? text "Q2.0")))
    (.destroy ^js legend-control)))

(deftest legend-create-throws-when-document-is-missing-test
  (let [original-document (aget js/globalThis "document")
        container (fake-dom/make-fake-element "div")
        chart #js {:subscribeCrosshairMove (fn [_] nil)
                   :unsubscribeCrosshairMove (fn [_] nil)}]
    (aset js/globalThis "document" nil)
    (try
      (let [error (try
                    (legend/create-legend! container chart {})
                    nil
                    (catch :default e e))]
        (is (some? error))
        (is (str/includes? (.-message error) "Legend rendering requires a DOM document.")))
      (finally
        (aset js/globalThis "document" original-document)))))

(deftest legend-supports-time-lookups-update-and-destroy-cleanup-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        _ (set! (.-position (.-style container)) "static")
        crosshair-handler* (atom nil)
        unsubscribed-handler* (atom nil)
        chart #js {:subscribeCrosshairMove (fn [handler]
                                             (reset! crosshair-handler* handler))
                   :unsubscribeCrosshairMove (fn [handler]
                                               (reset! unsubscribed-handler* handler))}
        legend-control (legend/create-legend!
                        container
                        chart
                        {:symbol "ETH"
                         :timeframe-label "4H"
                         :venue "Demo"
                         :candle-data [{:time "2026-02-15"
                                        :open 100 :high 105 :low 99 :close 100}
                                       {:time 1700000100
                                        :open 100 :high 102 :low 90 :close 95}]}
                        {:document document
                         :format-price (fn [price]
                                         (when (number? price)
                                           (str "$" price)))
                         :format-delta (fn [delta]
                                         (when (number? delta)
                                           (str "d" delta)))
                         :format-pct (fn [pct]
                                       (when (number? pct)
                                         (str "p" (.toFixed pct 1))))})]
    (is (fn? @crosshair-handler*))
    (is (= "relative" (.-position (.-style container))))
    (@crosshair-handler* #js {:time 1700000100})
    (let [legend-node (aget (.-children container) 0)
          values-row (aget (.-children legend-node) 1)
          delta-node (aget (.-children values-row) (dec (.-length (.-children values-row))))
          text (str/join " " (fake-dom/collect-text-content legend-node))]
      (is (str/includes? text "ETH · 4H · Demo"))
      (is (str/includes? text "$95"))
      (is (str/includes? text "d-5"))
      (is (str/includes? text "p-5.0"))
      (is (= "#ef4444" (.-color (.-style delta-node)))))
    (@crosshair-handler* #js {:time :no-match})
    (let [text (str/join " " (fake-dom/collect-text-content (aget (.-children container) 0)))]
      (is (str/includes? text "$95")))
    (.update ^js legend-control {:symbol "SOL"
                                 :timeframe-label "1H"
                                 :venue "Alt"})
    (let [text (str/join " " (fake-dom/collect-text-content (aget (.-children container) 0)))]
      (is (str/includes? text "SOL · 1H · Alt"))
      (is (str/includes? text "-- (--)")))
    (.destroy ^js legend-control)
    (is (identical? @crosshair-handler* @unsubscribed-handler*))
    (is (zero? (alength (.-children container))))))

