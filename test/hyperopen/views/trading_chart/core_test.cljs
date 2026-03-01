(ns hyperopen.views.trading-chart.core-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [replicant.core :as replicant-core]
            [hyperopen.state.trading :as trading-state]
            [hyperopen.views.trading-chart.derived-cache :as derived-cache]
            [hyperopen.views.trading-chart.core :as chart-core]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]
            [hyperopen.views.trading-chart.utils.position-overlay-model :as position-overlay-model]
            [hyperopen.views.trading-chart.utils.data-processing :as data-processing]))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- classes-from-tag [tag]
  (if (keyword? tag)
    (let [parts (str/split (name tag) #"\.")]
      (if (> (count parts) 1)
        (rest parts)
        []))
    []))

(defn- collect-all-classes [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (concat (classes-from-tag (first node))
              (class-values (:class attrs))
              (mapcat collect-all-classes children)))

    (seq? node)
    (mapcat collect-all-classes node)

    :else []))

(defn- class-strings [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) [class-attr]
    (sequential? class-attr) (mapcat class-strings class-attr)
    :else []))

(defn- collect-class-strings [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (concat (class-strings (:class attrs))
              (mapcat collect-class-strings children)))

    (seq? node)
    (mapcat collect-class-strings node)

    :else []))

(defn- spaced-class-string? [value]
  (and (string? value)
       (<= 2 (count (remove str/blank? (str/split value #"\s+"))))))

(defn- collect-background-colors [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))
          bg-color (get-in attrs [:style :background-color])]
      (concat (if (string? bg-color) [bg-color] [])
              (mapcat collect-background-colors children)))

    (seq? node)
    (mapcat collect-background-colors node)

    :else []))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (if (map? (second node)) (drop 2 node) (drop 1 node)))
    (seq? node) (mapcat collect-strings node)
    :else []))

(deftest chart-top-menu-uses-base-background-class-test
  (let [menu (chart-core/chart-top-menu {:chart-options {:timeframes-dropdown-visible false
                                                          :selected-timeframe :1d
                                                          :chart-type-dropdown-visible false
                                                          :selected-chart-type :candlestick
                                                          :indicators-dropdown-visible false
                                                          :active-indicators {}}})
        classes (set (collect-all-classes menu))
        bg-colors (set (collect-background-colors menu))]
    (is (contains? classes "bg-base-100"))
    (is (not (contains? bg-colors "rgb(30, 41, 55)")))))

(deftest chart-canvas-uses-base-background-class-and-no-inline-legacy-color-test
  (let [canvas (chart-core/chart-canvas []
                                       :candlestick
                                       {}
                                       {:symbol "BTC"
                                        :timeframe-label "1D"
                                        :venue "Hyperopen"
                                        :candle-data []}
                                       :1d
                                       {})
        classes (set (class-values (get-in canvas [1 :class])))
        bg-colors (set (collect-background-colors canvas))]
    (is (contains? classes "bg-base-100"))
    (is (contains? classes "trading-chart-host"))
    (is (not (contains? bg-colors "rgb(30, 41, 55)")))))

(deftest chart-top-menu-dropdowns-use-high-z-opaque-tokenized-classes-test
  (let [menu (chart-core/chart-top-menu {:chart-options {:timeframes-dropdown-visible true
                                                          :selected-timeframe :1d
                                                          :chart-type-dropdown-visible true
                                                          :selected-chart-type :candlestick
                                                          :indicators-dropdown-visible true
                                                          :active-indicators {}}})
        classes (set (collect-all-classes menu))
        class-strings* (collect-class-strings menu)]
    (is (contains? classes "z-[120]"))
    (is (contains? classes "isolate"))
    (is (contains? classes "bg-base-100"))
    (is (contains? classes "opacity-100"))
    (is (not-any? spaced-class-string? class-strings*))))

