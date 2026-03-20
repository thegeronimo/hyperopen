(ns hyperopen.views.active-asset.funding-tooltip-model-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.active-asset.funding-policy :as funding-policy]
            [hyperopen.state.trading :as trading-state]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.active-asset.row :as row]
            [hyperopen.views.active-asset.test-support :as support]))

(defn- gold-ctx-data
  ([] (gold-ctx-data {}))
  ([overrides]
   (support/active-asset-row-ctx
    (merge {:coin "xyz:GOLD"
            :mark 5000.0
            :markRaw nil
            :oracle 4998.0
            :oracleRaw nil
            :change24h 5.0
            :change24hPct 0.5
            :volume24h 2000000
            :openInterest 200
            :fundingRate 0.01}
           overrides))))

(defn- gold-market
  ([] (gold-market {}))
  ([overrides]
   (support/active-asset-market
    (merge {:coin "xyz:GOLD"
            :symbol "GOLD-USDC"
            :base "GOLD"}
           overrides))))

(defn- visible-gold-state
  ([] (visible-gold-state {}))
  ([overrides]
   (support/with-visible-funding-tooltip
    (-> (merge {:active-asset "xyz:GOLD"} overrides)
        (update :asset-selector #(merge {:missing-icons #{}} %)))
    "xyz:GOLD")))

(deftest active-asset-row-skips-funding-tooltip-derivation-when-closed-test
  (let [ctx-data (gold-ctx-data {})
        market (gold-market {})
        full-state {:active-asset "xyz:GOLD"
                    :asset-selector {:missing-icons #{}}}
        cache* @#'hyperopen.active-asset.funding-policy/funding-tooltip-model-cache]
    (reset! cache* nil)
    (with-redefs [fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state))
    (is (nil? @cache*))))

(deftest active-asset-row-funding-tooltip-memoizes-by-summary-signature-test
  (let [memoized-tooltip funding-policy/memoized-funding-tooltip-model
        cache* @#'hyperopen.active-asset.funding-policy/funding-tooltip-model-cache
        position {:coin "xyz:GOLD"
                  :szi "1"
                  :positionValue "5000"}
        market (gold-market {})
        summary-1 {:mean 0.1008
                   :stddev 0.0916108152
                   :daily-funding-series [{:day-index 1 :daily-rate 0.0288}
                                          {:day-index 2 :daily-rate -0.0096}]
                   :autocorrelation {:lag-1d {:value 0.714}
                                     :lag-5d {:value 0.482}
                                     :lag-15d {:value 0.21}}
                   :autocorrelation-series [{:lag-days 1 :value 0.714}
                                            {:lag-days 2 :value 0.55}]}
        summary-2 {:mean 0.1008
                   :stddev 0.0916108152
                   :daily-funding-series [{:day-index 1 :daily-rate 0.0288}
                                          {:day-index 2 :daily-rate -0.0096}]
                   :autocorrelation {:lag-1d {:value 0.714}
                                     :lag-5d {:value 0.482}
                                     :lag-15d {:value 0.21}}
                   :autocorrelation-series [{:lag-days 1 :value 0.714}
                                            {:lag-days 2 :value 0.55}]}
        summary-3 {:mean 0.0900
                   :stddev 0.0916108152
                   :daily-funding-series [{:day-index 1 :daily-rate 0.0288}
                                          {:day-index 2 :daily-rate -0.0096}]
                   :autocorrelation {:lag-1d {:value 0.714}
                                     :lag-5d {:value 0.482}
                                     :lag-15d {:value 0.21}}
                   :autocorrelation-series [{:lag-days 1 :value 0.714}
                                            {:lag-days 2 :value 0.55}]}
        tooltip-1 (do
                    (reset! cache* nil)
                    (memoized-tooltip position
                                      market
                                      "xyz:GOLD"
                                      5000.0
                                      0.01
                                      {:summary summary-1
                                       :loading? false
                                       :error nil}
                                      nil
                                      nil))
        tooltip-2 (memoized-tooltip {:coin "xyz:GOLD"
                                     :szi "1"
                                     :positionValue "5000"}
                                    (gold-market {})
                                    "xyz:GOLD"
                                    5000.0
                                    0.01
                                    {:summary summary-2
                                     :loading? false
                                     :error nil}
                                    nil
                                    nil)
        tooltip-3 (memoized-tooltip {:coin "xyz:GOLD"
                                     :szi "1"
                                     :positionValue "5000"}
                                    (gold-market {})
                                    "xyz:GOLD"
                                    5000.0
                                    0.01
                                    {:summary summary-3
                                     :loading? false
                                     :error nil}
                                    nil
                                    nil)]
    (is (identical? tooltip-1 tooltip-2))
    (is (not (identical? tooltip-2 tooltip-3)))))

(deftest active-asset-row-funding-tooltip-shows-position-projections-test
  (let [ctx-data (gold-ctx-data {:mark 5372.43
                                 :oracle 5370.0
                                 :change24h 10.0
                                 :change24hPct 1.5
                                 :volume24h 1000000
                                 :fundingRate 0.0056})
        market (gold-market {})
        full-state (visible-gold-state {})]
    (with-redefs [trading-state/position-for-active-asset
                  (fn [_]
                    {:coin "xyz:GOLD"
                     :szi "0.0185"
                     :positionValue "99.39"})
                  fmt/format-funding-countdown
                  (fn [] "00:22:01")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))
            rate-node (support/find-first-node view-node
                                               #(and (= :span (first %))
                                                     (contains? (set (support/collect-strings %)) "+0.1344%")))
            payment-node (support/find-first-node view-node
                                                  #(and (= :span (first %))
                                                        (contains? (set (support/collect-strings %)) "-$0.13")))
            rate-classes (set (support/class-values (get-in rate-node [1 :class])))
            payment-classes (set (support/class-values (get-in payment-node [1 :class])))]
        (is (contains? strings "Position"))
        (is (contains? strings "Projections"))
        (is (contains? strings "Predictability (30d)"))
        (is (contains? strings "Size"))
        (is (contains? strings "Value"))
        (is (contains? strings "Rate"))
        (is (contains? strings "Payment"))
        (is (contains? strings "Long 0.0185 GOLD"))
        (is (contains? strings "$99.39"))
        (is (not (contains? strings "Current in 22:01")))
        (is (contains? strings "Next 24h"))
        (is (contains? strings "APY"))
        (is (not (contains? strings "Next 24h *")))
        (is (not (contains? strings "APY *")))
        (is (contains? strings "+0.1344%"))
        (is (contains? strings "+49.0560%"))
        (is (not (contains? strings "-$0.01")))
        (is (contains? strings "-$0.13"))
        (is (contains? strings "-$48.76"))
        (is (contains? rate-classes "justify-self-end"))
        (is (not (contains? rate-classes "justify-self-center")))
        (is (not (contains? rate-classes "text-left")))
        (is (not (contains? rate-classes "text-center")))
        (is (contains? payment-classes "text-left"))
        (is (not (contains? payment-classes "text-center")))))))

