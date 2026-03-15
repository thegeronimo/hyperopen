(ns hyperopen.views.chart.d3.runtime-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.chart.d3.runtime :as runtime]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]))

(def ^:private test-theme
  {:data-role-prefix "portfolio-chart"
   :baseline-stroke "#28414a"
   :baseline-stroke-width 0.8
   :hover-line-stroke "#9fb3be"
   :line-stroke-width 1.4
   :line-linecap "round"
   :line-linejoin "round"
   :tooltip-border-color "rgba(98, 114, 130, 0.65)"
   :tooltip-background "rgba(16, 25, 38, 0.95)"})

(defn- build-tooltip
  [{:keys [index]} _series]
  {:timestamp (str "T" index)
   :metric-label "Returns"
   :metric-value (str "V" index)
   :value-classes ["text-[#16d6a1]"]
   :benchmark-values [{:coin "SPY"
                       :label "SPY"
                       :value (str "B" index)
                       :stroke "#f2cf66"}]})

(defn- chart-spec
  ([]
   (chart-spec {}))
  ([overrides]
   (merge {:surface :portfolio
           :axis-kind :returns
           :time-range :month
           :points [{:time-ms 1 :value 0.2 :x-ratio 0 :y-ratio 0.7}
                    {:time-ms 2 :value 0.6 :x-ratio 1 :y-ratio 0.3}]
           :series [{:id :strategy
                     :label "Portfolio"
                     :stroke "#f5f7f8"
                     :points [{:time-ms 1 :value 0.2 :x-ratio 0 :y-ratio 0.7}
                              {:time-ms 2 :value 0.6 :x-ratio 1 :y-ratio 0.3}]}
                    {:id :benchmark-0
                     :coin "SPY"
                     :label "SPY"
                     :stroke "#f2cf66"
                     :points [{:time-ms 1 :value 0.1 :x-ratio 0 :y-ratio 0.8}
                              {:time-ms 2 :value 0.5 :x-ratio 1 :y-ratio 0.4}]}]
           :y-ticks [{:value 1 :y-ratio 0}
                     {:value 0 :y-ratio 1}]
           :theme test-theme
           :build-tooltip build-tooltip}
          overrides)))

(defn- data-role-node
  [root data-role]
  (fake-dom/find-dom-node root #(and (= 1 (.-nodeType %))
                                     (= data-role (.getAttribute % "data-role")))))

(defn- child-series-order
  [series-layer]
  (mapv #(.getAttribute % "data-series-id")
        (array-seq (.-children series-layer))))

(defn- install-fake-raf!
  []
  (let [callbacks* (atom {})
        next-id* (atom 0)
        original-raf (aget js/globalThis "requestAnimationFrame")
        original-cancel-raf (aget js/globalThis "cancelAnimationFrame")]
    (aset js/globalThis
          "requestAnimationFrame"
          (fn [callback]
            (let [frame-id (swap! next-id* inc)]
              (swap! callbacks* assoc frame-id callback)
              frame-id)))
    (aset js/globalThis
          "cancelAnimationFrame"
          (fn [frame-id]
            (swap! callbacks* dissoc frame-id)))
    {:flush! (fn []
               (let [pending-callbacks (map second (sort-by first @callbacks*))]
                 (reset! callbacks* {})
                 (doseq [callback pending-callbacks]
                   (callback))))
     :restore! (fn []
                 (aset js/globalThis "requestAnimationFrame" original-raf)
                 (aset js/globalThis "cancelAnimationFrame" original-cancel-raf))}))

