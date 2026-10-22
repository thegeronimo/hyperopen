(ns hyperopen.views.trading-chart.utils.chart-interop.trade-markers-primitive-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.trading-chart.utils.chart-interop.trade-markers-primitive :as primitive]))

;; time t -> x = t / 10 ; price p -> y = 1000 - 10p  (CSS px)
(def ^:private env
  {:time->x (fn [t] (when (some? t) (/ t 10)))
   :price->y (fn [p] (when (number? p) (- 1000 (* 10 p))))
   :bar-spacing 14
   :pane-width 2000})

(defn- group
  [overrides]
  (merge {:id "100-buy" :time 1000 :side-kind :buy :sign 1 :count 1 :size 1
          :avg-price 50 :candle-high 60 :candle-low 40 :size-step :typical}
         overrides))

(deftest layout-places-buys-below-the-low-and-sells-above-the-high-test
  (let [[buy sell] (primitive/layout-groups
                    [(group {})
                     (group {:id "200-sell" :time 2000 :side-kind :sell :sign -1 :size-step :large})]
                    env)]
    (testing "tick sits at the average fill price"
      (is (= 500 (:tick-y buy))))
    (testing "buy badge: low y (600) + 8 gap + radius 10"
      (is (= 618 (:cy buy)))
      (is (true? (:below? buy))))
    (testing "large sell badge: high y (400) - 8 gap - radius 12"
      (is (= 380 (:cy sell)))
      (is (= 12 (:r sell))))
    (is (= [100 200] (mapv :x [buy sell])))
    (is (every? false? (map :dense? [buy sell])))))

(deftest layout-skips-groups-the-chart-cannot-map-test
  (is (= [] (primitive/layout-groups [(group {:time nil})] env))))