(deftest active-asset-row-funding-tooltip-short-position-shows-positive-payment-test
  (let [ctx-data (gold-ctx-data {})
        market (gold-market {})
        full-state (visible-gold-state {})]
    (with-redefs [trading-state/position-for-active-asset
                  (fn [_]
                    {:coin "xyz:GOLD"
                     :szi "-2"
                     :positionValue "1500"})
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))]
        (is (contains? strings "Short 2 GOLD"))
        (is (not (contains? strings "+$0.15")))
        (is (contains? strings "+$3.60"))
        (is (contains? strings "+$1,314.00"))))))

(deftest active-asset-row-funding-tooltip-uses-hypothetical-position-when-no-open-position-test
  (let [ctx-data (gold-ctx-data {:fundingRate 0.0056})
        market (gold-market {})
        full-state (visible-gold-state {})]
    (with-redefs [trading-state/position-for-active-asset
                  (fn [_] nil)
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))]
        (is (contains? strings "Hypothetical Position"))
        (is (contains? strings "Edit size or value to estimate payments. Use negative size or value for short."))
        (is (contains? strings "-$1.34"))
        (is (contains? strings "-$490.56"))
        (is (not (contains? strings "No open position")))))))

(deftest active-asset-row-funding-tooltip-uses-negative-hypothetical-value-for-short-direction-test
  (let [ctx-data (gold-ctx-data {:fundingRate 0.0056})
        market (gold-market {})
        full-state (visible-gold-state
                    {:funding-ui {:hypothetical-position-by-coin {"XYZ:GOLD"
                                                                  {:size-input "oops"
                                                                   :value-input "-1000.00"}}}})]
    (with-redefs [trading-state/position-for-active-asset
                  (fn [_] nil)
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))]
        (is (contains? strings "Hypothetical Position"))
        (is (contains? strings "+$1.34"))
        (is (contains? strings "+$490.56"))
        (is (not (contains? strings "-$490.56")))))))

(deftest active-asset-row-funding-tooltip-parses-localized-hypothetical-value-input-test
  (let [ctx-data (gold-ctx-data {:fundingRate 0.0056})
        market (gold-market {})
        full-state (visible-gold-state
                    {:ui {:locale "fr-FR"}
                     :funding-ui {:hypothetical-position-by-coin {"XYZ:GOLD"
                                                                  {:size-input "oops"
                                                                   :value-input "-1000,00"}}}})]
    (with-redefs [trading-state/position-for-active-asset
                  (fn [_] nil)
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))]
        (is (contains? strings "Hypothetical Position"))
        (is (contains? strings "+$1.34"))
        (is (contains? strings "+$490.56"))
        (is (not (contains? strings "-$490.56")))))))

