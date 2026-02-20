(ns hyperopen.views.trading-chart.utils.chart-interop.volume-indicator-overlay-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.utils.chart-interop.volume-indicator-overlay :as volume-indicator-overlay]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]))

(deftest volume-indicator-overlay-sync-renders-controls-and-crosshair-updates-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        pane-heights* (atom {0 24
                             1 36
                             2 48})
        crosshair-handler* (atom nil)
        pane-time-handler* (atom nil)
        pane-logical-handler* (atom nil)
        pane-size-handler* (atom nil)
        remove-calls* (atom 0)
        time-scale #js {:subscribeVisibleTimeRangeChange (fn [handler]
                                                           (reset! pane-time-handler* handler))
                        :unsubscribeVisibleTimeRangeChange (fn [_] nil)
                        :subscribeVisibleLogicalRangeChange (fn [handler]
                                                              (reset! pane-logical-handler* handler))
                        :unsubscribeVisibleLogicalRangeChange (fn [_] nil)
                        :subscribeSizeChange (fn [handler]
                                               (reset! pane-size-handler* handler))
                        :unsubscribeSizeChange (fn [_] nil)}
        chart #js {:timeScale (fn []
                                time-scale)
                   :panesCount (fn []
                                 3)
                   :paneSize (fn [idx]
                               #js {"height" (get @pane-heights* idx 0)})
                   :subscribeCrosshairMove (fn [handler]
                                             (reset! crosshair-handler* handler))
                   :unsubscribeCrosshairMove (fn [_] nil)}
        chart-obj #js {:chart chart
                       :volumeSeries #js {:id "volume"}
                       :volumePaneIndex 2}
        candles [{:time {:year 2026 :month 2 :day 20}
                  :open 10
                  :close 11
                  :volume 2000}
                 {:time 1700000000
                  :open 12
                  :close 8
                  :volume 3200}]]
    (volume-indicator-overlay/sync-volume-indicator-overlay!
     chart-obj
     container
     candles
     {:document document
      :on-remove (fn []
                   (swap! remove-calls* inc))})
    (let [state (@#'hyperopen.views.trading-chart.utils.chart-interop.volume-indicator-overlay/overlay-state
                 chart-obj)
          root (:root state)
          panel (aget (.-children root) 0)
          controls (:controls state)
          value-node (:value-node state)
          focusout-handler (aget (.-listeners ^js panel) "focusout")
          remove-button (fake-dom/find-dom-node root
                                       #(= "Remove volume indicator"
                                           (aget ^js % "aria-label")))
          settings-button (fake-dom/find-dom-node root
                                         #(= "Volume settings (coming soon)"
                                             (aget ^js % "aria-label")))]
      (is (= "relative" (.-position (.-style container))))
      (is (= "68px" (.-top (.-style root))))
      (is (= "3.2K" (.-textContent value-node)))
      (is (= "#f7525f" (.-color (.-style value-node))))
      (is (fn? @crosshair-handler*))
      (is (fn? @pane-time-handler*))
      (is (fn? @pane-logical-handler*))
      (is (fn? @pane-size-handler*))
      (is (nil? (.-display (.-style controls))))
      (fake-dom/dispatch-dom-event! panel "mouseenter")
      (is (= "inline-flex" (.-display (.-style controls))))
      (fake-dom/dispatch-dom-event! panel "mouseleave")
      (is (= "none" (.-display (.-style controls))))
      (fake-dom/dispatch-dom-event! panel "focusin")
      (is (= "inline-flex" (.-display (.-style controls))))
      (focusout-handler #js {:relatedTarget nil})
      (is (= "none" (.-display (.-style controls))))
      (@crosshair-handler* #js {:time #js {:year 2026 :month 2 :day 20}})
      (is (= "2K" (.-textContent value-node)))
      (is (= "#22ab94" (.-color (.-style value-node))))
      (@crosshair-handler* #js {:time "does-not-match"})
      (is (= "3.2K" (.-textContent value-node)))
      (is (= "#f7525f" (.-color (.-style value-node))))
      (reset! pane-heights* {0 30
                             1 40
                             2 50})
      (@pane-size-handler* #js {})
      (is (= "78px" (.-top (.-style root))))
      (@pane-time-handler* #js {})
      (@pane-logical-handler* #js {})
      (fake-dom/click-dom-node! settings-button)
      (fake-dom/click-dom-node! remove-button)
      (is (= 1 @remove-calls*)))))

(deftest volume-indicator-overlay-resubscribes-and-clears-on-invalid-state-test
  (let [document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        make-chart! (fn [events*]
                      (let [time-scale #js {:subscribeVisibleTimeRangeChange (fn [handler]
                                                                               (swap! events* conj [:sub :time-range handler]))
                                            :unsubscribeVisibleTimeRangeChange (fn [handler]
                                                                                 (swap! events* conj [:unsub :time-range handler]))
                                            :subscribeVisibleLogicalRangeChange (fn [handler]
                                                                                  (swap! events* conj [:sub :logical-range handler]))
                                            :unsubscribeVisibleLogicalRangeChange (fn [handler]
                                                                                    (swap! events* conj [:unsub :logical-range handler]))
                                            :subscribeSizeChange (fn [handler]
                                                                   (swap! events* conj [:sub :size handler]))
                                            :unsubscribeSizeChange (fn [handler]
                                                                    (swap! events* conj [:unsub :size handler]))}]
                        #js {:timeScale (fn []
                                          time-scale)
                             :panesCount (fn []
                                           2)
                             :paneSize (fn [_idx]
                                         #js {"height" 25})
                             :subscribeCrosshairMove (fn [handler]
                                                       (swap! events* conj [:sub :crosshair handler]))
                             :unsubscribeCrosshairMove (fn [handler]
                                                         (swap! events* conj [:unsub :crosshair handler]))}))
        events-a* (atom [])
        events-b* (atom [])
        chart-a (make-chart! events-a*)
        chart-b (make-chart! events-b*)
        chart-obj #js {:chart chart-a
                       :volumeSeries #js {:id "volume"}
                       :volumePaneIndex 1}
        candles [{:time 1 :open 1 :close 2 :volume 100}]]
    (volume-indicator-overlay/sync-volume-indicator-overlay!
     chart-obj
     container
     candles
     {:document document})
    (set! (.-chart chart-obj) chart-b)
    (volume-indicator-overlay/sync-volume-indicator-overlay!
     chart-obj
     container
     candles
     {:document document})
    (is (some #(= [:unsub :crosshair] (subvec % 0 2)) @events-a*))
    (is (some #(= [:unsub :time-range] (subvec % 0 2)) @events-a*))
    (is (some #(= [:sub :crosshair] (subvec % 0 2)) @events-b*))
    (is (some #(= [:sub :time-range] (subvec % 0 2)) @events-b*))
    (set! (.-volumeSeries chart-obj) nil)
    (volume-indicator-overlay/sync-volume-indicator-overlay!
     chart-obj
     container
     candles
     {:document document})
    (is (zero? (alength (.-children container))))
    (is (= {} (@#'hyperopen.views.trading-chart.utils.chart-interop.volume-indicator-overlay/overlay-state
               chart-obj)))
    (is (some #(= [:unsub :crosshair] (subvec % 0 2)) @events-b*))
    (is (nil? (volume-indicator-overlay/clear-volume-indicator-overlay! nil)))))

(deftest volume-indicator-overlay-private-formatting-and-time-normalization-branches-test
  (let [normalize-time-key @#'hyperopen.views.trading-chart.utils.chart-interop.volume-indicator-overlay/normalize-time-key
        format-volume-compact @#'hyperopen.views.trading-chart.utils.chart-interop.volume-indicator-overlay/format-volume-compact
        volume-value-color @#'hyperopen.views.trading-chart.utils.chart-interop.volume-indicator-overlay/volume-value-color
        build-candle-index @#'hyperopen.views.trading-chart.utils.chart-interop.volume-indicator-overlay/build-candle-index
        {:keys [lookup latest]} (build-candle-index [{:time 1 :open 10 :close 11}
                                                     {:time "t2" :open 11 :close 12}
                                                     {:time :ignored :open 12 :close 10}])]
    (is (= "ts:42" (normalize-time-key 42)))
    (is (= "txt:hello" (normalize-time-key "hello")))
    (is (= "bd:2026-2-18" (normalize-time-key {:year 2026 :month 2 :day 18})))
    (is (= "bd:2026-2-18" (normalize-time-key #js {:year 2026 :month 2 :day 18})))
    (is (nil? (normalize-time-key :ignored)))
    (is (= "--" (format-volume-compact nil)))
    (is (= "95" (format-volume-compact 950)))
    (is (= "1.5K" (format-volume-compact 1500)))
    (is (= "-2.5M" (format-volume-compact -2500000)))
    (is (= "#9ca3af" (volume-value-color {:open nil :close 1})))
    (is (= "#22ab94" (volume-value-color {:open 5 :close 5})))
    (is (= "#f7525f" (volume-value-color {:open 5 :close 4})))
    (is (= {:time :ignored :open 12 :close 10}
           latest))
    (is (= 2 (count lookup)))
    (is (= 11 (:close (get lookup "ts:1"))))
    (is (= 12 (:close (get lookup "txt:t2"))))))

