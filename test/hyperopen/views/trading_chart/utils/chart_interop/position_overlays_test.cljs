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
                                   (str/includes? border-top "1px dashed"))))))

(defn- find-pnl-price-chip
  [root]
  (fake-dom/find-dom-node root
                          (fn [node]
                            (= "true" (aget node "data-position-pnl-price-chip")))))

(defn- find-liquidation-price-chip
  [root]
  (fake-dom/find-dom-node root
                          (fn [node]
                            (= "true" (aget node "data-position-liq-price-chip")))))

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

(defn- first-text-node
  [node]
  (first (fake-dom/collect-text-nodes node)))

(defn- text-node-text
  [text-node]
  (or (some-> text-node .-data)
      (some-> text-node .-nodeValue)
      ""))

(defn- emit-overlay-repaint!
  [{:keys [subscription-callbacks*]}]
  (when-let [callback (or (get @subscription-callbacks* :size-change)
                          (get @subscription-callbacks* :data-changed)
                          (get @subscription-callbacks* :visible-time-range)
                          (get @subscription-callbacks* :visible-logical-range))]
    (callback nil)))

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
  (let [subscription-callbacks* (atom {})
        subscribe-fn (fn [k]
                       (fn [callback]
                         (swap! subscription-callbacks* assoc k callback)))
        unsubscribe-calls* (atom 0)
        unsubscribe-fn (fn [k]
                         (fn [callback]
                           (swap! unsubscribe-calls* inc)
                           (swap! subscription-callbacks*
                                  (fn [callbacks]
                                    (if (identical? callback (get callbacks k))
                                      (dissoc callbacks k)
                                      callbacks)))))
        width* (atom width)
        time-scale #js {:subscribeVisibleTimeRangeChange (subscribe-fn :visible-time-range)
                        :unsubscribeVisibleTimeRangeChange (unsubscribe-fn :visible-time-range)
                        :subscribeVisibleLogicalRangeChange (subscribe-fn :visible-logical-range)
                        :unsubscribeVisibleLogicalRangeChange (unsubscribe-fn :visible-logical-range)
                        :subscribeSizeChange (subscribe-fn :size-change)
                        :unsubscribeSizeChange (unsubscribe-fn :size-change)
                        :timeToCoordinate time-to-x}
        main-series #js {:priceToCoordinate price-to-y
                         :coordinateToPrice y-to-price
                         :subscribeDataChanged (subscribe-fn :data-changed)
                         :unsubscribeDataChanged (unsubscribe-fn :data-changed)}
        chart #js {:timeScale (fn [] time-scale)
                   :paneSize (fn [_pane-index]
                               #js {:width @width*})}
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
     :chart chart
     :main-series main-series
     :subscription-callbacks* subscription-callbacks*
     :window-target window-target
     :width* width*
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
          pnl-chip (find-pnl-price-chip overlay-root)
          pnl-chip-text (some->> pnl-chip
                                 fake-dom/collect-text-content
                                 (str/join " ")
                                 str/trim)
          liq-badge (find-inline-badge overlay-root "Liq. Price")
          liq-chip (find-liquidation-price-chip overlay-root)
          liq-chip-text (some->> liq-chip
                                 fake-dom/collect-text-content
                                 (str/join " ")
                                 str/trim)
          pnl-left (some-> pnl-badge .-style (aget "left") parse-px)
          liq-left (some-> liq-badge .-style (aget "left") parse-px)]
      (is (str/includes? text "PNL -$12.40 | 1.25"))
      (is (str/includes? text "Liq. Price"))
      (is (str/includes? text "$130"))
      (is (some? pnl-chip))
      (is (= "100" pnl-chip-text))
      (is (some? liq-chip))
      (is (= "130" liq-chip-text))
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

(deftest position-overlays-repaint-retains-dom-nodes-and-patches-coordinates-test
  (let [{:keys [chart-obj document container main-series] :as fixture}
        (build-chart-fixture {})
        overlay {:side :short
                 :entry-price 100
                 :unrealized-pnl -2.5
                 :abs-size 1.5
                 :liquidation-price 130
                 :entry-time 1700000000
                 :entry-time-ms 1700000000000
                 :latest-time 1700003600}]
    (position-overlays/sync-position-overlays!
     chart-obj
     container
     overlay
     {:document document
      :format-price (fn [price _raw] (str price))
      :format-size (fn [size] (str size))})
    (let [overlay-root (aget (.-children container) 0)
          pnl-chip-before (find-pnl-price-chip overlay-root)
          liq-chip-before (find-liquidation-price-chip overlay-root)
          drag-hit-before (find-liquidation-drag-hit overlay-root)
          pnl-row-before (some-> pnl-chip-before .-parentNode)
          liq-row-before (some-> liq-chip-before .-parentNode)
          pnl-top-before (some-> pnl-row-before .-style (aget "top"))
          liq-top-before (some-> liq-row-before .-style (aget "top"))]
      (aset main-series
            "priceToCoordinate"
            (fn [price]
              (- 260 price)))
      (emit-overlay-repaint! fixture)
      (let [pnl-chip-after (find-pnl-price-chip overlay-root)
            liq-chip-after (find-liquidation-price-chip overlay-root)
            drag-hit-after (find-liquidation-drag-hit overlay-root)
            pnl-top-after (some-> pnl-row-before .-style (aget "top"))
            liq-top-after (some-> liq-row-before .-style (aget "top"))]
        (is (identical? pnl-chip-before pnl-chip-after))
        (is (identical? liq-chip-before liq-chip-after))
        (is (identical? drag-hit-before drag-hit-after))
        (is (= "160px" pnl-top-after))
        (is (= "130px" liq-top-after))
        (is (not= pnl-top-before pnl-top-after))
        (is (not= liq-top-before liq-top-after))))
    (position-overlays/clear-position-overlays! chart-obj)))

