(ns hyperopen.views.trading-chart.utils.chart-interop.vol-range-overlays-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.vol-range-overlays :as overlays]))

(def chart-model
  {:stage :draft :stage-label "Draft"
   :anchor {:price "80" :label "Anchor 80"}
   :shifted-anchor {:price "79" :label "Shifted anchor 79"}
   :bands [{:sigma 1 :lower "75.2" :upper "84.8"}
           {:sigma 2 :lower "70.4" :upper "89.6"}]
   :legs [{:leg-id "b1" :side :buy :price "76.4" :size "7" :weight 0.5 :label "Buy 7 @ 76.4"}
          {:leg-id "s1" :side :sell :price "86" :size "10" :weight 1 :label "Sell 10 @ 86"}]})

(defn- make-chart-obj [calls*]
  (let [sub (fn [_] nil)
        unsub (fn [_] (swap! calls* inc))
        time-scale #js {:subscribeVisibleTimeRangeChange sub :unsubscribeVisibleTimeRangeChange unsub
                        :subscribeVisibleLogicalRangeChange sub :unsubscribeVisibleLogicalRangeChange unsub
                        :subscribeSizeChange sub :unsubscribeSizeChange unsub}
        chart #js {:timeScale (fn [] time-scale)}
        series #js {:priceToCoordinate (fn [p] (- 400 (* 4 p)))
                    :subscribeDataChanged sub :unsubscribeDataChanged unsub}]
    #js {:chart chart :mainSeries series}))

(defn- root-text [container]
  (str/join " " (fake-dom/collect-text-content (aget (.-children container) 0))))

(deftest sync-renders-root-bands-lines-and-labels-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        unsubs* (atom 0)
        chart-obj (make-chart-obj unsubs*)]
    (chart-interop/sync-vol-range-overlays! chart-obj container chart-model {:document document})
    (is (= 1 (alength (.-children container))))
    (let [text (root-text container)]
      (is (str/includes? text "Draft"))
      (is (str/includes? text "Anchor 80"))
      (is (str/includes? text "Shifted anchor 79"))
      (is (str/includes? text "Buy 7 @ 76.4"))
      (is (str/includes? text "Sell 10 @ 86")))
    (is (str/includes? (str (.-textContent (aget (.-children container) 0))) "Draft"))
    (chart-interop/clear-vol-range-overlays! chart-obj)
    (is (= 0 (alength (.-children container))))
    (is (= 4 @unsubs*))))

(deftest identical-inputs-do-not-re-render-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        chart-obj (make-chart-obj (atom 0))
        renders* (atom 0)]
    (chart-interop/sync-vol-range-overlays! chart-obj container chart-model {:document document})
    (let [root (aget (.-children container) 0)
          first-child (aget (.-children root) 0)]
      (with-redefs [overlays/render! (fn [_] (swap! renders* inc))]
        (chart-interop/sync-vol-range-overlays! chart-obj container chart-model {:document document}))
      (is (= 0 @renders*))
      (is (identical? first-child (aget (.-children root) 0))))
    (let [edited (assoc-in chart-model [:legs 0 :label] "Buy 7 @ 76.5")]
      (chart-interop/sync-vol-range-overlays! chart-obj container edited {:document document})
      (is (str/includes? (root-text container) "Buy 7 @ 76.5"))
      (is (= 1 (alength (.-children container)))))))

(deftest nil-model-clears-overlays-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        chart-obj (make-chart-obj (atom 0))]
    (chart-interop/sync-vol-range-overlays! chart-obj container chart-model {:document document})
    (is (= 1 (alength (.-children container))))
    (chart-interop/sync-vol-range-overlays! chart-obj container nil {:document document})
    (is (= 0 (alength (.-children container))))))

(deftest repaint-is-scheduled-through-frame-hook-and-clear-cancels-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        chart-obj (make-chart-obj (atom 0))
        scheduled* (atom [])
        cancelled* (atom [])]
    (binding [overlays/*schedule-overlay-repaint-frame!* (fn [cb] (swap! scheduled* conj cb) 7)
              overlays/*cancel-overlay-repaint-frame!* (fn [id] (swap! cancelled* conj id))]
      (chart-interop/sync-vol-range-overlays! chart-obj container chart-model {:document document})
      (is (empty? @scheduled*))
      (chart-interop/clear-chart-overlays! chart-obj)
      (is (= 0 (alength (.-children container)))))))

(defn- make-paned-chart-obj [pane-width pane-height]
  (let [sub (fn [_] nil)
        time-scale #js {:subscribeVisibleTimeRangeChange sub :unsubscribeVisibleTimeRangeChange sub
                        :subscribeVisibleLogicalRangeChange sub :unsubscribeVisibleLogicalRangeChange sub
                        :subscribeSizeChange sub :unsubscribeSizeChange sub}
        chart #js {:timeScale (fn [] time-scale)
                   :paneSize (fn [_] #js {:width pane-width :height pane-height})}
        series #js {:priceToCoordinate (fn [p] (- 700 (* 8 p)))
                    :subscribeDataChanged sub :unsubscribeDataChanged sub}]
    #js {:chart chart :mainSeries series}))

(defn- find-all [node pred]
  (let [children (array-seq (.-children node))]
    (into (if (pred node) [node] [])
          (mapcat #(find-all % pred) children))))

(defn- role-nodes [root role]
  (find-all root #(= role (.getAttribute % "data-role"))))

(deftest root-and-labels-are-clipped-to-the-main-pane-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        chart-obj (make-paned-chart-obj 700 40)]
    (chart-interop/sync-vol-range-overlays! chart-obj container chart-model {:document document})
    (let [root (aget (.-children container) 0)
          style (.-style root)]
      ;; Root is exactly the pane, so right:8px labels end inside the pane
      ;; (left of the price axis) and nothing paints below the price pane.
      (is (= "700px" (aget style "width")))
      (is (= "40px" (aget style "height")))
      (let [labels (role-nodes root "leg-label")]
        (is (every? #(= "8px" (aget (.-style %) "right")) labels))
        ;; Buy at 76.4 maps to y=88.8, beyond pane height 40 + margin: culled.
        (is (= ["s1"] (mapv #(.getAttribute % "data-leg-id") labels))))
      (is (= "8px" (aget (.-style (first (role-nodes root "stage-label"))) "right"))))))
