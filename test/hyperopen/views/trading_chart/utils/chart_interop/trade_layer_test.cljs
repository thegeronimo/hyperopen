(ns hyperopen.views.trading-chart.utils.chart-interop.trade-layer-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]
            [hyperopen.views.trading-chart.utils.chart-interop.trade-card :as trade-card]
            [hyperopen.views.trading-chart.utils.chart-interop.trade-layer :as trade-layer]
            [hyperopen.views.trading-chart.utils.chart-interop.trade-markers-primitive :as primitive]))

(def ^:private buy-group
  {:id "1700006400-buy" :time 1700006400 :side-kind :buy :sign 1 :count 2 :size 3
   :avg-price 11.5 :notional 34.5 :fees 0.01 :fee-token "USDC" :closed-pnl 0 :position-after 3
   :candle-high 12 :candle-low 10 :size-step :typical
   :fills [{:time-ms 1700010000000 :size 1 :price 11 :maker? true}
           {:time-ms 1700013600000 :size 2 :price 11.75 :maker? false}]})

(def ^:private sell-group
  (assoc buy-group :id "1700092800-sell" :time 1700092800 :side-kind :sell :sign -1 :count 1
         :closed-pnl 4.2 :position-after 0))

(def ^:private chart-trades
  {:groups [buy-group sell-group]
   :groups-by-id {(:id buy-group) buy-group (:id sell-group) sell-group}
   :ribbon []})

(defn- fake-series
  [label attached* & [chart]]
  #js {:label label
       :attachPrimitive (fn [p]
                          (swap! attached* conj [:attach label p])
                          (when chart
                            ((unchecked-get p "attached")
                             #js {:chart chart
                                  :series #js {:priceToCoordinate (fn [price] (- 500 (* 10 price)))}
                                  :requestUpdate (fn [] nil)})))
       :detachPrimitive (fn [p] (swap! attached* conj [:detach label p]))
       :priceToCoordinate (fn [p] (- 500 (* 10 p)))})

(defn- fake-chart
  [subs*]
  (let [time-scale #js {:timeToCoordinate (fn [t] (/ (- t 1700000000) 100))
                        :options (fn [] #js {:barSpacing 14})
                        :subscribeVisibleLogicalRangeChange (fn [f] (swap! subs* assoc :range f))
                        :unsubscribeVisibleLogicalRangeChange (fn [_] (swap! subs* dissoc :range))}]
    #js {:timeScale (fn [] time-scale)
         :priceScale (fn [_] #js {:width (fn [] 60)})
         :subscribeCrosshairMove (fn [f] (swap! subs* assoc :crosshair f))
         :unsubscribeCrosshairMove (fn [_] (swap! subs* dissoc :crosshair))
         :subscribeClick (fn [f] (swap! subs* assoc :click f))
         :unsubscribeClick (fn [_] (swap! subs* dissoc :click))}))

