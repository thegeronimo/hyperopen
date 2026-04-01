(ns hyperopen.chart.settings-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.chart.settings :as chart-settings]
            [hyperopen.platform :as platform]))

(defn- local-storage-get-stub
  [values]
  (fn [key]
    (get values key)))

(deftest restore-chart-options-migrates-legacy-histogram-key-test
  (let [store (atom {:chart-options {}})]
    (with-redefs [platform/local-storage-get (local-storage-get-stub {"chart-timeframe" "1h"
                                                                      "chart-type" "histogram"
                                                                      "chart-volume-visible" "false"
                                                                      "chart-active-indicators" "{}"})]
      (chart-settings/restore-chart-options! store))
    (is (= :1h (get-in @store [:chart-options :selected-timeframe])))
    (is (= :columns (get-in @store [:chart-options :selected-chart-type])))
    (is (= false (get-in @store [:chart-options :volume-visible?])))))

(deftest restore-chart-options-accepts-wave1-chart-type-keys-test
  (let [store (atom {:chart-options {}})]
    (with-redefs [platform/local-storage-get (local-storage-get-stub {"chart-timeframe" "1d"
                                                                      "chart-type" "step-line"
                                                                      "chart-active-indicators" "{}"})]
      (chart-settings/restore-chart-options! store))
    (is (= :step-line (get-in @store [:chart-options :selected-chart-type])))
    (is (= true (get-in @store [:chart-options :volume-visible?])))))

(deftest restore-chart-options-falls-back-for-unknown-chart-type-test
  (let [store (atom {:chart-options {}})]
    (with-redefs [platform/local-storage-get (local-storage-get-stub {"chart-timeframe" "1d"
                                                                      "chart-type" "not-a-real-type"
                                                                      "chart-active-indicators" "{}"})]
      (chart-settings/restore-chart-options! store))
    (is (= :candlestick (get-in @store [:chart-options :selected-chart-type])))))

(deftest show-volume-indicator-persists-visible-flag-test
  (is (= [[:effects/save [:chart-options :volume-visible?] true]
          [:effects/local-storage-set "chart-volume-visible" "true"]]
         (chart-settings/show-volume-indicator {}))))

(deftest hide-volume-indicator-persists-visible-flag-test
  (is (= [[:effects/save [:chart-options :volume-visible?] false]
          [:effects/local-storage-set "chart-volume-visible" "false"]]
         (chart-settings/hide-volume-indicator {}))))

(deftest add-indicator-persists-and-loads-indicator-runtime-test
  (is (= [[:effects/save [:chart-options :active-indicators] {:sma {:period 20}}]
          [:effects/local-storage-set-json "chart-active-indicators" {"sma" {:period 20}}]
          [:effects/load-trading-indicators-module]]
         (chart-settings/add-indicator {:chart-options {:active-indicators {}}}
                                       :sma
                                       {:period 20}))))

(deftest update-indicator-period-parses-localized-integer-inputs-test
  (let [state {:ui {:locale "fr-FR"}
               :chart-options {:active-indicators {:ema {:period 20}}}}
        decimal-effects (chart-settings/update-indicator-period state :ema "21,9")
        grouped-effects (chart-settings/update-indicator-period state :ema (str "1\u202F234"))
        invalid-effects (chart-settings/update-indicator-period state :ema "abc")]
    (is (= 21 (get-in (nth (first decimal-effects) 2) [:ema :period])))
    (is (= 1234 (get-in (nth (first grouped-effects) 2) [:ema :period])))
    (is (= 20 (get-in (nth (first invalid-effects) 2) [:ema :period])))
    (is (= :effects/load-trading-indicators-module (first (last decimal-effects))))))
