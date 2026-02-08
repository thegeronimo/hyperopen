(ns hyperopen.views.trading-chart.utils.chart-options-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.trading-chart.utils.chart-options :as chart-options]))

(deftest base-chart-options-defaults-test
  (let [options (chart-options/base-chart-options)]
    (testing "uses a default right-side gap in bars"
      (is (= chart-options/default-right-offset-bars
             (get-in options [:timeScale :rightOffset])))
      (is (= 4 (get-in options [:timeScale :rightOffset]))))
    (testing "uses hyperliquid background color for chart canvas"
      (is (= "rgb(15, 26, 31)"
             (get-in options [:layout :background :color]))))
    (testing "defaults to subtle-v1 grid and border palette"
      (is (= "rgba(139, 148, 158, 0.16)"
             (get-in options [:grid :vertLines :color])))
      (is (= "rgba(139, 148, 158, 0.16)"
             (get-in options [:grid :horzLines :color])))
      (is (= "rgba(139, 148, 158, 0.24)"
             (get-in options [:timeScale :borderColor])))
      (is (= "rgba(139, 148, 158, 0.24)"
             (get-in options [:rightPriceScale :borderColor])))
      (is (= "rgba(139, 148, 158, 0.22)"
             (get-in options [:layout :panes :separatorColor])))
      (is (= "rgba(139, 148, 158, 0.30)"
             (get-in options [:layout :panes :separatorHoverColor]))))))

(deftest base-chart-options-profile-overrides-test
  (let [legacy (chart-options/base-chart-options :legacy)
        subtle (chart-options/base-chart-options :subtle-v1)]
    (testing "legacy profile preserves old higher-contrast lines"
      (is (= "#374151" (get-in legacy [:grid :vertLines :color])))
      (is (= "#374151" (get-in legacy [:grid :horzLines :color])))
      (is (= "#374151" (get-in legacy [:timeScale :borderColor]))))
    (testing "subtle profile can be requested explicitly"
      (is (= "rgba(139, 148, 158, 0.16)"
             (get-in subtle [:grid :vertLines :color]))))))

(deftest normalize-chart-visual-profile-test
  (testing "accepts supported keyword and string profiles"
    (is (= :legacy (chart-options/normalize-chart-visual-profile :legacy)))
    (is (= :legacy (chart-options/normalize-chart-visual-profile "legacy")))
    (is (= :subtle-v1 (chart-options/normalize-chart-visual-profile :subtle-v1))))
  (testing "falls back to subtle-v1 for unsupported values"
    (is (= :subtle-v1 (chart-options/normalize-chart-visual-profile :unknown)))
    (is (= :subtle-v1 (chart-options/normalize-chart-visual-profile "nope")))
    (is (= :subtle-v1 (chart-options/normalize-chart-visual-profile nil)))))

(deftest fixed-height-chart-options-default-right-offset-test
  (let [options (chart-options/fixed-height-chart-options 400)]
    (testing "uses the same default right-side gap in fixed-height charts"
      (is (= chart-options/default-right-offset-bars
             (get-in options [:timeScale :rightOffset]))))
    (testing "retains fixed height configuration"
      (is (= 400 (:height options))))
    (testing "inherits subtle-v1 grid defaults"
      (is (= "rgba(139, 148, 158, 0.16)"
             (get-in options [:grid :vertLines :color]))))))