(defn- setup
  []
  (let [attached* (atom [])
        subs* (atom {})
        doc (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        chart (fake-chart subs*)
        series (fake-series "candles" attached* chart)
        chart-obj #js {:chart chart :mainSeries series}
        opened* (atom 0)
        prim (primitive/create-trade-markers-primitive {:font-family "sans-serif" :touch? false})
        opts {:document doc
              :perp? true
              :coin-label "HYPE"
              :format-price #(.toFixed % 2)
              :on-open-history #(swap! opened* inc)
              :create-primitive (constantly prim)}]
    {:attached* attached* :subs* subs* :doc doc :container container :series series
     :chart chart :chart-obj chart-obj :opened* opened* :prim prim :opts opts}))

(defn- card [container]
  (fake-dom/find-dom-node container #(= "chart-trade-card" (aget % "data-role"))))

(defn- card-text [container]
  (fake-dom/collect-text-content (card container)))

(defn- hover! [{:keys [subs*]} id]
  ((:crosshair @subs*) #js {:hoveredObjectId id}))

(defn- click! [{:keys [subs*]} id]
  ((:click @subs*) #js {:hoveredObjectId id}))

(deftest sync-attaches-the-primitive-and-subscribes-once-test
  (let [{:keys [attached* subs* container chart-obj opts prim] :as env} (setup)]
    (trade-layer/sync-chart-trades! chart-obj container chart-trades opts)
    (trade-layer/sync-chart-trades! chart-obj container chart-trades opts)
    (is (= [[:attach "candles" prim]] @attached*))
    (is (= #{:crosshair :click :range} (set (keys @subs*))))
    (is (some? (card container)))
    (is (= "none" (.. (card container) -style -display)) "hidden until hovered")
    (is (= "117" (.. (card container) -style -zIndex)) "above the chart navigation controls")
    (trade-layer/clear-chart-trades! chart-obj)
    (is (= {} @subs*) "all chart subscriptions removed")
    (is (= [:detach "candles" prim] (last @attached*)))
    (is (nil? (card container)) "card removed from the container")
    (is (nil? (aget (aget (:doc env) "listeners") "keydown")) "Escape listener removed")))

(deftest hover-shows-the-card-and-moving-away-hides-it-test
  (let [{:keys [container chart-obj opts] :as env} (setup)]
    (trade-layer/sync-chart-trades! chart-obj container chart-trades opts)
    (hover! env (:id buy-group))
    (is (= "flex" (.. (card container) -style -display)))
    (let [text (card-text container)]
      (is (str/includes? text "Bought 3 HYPE"))
      (is (str/includes? text "Nov 15, 2023"))
      (is (str/includes? text "Long 3 HYPE"))
      (is (str/includes? text "Maker"))
      (is (str/includes? text "Taker"))
      (is (str/includes? text "Click the marker to pin")))
    (hover! env js/undefined)
    (is (= "none" (.. (card container) -style -display)))))

(deftest click-pins-the-card-escape-unpins-test
  (let [{:keys [container chart-obj opts doc] :as env} (setup)]
    (trade-layer/sync-chart-trades! chart-obj container chart-trades opts)
    (click! env (:id sell-group))
    (hover! env js/undefined)
    (is (= "flex" (.. (card container) -style -display)) "pinned card survives hover-out")
    (let [text (card-text container)]
      (is (str/includes? text "Sold 3 HYPE"))
      (is (str/includes? text "Realized PnL"))
      (is (str/includes? text "+$4.20"))
      (is (str/includes? text "Flat"))
      (is (not (str/includes? text "Click the marker to pin"))))
    (testing "hovering another badge does not replace a pinned card"
      (hover! env (:id buy-group))
      (is (str/includes? (card-text container) "Sold 3 HYPE")))
    ((aget (aget doc "listeners") "keydown") #js {:key "Escape"})
    (is (= "none" (.. (card container) -style -display)))
    (testing "clicking empty chart space also unpins"
      (click! env (:id buy-group))
      (click! env js/undefined)
      (is (= "none" (.. (card container) -style -display))))))

(deftest history-link-calls-the-callback-test
  (let [{:keys [container chart-obj opts opened*] :as env} (setup)]
    (trade-layer/sync-chart-trades! chart-obj container chart-trades opts)
    (click! env (:id buy-group))
    (fake-dom/click-dom-node!
     (fake-dom/find-dom-node container #(= "chart-trade-card-history-link" (aget % "data-role"))))
    (is (= 1 @opened*))))

(deftest the-close-button-unpins-a-pinned-card-test
  (let [{:keys [container chart-obj opts] :as env} (setup)
        close-button #(fake-dom/find-dom-node container
                                              (fn [n] (= "chart-trade-card-close" (aget n "data-role"))))]
    (trade-layer/sync-chart-trades! chart-obj container chart-trades opts)
    (hover! env (:id buy-group))
    (is (nil? (close-button)) "a hover card closes by itself")
    (click! env (:id buy-group))
    (is (some? (close-button)))
    (fake-dom/click-dom-node! (close-button))
    (is (= "none" (.. (card container) -style -display)))))

(deftest pinned-card-closes-when-its-group-disappears-test
  (let [{:keys [container chart-obj opts] :as env} (setup)]
    (trade-layer/sync-chart-trades! chart-obj container chart-trades opts)
    (click! env (:id sell-group))
    (trade-layer/sync-chart-trades! chart-obj container
                                    {:groups [buy-group]
                                     :groups-by-id {(:id buy-group) buy-group}
                                     :ribbon []}
                                    opts)
    (is (= "none" (.. (card container) -style -display)))))

(deftest reattaches-to-a-swapped-main-series-and-clears-when-setting-is-off-test
  (let [{:keys [attached* container chart-obj opts prim]} (setup)
        next-series (fake-series "line" attached*)]
    (trade-layer/sync-chart-trades! chart-obj container chart-trades opts)
    (aset chart-obj "mainSeries" next-series)
    (trade-layer/sync-chart-trades! chart-obj container chart-trades opts)
    (is (= [[:attach "candles" prim] [:detach "candles" prim] [:attach "line" prim]]
           @attached*))
    (trade-layer/sync-chart-trades! chart-obj container nil opts)
    (is (= [:detach "line" prim] (last @attached*)))
    (is (= [] (:groups @(unchecked-get prim "__state")))
        "emptied before detaching so the repaint it requests clears the canvas")
    (is (nil? (card container)))))


(defn- paint!
  "Run the primitive's renderers so its hit-test layout exists."
  [prim]
  (let [noop (fn [& _] nil)
        ctx #js {:beginPath noop :closePath noop :arc noop :moveTo noop :lineTo noop :fill noop
                 :stroke noop :save noop :restore noop :setLineDash noop :fillRect noop :fillText noop}
        scope #js {:context ctx :horizontalPixelRatio 1 :verticalPixelRatio 1
                   :mediaSize #js {:width 260 :height 200}}
        target #js {:useBitmapCoordinateSpace (fn [f] (f scope))}]
    (doseq [view (array-seq ((unchecked-get prim "paneViews")))]
      ((unchecked-get ((unchecked-get view "renderer")) "draw") target))))

(deftest a-touch-tap-without-hovered-object-id-hit-tests-the-badge-test
  ;; buy badge: x = (1700006400 - 1700000000) / 100 = 64, cy = 500 - 10*10 + 8 + 10 = 418
  (let [{:keys [container chart-obj opts prim subs*]} (setup)]
    (trade-layer/sync-chart-trades! chart-obj container chart-trades opts)
    (paint! prim)
    ((:click @subs*) #js {:point #js {:x 66 :y 425} :paneIndex 0})
    (is (= "flex" (.. (card container) -style -display)))
    (is (str/includes? (card-text container) "Bought 3 HYPE"))
    (testing "taps in another pane are ignored"
      ((:click @subs*) #js {:point #js {:x 66 :y 425} :paneIndex 1})
      (is (= "none" (.. (card container) -style -display))))))

(deftest a-pinned-card-hides-while-its-badge-is-off-screen-test
  ;; the sell badge sits at x = 928, beyond the 800 px container
  (let [{:keys [container chart-obj opts prim] :as env} (setup)]
    (aset container "clientWidth" 800)
    (trade-layer/sync-chart-trades! chart-obj container chart-trades opts)
    (paint! prim)
    (click! env (:id sell-group))
    (is (= "hidden" (.. (card container) -style -visibility)))
    (click! env (:id buy-group))
    (is (= "visible" (.. (card container) -style -visibility)))
    (is (= "88px" (.. (card container) -style -left)) "24 px right of the badge at x 64")))

(deftest fees-under-a-cent-keep-their-digits-and-never-read-minus-zero-test
  (is (= "-$0.0035" (trade-card/format-fee -0.0035 "USDC")) "maker rebate")
  (is (= "$0.0012" (trade-card/format-fee 0.0012 "USDC")))
  (is (= "$0.00" (trade-card/format-fee -0.00001 "USDC")))
  (is (= "$1.25" (trade-card/format-fee 1.2512 "USDC")))
  (is (= "0.5 HYPE" (trade-card/format-fee 0.5 "HYPE"))))
