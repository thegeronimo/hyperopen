(ns hyperopen.core-bootstrap.chart-menu-and-storage-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.core.compat :as core]
            [hyperopen.core-bootstrap.test-support.effect-extractors :as effect-extractors]
            [hyperopen.core-bootstrap.test-support.browser-mocks :as browser-mocks]))

(def with-test-local-storage browser-mocks/with-test-local-storage)
(def ^:private chart-timeframe-heavy-effect-ids
  #{:effects/sync-active-candle-subscription
    :effects/fetch-candle-snapshot})

(deftest toggle-timeframes-dropdown-opens-timeframes-and-closes-other-chart-menus-test
  (let [effects (core/toggle-timeframes-dropdown
                 {:chart-options {:timeframes-dropdown-visible false
                                  :chart-type-dropdown-visible true
                                  :indicators-dropdown-visible true}})]
    (is (= [[:effects/save-many [[[:chart-options :timeframes-dropdown-visible] true]
                                 [[:chart-options :chart-type-dropdown-visible] false]
                                 [[:chart-options :indicators-dropdown-visible] false]]]]
           effects))))

(deftest toggle-chart-type-dropdown-opens-chart-type-and-closes-other-chart-menus-test
  (let [effects (core/toggle-chart-type-dropdown
                 {:chart-options {:timeframes-dropdown-visible true
                                  :chart-type-dropdown-visible false
                                  :indicators-dropdown-visible true}})]
    (is (= [[:effects/save-many [[[:chart-options :timeframes-dropdown-visible] false]
                                 [[:chart-options :chart-type-dropdown-visible] true]
                                 [[:chart-options :indicators-dropdown-visible] false]]]]
           effects))))

(deftest toggle-indicators-dropdown-opens-indicators-and-closes-other-chart-menus-test
  (let [effects (core/toggle-indicators-dropdown
                 {:chart-options {:timeframes-dropdown-visible true
                                  :chart-type-dropdown-visible true
                                  :indicators-dropdown-visible false}})]
    (is (= [[:effects/save-many [[[:chart-options :indicators-search-term] ""]
                                 [[:chart-options :timeframes-dropdown-visible] false]
                                 [[:chart-options :chart-type-dropdown-visible] false]
                                 [[:chart-options :indicators-dropdown-visible] true]]]
            [:effects/load-trading-indicators-module]]
           effects))))

(deftest update-indicators-search-saves-string-value-test
  (let [effects (core/update-indicators-search {} "sma")]
    (is (= [[:effects/save [:chart-options :indicators-search-term] "sma"]]
           effects))))

(deftest show-volume-indicator-persists-visible-state-test
  (let [effects (core/show-volume-indicator {})]
    (is (= [[:effects/save [:chart-options :volume-visible?] true]
            [:effects/local-storage-set "chart-volume-visible" "true"]]
           effects))))

(deftest hide-volume-indicator-persists-visible-state-test
  (let [effects (core/hide-volume-indicator {})]
    (is (= [[:effects/save [:chart-options :volume-visible?] false]
            [:effects/local-storage-set "chart-volume-visible" "false"]]
           effects))))

(deftest toggle-open-indicators-dropdown-clears-search-and-closes-all-chart-menus-test
  (let [effects (core/toggle-indicators-dropdown
                 {:chart-options {:timeframes-dropdown-visible true
                                  :chart-type-dropdown-visible false
                                  :indicators-dropdown-visible true
                                  :indicators-search-term "moving"}})]
    (is (= [[:effects/save-many [[[:chart-options :indicators-search-term] ""]
                                 [[:chart-options :timeframes-dropdown-visible] false]
                                 [[:chart-options :chart-type-dropdown-visible] false]
                                 [[:chart-options :indicators-dropdown-visible] false]]]]
           effects))))

(deftest toggle-open-chart-menu-closes-all-chart-menus-test
  (let [effects (core/toggle-timeframes-dropdown
                 {:chart-options {:timeframes-dropdown-visible true
                                  :chart-type-dropdown-visible false
                                  :indicators-dropdown-visible false}})]
    (is (= [[:effects/save-many [[[:chart-options :timeframes-dropdown-visible] false]
                                 [[:chart-options :chart-type-dropdown-visible] false]
                                 [[:chart-options :indicators-dropdown-visible] false]]]]
           effects))))

