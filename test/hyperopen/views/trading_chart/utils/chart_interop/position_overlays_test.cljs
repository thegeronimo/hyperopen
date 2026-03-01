(ns hyperopen.views.trading-chart.utils.chart-interop.position-overlays-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.utils.chart-interop.position-overlays :as position-overlays]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]))

(defn- parse-px
  [value]
  (when (string? value)
    (let [num (js/parseFloat value)]
      (when-not (js/isNaN num)
        num))))

(defn- find-inline-badge
  [root text-fragment]
  (fake-dom/find-dom-node root
                          (fn [node]
                            (let [style (.-style node)
                                  display (when style (aget style "display"))
                                  left (when style (aget style "left"))
                                  text (str/join " " (fake-dom/collect-text-content node))]
                              (and (= "inline-flex" display)
                                   (string? left)
                                   (str/includes? text text-fragment))))))

(defn- find-pnl-segment-line
  [root]
  (fake-dom/find-dom-node root
                          (fn [node]
                            (let [style (.-style node)
                                  border-top (when style (aget style "borderTop"))]
                              (and (string? border-top)
                                   (str/includes? border-top "2px solid"))))))

(defn- find-liquidation-drag-handle
  [root]
  (fake-dom/find-dom-node root
                          (fn [node]
                            (= "true" (aget node "data-position-liq-drag-handle")))))

(defn- find-liquidation-drag-hit
  [root]
  (fake-dom/find-dom-node root
                          (fn [node]
                            (= "true" (aget node "data-position-liq-drag-hit")))))

(defn- build-chart-fixture
  [{:keys [width price-to-y time-to-x y-to-price]
    :or {width 320
         price-to-y (fn [price] (- 220 price))
         y-to-price (fn [y] (- 220 y))
         time-to-x (fn [time]
                     (case time
                       1700000000 48
                       1700003600 228
                       nil))}}]
  (let [subscribe-fn (fn [_] nil)
        unsubscribe-calls* (atom 0)
        unsubscribe-fn (fn [_]
                         (swap! unsubscribe-calls* inc))
        time-scale #js {:subscribeVisibleTimeRangeChange subscribe-fn
                        :unsubscribeVisibleTimeRangeChange unsubscribe-fn
                        :subscribeVisibleLogicalRangeChange subscribe-fn
                        :unsubscribeVisibleLogicalRangeChange unsubscribe-fn
                        :subscribeSizeChange subscribe-fn
                        :unsubscribeSizeChange unsubscribe-fn
                        :timeToCoordinate time-to-x}
        main-series #js {:priceToCoordinate price-to-y
                         :coordinateToPrice y-to-price
                         :subscribeDataChanged subscribe-fn
                         :unsubscribeDataChanged unsubscribe-fn}
        chart #js {:timeScale (fn [] time-scale)
                   :paneSize (fn [_pane-index]
                               #js {:width width})}
        chart-obj #js {:chart chart
                       :mainSeries main-series}
        document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        window-target (fake-dom/make-fake-element "window")]
    (set! (.-clientWidth container) width)
    (set! (.-clientHeight container) 240)
    {:chart-obj chart-obj
     :document document
     :container container
     :window-target window-target
     :unsubscribe-calls* unsubscribe-calls*}))

(deftest position-overlays-render-pnl-and-liquidation-rows-and-clear-test
  (let [{:keys [chart-obj document container unsubscribe-calls*]}
        (build-chart-fixture {})
        overlay {:side :short
                 :entry-price 100
                 :unrealized-pnl -12.4
                 :abs-size 1.25
                 :liquidation-price 130
                 :entry-time 1700000000
                 :entry-time-ms 1700000000000
                 :latest-time 1700003600}]
    (position-overlays/sync-position-overlays!
     chart-obj
     container
     overlay
     {:document document
      :format-price (fn [price _raw]
                      (str price))
      :format-size (fn [size]
                     (str size))})
    (is (= 1 (alength (.-children container))))
    (let [overlay-root (aget (.-children container) 0)
          text (str/join " " (fake-dom/collect-text-content overlay-root))
          pnl-badge (find-inline-badge overlay-root "PNL -$12.40 | 1.25")
          liq-badge (find-inline-badge overlay-root "Liq. Price")
          pnl-left (some-> pnl-badge .-style (aget "left") parse-px)
          liq-left (some-> liq-badge .-style (aget "left") parse-px)]
      (is (str/includes? text "PNL -$12.40 | 1.25"))
      (is (str/includes? text "Liq. Price"))
      (is (str/includes? text "$130"))
      (is (number? pnl-left))
      (is (number? liq-left))
      (is (<= 90 pnl-left 140) "PNL badge should stay inside readable zone, away from edges")
      (is (<= 80 liq-left 130) "Liq badge should stay inside readable zone, away from edges"))
    (position-overlays/clear-position-overlays! chart-obj)
    (is (= 0 (alength (.-children container))))
    (is (= 4 @unsubscribe-calls*))))