(deftest on-render-mounts-and-updates-hover-dom-locally-test
  (let [{:keys [flush! restore!]} (install-fake-raf!)
        document (fake-dom/make-fake-document)
        host (doto (fake-dom/make-fake-element "div")
               (aset "ownerDocument" document))
        remember-count* (atom 0)
        remembered* (atom nil)
        mount! (runtime/on-render (chart-spec {}))]
    (try
      (set! (.-clientWidth host) 400)
      (set! (.-clientHeight host) 240)
      (mount! {:replicant/life-cycle :replicant.life-cycle/mount
               :replicant/node host
               :replicant/remember (fn [memory]
                                     (swap! remember-count* inc)
                                     (reset! remembered* memory))})
      (let [svg (data-role-node host "portfolio-chart-svg")
            path (data-role-node host "portfolio-chart-path")
            benchmark-path (data-role-node host "portfolio-chart-path-benchmark-0")
            hover-line (data-role-node host "portfolio-chart-hover-line")
            tooltip (data-role-node host "portfolio-chart-hover-tooltip")]
        (is (= 1 @remember-count*))
        (is (some? (:runtime @remembered*)))
        (is (= "0 0 400 240" (.getAttribute svg "viewBox")))
        (is (string? (.getAttribute path "d")))
        (is (string? (.getAttribute benchmark-path "d")))
        (is (= "none" (aget (.-style tooltip) "display")))
        (fake-dom/dispatch-dom-event-with-payload! host "pointermove" #js {:clientX 380})
        (is (= "none" (aget (.-style tooltip) "display")))
        (flush!)
        (is (= 1 @remember-count*))
        (is (= "" (aget (.-style tooltip) "display")))
        (is (= "" (aget (.-style hover-line) "display")))
        (is (= "380" (.getAttribute hover-line "x1")))
        (is (= "translate(calc(-100% - 8px), -50%)"
               (aget (.-style tooltip) "transform")))
        (is (str/includes? (str/join " " (fake-dom/collect-text-content tooltip))
                           "T1 Returns V1"))
        (fake-dom/dispatch-dom-event! host "pointerleave")
        (is (= "none" (aget (.-style tooltip) "display")))
        (is (= "none" (aget (.-style hover-line) "display"))))
      (finally
        (restore!)))))

(deftest on-render-batches-pointer-moves-and-reuses-tooltip-rows-for-same-index-test
  (let [{:keys [flush! restore!]} (install-fake-raf!)
        document (fake-dom/make-fake-document)
        host (doto (fake-dom/make-fake-element "div")
               (aset "ownerDocument" document))
        remembered* (atom nil)
        mount! (runtime/on-render (chart-spec {}))]
    (try
      (set! (.-clientWidth host) 400)
      (set! (.-clientHeight host) 240)
      (mount! {:replicant/life-cycle :replicant.life-cycle/mount
               :replicant/node host
               :replicant/remember (fn [memory]
                                     (reset! remembered* memory))})
      (let [hover-line (data-role-node host "portfolio-chart-hover-line")
            tooltip (data-role-node host "portfolio-chart-hover-tooltip")]
        (fake-dom/dispatch-dom-event-with-payload! host "pointermove" #js {:clientX 320})
        (fake-dom/dispatch-dom-event-with-payload! host "pointermove" #js {:clientX 384})
        (is (= "none" (aget (.-style tooltip) "display")))
        (flush!)
        (let [row-before (data-role-node host "portfolio-chart-hover-tooltip-benchmark-row-SPY")]
          (is (= "" (aget (.-style tooltip) "display")))
          (is (= "384" (.getAttribute hover-line "x1")))
          (fake-dom/dispatch-dom-event-with-payload! host "pointermove" #js {:clientX 360})
          (flush!)
          (is (= "360" (.getAttribute hover-line "x1")))
          (is (identical? row-before
                          (data-role-node host "portfolio-chart-hover-tooltip-benchmark-row-SPY")))))
      (finally
        (restore!)))))

(deftest on-render-update-rekeys-series-roots-and-rebuilds-area-structure-test
  (let [document (fake-dom/make-fake-document)
        host (doto (fake-dom/make-fake-element "div")
               (aset "ownerDocument" document))
        remembered* (atom nil)
        mount! (runtime/on-render (chart-spec {}))
        update-spec (chart-spec {:series [{:id :benchmark-1
                                           :coin "QQQ"
                                           :label "QQQ"
                                           :stroke "#7dd3fc"
                                           :points [{:time-ms 1 :value 0.3 :x-ratio 0 :y-ratio 0.75}
                                                    {:time-ms 2 :value 0.9 :x-ratio 1 :y-ratio 0.25}]}
                                          {:id :strategy
                                           :label "Portfolio"
                                           :stroke "#f5f7f8"
                                           :area-positive-fill "rgba(22, 214, 161, 0.24)"
                                           :area-negative-fill "rgba(237, 112, 136, 0.24)"
                                           :zero-y-ratio 0.55
                                           :points [{:time-ms 1 :value -0.2 :x-ratio 0 :y-ratio 0.8}
                                                    {:time-ms 2 :value 0.6 :x-ratio 1 :y-ratio 0.3}]}]
                                :points [{:time-ms 1 :value -0.2 :x-ratio 0 :y-ratio 0.8}
                                         {:time-ms 2 :value 0.6 :x-ratio 1 :y-ratio 0.3}]})
        update! (runtime/on-render update-spec)]
    (set! (.-clientWidth host) 420)
    (set! (.-clientHeight host) 260)
    (mount! {:replicant/life-cycle :replicant.life-cycle/mount
             :replicant/node host
             :replicant/remember (fn [memory]
                                   (reset! remembered* memory))})
    (update! {:replicant/life-cycle :replicant.life-cycle/update
              :replicant/node host
              :replicant/memory @remembered*
              :replicant/remember (fn [memory]
                                    (reset! remembered* memory))})
    (let [series-layer (data-role-node host "portfolio-chart-series-layer")
          series-roots (array-seq (.-children series-layer))
          strategy-root (some #(when (= "strategy" (.getAttribute % "data-series-id"))
                                 %)
                              series-roots)
          positive-area (data-role-node host "portfolio-chart-area-positive")
          negative-area (data-role-node host "portfolio-chart-area-negative")
          qqq-path (data-role-node host "portfolio-chart-path-benchmark-1")
          spy-path (data-role-node host "portfolio-chart-path-benchmark-0")]
      (is (= ["benchmark-1" "strategy"]
             (child-series-order series-layer)))
      (is (= "split-zero" (.getAttribute strategy-root "data-area-type")))
      (is (some? positive-area))
      (is (some? negative-area))
      (is (= "rgba(22, 214, 161, 0.24)"
             (.getAttribute positive-area "fill")))
      (is (= "rgba(237, 112, 136, 0.24)"
             (.getAttribute negative-area "fill")))
      (is (some? qqq-path))
      (is (nil? spy-path)))))

(deftest on-render-observes-resize-and-cleans-up-on-unmount-test
  (let [{:keys [restore!]} (install-fake-raf!)
        document (fake-dom/make-fake-document)
        host (doto (fake-dom/make-fake-element "div")
               (aset "ownerDocument" document))
        remembered* (atom nil)
        resize-callback* (atom nil)
        observed-node* (atom nil)
        disconnect-count* (atom 0)
        original-resize-observer (aget js/globalThis "ResizeObserver")
        mount! (runtime/on-render (chart-spec {}))]
    (set! (.-clientWidth host) 360)
    (set! (.-clientHeight host) 220)
    (aset js/globalThis
          "ResizeObserver"
          (fn [callback]
            (reset! resize-callback* callback)
            #js {:observe (fn [node]
                            (reset! observed-node* node))
                 :disconnect (fn []
                               (swap! disconnect-count* inc))}))
    (try
      (mount! {:replicant/life-cycle :replicant.life-cycle/mount
               :replicant/node host
               :replicant/remember (fn [memory]
                                     (reset! remembered* memory))})
      (let [svg (data-role-node host "portfolio-chart-svg")]
        (is (identical? host @observed-node*))
        (is (fn? @resize-callback*))
        (set! (.-clientWidth host) 512)
        (set! (.-clientHeight host) 256)
        (@resize-callback* #js [] nil)
        (is (= "0 0 512 256" (.getAttribute svg "viewBox"))))
      (mount! {:replicant/life-cycle :replicant.life-cycle/unmount
               :replicant/node host
               :replicant/memory @remembered*})
      (is (= 1 @disconnect-count*))
      (is (nil? (aget (.-listeners host) "pointerenter")))
      (is (nil? (aget (.-listeners host) "pointermove")))
      (is (nil? (aget (.-listeners host) "pointerleave")))
      (is (zero? (alength (.-children host))))
      (finally
        (aset js/globalThis "ResizeObserver" original-resize-observer)
        (restore!)))))
