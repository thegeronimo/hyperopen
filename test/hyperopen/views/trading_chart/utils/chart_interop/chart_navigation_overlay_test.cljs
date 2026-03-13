(ns hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay :as chart-navigation-overlay]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]))

(defn- make-animation-clock
  []
  (let [queued-frames* (atom [])
        next-frame-id* (atom 0)
        now-ms* (atom 1000)
        cancelled-frame-ids* (atom [])]
    (letfn [(flush-next! []
              (when-let [[_ callback] (first @queued-frames*)]
                (swap! queued-frames* #(vec (rest %)))
                (swap! now-ms* + 90)
                (callback @now-ms*)
                true))
            (flush-all! []
              (loop []
                (when (flush-next!)
                  (recur))))]
      {:request-frame! (fn [callback]
                         (let [frame-id (swap! next-frame-id* inc)]
                           (swap! queued-frames* conj [frame-id callback])
                           frame-id))
       :cancel-frame! (fn [frame-id]
                        (swap! cancelled-frame-ids* conj frame-id)
                        (swap! queued-frames*
                               (fn [queued]
                                 (vec (remove #(= frame-id (first %)) queued)))))
       :now-ms! (fn []
                  @now-ms*)
       :flush-next! flush-next!
       :flush-all! flush-all!
       :queued-frames* queued-frames*
       :cancelled-frame-ids* cancelled-frame-ids*})))

(deftest chart-navigation-overlay-sync-renders-hover-focus-and-controls-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        animation-clock (make-animation-clock)
        visible-range* (atom {:from 100 :to 200})
        applied-ranges* (atom [])
        interaction-count* (atom 0)
        reset-count* (atom 0)
        time-scale #js {:getVisibleLogicalRange (fn []
                                                  (clj->js @visible-range*))
                        :setVisibleLogicalRange (fn [range]
                                                  (let [next-range (js->clj range :keywordize-keys true)]
                                                    (swap! applied-ranges* conj next-range)
                                                    (reset! visible-range* next-range)))}
        chart #js {:timeScale (fn []
                                time-scale)}
        chart-obj #js {:chart chart}
        candles (vec (for [idx (range 320)]
                       {:time idx
                        :open 100
                        :high 105
                        :low 95
                        :close 102
                        :volume 10}))]
    (chart-navigation-overlay/sync-chart-navigation-overlay!
     chart-obj
     container
     candles
     {:document document
      :on-interaction (fn []
                        (swap! interaction-count* inc))
      :now-ms-fn (:now-ms! animation-clock)
      :request-animation-frame-fn (:request-frame! animation-clock)
      :cancel-animation-frame-fn (:cancel-frame! animation-clock)
      :on-reset (fn [_chart _candles]
                  (swap! reset-count* inc)
                  (reset! visible-range* {:from 50 :to 150}))})
    (let [state (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay/overlay-state
                 chart-obj)
          root (:root state)
          panel (:panel state)
          focusout-handler (aget (.-listeners ^js panel) "focusout")
          zoom-out-button (fake-dom/find-dom-node root
                                                  #(= "Zoom out"
                                                      (aget ^js % "aria-label")))
          zoom-in-button (fake-dom/find-dom-node root
                                                 #(= "Zoom in"
                                                     (aget ^js % "aria-label")))
          scroll-left-button (fake-dom/find-dom-node root
                                                     #(= "Scroll left"
                                                         (aget ^js % "aria-label")))
          scroll-right-button (fake-dom/find-dom-node root
                                                      #(= "Scroll right"
                                                          (aget ^js % "aria-label")))
          reset-button (fake-dom/find-dom-node root
                                               #(= "Reset chart view"
                                                   (aget ^js % "aria-label")))]
      (is (= "relative" (.-position (.-style container))))
      (is (= "0" (.-opacity (.-style root))))
      (is (= "none" (.-pointerEvents (.-style root))))
      (is (= "Reset chart view" (aget ^js reset-button "title")))

      (fake-dom/dispatch-dom-event! container "pointerenter")
      (is (= "1" (.-opacity (.-style root))))
      (is (= "auto" (.-pointerEvents (.-style root))))

      (fake-dom/dispatch-dom-event! container "pointerleave")
      (is (= "0" (.-opacity (.-style root))))
      (is (= "none" (.-pointerEvents (.-style root))))

      (fake-dom/dispatch-dom-event! panel "focusin")
      (is (= "1" (.-opacity (.-style root))))
      (focusout-handler #js {:relatedTarget nil})
      (is (= "0" (.-opacity (.-style root))))

      (reset! visible-range* {:from 100 :to 200})
      (fake-dom/click-dom-node! zoom-out-button)
      (is (= {:from 100 :to 200} @visible-range*))
      ((:flush-next! animation-clock))
      (is (> (- (:to @visible-range*) (:from @visible-range*)) 100))
      ((:flush-all! animation-clock))

      (reset! visible-range* {:from 100 :to 200})
      (fake-dom/click-dom-node! zoom-in-button)
      (is (= {:from 100 :to 200} @visible-range*))
      ((:flush-next! animation-clock))
      (is (< (- (:to @visible-range*) (:from @visible-range*)) 100))
      ((:flush-all! animation-clock))

      (reset! visible-range* {:from 100 :to 200})
      (fake-dom/click-dom-node! scroll-left-button)
      ((:flush-all! animation-clock))
      (is (= {:from 96 :to 196} @visible-range*))

      (reset! visible-range* {:from 100 :to 200})
      (fake-dom/click-dom-node! scroll-right-button)
      ((:flush-all! animation-clock))
      (is (= {:from 104 :to 204} @visible-range*))

      (fake-dom/click-dom-node! reset-button)
      (is (= 1 @reset-count*))
      (is (= {:from 50 :to 150} @visible-range*))
      (is (= 5 @interaction-count*))
      (is (>= (count @applied-ranges*) 8)))

    (chart-navigation-overlay/clear-chart-navigation-overlay! chart-obj)
    (is (zero? (alength (.-children container))))
    (is (= {} (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay/overlay-state
               chart-obj)))))