(deftest position-overlays-coalesces-subscription-repaints-per-frame-test
  (let [{:keys [chart-obj document container subscription-callbacks*]}
        (build-chart-fixture {})
        overlay {:side :short
                 :entry-price 100
                 :unrealized-pnl -2.5
                 :abs-size 1.5
                 :liquidation-price 130
                 :entry-time 1700000000
                 :entry-time-ms 1700000000000
                 :latest-time 1700003600}
        render-calls* (atom 0)
        next-frame-id* (atom 0)
        scheduled-frame* (atom nil)]
    (with-redefs [position-overlays/render-overlays! (fn [_]
                                                       (swap! render-calls* inc))
                  position-overlays/*schedule-overlay-repaint-frame!* (fn [callback]
                                                                        (let [frame-id (swap! next-frame-id* inc)
                                                                              wrapped (fn []
                                                                                        (reset! scheduled-frame* nil)
                                                                                        (callback))]
                                                                          (reset! scheduled-frame* {:id frame-id
                                                                                                    :callback wrapped})
                                                                          frame-id))
                  position-overlays/*cancel-overlay-repaint-frame!* (fn [frame-id]
                                                                      (when (= frame-id (:id @scheduled-frame*))
                                                                        (reset! scheduled-frame* nil)))]
      (position-overlays/sync-position-overlays!
       chart-obj
       container
       overlay
       {:document document
        :format-price (fn [price _raw]
                        (str price))
        :format-size (fn [size]
                       (str size))})
      (is (= 1 @render-calls*))
      ((get @subscription-callbacks* :visible-time-range) nil)
      ((get @subscription-callbacks* :size-change) nil)
      ((get @subscription-callbacks* :data-changed) nil)
      (is (= 1 (:id @scheduled-frame*)))
      (is (= 1 @render-calls*))
      ((:callback @scheduled-frame*))
      (is (= 2 @render-calls*))
      (is (nil? @scheduled-frame*))
      (position-overlays/clear-position-overlays! chart-obj))))

(deftest position-overlays-sync-patches-retained-nodes-in-place-test
  (let [{:keys [chart-obj document container]}
        (build-chart-fixture {})
        overlay {:side :short
                 :entry-price 100
                 :unrealized-pnl -2.5
                 :abs-size 1.5
                 :liquidation-price 130
                 :entry-time 1700000000
                 :entry-time-ms 1700000000000
                 :latest-time 1700003600}
        next-overlay {:side :short
                      :entry-price 105
                      :unrealized-pnl 8.0
                      :abs-size 3.0
                      :liquidation-price 125
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
    (let [overlay-root (aget (.-children container) 0)
          pnl-chip-before (find-pnl-price-chip overlay-root)
          liq-chip-before (find-liquidation-price-chip overlay-root)
          pnl-line-before (find-pnl-segment-line overlay-root)]
      (position-overlays/sync-position-overlays!
       chart-obj
       container
       next-overlay
       {:document document
        :format-price format-price
        :format-size format-size})
      (let [text (str/join " " (fake-dom/collect-text-content overlay-root))
            pnl-chip-after (find-pnl-price-chip overlay-root)
            liq-chip-after (find-liquidation-price-chip overlay-root)
            pnl-line-after (find-pnl-segment-line overlay-root)
            pnl-chip-text (some->> pnl-chip-after
                                   fake-dom/collect-text-content
                                   (str/join " ")
                                   str/trim)
            liq-chip-text (some->> liq-chip-after
                                   fake-dom/collect-text-content
                                   (str/join " ")
                                   str/trim)
            border-top (some-> pnl-line-after .-style (aget "borderTop"))]
        (is (identical? pnl-chip-before pnl-chip-after))
        (is (identical? liq-chip-before liq-chip-after))
        (is (identical? pnl-line-before pnl-line-after))
        (is (str/includes? text "PNL +$8.00 | 3"))
        (is (= "105" pnl-chip-text))
        (is (= "125" liq-chip-text))
        (is (str/includes? border-top "34, 201, 151"))))
    (position-overlays/clear-position-overlays! chart-obj)))

(deftest position-overlays-sync-retains-text-nodes-while-patching-text-test
  (let [{:keys [chart-obj document container]}
        (build-chart-fixture {})
        overlay {:side :short
                 :entry-price 100
                 :unrealized-pnl -2.5
                 :abs-size 1.5
                 :current-liquidation-price 100
                 :liquidation-price 95
                 :entry-time 1700000000
                 :entry-time-ms 1700000000000
                 :latest-time 1700003600}
        next-overlay {:side :short
                      :entry-price 105
                      :unrealized-pnl 8.0
                      :abs-size 3.0
                      :current-liquidation-price 100
                      :liquidation-price 97.5
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
    (let [overlay-root (aget (.-children container) 0)
          pnl-badge-before (find-inline-badge overlay-root "PNL -$2.50 | 1.5")
          pnl-chip-before (find-pnl-price-chip overlay-root)
          liq-badge-before (find-inline-badge overlay-root "Liq. Price")
          liq-chip-before (find-liquidation-price-chip overlay-root)
          pnl-badge-text-node-before (first-text-node pnl-badge-before)
          pnl-chip-text-node-before (first-text-node pnl-chip-before)
          [liq-label-text-node-before
           liq-price-text-node-before
           liq-drag-note-text-node-before] (vec (fake-dom/collect-text-nodes liq-badge-before))
          liq-chip-text-node-before (first-text-node liq-chip-before)]
      (position-overlays/sync-position-overlays!
       chart-obj
       container
       next-overlay
       {:document document
        :format-price format-price
        :format-size format-size})
      (let [pnl-badge-after (find-inline-badge overlay-root "PNL +$8.00 | 3")
            pnl-chip-after (find-pnl-price-chip overlay-root)
            liq-badge-after (find-inline-badge overlay-root "Liq. Price")
            liq-chip-after (find-liquidation-price-chip overlay-root)
            pnl-badge-text-node-after (first-text-node pnl-badge-after)
            pnl-chip-text-node-after (first-text-node pnl-chip-after)
            [liq-label-text-node-after
             liq-price-text-node-after
             liq-drag-note-text-node-after] (vec (fake-dom/collect-text-nodes liq-badge-after))
            liq-chip-text-node-after (first-text-node liq-chip-after)]
        (is (identical? pnl-badge-text-node-before pnl-badge-text-node-after))
        (is (identical? pnl-chip-text-node-before pnl-chip-text-node-after))
        (is (identical? liq-label-text-node-before liq-label-text-node-after))
        (is (identical? liq-price-text-node-before liq-price-text-node-after))
        (is (identical? liq-drag-note-text-node-before liq-drag-note-text-node-after))
        (is (identical? liq-chip-text-node-before liq-chip-text-node-after))
        (is (= "PNL +$8.00 | 3" (text-node-text pnl-badge-text-node-after)))
        (is (= "105" (text-node-text pnl-chip-text-node-after)))
        (is (= "Liq. Price" (text-node-text liq-label-text-node-after)))
        (is (= "$97.5" (text-node-text liq-price-text-node-after)))
        (is (= "Remove $7.50 Margin" (text-node-text liq-drag-note-text-node-after)))
        (is (= "97.5" (text-node-text liq-chip-text-node-after)))))
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

(deftest position-overlays-renders-pnl-segment-when-time-span-is-tiny-test
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
          pnl-line (find-pnl-segment-line overlay-root)
          pnl-chip (find-pnl-price-chip overlay-root)
          pnl-left (some-> pnl-line .-style (aget "left"))
          pnl-right (some-> pnl-line .-style (aget "right"))]
      (is (some? pnl-badge))
      (is (some? pnl-line))
      (is (some? pnl-chip))
      (is (= "0px" pnl-left))
      (is (= "0px" pnl-right)))
    (position-overlays/clear-position-overlays! chart-obj)))

(deftest position-overlays-colors-line-and-chip-by-pnl-sign-test
  (let [cases [{:label "positive pnl uses green tone even for short side"
                :overlay {:side :short
                          :entry-price 102
                          :unrealized-pnl 3.4
                          :abs-size 1
                          :entry-time 1700000000
                          :entry-time-ms 1700000000000
                          :latest-time 1700003600}
                :expected-color "34, 201, 151"}
               {:label "negative pnl uses red tone even for long side"
                :overlay {:side :long
                          :entry-price 98
                          :unrealized-pnl -4.1
                          :abs-size 1
                          :entry-time 1700000000
                          :entry-time-ms 1700000000000
                          :latest-time 1700003600}
                :expected-color "227, 95, 120"}]]
    (doseq [{:keys [label overlay expected-color]} cases]
      (let [{:keys [chart-obj document container]} (build-chart-fixture {})]
        (position-overlays/sync-position-overlays!
         chart-obj
         container
         overlay
         {:document document})
        (let [overlay-root (aget (.-children container) 0)
              pnl-line (find-pnl-segment-line overlay-root)
              pnl-chip (find-pnl-price-chip overlay-root)
              border-top (some-> pnl-line .-style (aget "borderTop"))
              chip-bg (some-> pnl-chip .-style (aget "background"))]
          (is (some? pnl-line) label)
          (is (some? pnl-chip) label)
          (is (str/includes? border-top expected-color) label)
          (is (str/includes? chip-bg expected-color) label))
        (position-overlays/clear-position-overlays! chart-obj)))))

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