(deftest position-overlays-sync-skips-rerender-for-identical-overlay-reference-test
  (let [price-to-coordinate-calls* (atom 0)
        {:keys [chart-obj document container]}
        (build-chart-fixture {:price-to-y (fn [price]
                                            (swap! price-to-coordinate-calls* inc)
                                            (- 220 price))})
        overlay {:side :long
                 :entry-price 101
                 :unrealized-pnl 5.5
                 :abs-size 2
                 :liquidation-price 80
                 :entry-time 1700000000
                 :entry-time-ms 1700000000000
                 :latest-time 1700003600}
        format-price (fn [price _raw] (str price))
        format-size (fn [size] (str size))]
    (position-overlays/sync-position-overlays!
     chart-obj
     container
     overlay
     {:document document
      :format-price format-price
      :format-size format-size})
    (is (= 2 @price-to-coordinate-calls*))
    (position-overlays/sync-position-overlays!
     chart-obj
     container
     overlay
     {:document document
      :format-price format-price
      :format-size format-size})
    (is (= 2 @price-to-coordinate-calls*))
    (position-overlays/sync-position-overlays!
     chart-obj
     container
     (assoc overlay :unrealized-pnl 8.0)
     {:document document
      :format-price format-price
      :format-size format-size})
    (is (= 4 @price-to-coordinate-calls*))
    (position-overlays/clear-position-overlays! chart-obj)))

