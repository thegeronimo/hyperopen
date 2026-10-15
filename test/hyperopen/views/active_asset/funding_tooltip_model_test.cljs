(ns hyperopen.views.active-asset.funding-tooltip-model-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.active-asset.funding-policy :as funding-policy]
            [hyperopen.state.trading :as trading-state]
            [hyperopen.test-support.hiccup :as hiccup]
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

(defn- some-string-includes?
  [strings fragment]
  (boolean (some #(str/includes? % fragment) strings)))

(defn- node-classes
  [node]
  (set (support/class-values (get-in node [1 :class]))))

(defn- span-with-text
  [view-node text]
  (support/find-first-node view-node
                           #(and (= :span (first %))
                                 (= [text] (vec (support/collect-strings %))))))

(deftest active-asset-row-funding-tooltip-shows-position-projections-test
  (let [ctx-data (gold-ctx-data {:mark 5372.43
                                 :oracle 5370.0
                                 :change24h 10.0
                                 :change24hPct 1.5
                                 :volume24h 1000000
                                 :fundingRate 0.0056})
        market (gold-market {})
        full-state (visible-gold-state {})]
    (with-redefs [trading-state/position-for-market
                  (fn [_ _ _]
                    {:coin "xyz:GOLD"
                     :szi "0.0185"
                     :positionValue "99.39"})
                  fmt/format-funding-countdown
                  (fn [] "00:22:01")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))
            action-button (support/find-node-by-role view-node "active-asset-funding-position-action")
            verdict (support/find-node-by-role view-node "funding-tooltip-verdict")
            next-24h (support/find-node-by-role view-node "funding-projection-next-24h")]
        (is (contains? strings "GOLD funding"))
        (is (contains? strings "22:01"))
        (is (contains? strings "Your position"))
        (is (contains? strings "Edit estimate"))
        (is (contains? strings "0.0185 GOLD"))
        (is (contains? strings "$99.39"))
        (is (= [[:actions/enter-funding-hypothetical-position
                 "xyz:GOLD"
                 5372.43
                 {:size-input "0.0185"
                  :value-input "99.39"}]
                [:actions/set-funding-tooltip-pinned
                 (funding-policy/funding-tooltip-pin-id "xyz:GOLD")
                 true]
                [:actions/set-funding-tooltip-visible
                 (funding-policy/funding-tooltip-pin-id "xyz:GOLD")
                 true]]
               (get-in action-button [1 :on :click])))
        (is (= "pays" (get-in verdict [1 :data-verdict])))
        (is (contains? strings "Pays funding every hour"))
        (is (contains? strings "Projected at today's rate"))
        (is (contains? strings "You pay"))
        (is (contains? strings "Next 24 hours"))
        (is (contains? strings "Next 30 days"))
        (is (contains? strings "Next year"))
        (is (contains? strings "+0.1344%"))
        (is (contains? strings "+4.032%"))
        (is (contains? strings "+49.06% APY"))
        (is (contains? (set (support/collect-strings next-24h)) "$0.13"))
        (is (contains? strings "$4.01"))
        (is (contains? strings "$48.76"))
        (is (contains? (node-classes (span-with-text view-node "$0.13")) "text-error"))
        (is (not (contains? strings "Of margin")))
        (is (some-string-includes? strings "needs to rise 4.03%"))
        (is (not (contains? strings "Why this rate")))
        (is (not (contains? strings "Funding so far")))))))

(deftest active-asset-row-funding-tooltip-short-position-shows-positive-payment-test
  (let [ctx-data (gold-ctx-data {})
        market (gold-market {})
        full-state (visible-gold-state {})]
    (with-redefs [trading-state/position-for-market
                  (fn [_ _ _]
                    {:coin "xyz:GOLD"
                     :szi "-2"
                     :positionValue "1500"})
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))]
        (is (contains? strings "2 GOLD"))
        (is (contains? strings "Earns funding every hour"))
        (is (contains? strings "You earn"))
        (is (contains? strings "$3.60"))
        (is (contains? strings "$1,314.00"))
        (is (contains? (node-classes (span-with-text view-node "$3.60")) "text-success"))
        (is (some-string-includes? strings "funding offsets a 7.20% move against you"))))))

(deftest active-asset-row-funding-tooltip-uses-hypothetical-position-when-no-open-position-test
  (let [ctx-data (gold-ctx-data {:fundingRate 0.0056})
        market (gold-market {})
        full-state (visible-gold-state {})]
    (with-redefs [trading-state/position-for-market
                  (fn [_ _ _] nil)
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))
            long-button (support/find-node-by-role view-node "funding-hypothetical-side-long")
            short-button (support/find-node-by-role view-node "funding-hypothetical-side-short")]
        (is (contains? strings "If you hold"))
        (is (= "true" (get-in long-button [1 :aria-pressed])))
        (is (= "false" (get-in short-button [1 :aria-pressed])))
        (is (= [[:actions/set-funding-hypothetical-side "xyz:GOLD" 5000.0 "short"]]
               (get-in short-button [1 :on :click])))
        (is (contains? strings "$1.34"))
        (is (contains? strings "$490.56"))
        (is (not (contains? strings "No open position")))))))

