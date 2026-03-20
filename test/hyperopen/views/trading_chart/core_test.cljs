(ns hyperopen.views.trading-chart.core-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [replicant.core :as replicant-core]
            [hyperopen.system :as app-system]
            [hyperopen.state.trading :as trading-state]
            [hyperopen.views.trading-chart.derived-cache :as derived-cache]
            [hyperopen.views.trading-chart.core :as chart-core]
            [hyperopen.views.trading-chart.runtime-state :as chart-runtime]
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

(defn- chart-canvas-render-hook
  [canvas]
  (get-in canvas [1 :replicant/on-render]))

(defn- render-chart-canvas!
  [canvas lifecycle node]
  ((chart-canvas-render-hook canvas)
   {:replicant/life-cycle lifecycle
    :replicant/node node}))

(defn- fake-time-scale
  [visible-range set-visible-range-calls]
  (doto #js {}
    (aset "getVisibleLogicalRange" (fn [] visible-range))
    (aset "setVisibleLogicalRange" (fn [range]
                                     (swap! set-visible-range-calls conj range)))))

(defn- fake-chart
  [time-scale remove-series-calls remove-chart-calls]
  (doto #js {}
    (aset "timeScale" (fn [] time-scale))
    (aset "removeSeries" (fn [series]
                           (swap! remove-series-calls conj series)))
    (aset "remove" (fn []
                     (swap! remove-chart-calls inc)))))

(defn- fake-chart-obj
  [{:keys [main-series volume-series indicator-series chart]}]
  (doto #js {}
    (aset "chart" chart)
    (aset "mainSeries" main-series)
    (aset "volumeSeries" volume-series)
    (aset "indicatorSeries" indicator-series)))

(defn- expose-arity!
  [f arity]
  (aset f (str "cljs$core$IFn$_invoke$arity$" arity) f)
  f)

(def noop-2
  (expose-arity!
   (fn [_ _]
     nil)
   2))

(def noop-4
  (expose-arity!
   (fn [_ _ _ _]
     nil)
   4))

(def ^:dynamic *chart-obj* nil)
(def ^:dynamic *legend-control* nil)
(def ^:dynamic *new-main-series* nil)
(def ^:dynamic *volume-creations* nil)
(def ^:dynamic *indicator-creations* nil)
(def ^:dynamic *legend-creations* nil)
(def ^:dynamic *restore-calls* nil)
(def ^:dynamic *restore-counter* nil)
(def ^:dynamic *persistence-calls* nil)
(def ^:dynamic *persistence-counter* nil)
(def ^:dynamic *add-series-calls* nil)
(def ^:dynamic *series-data-calls* nil)
(def ^:dynamic *baseline-sync-calls* nil)

(def stub-create-chart-with-volume-and-series!
  (expose-arity!
   (fn [container chart-type data opts]
     (when *volume-creations*
       (reset! *volume-creations* [container chart-type data opts]))
     *chart-obj*)
   4))

(def stub-unexpected-volume-chart-creation!
  (expose-arity!
   (fn [_ _ _ _]
     (throw (js/Error. "unexpected volume path")))
   4))

(def stub-create-chart-with-indicators!
  (expose-arity!
   (fn [container chart-type data indicators opts]
     (when *indicator-creations*
       (reset! *indicator-creations* [container chart-type data indicators opts]))
     *chart-obj*)
   5))

(def stub-create-legend!
  (expose-arity!
   (fn [container chart legend-meta deps]
     (when *legend-creations*
       (reset! *legend-creations* [container chart legend-meta deps]))
     *legend-control*)
   4))

(def fresh-legend-control
  (expose-arity!
   (fn [_ _ _ _]
     #js {:update (fn [_] nil)
          :destroy (fn [] nil)})
   4))

(def stub-apply-default-visible-range!
  (expose-arity!
   (fn [chart candles]
     (when *restore-calls*
       (swap! *restore-calls* conj [:default chart candles]))
     nil)
   2))