(deftest active-asset-row-funding-tooltip-renders-predictability-metrics-test
  (let [ctx-data (gold-ctx-data {})
        market (gold-market {})
        full-state (visible-gold-state
                    {:active-assets {:funding-predictability {:by-coin {"XYZ:GOLD"
                                                                       {:mean 0.1008
                                                                        :stddev 0.0916108152
                                                                        :daily-funding-series [{:day-index 1 :daily-rate 0.0288}
                                                                                              {:day-index 2 :daily-rate -0.0096}
                                                                                              {:day-index 3 :daily-rate 0.0192}]
                                                                        :autocorrelation {:lag-1d {:value 0.714}
                                                                                          :lag-5d {:value 0.482}
                                                                                          :lag-15d {:value 0.21}}
                                                                        :autocorrelation-series [{:lag-days 1 :value 0.714}
                                                                                                 {:lag-days 2 :value 0.55}
                                                                                                 {:lag-days 3 :value 0.44}]}}
                                                          :loading-by-coin {}
                                                          :error-by-coin {}}}})]
    (with-redefs [trading-state/position-for-active-asset
                  (fn [_]
                    {:coin "xyz:GOLD"
                     :szi "1"
                     :positionValue "5000"})
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))]
        (is (contains? strings "Predictability (30d)"))
        (is (contains? strings "Mean APY"))
        (is (contains? strings "Volatility"))
        (is (not (contains? strings "ACF Lag 1d")))
        (is (not (contains? strings "ACF Lag 5d")))
        (is (not (contains? strings "ACF Lag 15d")))
        (is (contains? strings "Rate History"))
        (is (contains? strings "Past Rate Correlation"))
        (is (contains? strings "+3679.2000%"))
        (is (contains? strings "-$183,960.00"))
        (is (contains? strings "175.0222%"))
        (is (contains? strings "-$175,208.89 to -$192,711.11"))))))

(deftest active-asset-row-funding-tooltip-renders-predictability-loading-and-insufficient-copy-test
  (let [ctx-data (gold-ctx-data {})
        market (gold-market {})
        full-state (visible-gold-state
                    {:active-assets {:funding-predictability {:by-coin {"XYZ:GOLD"
                                                                       {:mean 0.1008
                                                                        :stddev 0.0916108152
                                                                        :daily-funding-series [{:day-index 1 :daily-rate 0.0288}
                                                                                              {:day-index 2 :daily-rate nil}
                                                                                              {:day-index 3 :daily-rate -0.0144}]
                                                                        :autocorrelation {:lag-1d {:value 0.714}
                                                                                          :lag-5d {:value 0.482}
                                                                                          :lag-15d {:value nil
                                                                                                    :lag-days 15
                                                                                                    :minimum-daily-count 16
                                                                                                    :insufficient? true}}
                                                                        :autocorrelation-series [{:lag-days 1 :value 0.714}
                                                                                                 {:lag-days 2 :value nil :undefined? true}
                                                                                                 {:lag-days 3 :value -0.12}]}}
                                                          :loading-by-coin {"XYZ:GOLD" true}
                                                          :error-by-coin {}}}})]
    (with-redefs [trading-state/position-for-active-asset
                  (fn [_]
                    {:coin "xyz:GOLD"
                     :szi "1"
                     :positionValue "5000"})
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [loading-view (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            loading-strings (set (support/collect-strings loading-view))
            ready-state (assoc-in full-state
                                  [:active-assets :funding-predictability :loading-by-coin "XYZ:GOLD"]
                                  false)
            ready-view (row/active-asset-row ctx-data market {:visible-dropdown nil} ready-state)
            ready-strings (set (support/collect-strings ready-view))]
        (is (contains? loading-strings "Loading 30d stats..."))
        (is (contains? ready-strings "Rate History"))
        (is (contains? ready-strings "Past Rate Correlation"))
        (is (contains? ready-strings "Lag 15d needs at least 16 daily points"))))))

(deftest active-asset-row-funding-tooltip-uses-opaque-high-stack-surface-test
  (let [ctx-data (gold-ctx-data {})
        market (gold-market {})
        full-state (visible-gold-state {})]
    (with-redefs [trading-state/position-for-active-asset
                  (fn [_] nil)
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)]
        (is (support/contains-class? view-node "z-[140]"))
        (is (support/contains-class? view-node "bg-[#06131a]"))
        (is (support/contains-class? view-node "isolate"))
        (is (support/contains-class? view-node "bg-[#0b1820]"))
        (is (not (support/contains-class? view-node "backdrop-blur-sm")))))))
