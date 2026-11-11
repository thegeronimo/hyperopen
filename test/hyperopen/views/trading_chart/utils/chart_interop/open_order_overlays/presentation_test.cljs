(ns hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays.presentation-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.outcomes.domain.price-unit :as price-unit]
            [hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays.presentation :as presentation]))

(def ^:private order
  {:coin "#9900010" :oid 4242 :side "B" :sz "100" :px "0.56" :type "limit"})

(defn- size-text [sz] (str sz))

(deftest outcome-order-badges-read-in-the-chart-unit-test
  (doseq [[unit expected] [[:percent "56.0%"] [:cents "56.0¢"]]]
    (let [opts {:unit unit :price-decimals 5}
          {:keys [label-text cancel-aria-label title-text]}
          (presentation/overlay-row-presentation order :standard #(price-unit/format-price % opts) size-text)]
      (is (= (str "Limit 100 @ " expected) label-text))
      (is (= (str "Cancel buy order at " expected) cancel-aria-label))
      (is (not (re-find #"\$" title-text)) "a unit price is not dollars"))))

(deftest dollar-order-badges-keep-the-dollar-sign-test
  (let [{:keys [label-text]} (presentation/overlay-row-presentation
                              (assoc order :coin "BTC" :px "66880")
                              :standard
                              (fn [px] (str px))
                              size-text)]
    (is (= "Limit 100 @ $66880" label-text))))