(def stub-apply-persisted-visible-range!
  (expose-arity!
   (fn [chart timeframe deps]
     (when *restore-calls*
       (swap! *restore-calls* conj [:persisted chart timeframe deps]))
     (when *restore-counter*
       (swap! *restore-counter* inc))
     (js/Promise.resolve nil))
   3))

(def stub-subscribe-visible-range-persistence!
  (expose-arity!
   (fn [chart timeframe deps]
     (when *persistence-calls*
       (swap! *persistence-calls* conj [chart timeframe deps]))
     (when *persistence-counter*
       (swap! *persistence-counter* inc))
     (fn [] nil))
   3))

(def stub-add-series!
  (expose-arity!
   (fn [chart next-type]
     (when *add-series-calls*
       (swap! *add-series-calls* conj [chart next-type]))
     *new-main-series*)
   2))

(def stub-set-series-data!
  (expose-arity!
   (fn [series data type options]
     (when *series-data-calls*
       (swap! *series-data-calls* conj [series data type options]))
     nil)
   4))

(def stub-sync-baseline-base-value-subscription!
  (expose-arity!
   (fn [chart next-type]
     (when *baseline-sync-calls*
       (swap! *baseline-sync-calls* conj [chart next-type]))
     nil)
   2))

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
    (is (contains? classes "min-w-0"))
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
    (is (contains? classes "min-w-0"))
    (is (contains? classes "overflow-hidden"))
    (is (contains? classes "trading-chart-host"))
    (is (not (contains? bg-colors "rgb(30, 41, 55)")))))