(deftest chart-navigation-overlay-resync-rebinds-container-listeners-and-default-reset-test
  (let [document (fake-dom/make-fake-document)
        container-a (fake-dom/make-fake-element "div")
        container-b (fake-dom/make-fake-element "div")
        animation-clock (make-animation-clock)
        visible-range* (atom {:from 1 :to 2})
        reset-calls* (atom 0)
        interaction-calls* (atom 0)
        time-scale #js {:getVisibleLogicalRange (fn []
                                                  (clj->js @visible-range*))
                        :setVisibleLogicalRange (fn [range]
                                                  (reset! visible-range* (js->clj range :keywordize-keys true)))
                        :fitContent (fn []
                                      (swap! reset-calls* inc))
                        :scrollToRealTime (fn [] nil)}
        chart #js {:timeScale (fn []
                                time-scale)
                   :resetTimeScale (fn []
                                     false)}
        chart-obj #js {:chart chart}
        candles [{:time 1 :open 1 :high 2 :low 0.5 :close 1.5 :volume 10}]]
    (chart-navigation-overlay/sync-chart-navigation-overlay!
     chart-obj
     container-a
     candles
     {:document document
      :on-interaction (fn []
                        (swap! interaction-calls* inc))
      :now-ms-fn (:now-ms! animation-clock)
      :request-animation-frame-fn (:request-frame! animation-clock)
      :cancel-animation-frame-fn (:cancel-frame! animation-clock)})
    (is (fn? (aget (.-listeners ^js container-a) "pointerenter")))

    (chart-navigation-overlay/sync-chart-navigation-overlay!
     chart-obj
     container-b
     candles
     {:document document
      :on-interaction (fn []
                        (swap! interaction-calls* inc))
      :now-ms-fn (:now-ms! animation-clock)
      :request-animation-frame-fn (:request-frame! animation-clock)
      :cancel-animation-frame-fn (:cancel-frame! animation-clock)})
    (is (nil? (aget (.-listeners ^js container-a) "pointerenter")))
    (is (fn? (aget (.-listeners ^js container-b) "pointerenter")))

    (let [state (@#'hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay/overlay-state
                 chart-obj)
          root (:root state)
          zoom-out-button (fake-dom/find-dom-node root
                                                  #(= "Zoom out"
                                                      (aget ^js % "aria-label")))
          reset-button (fake-dom/find-dom-node root
                                               #(= "Reset chart view"
                                                   (aget ^js % "aria-label")))]
      (fake-dom/click-dom-node! reset-button)
      (is (= 1 @reset-calls*))
      (is (= 1 @interaction-calls*))
      (fake-dom/click-dom-node! zoom-out-button)
      (is (= 1 (count @(:queued-frames* animation-clock)))))

    (chart-navigation-overlay/clear-chart-navigation-overlay! chart-obj)
    (is (nil? (aget (.-listeners ^js container-b) "pointerenter")))
    (is (= [1] @(:cancelled-frame-ids* animation-clock)))
    (is (empty? @(:queued-frames* animation-clock)))))