(deftest select-chart-timeframe-emits-batched-projection-before-single-fetch-test
  (with-test-local-storage
    (fn []
      (let [effects (core/select-chart-timeframe
                     {:chart-options {:timeframes-dropdown-visible true
                                      :chart-type-dropdown-visible true
                                      :indicators-dropdown-visible true}}
                     :5m)]
        (is (= [[:effects/save-many [[[:chart-options :selected-timeframe] :5m]
                                     [[:chart-options :timeframes-dropdown-visible] false]
                                     [[:chart-options :chart-type-dropdown-visible] false]
                                     [[:chart-options :indicators-dropdown-visible] false]]]
                [:effects/local-storage-set "chart-timeframe" "5m"]
                [:effects/sync-active-candle-subscription :interval :5m]
                [:effects/fetch-candle-snapshot :interval :5m]]
               effects))
        (is (= 1 (count (filter #(= :effects/fetch-candle-snapshot (first %)) effects))))
        (is (= :effects/save-many (ffirst effects)))
        (is (effect-extractors/projection-before-heavy? effects chart-timeframe-heavy-effect-ids))
        (is (effect-extractors/phase-order-valid? effects chart-timeframe-heavy-effect-ids))
        (is (empty? (effect-extractors/duplicate-heavy-effect-ids effects chart-timeframe-heavy-effect-ids)))
        (is (= :effects/fetch-candle-snapshot (first (last effects))))))))

(deftest select-chart-timeframe-skips-rest-candle-fetch-when-candle-migration-enabled-and-cached-test
  (with-test-local-storage
    (fn []
      (let [effects (core/select-chart-timeframe
                     {:active-asset "BTC"
                      :candles {"BTC" {:1h [{:t 1 :o 1 :h 1 :l 1 :c 1 :v 1}]}}
                      :websocket {:migration-flags {:candle-subscriptions? true}}
                      :chart-options {:timeframes-dropdown-visible true
                                      :chart-type-dropdown-visible true
                                      :indicators-dropdown-visible true}}
                     :1h)]
        (is (= [[:effects/save-many [[[:chart-options :selected-timeframe] :1h]
                                     [[:chart-options :timeframes-dropdown-visible] false]
                                     [[:chart-options :chart-type-dropdown-visible] false]
                                     [[:chart-options :indicators-dropdown-visible] false]]]
                [:effects/local-storage-set "chart-timeframe" "1h"]
                [:effects/sync-active-candle-subscription :interval :1h]]
               effects))
        (is (not-any? #(= :effects/fetch-candle-snapshot (first %)) effects))))))

(deftest select-chart-timeframe-keeps-rest-backfill-when-candle-migration-enabled-without-cache-test
  (with-test-local-storage
    (fn []
      (let [effects (core/select-chart-timeframe
                     {:active-asset "BTC"
                      :candles {"BTC" {:1d [{:t 1 :o 1 :h 1 :l 1 :c 1 :v 1}]}}
                      :websocket {:migration-flags {:candle-subscriptions? true}}
                      :chart-options {:timeframes-dropdown-visible true
                                      :chart-type-dropdown-visible true
                                      :indicators-dropdown-visible true}}
                     :1h)]
        (is (= :effects/fetch-candle-snapshot (first (last effects))))
        (is (= [:effects/fetch-candle-snapshot :interval :1h]
               (last effects)))
        (is (= [:effects/sync-active-candle-subscription :interval :1h]
               (nth effects 2)))))))

(deftest select-chart-type-emits-single-batched-projection-and-no-network-effects-test
  (with-test-local-storage
    (fn []
      (let [effects (core/select-chart-type
                     {:chart-options {:timeframes-dropdown-visible true
                                      :chart-type-dropdown-visible true
                                      :indicators-dropdown-visible true}}
                     :line)]
        (is (= [[:effects/save-many [[[:chart-options :selected-chart-type] :line]
                                     [[:chart-options :timeframes-dropdown-visible] false]
                                     [[:chart-options :chart-type-dropdown-visible] false]
                                     [[:chart-options :indicators-dropdown-visible] false]]]
                [:effects/local-storage-set "chart-type" "line"]]
               effects))
        (is (= 2 (count effects)))
        (is (not-any? #(= :effects/fetch-candle-snapshot (first %)) effects))
        (is (not-any? #(= :effects/subscribe-active-asset (first %)) effects))))))

(deftest local-storage-effect-interpreters-persist-string-and-json-values-test
  (with-test-local-storage
    (fn []
      (core/local-storage-set nil nil "sample-key" "sample-value")
      (is (= "sample-value" (.getItem js/localStorage "sample-key")))
      (core/local-storage-set-json nil nil "sample-json" {:a 1 :b "two"})
      (is (= {:a 1 :b "two"}
             (js->clj (js/JSON.parse (.getItem js/localStorage "sample-json"))
                      :keywordize-keys true))))))
