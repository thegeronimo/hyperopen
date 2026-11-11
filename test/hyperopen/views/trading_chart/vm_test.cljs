(ns hyperopen.views.trading-chart.vm-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.utils.formatting :as fmt]
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
   :unrealized-pnl 4})

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
   :trading-settings {:show-chart-trades? true}})

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
          (is (fn? (:on-history-backfill-request runtime-options)))
          (is (false? (:history-backfill-loading? runtime-options)))
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

(deftest chart-view-model-history-backfill-callback-dispatches-action-test
  (let [dispatch-calls (atom [])
        dispatch-fn (fn [event actions]
                      (swap! dispatch-calls conj [event actions]))]
    (binding [derived-cache/*process-candle-data* (fn [_] transformed-candles)]
      (with-redefs [trading-state/position-for-active-asset (fn [_] nil)
                    position-overlay-model/build-position-overlay (fn [_] nil)]
        (let [callback (get-in (vm/chart-view-model (base-state) dispatch-fn)
                               [:chart-runtime-options :on-history-backfill-request])]
          (callback {:bars 330
                     :end-time-ms 1751068799999})
          (is (= [[{:replicant/trigger :chart-candle-history-backfill}
                   [[:actions/request-chart-candle-backfill
                     {:coin "BTC"
                      :interval :1d
                      :bars 330
                      :end-time-ms 1751068799999}]]]]
                 @dispatch-calls)))))))

(deftest chart-view-model-exposes-history-backfill-loading-state-test
  (let [dispatch-fn (fn [_event _actions] nil)]
    (binding [derived-cache/*process-candle-data* (fn [_] transformed-candles)]
      (with-redefs [trading-state/position-for-active-asset (fn [_] nil)
                    position-overlay-model/build-position-overlay (fn [_] nil)]
        (let [model (vm/chart-view-model
                     (assoc-in (base-state)
                               [:chart-options :history-backfill-pending-count]
                               1)
                     dispatch-fn)]
          (is (true? (get-in model [:chart-runtime-options :history-backfill-loading?]))))))))

(def ^:private wallet-address "0xabc0000000000000000000000000000000000001")

(defn- chart-trades-of
  [state]
  (get-in (vm/chart-view-model state (fn [_ _] nil)) [:chart-runtime-options :chart-trades]))

(deftest chart-view-model-draws-the-chart-history-only-for-the-effective-account-test
  (let [shared [{:coin "BTC" :tid 1 :side "B" :px "100" :sz "1" :time 1700000000000}]
        history [{:coin "BTC" :tid 2 :side "B" :px "100" :sz "1" :time 1700000000000}
                 {:coin "BTC" :tid 3 :side "A" :px "101" :sz "1" :time 1700000060000}]
        state (-> (base-state)
                  (assoc :wallet {:address wallet-address})
                  (assoc-in [:orders :fills] shared))]
    (is (= 2 (count (:groups (chart-trades-of
                              (assoc state :chart-trades {:address wallet-address :fills history})))))
        "the uncapped chart history wins for its own account")
    (is (= 1 (count (:groups (chart-trades-of
                              (assoc state :chart-trades {:address "0xdef0000000000000000000000000000000000002"
                                                          :fills history})))))
        "a history left over from another account is never drawn")))

(deftest chart-view-model-asks-for-older-fills-when-candles-predate-coverage-test
  (let [state (-> (base-state)
                  (assoc :wallet {:address wallet-address})
                  (assoc :chart-trades {:address wallet-address
                                        :fills [{:coin "BTC" :tid 2 :side "B" :px "100" :sz "1"
                                                 :time 1700000060000}]
                                        :covered-from-ms 1700000060000}))
        deps (get-in (vm/chart-view-model state (fn [_ _] nil)) [:chart-runtime-options :chart-trades-deps])]
    (is (= 1700000059999 (:older-fills-end-ms deps)))
    (is (fn? (:on-request-older-fills deps)))))

(defn- outcome-state
  [unit]
  (-> (base-state)
      (assoc :active-asset "#9900010"
             :active-market {:market-type :outcome :coin "#9900010" :price-decimals 5}
             :candles {"#9900010" {:1d raw-candles}})
      (assoc-in [:chart-options :outcome-price-unit] unit)))

(deftest chart-price-unit-only-for-the-active-outcome-market-test
  (is (nil? (vm/chart-price-unit (base-state))))
  (is (= :percent (vm/chart-price-unit (outcome-state nil))) "percent by default")
  (is (= :cents (vm/chart-price-unit (outcome-state :cents))))
  (is (nil? (vm/chart-price-unit (assoc (outcome-state :cents) :active-asset "BTC")))
      "a lagging outcome market never labels a perp chart"))

(deftest chart-view-model-swaps-formatters-between-outcome-and-perp-test
  (binding [derived-cache/*process-candle-data* (fn [_] transformed-candles)]
    (with-redefs [trading-state/position-for-active-asset (fn [_] nil)
                  position-overlay-model/build-position-overlay (fn [_] nil)]
      (let [run (fn [state] (:chart-runtime-options (vm/chart-view-model state (fn [_ _] nil))))
            outcome (run (outcome-state :cents))
            perp (run (base-state))
            outcome-pct (run (outcome-state :percent))]
        (is (= {:price-decimals 5 :price-unit :cents} (:series-options outcome)))
        (is (= "20.9¢" ((get-in outcome [:legend-deps :format-price]) 0.209)))
        (is (= "20.9%" ((get-in outcome-pct [:legend-deps :format-price]) 0.209)))
        (testing "overlays, trade tooltips and copy price follow the unit"
          (is (= "20.9¢" ((get-in outcome [:price-formatters :format-price]) 0.209)))
          (is (= "20.9%" ((get-in outcome-pct [:price-formatters :format-price]) 0.209)))
          (is (= "20.9¢" ((get-in outcome [:chart-trades-deps :format-price]) 0.209)))
          (is (= "20.9" ((get-in outcome-pct [:price-formatters :copy-price :format-price]) 0.209))
              "copy price is the cents number in either unit")
          (is (= "¢" (get-in outcome-pct [:price-formatters :copy-price :price-unit-suffix])))
          (is (false? (get-in outcome [:legend-deps :show-pct?])) "no relative % beside points"))
        (is (= {:price-decimals 2} (:series-options perp)) "no unit on a perp")
        (is (nil? (get-in perp [:price-formatters :copy-price])))
        (is (not (contains? (:legend-deps perp) :show-pct?)))
        (is (identical? fmt/format-trade-price-plain (get-in perp [:chart-trades-deps :format-price])))
        (is (not (re-find #"[%¢]" ((get-in perp [:legend-deps :format-price]) 100))))))))