(deftest position-overlays-do-not-render-secondary-side-glyphs-test
  (let [{:keys [chart-obj document container]}
        (build-chart-fixture {})
        overlay {:side :long
                 :entry-price 95
                 :unrealized-pnl 7.1
                 :abs-size 1
                 :entry-time 1700000000
                 :entry-time-ms 1700000000000
                 :latest-time 1700003600}]
    (position-overlays/sync-position-overlays!
     chart-obj
     container
     overlay
     {:document document})
    (let [overlay-root (aget (.-children container) 0)
          side-glyph (fake-dom/find-dom-node overlay-root
                                             (fn [node]
                                               (let [text (some-> (.-textContent node) str str/trim)
                                                     style (.-style node)
                                                     rounded? (= "999px" (when style (aget style "borderRadius")))]
                                                 (and rounded?
                                                      (contains? #{"L" "S"} text)))))]
      (is (= 1 (alength (.-children container))))
      (is (nil? side-glyph)))
    (position-overlays/clear-position-overlays! chart-obj)))

(deftest position-overlays-hide-pnl-segment-when-time-span-is-tiny-test
  (let [{:keys [chart-obj document container]}
        (build-chart-fixture {:time-to-x (fn [time]
                                           (case time
                                             1700000000 104
                                             1700003600 112
                                             nil))})
        overlay {:side :short
                 :entry-price 100
                 :unrealized-pnl -1.2
                 :abs-size 0.5
                 :entry-time 1700000000
                 :entry-time-ms 1700000000000
                 :latest-time 1700003600}]
    (position-overlays/sync-position-overlays!
     chart-obj
     container
     overlay
     {:document document})
    (let [overlay-root (aget (.-children container) 0)
          pnl-badge (find-inline-badge overlay-root "PNL -$1.20 | 0.50")
          pnl-line (find-pnl-segment-line overlay-root)]
      (is (some? pnl-badge))
      (is (nil? pnl-line)))
    (position-overlays/clear-position-overlays! chart-obj)))

(deftest position-overlays-keep-pnl-badge-left-aligned-when-position-is-near-right-edge-test
  (let [{:keys [chart-obj document container]}
        (build-chart-fixture {:time-to-x (fn [time]
                                           (case time
                                             1700000000 252
                                             1700003600 304
                                             nil))})
        overlay {:side :short
                 :entry-price 100
                 :unrealized-pnl -3.6
                 :abs-size 2
                 :entry-time 1700000000
                 :entry-time-ms 1700000000000
                 :latest-time 1700003600}]
    (position-overlays/sync-position-overlays!
     chart-obj
     container
     overlay
     {:document document})
    (let [overlay-root (aget (.-children container) 0)
          pnl-badge (find-inline-badge overlay-root "PNL -$3.60 | 2.00")
          pnl-left (some-> pnl-badge .-style (aget "left") parse-px)]
      (is (number? pnl-left))
      (is (<= 90 pnl-left 145)
          "PNL badge should remain left-aligned even when entry/latest is near right edge"))
    (position-overlays/clear-position-overlays! chart-obj)))

(deftest position-overlays-renders-margin-delta-label-for-preview-liquidation-target-test
  (let [{:keys [chart-obj document container]}
        (build-chart-fixture {})
        overlay {:side :long
                 :entry-price 100
                 :unrealized-pnl 2
                 :abs-size 2
                 :liquidation-price 95
                 :current-liquidation-price 100
                 :entry-time 1700000000
                 :entry-time-ms 1700000000000
                 :latest-time 1700003600}]
    (position-overlays/sync-position-overlays!
     chart-obj
     container
     overlay
     {:document document})
    (let [overlay-root (aget (.-children container) 0)
          text (str/join " " (fake-dom/collect-text-content overlay-root))]
      (is (str/includes? text "Liq. Price"))
      (is (str/includes? text "Add $10.00 Margin")))
    (position-overlays/clear-position-overlays! chart-obj)))

(deftest position-overlays-liquidation-drag-emits-live-preview-suggestion-on-move-test
  (let [preview-calls* (atom [])
        {:keys [chart-obj document container window-target]}
        (build-chart-fixture {})
        overlay {:side :long
                 :entry-price 100
                 :unrealized-pnl 2.0
                 :abs-size 2
                 :liquidation-price 100
                 :entry-time 1700000000
                 :entry-time-ms 1700000000000
                 :latest-time 1700003600}]
    (position-overlays/sync-position-overlays!
     chart-obj
     container
     overlay
     {:document document
      :window window-target
      :on-liquidation-drag-preview (fn [payload]
                                     (swap! preview-calls* conj payload))})
    (let [overlay-root (aget (.-children container) 0)
          drag-hit (find-liquidation-drag-hit overlay-root)]
      (is (some? drag-hit))
      (fake-dom/dispatch-dom-event-with-payload! drag-hit
                                                 "pointerdown"
                                                 #js {:clientX 64
                                                      :clientY 120})
      (fake-dom/dispatch-dom-event-with-payload! window-target
                                                 "pointermove"
                                                 #js {:clientX 64
                                                      :clientY 125}))
    (let [payload (first @preview-calls*)]
      (is (= :add (:mode payload)))
      (is (= 10 (:amount payload)))
      (is (= 100 (:current-liquidation-price payload)))
      (is (= 95 (:target-liquidation-price payload)))
      (is (map? (:anchor payload))))
    (position-overlays/clear-position-overlays! chart-obj)))

(deftest position-overlays-liquidation-drag-emits-margin-confirmation-suggestion-test
  (let [confirm-calls* (atom [])
        {:keys [chart-obj document container window-target]}
        (build-chart-fixture {})
        overlay {:side :long
                 :entry-price 100
                 :unrealized-pnl 2.0
                 :abs-size 2
                 :liquidation-price 100
                 :entry-time 1700000000
                 :entry-time-ms 1700000000000
                 :latest-time 1700003600}]
    (position-overlays/sync-position-overlays!
     chart-obj
     container
     overlay
     {:document document
      :window window-target
      :on-liquidation-drag-confirm (fn [payload]
                                     (swap! confirm-calls* conj payload))})
    (let [overlay-root (aget (.-children container) 0)
          drag-handle (find-liquidation-drag-handle overlay-root)
          drag-hit (find-liquidation-drag-hit overlay-root)]
      (is (some? drag-handle))
      (is (some? drag-hit))
      (fake-dom/dispatch-dom-event-with-payload! drag-hit
                                                 "pointerdown"
                                                 #js {:clientX 64
                                                      :clientY 120})
      (fake-dom/dispatch-dom-event-with-payload! window-target
                                                 "pointermove"
                                                 #js {:clientX 64
                                                      :clientY 125})
      (fake-dom/dispatch-dom-event-with-payload! window-target
                                                 "pointerup"
                                                 #js {:clientX 64
                                                      :clientY 125}))
    (let [payload (first @confirm-calls*)]
      (is (= :add (:mode payload)))
      (is (= 10 (:amount payload)))
      (is (= 100 (:current-liquidation-price payload)))
      (is (= 95 (:target-liquidation-price payload)))
      (is (map? (:anchor payload))))
    (position-overlays/clear-position-overlays! chart-obj)))