(deftest chart-top-menu-selected-timeframe-uses-green-text-without-blue-background-test
  (let [menu (chart-core/chart-top-menu {:chart-options {:timeframes-dropdown-visible false
                                                          :selected-timeframe :1d
                                                          :chart-type-dropdown-visible false
                                                          :selected-chart-type :candlestick
                                                          :indicators-dropdown-visible false
                                                          :active-indicators {}}})
        classes (set (collect-all-classes menu))]
    (is (contains? classes "text-trading-green"))
    (is (not (contains? classes "bg-blue-600")))))

(deftest chart-top-menu-indicators-dropdown-renders-search-input-test
  (let [menu (chart-core/chart-top-menu {:chart-options {:timeframes-dropdown-visible false
                                                          :selected-timeframe :1d
                                                          :chart-type-dropdown-visible false
                                                          :selected-chart-type :candlestick
                                                          :indicators-dropdown-visible true
                                                          :indicators-search-term "sm"
                                                          :active-indicators {}}})
        search-node (find-first-node menu #(= "chart-indicators-search" (get-in % [1 :id])))]
    (is (some? search-node))
    (is (= [[:actions/update-indicators-search [:event.target/value]]]
           (get-in search-node [1 :on :input])))))

(deftest chart-top-menu-indicator-rows-use-full-row-click-add-action-test
  (let [menu (chart-core/chart-top-menu {:chart-options {:timeframes-dropdown-visible false
                                                          :selected-timeframe :1d
                                                          :chart-type-dropdown-visible false
                                                          :selected-chart-type :candlestick
                                                          :indicators-dropdown-visible true
                                                          :indicators-search-term ""
                                                          :active-indicators {}}})
        add-node (find-first-node menu #(= [[:actions/add-indicator :sma {:period 20}]]
                                            (get-in % [1 :on :click])))]
    (is (some? add-node))
    (is (= :button (first add-node)))))

(deftest chart-top-menu-active-indicator-rows-toggle-to-remove-action-test
  (let [menu (chart-core/chart-top-menu {:chart-options {:timeframes-dropdown-visible false
                                                          :selected-timeframe :1d
                                                          :chart-type-dropdown-visible false
                                                          :selected-chart-type :candlestick
                                                          :indicators-dropdown-visible true
                                                          :indicators-search-term ""
                                                          :active-indicators {:sma {:period 20}}}})
        remove-node (find-first-node menu #(= [[:actions/remove-indicator :sma]]
                                               (get-in % [1 :on :click])))]
    (is (some? remove-node))
    (is (= true (get-in remove-node [1 :aria-pressed])))))

(deftest chart-top-menu-chart-volume-row-renders-hide-action-when-volume-visible-test
  (let [menu (chart-core/chart-top-menu {:chart-options {:timeframes-dropdown-visible false
                                                          :selected-timeframe :1d
                                                          :chart-type-dropdown-visible false
                                                          :selected-chart-type :candlestick
                                                          :indicators-dropdown-visible true
                                                          :volume-visible? true
                                                          :active-indicators {}}})
        hide-node (find-first-node menu #(= [[:actions/hide-volume-indicator]]
                                             (get-in % [1 :on :click])))]
    (is (some? hide-node))
    (is (= true (get-in hide-node [1 :aria-pressed])))))

(deftest chart-top-menu-chart-volume-row-renders-show-action-when-volume-hidden-test
  (let [menu (chart-core/chart-top-menu {:chart-options {:timeframes-dropdown-visible false
                                                          :selected-timeframe :1d
                                                          :chart-type-dropdown-visible false
                                                          :selected-chart-type :candlestick
                                                          :indicators-dropdown-visible true
                                                          :volume-visible? false
                                                          :active-indicators {}}})
        show-node (find-first-node menu #(= [[:actions/show-volume-indicator]]
                                             (get-in % [1 :on :click])))]
    (is (some? show-node))
    (is (= false (get-in show-node [1 :aria-pressed])))))

