(ns hyperopen.views.trading-chart.vm-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.state.trading :as trading-state]
            [hyperopen.views.trading-chart.derived-cache :as derived-cache]
            [hyperopen.views.trading-chart.utils.position-overlay-model :as position-overlay-model]
            [hyperopen.views.trading-chart.vm :as vm]))

(def raw-candles
  [{:t 1700000000000 :o "100" :h "101" :l "99" :c "100" :v "10"}
   {:t 1700000060000 :o "100" :h "102" :l "98" :c "101" :v "12"}])

(def transformed-candles
  [{:time 1700000000 :open 100 :high 101 :low 99 :close 100 :volume 10}
   {:time 1700000060 :open 100 :high 102 :low 98 :close 101 :volume 12}])

(def active-position
  {:coin "BTC"
   :szi "1"
   :entryPx "100"
   :liquidationPx "90"})

(def position-overlay
  {:side :long
   :entry-price 100
   :liquidation-price 90
   :unrealized-pnl 4
   :fill-markers [{:time 1700000000
                   :position "belowBar"
                   :shape "circle"
                   :color "#22ab94"}]})

(defn- base-state
  []
  {:active-asset "BTC"
   :active-market {:price-decimals 2
                   :dex "xyz"}
   :asset-selector {:market-by-key {}}
   :candles {"BTC" {:1d raw-candles}}
   :chart-options {:selected-timeframe :1d
                   :selected-chart-type :candlestick
                   :active-indicators {}
                   :volume-visible? true}
   :orders {:open-orders [{:coin "BTC"
                           :oid 42
                           :side "A"
                           :type "limit"
                           :sz "1"
                           :px "100"}]
            :fills []}
   :trading-settings {:show-fill-markers? true}})

(deftest chart-view-model-owner-preserves-derived-model-contract-test
  (let [dispatch-fn (fn [_event _actions] nil)
        required-keys [:has-error?
                       :candle-data
                       :selected-chart-type
                       :selected-timeframe
                       :active-indicators
                       :legend-meta
                       :chart-runtime-options
                       :active-open-orders
                       :on-cancel-order]]
    (derived-cache/reset-derived-cache!)
    (binding [derived-cache/*process-candle-data* (fn [_] transformed-candles)]
      (with-redefs [trading-state/position-for-active-asset (fn [_] active-position)
                    position-overlay-model/build-position-overlay (fn [_] position-overlay)]
        (let [first-model (vm/chart-view-model (base-state) dispatch-fn)
              second-model (vm/chart-view-model (base-state) dispatch-fn)
              runtime-options (:chart-runtime-options first-model)
              second-runtime-options (:chart-runtime-options second-model)]
          (doseq [k required-keys]
            (is (contains? first-model k)))
          (is (false? (:has-error? first-model)))
          (is (= :candlestick (:selected-chart-type first-model)))
          (is (= :1d (:selected-timeframe first-model)))
          (is (= {} (:active-indicators first-model)))
          (is (identical? (:candle-data first-model)
                          (:candle-data second-model)))
          (is (identical? (:legend-meta first-model)
                          (:legend-meta second-model)))
          (is (identical? runtime-options second-runtime-options))
          (is (identical? (:active-open-orders first-model)
                          (:active-open-orders second-model)))
          (is (identical? (:on-cancel-order first-model)
                          (:on-cancel-order second-model)))
          (is (identical? (:position-overlay runtime-options)
                          (:position-overlay second-runtime-options)))
          (is (identical? (:on-hide-volume-indicator runtime-options)
                          (:on-hide-volume-indicator second-runtime-options)))
          (is (identical? (:on-liquidation-drag-preview runtime-options)
                          (:on-liquidation-drag-preview second-runtime-options)))
          (is (identical? (:on-liquidation-drag-confirm runtime-options)
                          (:on-liquidation-drag-confirm second-runtime-options)))
          (is (= {:asset "BTC"
                  :candles transformed-candles}
                 (:persistence-deps runtime-options)))))))

  (binding [derived-cache/*process-candle-data* (fn [_] transformed-candles)]
    (with-redefs [trading-state/position-for-active-asset (fn [_] nil)
                  position-overlay-model/build-position-overlay (fn [_] nil)]
      (let [model (vm/chart-view-model (assoc (base-state)
                                              :orders {:open-orders []}
                                              :webdata2 {:open-orders [{:coin "BTC"
                                                                        :oid 99
                                                                        :side "A"
                                                                        :type "limit"
                                                                        :sz "2"
                                                                        :px "88"}]})
                                      (fn [_event _actions] nil))]
        (is (empty? (:active-open-orders model))))))

  (binding [derived-cache/*process-candle-data* (fn [_] transformed-candles)]
    (with-redefs [trading-state/position-for-active-asset (fn [_] active-position)
                  position-overlay-model/build-position-overlay (fn [_] position-overlay)]
      (let [valid-preview-state (assoc (base-state)
                                       :positions-ui {:margin-modal {:open? true
                                                                     :position-key "BTC|xyz"
                                                                     :prefill-source :chart-liquidation-drag
                                                                     :prefill-liquidation-current-price "90"
                                                                     :prefill-liquidation-target-price "85"}})
            invalid-preview-state (assoc-in valid-preview-state
                                            [:positions-ui :margin-modal :position-key]
                                            "ETH|xyz")
            malformed-preview-state (assoc-in valid-preview-state
                                              [:positions-ui :margin-modal :prefill-liquidation-target-price]
                                              "not-a-number")
            valid-overlay (get-in (vm/chart-view-model valid-preview-state
                                                       (fn [_event _actions] nil))
                                  [:chart-runtime-options :position-overlay])
            invalid-overlay (get-in (vm/chart-view-model invalid-preview-state
                                                         (fn [_event _actions] nil))
                                    [:chart-runtime-options :position-overlay])
            malformed-overlay (get-in (vm/chart-view-model malformed-preview-state
                                                           (fn [_event _actions] nil))
                                      [:chart-runtime-options :position-overlay])]
        (is (= 85 (:liquidation-price valid-overlay)))
        (is (= 90 (:current-liquidation-price valid-overlay)))
        (is (= 100 (:entry-price valid-overlay)))
        (is (= :long (:side valid-overlay)))
        (is (= 90 (:liquidation-price invalid-overlay)))
        (is (= 90 (:liquidation-price malformed-overlay)))))))