(deftest active-asset-row-funding-tooltip-uses-negative-hypothetical-value-for-short-direction-test
  (let [ctx-data (gold-ctx-data {:fundingRate 0.0056})
        market (gold-market {})
        full-state (visible-gold-state
                    {:funding-ui {:hypothetical-position-by-coin {"XYZ:GOLD"
                                                                  {:size-input "oops"
                                                                   :value-input "-1000.00"}}}})]
    (with-redefs [trading-state/position-for-market
                  (fn [_ _ _] nil)
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))
            short-button (support/find-node-by-role view-node "funding-hypothetical-side-short")
            value-input (support/find-first-node view-node
                                                 #(= "Hypothetical position value"
                                                     (get-in % [1 :aria-label])))]
        (is (contains? strings "If you hold"))
        (is (= "true" (get-in short-button [1 :aria-pressed])))
        (is (= "1000.00" (get-in value-input [1 :value])))
        (is (contains? strings "Earns funding every hour"))
        (is (contains? strings "$1.34"))
        (is (contains? strings "$490.56"))))))

(deftest active-asset-row-funding-tooltip-parses-localized-hypothetical-value-input-test
  (let [ctx-data (gold-ctx-data {:fundingRate 0.0056})
        market (gold-market {})
        full-state (visible-gold-state
                    {:ui {:locale "fr-FR"}
                     :funding-ui {:hypothetical-position-by-coin {"XYZ:GOLD"
                                                                  {:size-input "oops"
                                                                   :value-input "-1000,00"}}}})]
    (with-redefs [trading-state/position-for-market
                  (fn [_ _ _] nil)
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))]
        (is (contains? strings "If you hold"))
        (is (contains? strings "Earns funding every hour"))
        (is (contains? strings "$1.34"))
        (is (contains? strings "$490.56"))))))

(def ^:private gold-predictability-summary
  {:mean 0.1008
   :stddev 0.0916108152
   :daily-funding-series [{:day-index 1 :daily-rate 0.0288}
                          {:day-index 2 :daily-rate -0.0096}
                          {:day-index 3 :daily-rate 0.0192}]
   :autocorrelation {:lag-1d {:value 0.714}
                     :lag-5d {:value 0.482}
                     :lag-15d {:value 0.21}}
   :autocorrelation-series [{:lag-days 1 :value 0.714 :pair-count 28}
                            {:lag-days 2 :value 0.55 :pair-count 27}
                            {:lag-days 3 :value 0.44 :pair-count 26}
                            {:lag-days 7 :value 0.05 :pair-count 2}]})