(deftest chart-top-menu-renders-trades-freshness-cue-from-health-snapshot-test
  (let [menu (chart-core/chart-top-menu {:active-asset "BTC"
                                         :websocket-ui {:show-surface-freshness-cues? true}
                                         :websocket {:health {:generated-at-ms 5000
                                                              :streams {["trades" "BTC" nil nil nil]
                                                                        {:topic "trades"
                                                                         :status :live
                                                                         :subscribed? true
                                                                         :last-payload-at-ms 4700
                                                                         :stale-threshold-ms 10000}}}}
                                         :chart-options {:timeframes-dropdown-visible false
                                                         :selected-timeframe :1d
                                                         :chart-type-dropdown-visible false
                                                         :selected-chart-type :candlestick
                                                         :indicators-dropdown-visible false
                                                         :active-indicators {}}})
        cue-node (find-first-node menu #(= "chart-freshness-cue" (get-in % [1 :data-role])))]
    (is (some? cue-node))
    (is (str/includes? (str/join " " (collect-strings cue-node)) "Last tick 300ms ago"))))

(deftest chart-top-menu-renders-idle-freshness-message-when-awaiting-first-update-test
  (let [menu (chart-core/chart-top-menu {:active-asset "BTC"
                                         :websocket-ui {:show-surface-freshness-cues? true}
                                         :websocket {:health {:generated-at-ms 5000
                                                              :streams {["trades" "BTC" nil nil nil]
                                                                        {:topic "trades"
                                                                         :status :idle
                                                                         :subscribed? true
                                                                         :last-payload-at-ms nil
                                                                         :stale-threshold-ms 10000}}}}
                                         :chart-options {:timeframes-dropdown-visible false
                                                         :selected-timeframe :1d
                                                         :chart-type-dropdown-visible false
                                                         :selected-chart-type :candlestick
                                                         :indicators-dropdown-visible false
                                                         :active-indicators {}}})
        cue-node (find-first-node menu #(= "chart-freshness-cue" (get-in % [1 :data-role])))]
    (is (some? cue-node))
    (is (str/includes? (str/join " " (collect-strings cue-node)) "Waiting for first update..."))))

(deftest chart-top-menu-hides-freshness-cue-by-default-test
  (let [menu (chart-core/chart-top-menu {:active-asset "BTC"
                                         :websocket {:health {:generated-at-ms 5000
                                                              :streams {["trades" "BTC" nil nil nil]
                                                                        {:topic "trades"
                                                                         :status :live
                                                                         :subscribed? true
                                                                         :last-payload-at-ms 4700
                                                                         :stale-threshold-ms 10000}}}}
                                         :chart-options {:timeframes-dropdown-visible false
                                                         :selected-timeframe :1d
                                                         :chart-type-dropdown-visible false
                                                         :selected-chart-type :candlestick
                                                         :indicators-dropdown-visible false
                                                         :active-indicators {}}})
        cue-node (find-first-node menu #(= "chart-freshness-cue" (get-in % [1 :data-role])))]
    (is (nil? cue-node))))

(deftest chart-candle-pipeline-smoke-test
  (let [raw-data [{:t 1700000000000 :o "100.0" :h "105.0" :l "98.0" :c "103.0" :v "1000"}
                  {:t 1700000060000 :o "103.0" :h "106.0" :l "102.0" :c "104.0" :v "900"}]
        candles (data-processing/process-candle-data raw-data)
        options* (atom nil)
        data* (atom nil)
        series #js {:applyOptions (fn [opts]
                                    (reset! options* (js->clj opts :keywordize-keys true)))
                    :setData (fn [points]
                               (reset! data* (js->clj points :keywordize-keys true)))}]
    (is (vector? candles))
    (is (= 2 (count candles)))
    (chart-interop/set-series-data! series candles :candlestick {:price-decimals 2})
    (is (= "custom" (get-in @options* [:priceFormat :type])))
    (is (= "64,205.00" ((get-in @options* [:priceFormat :formatter]) 64205)))
    (is (= 2 (count @data*)))
    (is (= 103.0 (:close (first @data*))))))

(deftest trading-chart-view-memoizes-candle-transform-by-candles-and-timeframe-test
  (let [raw-candles [{:t 1700000000000 :o "100" :h "101" :l "99" :c "100" :v "10"}
                     {:t 1700000060000 :o "100" :h "102" :l "98" :c "101" :v "12"}]
        transformed [{:time 1700000000 :open 100 :high 101 :low 99 :close 100 :volume 10}]
        calls* (atom 0)
        base-state {:active-asset "BTC"
                    :candles {"BTC" {:1d raw-candles
                                     :1h raw-candles}}
                    :chart-options {:selected-timeframe :1d
                                    :selected-chart-type :candlestick
                                    :active-indicators {}}}
        timeframe-changed-state (assoc-in base-state [:chart-options :selected-timeframe] :1h)
        candles-changed-state (assoc-in timeframe-changed-state [:candles "BTC" :1h] (mapv identity raw-candles))]
    (derived-cache/reset-derived-cache!)
    (binding [derived-cache/*process-candle-data* (fn [_]
                                                    (swap! calls* inc)
                                                    transformed)]
      (chart-core/trading-chart-view base-state)
      (chart-core/trading-chart-view base-state)
      (is (= 1 @calls*) "unchanged candles/timeframe should hit memoized candle transform")

      (chart-core/trading-chart-view timeframe-changed-state)
      (is (= 2 @calls*) "timeframe change should recompute candle transform once")

      (chart-core/trading-chart-view timeframe-changed-state)
      (is (= 2 @calls*) "repeated render after timeframe change should hit cache")

      (chart-core/trading-chart-view candles-changed-state)
      (is (= 3 @calls*) "new candle vector identity should recompute candle transform"))))

(deftest chart-canvas-memoizes-indicator-outputs-by-candles-timeframe-and-config-test
  (let [candle-data [{:time 1700000000 :open 100 :high 101 :low 99 :close 100 :volume 10}
                     {:time 1700000060 :open 100 :high 102 :low 98 :close 101 :volume 12}]
        candle-data-new-identity (mapv identity candle-data)
        active-indicators-a {:sma {:period 20}}
        active-indicators-b {:sma {:period 50}}
        legend-meta {:symbol "BTC"
                     :timeframe-label "1D"
                     :venue "Hyperopen"
                     :candle-data candle-data}
        chart-runtime-options {}
        calls* (atom 0)]
    (derived-cache/reset-derived-cache!)
    (binding [derived-cache/*calculate-indicator* (fn [indicator-type _ config]
                                                    (swap! calls* inc)
                                                    {:series [{:id (name indicator-type)
                                                               :config config
                                                               :data [{:time 1700000000 :value 100}]}]
                                                     :markers [{:time 1700000000
                                                                :position "aboveBar"
                                                                :shape "circle"
                                                                :color "#10b981"}]})]
      (chart-core/chart-canvas candle-data :candlestick active-indicators-a legend-meta :1d chart-runtime-options)
      (chart-core/chart-canvas candle-data :candlestick active-indicators-a legend-meta :1d chart-runtime-options)
      (is (= 1 @calls*) "unchanged candles/timeframe/config should hit memoized indicator output")

      (chart-core/chart-canvas candle-data :candlestick active-indicators-b legend-meta :1d chart-runtime-options)
      (is (= 2 @calls*) "indicator config change should recompute indicator output once")

      (chart-core/chart-canvas candle-data :candlestick active-indicators-b legend-meta :4h chart-runtime-options)
      (is (= 3 @calls*) "timeframe change should recompute indicator output once")

      (chart-core/chart-canvas candle-data :candlestick active-indicators-b legend-meta :4h chart-runtime-options)
      (is (= 3 @calls*) "repeated render after timeframe change should hit cache")

      (chart-core/chart-canvas candle-data-new-identity :candlestick active-indicators-b legend-meta :4h chart-runtime-options)
      (is (= 4 @calls*) "new candle data identity should recompute indicator output"))))

(deftest trading-chart-view-passes-asset-candles-and-position-overlay-into-runtime-options-test
  (let [raw-candles [{:t 1700000000000 :o "100" :h "101" :l "99" :c "100" :v "10"}
                     {:t 1700000060000 :o "100" :h "102" :l "98" :c "101" :v "12"}]
        transformed [{:time 1700000000 :open 100 :high 101 :low 99 :close 100 :volume 10}]
        overlay-inputs* (atom nil)
        overlay-result {:side :long
                        :entry-price 100
                        :unrealized-pnl 5}
        captured-args (atom nil)
        state {:active-asset "BTC"
               :candles {"BTC" {:1d raw-candles}}
               :chart-options {:selected-timeframe :1d
                               :selected-chart-type :candlestick
                               :active-indicators {}}}]
    (derived-cache/reset-derived-cache!)
    (binding [derived-cache/*process-candle-data* (fn [_] transformed)]
      (with-redefs [trading-state/position-for-active-asset (fn [_]
                                                               {:coin "BTC"
                                                                :szi "1"
                                                                :entryPx "100"})
                    position-overlay-model/build-position-overlay (fn [opts]
                                                                    (reset! overlay-inputs* opts)
                                                                    overlay-result)
                    chart-core/chart-canvas (fn
                                              ([a b c d e f]
                                               (reset! captured-args [a b c d e f])
                                               [:div])
                                              ([a b c d e f g h]
                                               (reset! captured-args [a b c d e f g h])
                                               [:div]))]
        (chart-core/trading-chart-view state)))
    (let [runtime-options (nth @captured-args 5)
          persistence-deps (:persistence-deps runtime-options)]
      (is (= "BTC" (:asset persistence-deps)))
      (is (= transformed (:candles persistence-deps)))
      (is (= overlay-result (:position-overlay runtime-options)))
      (is (fn? (:on-liquidation-drag-preview runtime-options)))
      (is (fn? (:on-liquidation-drag-confirm runtime-options)))
      (is (= "BTC" (:active-asset @overlay-inputs*)))
      (is (= transformed (:candle-data @overlay-inputs*)))
      (is (= :1d (:selected-timeframe @overlay-inputs*)))
      (is (= [] (:fills @overlay-inputs*))))))

(deftest trading-chart-view-keeps-chart-drag-liquidation-preview-while-margin-modal-open-test
  (let [captured-args (atom nil)
        overlay-result {:side :long
                        :entry-price 100
                        :liquidation-price 90
                        :unrealized-pnl 4}
        state {:active-asset "BTC"
               :active-market {:dex "xyz"}
               :candles {"BTC" {:1d []}}
               :chart-options {:selected-timeframe :1d
                               :selected-chart-type :candlestick
                               :active-indicators {}}
               :positions-ui {:margin-modal {:open? true
                                             :position-key "BTC|xyz"
                                             :prefill-source :chart-liquidation-drag
                                             :prefill-liquidation-current-price "90"
                                             :prefill-liquidation-target-price "85"}}}]
    (with-redefs [trading-state/position-for-active-asset (fn [_]
                                                             {:coin "BTC"
                                                              :szi "1"
                                                              :entryPx "100"
                                                              :liquidationPx "90"})
                  position-overlay-model/build-position-overlay (fn [_]
                                                                  overlay-result)
                  chart-core/chart-canvas (fn
                                            ([a b c d e f]
                                             (reset! captured-args [a b c d e f])
                                             [:div])
                                            ([a b c d e f g h]
                                             (reset! captured-args [a b c d e f g h])
                                             [:div]))]
      (chart-core/trading-chart-view state))
    (let [runtime-options (nth @captured-args 5)
          overlay (:position-overlay runtime-options)]
      (is (= 85 (:liquidation-price overlay)))
      (is (= 90 (:current-liquidation-price overlay)))
      (is (= 100 (:entry-price overlay)))
      (is (= :long (:side overlay))))))

(deftest trading-chart-liquidation-drag-callback-dispatches-margin-modal-prefill-test
  (let [captured-args (atom nil)
        dispatch-calls (atom [])
        state {:active-asset "BTC"
               :active-market {:dex "xyz"}
               :candles {"BTC" {:1d []}}
               :chart-options {:selected-timeframe :1d
                               :selected-chart-type :candlestick
                               :active-indicators {}}}]
    (with-redefs [trading-state/position-for-active-asset (fn [_]
                                                             {:coin "BTC"
                                                              :szi "1"
                                                              :entryPx "100"
                                                              :liquidationPx "90"})
                  position-overlay-model/build-position-overlay (fn [_]
                                                                  {:side :long
                                                                   :entry-price 100
                                                                   :liquidation-price 90})
                  chart-core/chart-canvas (fn
                                            ([a b c d e f]
                                             (reset! captured-args [a b c d e f])
                                             [:div])
                                            ([a b c d e f g h]
                                             (reset! captured-args [a b c d e f g h])
                                             [:div]))]
      (chart-core/trading-chart-view state))
    (let [runtime-options (nth @captured-args 5)
          callback (:on-liquidation-drag-confirm runtime-options)
          anchor {:left 10 :right 20 :top 30 :bottom 40 :width 10 :height 10
                  :viewport-width 1200 :viewport-height 800}]
      (binding [replicant-core/*dispatch* (fn [event actions]
                                            (swap! dispatch-calls conj [event actions]))]
        (callback {:mode :add
                   :amount 2.5
                   :current-liquidation-price 90
                   :target-liquidation-price 85
                   :anchor anchor}))
      (let [[event actions] (first @dispatch-calls)
            select-action (first (first actions))
            open-action (first (second actions))]
        (is (= :chart-liquidation-drag-margin-confirm (:replicant/trigger event)))
        (is (= :actions/select-account-info-tab select-action))
        (is (= :positions (second (first actions))))
        (is (= :actions/open-position-margin-modal open-action))
        (is (= :chart-liquidation-drag
               (get-in actions [1 1 :prefill-source])))
        (is (= :add
               (get-in actions [1 1 :prefill-margin-mode])))
        (is (= 2.5
               (get-in actions [1 1 :prefill-margin-amount])))
        (is (= "xyz"
               (get-in actions [1 1 :dex])))
        (is (= anchor
               (get-in actions [1 2])))))))

(deftest trading-chart-liquidation-drag-preview-callback-dispatches-margin-modal-prefill-test
  (let [captured-args (atom nil)
        dispatch-calls (atom [])
        state {:active-asset "BTC"
               :active-market {:dex "xyz"}
               :candles {"BTC" {:1d []}}
               :chart-options {:selected-timeframe :1d
                               :selected-chart-type :candlestick
                               :active-indicators {}}}]
    (with-redefs [trading-state/position-for-active-asset (fn [_]
                                                             {:coin "BTC"
                                                              :szi "1"
                                                              :entryPx "100"
                                                              :liquidationPx "90"})
                  position-overlay-model/build-position-overlay (fn [_]
                                                                  {:side :long
                                                                   :entry-price 100
                                                                   :liquidation-price 90})
                  chart-core/chart-canvas (fn
                                            ([a b c d e f]
                                             (reset! captured-args [a b c d e f])
                                             [:div])
                                            ([a b c d e f g h]
                                             (reset! captured-args [a b c d e f g h])
                                             [:div]))]
      (chart-core/trading-chart-view state))
    (let [runtime-options (nth @captured-args 5)
          callback (:on-liquidation-drag-preview runtime-options)
          anchor {:left 10 :right 20 :top 30 :bottom 40 :width 10 :height 10
                  :viewport-width 1200 :viewport-height 800}]
      (binding [replicant-core/*dispatch* (fn [event actions]
                                            (swap! dispatch-calls conj [event actions]))]
        (callback {:mode :add
                   :amount 2.5
                   :current-liquidation-price 90
                   :target-liquidation-price 85
                   :anchor anchor}))
      (let [[event actions] (first @dispatch-calls)
            select-action (first (first actions))
            open-action (first (second actions))]
        (is (= :chart-liquidation-drag-margin-preview (:replicant/trigger event)))
        (is (= :actions/select-account-info-tab select-action))
        (is (= :positions (second (first actions))))
        (is (= :actions/open-position-margin-modal open-action))
        (is (= :chart-liquidation-drag
               (get-in actions [1 1 :prefill-source])))
        (is (= :add
               (get-in actions [1 1 :prefill-margin-mode])))
        (is (= 2.5
               (get-in actions [1 1 :prefill-margin-amount])))
        (is (= "xyz"
               (get-in actions [1 1 :dex])))
        (is (= anchor
               (get-in actions [1 2])))))))