(deftest trading-chart-view-clips-chart-panel-overflow-test
  (let [view (chart-core/trading-chart-view {:active-asset "BTC"
                                             :active-market {:price-decimals 2}
                                             :candles {"BTC" {:1d []}}
                                             :chart-options {:selected-timeframe :1d
                                                             :selected-chart-type :candlestick
                                                             :active-indicators {}}})
        panel (find-first-node view #(= "chart-panel" (get-in % [1 :data-parity-id])))
        panel-classes (set (class-values (get-in panel [1 :class])))
        shell-classes (set (class-values (get-in (nth panel 2) [1 :class])))]
    (is (some? panel))
    (is (contains? panel-classes "overflow-hidden"))
    (is (contains? panel-classes "min-h-0"))
    (is (contains? panel-classes "min-w-0"))
    (is (contains? shell-classes "h-full"))
    (is (contains? shell-classes "overflow-hidden"))
    (is (contains? shell-classes "min-w-0"))
    (is (contains? shell-classes "min-h-0"))))

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

(deftest chart-canvas-mount-uses-volume-chart-creation-when-no-indicators-test
  (let [node #js {}
        candle-data [{:time 1700000000 :open 100 :high 101 :low 99 :close 100 :volume 10}]
        legend-meta {:symbol "BTC"
                     :timeframe-label "1D"
                     :venue "Hyperopen"
                     :candle-data candle-data}
        canvas (chart-core/chart-canvas candle-data :candlestick {} legend-meta :1d {})
        volume-creations (atom [])
        indicator-creations (atom [])
        legend-creations (atom [])
        restore-calls (atom [])
        persistence-calls (atom [])
        remove-series-calls (atom [])
        remove-chart-calls (atom 0)
        set-visible-range-calls (atom [])
        visible-range #js {:from 1 :to 3}
        chart (fake-chart (fake-time-scale visible-range set-visible-range-calls)
                          remove-series-calls
                          remove-chart-calls)
        chart-obj (fake-chart-obj {:chart chart
                                   :main-series #js {:id "main"}
                                   :volume-series #js {:id "volume"}
                                   :indicator-series #js []})
        legend-control (doto #js {}
                         (aset "update" (fn [_] nil))
                         (aset "destroy" (fn [] nil)))]
    (binding [*chart-obj* chart-obj
              *legend-control* legend-control
              *volume-creations* volume-creations
              *indicator-creations* indicator-creations
              *legend-creations* legend-creations
              *restore-calls* restore-calls
              *persistence-calls* persistence-calls]
      (with-redefs [chart-interop/create-chart-with-volume-and-series! stub-create-chart-with-volume-and-series!
                    chart-interop/create-chart-with-indicators! stub-create-chart-with-indicators!
                    chart-interop/create-legend! stub-create-legend!
                    chart-interop/set-main-series-markers! noop-2
                    chart-interop/sync-baseline-base-value-subscription! noop-2
                    chart-interop/sync-position-overlays! noop-4
                    chart-interop/sync-open-order-overlays! noop-4
                    chart-interop/sync-volume-indicator-overlay! noop-4
                    chart-interop/sync-chart-navigation-overlay! noop-4
                    chart-interop/apply-default-visible-range! stub-apply-default-visible-range!
                    chart-interop/apply-persisted-visible-range! stub-apply-persisted-visible-range!
                    chart-interop/subscribe-visible-range-persistence! stub-subscribe-visible-range-persistence!]
        (render-chart-canvas! canvas :replicant.life-cycle/mount node)))
    (is (= 4 (count @volume-creations)))
    (is (empty? @indicator-creations))
    (is (= [node chart legend-meta nil] @legend-creations))
    (is (= 2 (count @restore-calls)))
    (is (= 1 (count @persistence-calls)))
    (is (= chart-obj (:chart-obj (chart-runtime/get-state node))))
    (is (= legend-control (:legend-control (chart-runtime/get-state node))))
    (is (= :candlestick (:chart-type (chart-runtime/get-state node))))
    (is (true? (:visible-range-restore-tried? (chart-runtime/get-state node))))
    (is (true? (:visible-range-persistence-subscribed? (chart-runtime/get-state node))))
    (is (empty? @set-visible-range-calls))
    (chart-runtime/clear-state! node)))

(deftest chart-canvas-mount-uses-indicator-chart-creation-when-derived-indicators-exist-test
  (let [node #js {}
        candle-data [{:time 1700000000 :open 100 :high 101 :low 99 :close 100 :volume 10}]
        legend-meta {:symbol "BTC"
                     :timeframe-label "1D"
                     :venue "Hyperopen"
                     :candle-data candle-data}
        chart (fake-chart (fake-time-scale nil (atom [])) (atom []) (atom 0))
        chart-obj (fake-chart-obj {:chart chart
                                   :main-series #js {:id "main"}
                                   :volume-series #js {:id "volume"}
                                   :indicator-series #js []})
        indicator-creations (atom nil)]
    (binding [*chart-obj* chart-obj
              *indicator-creations* indicator-creations]
      (with-redefs [derived-cache/memoized-indicator-outputs
                    (fn [& _]
                      {:indicators-data [{:id :sma}]
                       :indicator-series []
                       :indicator-markers []})
                    chart-interop/create-chart-with-volume-and-series! stub-unexpected-volume-chart-creation!
                    chart-interop/create-chart-with-indicators! stub-create-chart-with-indicators!
                    chart-interop/create-legend! fresh-legend-control
                    chart-interop/set-main-series-markers! noop-2
                    chart-interop/sync-baseline-base-value-subscription! noop-2
                    chart-interop/sync-position-overlays! noop-4
                    chart-interop/sync-open-order-overlays! noop-4
                    chart-interop/sync-volume-indicator-overlay! noop-4
                    chart-interop/sync-chart-navigation-overlay! noop-4
                    chart-interop/apply-default-visible-range! noop-2
                    chart-interop/apply-persisted-visible-range! stub-apply-persisted-visible-range!
                    chart-interop/subscribe-visible-range-persistence! stub-subscribe-visible-range-persistence!]
        (render-chart-canvas! (chart-core/chart-canvas candle-data
                                                       :candlestick
                                                       {:sma {:period 20}}
                                                       legend-meta
                                                       :1d
                                                       {})
                              :replicant.life-cycle/mount
                              node)))
    (is (= 5 (count @indicator-creations)))
    (chart-runtime/clear-state! node)))

(deftest chart-canvas-update-swaps-main-series-and-preserves-visible-range-on-chart-type-change-test
  (let [node #js {}
        candle-data [{:time 1700000000 :open 100 :high 101 :low 99 :close 100 :volume 10}]
        legend-meta {:symbol "BTC"
                     :timeframe-label "1D"
                     :venue "Hyperopen"
                     :candle-data candle-data}
        remove-series-calls (atom [])
        remove-chart-calls (atom 0)
        set-visible-range-calls (atom [])
        visible-range #js {:from 2 :to 8}
        chart (fake-chart (fake-time-scale visible-range set-visible-range-calls)
                          remove-series-calls
                          remove-chart-calls)
        original-main-series #js {:id "candlestick"}
        new-main-series #js {:id "area"}
        chart-obj (fake-chart-obj {:chart chart
                                   :main-series original-main-series
                                   :volume-series #js {:id "volume"}
                                   :indicator-series #js []})
        add-series-calls (atom [])
        series-data-calls (atom [])
        baseline-sync-calls (atom [])]
    (chart-runtime/set-state! node {:chart-obj chart-obj
                                    :legend-control #js {:update (fn [_] nil)
                                                         :destroy (fn [] nil)}
                                    :chart-type :candlestick
                                    :visible-range-restore-tried? true
                                    :visible-range-restore-token 1
                                    :visible-range-interaction-epoch 0
                                    :visible-range-persistence-subscribed? true
                                    :visible-range-cleanup nil})
    (binding [*add-series-calls* add-series-calls
              *series-data-calls* series-data-calls
              *baseline-sync-calls* baseline-sync-calls
              *new-main-series* new-main-series]
      (with-redefs [chart-interop/add-series! stub-add-series!
                    chart-interop/set-series-data! stub-set-series-data!
                    chart-interop/sync-baseline-base-value-subscription! stub-sync-baseline-base-value-subscription!
                    chart-interop/set-volume-data! noop-2
                    chart-interop/set-main-series-markers! noop-2
                    chart-interop/sync-position-overlays! noop-4
                    chart-interop/sync-open-order-overlays! noop-4
                    chart-interop/sync-volume-indicator-overlay! noop-4
                    chart-interop/sync-chart-navigation-overlay! noop-4]
        (render-chart-canvas! (chart-core/chart-canvas candle-data
                                                       :area
                                                       {}
                                                       legend-meta
                                                       :1d
                                                       {:series-options {:price-decimals 2}})
                              :replicant.life-cycle/update
                              node)))
    (is (= [[chart :area]] @add-series-calls))
    (is (= [original-main-series] @remove-series-calls))
    (is (= [[new-main-series candle-data :area {:price-decimals 2}]] @series-data-calls))
    (is (= [visible-range] @set-visible-range-calls))
    (is (= :area (:chart-type (chart-runtime/get-state node))))
    (is (= new-main-series (.-mainSeries ^js (:chart-obj (chart-runtime/get-state node)))))
    (is (= 3 (count @baseline-sync-calls)))
    (chart-runtime/clear-state! node)))

(deftest chart-canvas-update-restores-and-subscribes-visible-range-only-once-test
  (let [node #js {}
        candle-data [{:time 1700000000 :open 100 :high 101 :low 99 :close 100 :volume 10}]
        legend-meta {:symbol "BTC"
                     :timeframe-label "1D"
                     :venue "Hyperopen"
                     :candle-data candle-data}
        chart (fake-chart (fake-time-scale nil (atom [])) (atom []) (atom 0))
        chart-obj (fake-chart-obj {:chart chart
                                   :main-series #js {:id "main"}
                                   :volume-series #js {:id "volume"}
                                   :indicator-series #js []})
        restore-calls (atom 0)
        persistence-calls (atom 0)]
    (binding [*chart-obj* chart-obj
              *restore-counter* restore-calls
              *persistence-counter* persistence-calls]
      (with-redefs [chart-interop/create-chart-with-volume-and-series! stub-create-chart-with-volume-and-series!
                    chart-interop/create-legend! fresh-legend-control
                    chart-interop/set-main-series-markers! noop-2
                    chart-interop/sync-baseline-base-value-subscription! noop-2
                    chart-interop/sync-position-overlays! noop-4
                    chart-interop/sync-open-order-overlays! noop-4
                    chart-interop/sync-volume-indicator-overlay! noop-4
                    chart-interop/sync-chart-navigation-overlay! noop-4
                    chart-interop/apply-default-visible-range! noop-2
                    chart-interop/apply-persisted-visible-range! stub-apply-persisted-visible-range!
                    chart-interop/subscribe-visible-range-persistence! stub-subscribe-visible-range-persistence!
                    chart-interop/set-series-data! noop-4
                    chart-interop/set-volume-data! noop-2]
        (let [mount-canvas (chart-core/chart-canvas candle-data :candlestick {} legend-meta :1d {})]
          (render-chart-canvas! mount-canvas :replicant.life-cycle/mount node)
          (render-chart-canvas! mount-canvas :replicant.life-cycle/update node))))
    (is (= 1 @restore-calls))
    (is (= 1 @persistence-calls))
    (chart-runtime/clear-state! node)))

(deftest chart-canvas-unmount-cleans-up-runtime-artifacts-in-order-test
  (let [node #js {}
        order-calls (atom [])
        cleanup-called (atom 0)
        chart (fake-chart (fake-time-scale nil (atom [])) (atom []) (atom 0))
        chart-obj (fake-chart-obj {:chart chart
                                   :main-series #js {:id "main"}
                                   :volume-series #js {:id "volume"}
                                   :indicator-series #js []})
        legend-control (doto #js {}
                         (aset "update" (fn [_] nil))
                         (aset "destroy" (fn []
                                           (swap! order-calls conj :legend-destroy))))]
    (chart-runtime/set-state! node {:chart-obj chart-obj
                                    :legend-control legend-control
                                    :chart-type :candlestick
                                    :visible-range-restore-tried? true
                                    :visible-range-restore-token 1
                                    :visible-range-interaction-epoch 0
                                    :visible-range-persistence-subscribed? true
                                    :visible-range-cleanup (fn []
                                                            (swap! cleanup-called inc)
                                                            (swap! order-calls conj :visible-range-cleanup))})
    (with-redefs [chart-interop/clear-open-order-overlays! (fn [_]
                                                             (swap! order-calls conj :clear-open-orders))
                  chart-interop/clear-position-overlays! (fn [_]
                                                          (swap! order-calls conj :clear-position-overlays))
                  chart-interop/clear-volume-indicator-overlay! (fn [_]
                                                                  (swap! order-calls conj :clear-volume-overlay))
                  chart-interop/clear-chart-navigation-overlay! (fn [_]
                                                                  (swap! order-calls conj :clear-navigation-overlay))
                  chart-interop/clear-baseline-base-value-subscription! (fn [_]
                                                                          (swap! order-calls conj :clear-baseline))]
      (render-chart-canvas! (chart-core/chart-canvas [] :candlestick {} {:symbol "BTC"
                                                                          :timeframe-label "1D"
                                                                          :venue "Hyperopen"
                                                                          :candle-data []}
                                                   :1d
                                                   {})
                            :replicant.life-cycle/unmount
                            node))
    (is (= [:legend-destroy
            :clear-open-orders
            :clear-position-overlays
            :clear-volume-overlay
            :clear-navigation-overlay
            :clear-baseline
            :visible-range-cleanup]
           @order-calls))
    (is (= 1 @cleanup-called))
    (is (= {} (chart-runtime/get-state node)))))

(deftest trading-chart-view-reuses-overlay-input-identities-when-chart-inputs-are-unchanged-test
  (let [captured-args* (atom [])
        raw-candles [{:t 1700000000000 :o "100" :h "101" :l "99" :c "100" :v "10"}
                     {:t 1700000060000 :o "100" :h "102" :l "98" :c "101" :v "12"}]
        transformed [{:time 1700000000 :open 100 :high 101 :low 99 :close 100 :volume 10}
                     {:time 1700000060 :open 100 :high 102 :low 98 :close 101 :volume 12}]
        state {:active-asset "BTC"
               :active-market {:price-decimals 2 :dex "xyz"}
               :candles {"BTC" {:1d raw-candles}}
               :chart-options {:selected-timeframe :1d
                               :selected-chart-type :candlestick
                               :active-indicators {}
                               :volume-visible? true}
               :orders {:open-orders [{:coin "BTC" :oid 1 :px "100" :sz "1" :side "B"}]
                        :fills [{:coin "BTC" :time 1700000000000 :side "B" :sz "1" :startPosition "0"}]}
               :asset-selector {:market-by-key {}}
               :positions-ui {}}]
    (derived-cache/reset-derived-cache!)
    (binding [derived-cache/*process-candle-data* (fn [_] transformed)]
      (with-redefs [trading-state/position-for-active-asset (fn [_]
                                                               {:coin "BTC"
                                                                :szi "1"
                                                                :entryPx "100"
                                                                :liquidationPx "90"})
                    chart-core/chart-canvas (fn
                                              ([a b c d e f]
                                               (swap! captured-args* conj [a b c d e f])
                                               [:div])
                                              ([a b c d e f g h]
                                               (swap! captured-args* conj [a b c d e f g h])
                                               [:div]))]
        (chart-core/trading-chart-view state)
        (chart-core/trading-chart-view state)))
    (let [[[first-candle-data _ _ first-legend-meta _ first-runtime-options first-open-orders first-on-cancel-order]
           [second-candle-data _ _ second-legend-meta _ second-runtime-options second-open-orders second-on-cancel-order]]
          @captured-args*]
      (is (identical? first-candle-data second-candle-data))
      (is (identical? first-legend-meta second-legend-meta))
      (is (identical? first-runtime-options second-runtime-options))
      (is (identical? first-open-orders second-open-orders))
      (is (identical? first-on-cancel-order second-on-cancel-order))
      (is (identical? (:position-overlay first-runtime-options)
                      (:position-overlay second-runtime-options)))
      (is (identical? (:on-hide-volume-indicator first-runtime-options)
                      (:on-hide-volume-indicator second-runtime-options)))
      (is (identical? (:on-liquidation-drag-preview first-runtime-options)
                      (:on-liquidation-drag-preview second-runtime-options)))
      (is (identical? (:on-liquidation-drag-confirm first-runtime-options)
                      (:on-liquidation-drag-confirm second-runtime-options))))))

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

(deftest trading-chart-view-threads-fill-marker-preference-into-runtime-options-test
  (let [captured-args (atom nil)
        state {:active-asset "BTC"
               :candles {"BTC" {:1d []}}
               :chart-options {:selected-timeframe :1d
                               :selected-chart-type :candlestick
                               :active-indicators {}}
               :trading-settings {:show-fill-markers? true}}]
    (with-redefs [chart-core/chart-canvas (fn
                                            ([a b c d e f]
                                             (reset! captured-args [a b c d e f])
                                             [:div])
                                            ([a b c d e f g h]
                                             (reset! captured-args [a b c d e f g h])
                                             [:div]))]
      (chart-core/trading-chart-view state))
    (let [runtime-options (nth @captured-args 5)]
      (is (true? (:show-fill-markers? runtime-options))))))

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

(deftest trading-chart-open-order-cancel-callback-captures-render-dispatch-test
  (let [captured-args (atom nil)
        dispatch-calls (atom [])
        state {:active-asset "BTC"
               :candles {"BTC" {:1d []}}
               :chart-options {:selected-timeframe :1d
                               :selected-chart-type :candlestick
                               :active-indicators {}}}
        order {:coin "BTC"
               :oid 42
               :side "A"
               :type "limit"
               :sz "1"
               :px "100"}]
    (binding [replicant-core/*dispatch* (fn [event actions]
                                          (swap! dispatch-calls conj [event actions]))]
      (with-redefs [chart-core/chart-canvas (fn
                                              ([a b c d e f]
                                               (reset! captured-args [a b c d e f])
                                               [:div])
                                              ([a b c d e f g h]
                                               (reset! captured-args [a b c d e f g h])
                                               [:div]))]
        (chart-core/trading-chart-view state)))
    (let [on-cancel-order (nth @captured-args 7)]
      (is (fn? on-cancel-order))
      (on-cancel-order order)
      (let [[event actions] (first @dispatch-calls)]
        (is (= :chart-order-overlay-cancel (:replicant/trigger event)))
        (is (= [[:actions/cancel-order order]] actions))))))

(deftest trading-chart-open-order-cancel-callback-falls-back-to-runtime-dispatch-test
  (let [captured-args (atom nil)
        store (atom {:wallet {:connected? true
                              :address "0xabc"
                              :agent {:status :not-ready
                                      :storage-mode :session}}})
        original-store app-system/store
        state {:active-asset "BTC"
               :candles {"BTC" {:1d []}}
               :chart-options {:selected-timeframe :1d
                               :selected-chart-type :candlestick
                               :active-indicators {}}}
        order {:coin "BTC"
               :oid 43
               :side "A"
               :type "limit"
               :sz "1"
               :px "101"}]
    (set! app-system/store store)
    (binding [replicant-core/*dispatch* nil]
      (with-redefs [chart-core/chart-canvas (fn
                                              ([a b c d e f]
                                               (reset! captured-args [a b c d e f])
                                               [:div])
                                              ([a b c d e f g h]
                                               (reset! captured-args [a b c d e f g h])
                                               [:div]))]
        (chart-core/trading-chart-view state)))
    (let [on-cancel-order (nth @captured-args 7)]
      (is (fn? on-cancel-order))
      (on-cancel-order order)
      (is (= "Enable trading before cancelling orders."
             (get-in @store [:orders :cancel-error]))))
    (set! app-system/store original-store)))

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
      (binding [replicant-core/*dispatch* (fn [event actions]
                                            (swap! dispatch-calls conj [event actions]))]
        (chart-core/trading-chart-view state)))
    (let [runtime-options (nth @captured-args 5)
          callback (:on-liquidation-drag-confirm runtime-options)
          anchor {:left 10 :right 20 :top 30 :bottom 40 :width 10 :height 10
                  :viewport-width 1200 :viewport-height 800}]
      (callback {:mode :add
                 :amount 2.5
                 :current-liquidation-price 90
                 :target-liquidation-price 85
                 :anchor anchor})
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
      (binding [replicant-core/*dispatch* (fn [event actions]
                                            (swap! dispatch-calls conj [event actions]))]
        (chart-core/trading-chart-view state)))
    (let [runtime-options (nth @captured-args 5)
          callback (:on-liquidation-drag-preview runtime-options)
          anchor {:left 10 :right 20 :top 30 :bottom 40 :width 10 :height 10
                  :viewport-width 1200 :viewport-height 800}]
      (callback {:mode :add
                 :amount 2.5
                 :current-liquidation-price 90
                 :target-liquidation-price 85
                 :anchor anchor})
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