(deftest layout-collapses-to-dots-when-bars-are-thin-or-crowded-test
  (testing "bar spacing under 12 px"
    (let [[item] (primitive/layout-groups [(group {})] (assoc env :bar-spacing 4))]
      (is (true? (:dense? item)))
      (is (= (:tick-y item) (:cy item)) "dot sits on the execution tick")
      (is (= 3.5 (:r item)))))
  (testing "more than 60 groups in view"
    (let [many (mapv #(group {:id (str % "-buy") :time (* 100 %)}) (range 61))]
      (is (every? :dense? (primitive/layout-groups many env))))))

(deftest hit-test-returns-the-nearest-badge-within-the-minimum-radius-test
  (let [layout (primitive/layout-groups
                [(group {})
                 (group {:id "120-sell" :time 1200 :side-kind :sell :sign -1})]
                env)]
    (is (= "100-buy" (primitive/hit-test-layout layout 100 618 false)))
    (is (= "100-buy" (primitive/hit-test-layout layout 112 618 false)) "16 px minimum hit radius")
    (is (nil? (primitive/hit-test-layout layout 100 700 false)))
    (is (= "100-buy" (primitive/hit-test-layout layout 100 638 true)) "22 px on touch")))

(defn- fake-ctx
  [calls]
  (let [record (fn [op] (fn [& args] (swap! calls conj (into [op] args))))]
    #js {:beginPath (fn [] nil)
         :closePath (fn [] nil)
         :arc (record :arc)
         :moveTo (fn [& _] nil)
         :lineTo (fn [& _] nil)
         :fill (fn [] nil)
         :stroke (record :stroke)
         :save (fn [] nil)
         :restore (fn [] nil)
         :setLineDash (fn [_] nil)
         :fillRect (record :fillRect)
         :fillText (record :fillText)}))

(defn- paint!
  "Attach the primitive to fake chart/series objects and run every pane view's
  renderer against a recording 2D context."
  [prim calls {:keys [width height] :or {width 2000 height 1000}}]
  (let [time-scale #js {:timeToCoordinate (fn [t] (/ t 10))
                        :options (fn [] #js {:barSpacing 14})}
        chart #js {:timeScale (fn [] time-scale)}
        series #js {:priceToCoordinate (fn [p] (- 1000 (* 10 p)))}
        update-count (atom 0)]
    ((unchecked-get prim "attached") #js {:chart chart :series series
                                          :requestUpdate #(swap! update-count inc)})
    (let [ctx (fake-ctx calls)
          scope #js {:context ctx
                     :horizontalPixelRatio 1
                     :verticalPixelRatio 1
                     :mediaSize #js {:width width :height height}}
          target #js {:useBitmapCoordinateSpace (fn [f] (f scope))}]
      (doseq [view (array-seq ((unchecked-get prim "paneViews")))]
        ((unchecked-get ((unchecked-get view "renderer")) "draw") target)))
    update-count))

(defn- texts [calls]
  (->> @calls (filter #(= :fillText (first %))) (mapv second)))

(deftest draws-badge-letters-and-count-bubbles-test
  (let [prim (primitive/create-trade-markers-primitive {:font-family "sans-serif" :touch? false})
        calls (atom [])]
    (primitive/set-trade-data! prim {:groups [(group {:count 3})
                                              (group {:id "200-sell" :time 2000 :side-kind :sell :sign -1})
                                              (group {:id "300-liquidation" :time 3000 :side-kind :liquidation
                                                      :sign -1})]
                                     :ribbon []})
    (paint! prim calls {})
    (is (= ["B" "3" "S" "L"] (texts calls)))
    (testing "hitTest answers from the painted layout"
      (let [hit ((unchecked-get prim "hitTest") 100 618)]
        (is (= "100-buy" (unchecked-get hit "externalId")))
        (is (= "pointer" (unchecked-get hit "cursorStyle")))
        (is (nil? ((unchecked-get prim "hitTest") 5 5)))))))

(deftest dense-mode-draws-dots-without-letters-test
  (let [prim (primitive/create-trade-markers-primitive {:font-family "sans-serif" :touch? false})
        calls (atom [])]
    (primitive/set-trade-data! prim {:groups (mapv #(group {:id (str % "-buy") :time (* 100 %)}) (range 61))})
    (paint! prim calls {})
    (is (= [] (texts calls)))
    (is (= (* 3 61) (count (filter #(= :arc (first %)) @calls))) "gap, ring and dot per group")))

(deftest ribbon-paints-long-and-open-ended-segments-along-the-pane-bottom-test
  (let [prim (primitive/create-trade-markers-primitive {:font-family "sans-serif" :touch? false})
        calls (atom [])]
    (primitive/set-trade-data! prim {:groups []
                                     :ribbon [{:from-time 1000 :to-time 2000 :direction :long :size 2
                                               :open-ended? false}
                                              {:from-time 3000 :to-time 4000 :direction :short :size 1
                                               :open-ended? true}]})
    (paint! prim calls {:height 500})
    (let [rects (filter #(= :fillRect (first %)) @calls)]
      ;; each segment: a 6 px tinted band + a 2 px top edge, flush with the bottom
      (is (= [[:fillRect 100 494 100 6] [:fillRect 100 494 100 2]
              ;; open-ended: extends half a bar (7 px) past the last candle
              [:fillRect 300 494 107 6] [:fillRect 300 494 107 2]]
             (vec rects))))))

(deftest set-trade-data-and-selection-request-repaints-only-on-change-test
  (let [prim (primitive/create-trade-markers-primitive {:font-family "sans-serif" :touch? false})
        calls (atom [])
        groups [(group {})]
        updates (paint! prim calls {})]
    (primitive/set-trade-data! prim {:groups groups :ribbon []})
    (primitive/set-trade-data! prim {:groups groups :ribbon []})
    (is (= 1 @updates) "identical data does not repaint again")
    (primitive/set-selected-id! prim "100-buy")
    (primitive/set-selected-id! prim "100-buy")
    (is (= 2 @updates))
    ((unchecked-get prim "detached"))
    (primitive/set-selected-id! prim nil)
    (is (= 2 @updates) "no repaint requests after detach")))