(deftest active-asset-row-funding-tooltip-renders-predictability-metrics-test
  (let [ctx-data (gold-ctx-data {})
        market (gold-market {})
        full-state (visible-gold-state
                    {:active-assets {:funding-predictability {:by-coin {"XYZ:GOLD" gold-predictability-summary}
                                                              :loading-by-coin {}
                                                              :error-by-coin {}}}})]
    (with-redefs [trading-state/position-for-market
                  (fn [_ _ _]
                    {:coin "xyz:GOLD"
                     :szi "1"
                     :positionValue "5000"})
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))
            chart (support/find-node-by-role view-node "funding-history-chart")
            area-button (support/find-node-by-role view-node "funding-chart-style-area")]
        (is (contains? strings "Last 30 days"))
        (is (= "area" (get-in chart [1 :data-chart-style])))
        (is (= "true" (get-in area-button [1 :aria-pressed])))
        (is (contains? strings "Range"))
        (is (contains? strings "-350.4–1,051.2%"))
        (is (= 3 (count (hiccup/find-all-nodes chart #(= "funding-history-day" (get-in % [1 :data-role]))))))
        (is (contains? strings "2d ago · +1,051.20% APY"))
        (is (contains? strings "Today · +700.80% APY"))
        (is (contains? strings "2 of 3 d"))
        (is (contains? strings "If the last 30 days repeat for a year"))
        (is (contains? strings "You'd pay about"))
        (is (contains? strings "$183,960.00"))
        (is (contains? strings "Likely range $175,208.89 to $192,711.11 (2 in 3 odds)"))
        (is (contains? strings "Does today predict later?"))
        (is (contains? strings "Strongly"))
        (is (contains? strings "Tomorrow"))
        (is (contains? strings "3 days out"))
        (is (contains? strings "+0.71"))
        (is (not (contains? strings "1 week out")))
        (is (not (contains? strings "Rate History")))
        (is (not (contains? strings "Past Rate Correlation")))))))

(deftest active-asset-row-funding-tooltip-renders-bar-chart-when-selected-test
  (let [ctx-data (gold-ctx-data {})
        market (gold-market {})
        full-state (-> (visible-gold-state
                        {:active-assets {:funding-predictability {:by-coin {"XYZ:GOLD" gold-predictability-summary}
                                                                  :loading-by-coin {}
                                                                  :error-by-coin {}}}})
                       (assoc-in [:funding-ui :chart-style] :bars))]
    (with-redefs [trading-state/position-for-market
                  (fn [_ _ _] nil)
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            chart (support/find-node-by-role view-node "funding-history-chart")
            bars-button (support/find-node-by-role view-node "funding-chart-style-bars")
            bar-fills (->> (hiccup/find-all-nodes chart #(= :rect (first %)))
                           (keep #(get-in % [1 :data-funding-rate-value]))
                           vec)]
        (is (= "bars" (get-in chart [1 :data-chart-style])))
        (is (= "true" (get-in bars-button [1 :aria-pressed])))
        (is (= [[:actions/set-funding-chart-style "bars"]]
               (get-in bars-button [1 :on :click])))
        (is (= 3 (count bar-fills)))))))

(deftest active-asset-row-funding-tooltip-renders-predictability-loading-copy-test
  (let [ctx-data (gold-ctx-data {})
        market (gold-market {})
        full-state (visible-gold-state
                    {:active-assets {:funding-predictability {:by-coin {"XYZ:GOLD" gold-predictability-summary}
                                                              :loading-by-coin {"XYZ:GOLD" true}
                                                              :error-by-coin {}}}})]
    (with-redefs [trading-state/position-for-market
                  (fn [_ _ _]
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
            ready-view (row/active-asset-row ctx-data market {:visible-dropdown nil} ready-state)]
        (is (contains? loading-strings "Loading 30d stats..."))
        (is (nil? (support/find-node-by-role loading-view "funding-tooltip-lag-strip")))
        (is (some? (support/find-node-by-role ready-view "funding-history-chart")))
        (is (some? (support/find-node-by-role ready-view "funding-tooltip-lag-strip")))))))

(deftest active-asset-row-funding-tooltip-shows-margin-and-since-open-for-live-position-test
  (let [ctx-data (support/active-asset-row-ctx {:coin "BTC"
                                                :mark 100000.0
                                                :markRaw nil
                                                :oracle 100000.0
                                                :oracleRaw nil
                                                :change24h 0
                                                :change24hPct 0
                                                :volume24h 1
                                                :openInterest 1
                                                :fundingRate 0.00125})
        market (support/active-asset-market {:coin "BTC" :symbol "BTC-USDC" :base "BTC"})
        full-state (support/with-visible-funding-tooltip
                     {:active-asset "BTC" :asset-selector {:missing-icons #{}}}
                     "BTC")]
    (with-redefs [trading-state/position-for-market
                  (fn [_ _ _]
                    {:coin "BTC"
                     :szi "0.01"
                     :positionValue "1000"
                     :marginUsed "100"
                     :leverage {:type "cross" :value 10}
                     :cumFunding {:sinceOpen "1.84"}})
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))
            since-open (support/find-node-by-role view-node "funding-tooltip-since-open")]
        (is (contains? strings "Margin · 10×"))
        (is (contains? strings "$100.00"))
        (is (contains? strings "0.30% of your margin"))
        (is (contains? strings " per day at 10×"))
        (is (contains? strings "Of margin"))
        (is (contains? strings "109.5%"))
        (is (contains? (set (support/collect-strings since-open)) "Paid $1.84"))
        (is (contains? strings "Why this rate"))
        (is (contains? strings "Baseline interest (fixed)"))
        (is (contains? strings "+10.95%"))
        (is (contains? strings "0.00%"))
        (is (contains? strings "The perp is trading close to spot, so only the fixed baseline is charged."))))))

(deftest active-asset-row-funding-tooltip-uses-opaque-high-stack-surface-test
  (let [ctx-data (gold-ctx-data {})
        market (gold-market {})
        full-state (visible-gold-state {})]
    (with-redefs [trading-state/position-for-market
                  (fn [_ _ _] nil)
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)]
        (is (support/contains-class? view-node "z-[140]"))
        (is (support/contains-class? view-node "bg-ho-bg-deep"))
        (is (support/contains-class? view-node "isolate"))
        (is (not (support/contains-class? view-node "backdrop-blur-sm")))))))

(deftest active-asset-row-funding-tooltip-renders-use-live-action-for-seeded-estimate-test
  (let [ctx-data (gold-ctx-data {:fundingRate 0.0056})
        market (gold-market {})
        full-state (visible-gold-state
                    {:funding-ui {:hypothetical-position-by-coin {"XYZ:GOLD"
                                                                  {:size-input "0.0200"
                                                                   :value-input "100.00"}}}})]
    (with-redefs [trading-state/position-for-market
                  (fn [_ _ _]
                    {:coin "xyz:GOLD"
                     :szi "0.0185"
                     :positionValue "99.39"})
                  fmt/format-funding-countdown
                  (fn [] "00:10:00")]
      (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
            strings (set (support/collect-strings view-node))
            action-button (support/find-node-by-role view-node "active-asset-funding-position-action")]
        (is (contains? strings "If you hold"))
        (is (contains? strings "Use live"))
        (is (= [[:actions/reset-funding-hypothetical-position "xyz:GOLD"]]
               (get-in action-button [1 :on :click])))))))
