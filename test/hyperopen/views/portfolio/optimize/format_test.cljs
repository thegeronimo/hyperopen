(ns hyperopen.views.portfolio.optimize.format-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(deftest finite-number-detects-displayable-numbers-test
  (is (true? (opt-format/finite-number? 12.5)))
  (is (false? (opt-format/finite-number? nil)))
  (is (false? (opt-format/finite-number? "12.5")))
  (is (false? (opt-format/finite-number? js/NaN)))
  (is (false? (opt-format/finite-number? js/Infinity))))

(deftest formats-optimizer-display-values-test
  (is (= "12.35%" (opt-format/format-pct 0.12345)))
  (is (= "N/A" (opt-format/format-pct nil)))
  (is (= "+2.50 pts" (opt-format/format-pct-delta 0.025)))
  (is (= "-2.50 pts" (opt-format/format-pct-delta -0.025)))
  (is (= "1.235" (opt-format/format-decimal 1.23456)))
  (is (= "2.2" (opt-format/format-effective-n 2.234 4)))
  (is (= "2" (opt-format/format-effective-n 3.5 2)))
  (is (= "$1,234.57" (opt-format/format-usdc 1234.567)))
  (is (= "$1,235" (opt-format/format-usdc 1234.567 {:maximum-fraction-digits 0})))
  (is (= "N/A" (opt-format/format-usdc js/NaN))))

(deftest formats-optimizer-labels-test
  (is (= "partially-blocked" (opt-format/keyword-label :partially-blocked)))
  (is (= "N/A" (opt-format/keyword-label nil)))
  (is (= "Maximum Sharpe" (opt-format/display-label :max-sharpe)))
  (is (= "Black-Litterman" (opt-format/display-label :black-litterman)))
  (is (= "custom-kind" (opt-format/display-label :custom-kind)))
  (is (= "N/A" (opt-format/format-time nil))))
