(ns hyperopen.views.trade.order-form-summary-display-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form-summary-display :as summary-display]))

(deftest format-fees-supports-structured-and-legacy-shapes-test
  (let [structured (summary-display/format-fees {:effective {:taker 0.045
                                                            :maker 0.015}
                                                :baseline {:taker 0.05
                                                           :maker 0.02}})
        legacy (summary-display/format-fees {:taker 0.045
                                             :maker 0.015})
        missing (summary-display/format-fees nil)]
    (is (= {:effective "0.0450% / 0.0150%"
            :baseline "0.0500% / 0.0200%"}
           structured))
    (is (= {:effective "0.0450% / 0.0150%"
            :baseline nil}
           legacy))
    (is (= {:effective "N/A"
            :baseline nil}
           missing))))

(deftest summary-display-formats-fees-as-structured-display-model-test
  (let [display (summary-display/summary-display {:available-to-trade 0
                                                  :current-position {:coin "BTC" :abs-size 0}
                                                  :liquidation-price nil
                                                  :order-value nil
                                                  :margin-required nil
                                                  :slippage-est nil
                                                  :slippage-max 8
                                                  :fees {:effective {:taker 0.01
                                                                     :maker 0.005}
                                                         :baseline {:taker 0.02
                                                                    :maker 0.01}}}
                                                 4)]
    (is (= {:effective "0.0100% / 0.0050%"
            :baseline "0.0200% / 0.0100%"}
           (:fees display)))))
