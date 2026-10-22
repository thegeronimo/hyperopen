(ns hyperopen.views.trading-chart.utils.chart-interop.vol-range-overlays
  "DOM overlay for the Vol Range draft (HO-SPEC-014 v2.1 11.1): faint +-1/+-2
   sigma market bands around the anchor, a solid anchor line, a separately
   labelled shifted (order) anchor, dashed draft order lines whose thickness
   encodes size, plain-text labels above each line's right end and a Draft
   stage label. Purely presentational: no OID, no cancel control, no order
   effects (VR-24). Colors come from semantic tokens via theme-colors."
  (:require [hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays.support :as support]
            [hyperopen.views.trading-chart.utils.theme-colors :as theme-colors]
            [hyperopen.views.trading-chart.utils.vol-range-overlay-model :as model]))

(defonce ^:private vol-range-overlays-sidecar (js/WeakMap.))

(def ^:dynamic *schedule-overlay-repaint-frame!*
  (fn [callback]
    (if-let [raf (some-> js/globalThis (aget "requestAnimationFrame"))]
      (raf callback)
      (do (callback) nil))))

(def ^:dynamic *cancel-overlay-repaint-frame!*
  (fn [frame-id]
    (when-let [cancel-frame (and frame-id
                                 (some-> js/globalThis (aget "cancelAnimationFrame")))]
      (cancel-frame frame-id))))

(defn- state-of [chart-obj]
  (if chart-obj (or (.get vol-range-overlays-sidecar chart-obj) {}) {}))

(defn- set-state! [chart-obj state]
  (when chart-obj (.set vol-range-overlays-sidecar chart-obj state))
  state)

(declare render!)

(defn- cancel-scheduled-repaint!
  [chart-obj]
  (let [{:keys [repaint-frame-id] :as state} (state-of chart-obj)]
    (when repaint-frame-id
      (*cancel-overlay-repaint-frame!* repaint-frame-id)
      (set-state! chart-obj (dissoc state :repaint-frame-id)))))

(defn- schedule-repaint!
  [chart-obj]
  (when-not (:repaint-frame-id (state-of chart-obj))
    (let [frame-id* (volatile! nil)
          callback (fn []
                     (let [{:keys [repaint-frame-id] :as state} (state-of chart-obj)]
                       (when (or (nil? @frame-id*) (= @frame-id* repaint-frame-id))
                         (when @frame-id*
                           (set-state! chart-obj (dissoc state :repaint-frame-id)))
                         (render! chart-obj))))
          frame-id (*schedule-overlay-repaint-frame!* callback)]
      (vreset! frame-id* frame-id)
      (when frame-id
        (set-state! chart-obj (assoc (state-of chart-obj) :repaint-frame-id frame-id))))))

(defn- teardown-subscription!
  [{:keys [main-series time-scale repaint]}]
  (when repaint
    (support/invoke-method time-scale "unsubscribeVisibleTimeRangeChange" repaint)
    (support/invoke-method time-scale "unsubscribeVisibleLogicalRangeChange" repaint)
    (support/invoke-method time-scale "unsubscribeSizeChange" repaint)
    (support/invoke-method main-series "unsubscribeDataChanged" repaint)))

(defn- subscribe-repaint!
  [chart-obj chart main-series]
  (let [time-scale (support/invoke-method chart "timeScale")
        repaint (fn [_] (schedule-repaint! chart-obj))]
    (support/invoke-method time-scale "subscribeVisibleTimeRangeChange" repaint)
    (support/invoke-method time-scale "subscribeVisibleLogicalRangeChange" repaint)
    (support/invoke-method time-scale "subscribeSizeChange" repaint)
    (support/invoke-method main-series "subscribeDataChanged" repaint)
    {:chart chart :main-series main-series :time-scale time-scale :repaint repaint}))

(defn- ensure-root!
  [{:keys [root]} container document]
  (let [mounted? (and root (identical? (.-parentNode root) container))
        next-root (if mounted?
                    root
                    (let [el (.createElement document "div")]
                      (set! (.-className el) "vol-range-chart-overlays")
                      (.setAttribute el "data-role" "vol-range-overlay-root")
                      (support/apply-inline-style!
                       el {"position" "absolute" "inset" "0px" "pointerEvents" "none"
                           "zIndex" "11" "overflow" "hidden"})
                      el))]
    (when (and root (not mounted?))
      (when-let [parent (.-parentNode root)] (.removeChild parent root)))
    (support/append-child! container next-root)
    next-root))

(defn- make-el
  [document role style & [text]]
  (let [el (.createElement document "div")]
    (.setAttribute el "data-role" role)
    (support/apply-inline-style! el (assoc style "position" "absolute"))
    (when text (set! (.-textContent el) text))
    el))

(def ^:private label-style
  {"fontSize" "11px" "lineHeight" "14px" "whiteSpace" "nowrap" "fontWeight" "500"
   "padding" "0 3px" "borderRadius" "2px"})

(defn- label-bg [] (theme-colors/token "--ho-chart-bg"))


(defn- px* [n] (str (/ (js/Math.round (* 10 n)) 10) "px"))

(defn- side-token [side] (if (= :buy side) "--ho-buy" "--ho-sell"))

(defn- append-band!
  [root document line-width {:keys [sigma top bottom]}]
  (.appendChild
   root
   (make-el document (str "band-" sigma)
            {"left" "0px" "width" line-width
             "top" (px* top) "height" (px* (max 0 (- bottom top)))
             "backgroundColor" (theme-colors/token-rgb "--ho-warn" (if (= 1 sigma) 0.12 0.05))})))

(defn- append-anchor!
  [root document line-width role {:keys [y label]} {:keys [dashed? alpha]}]
  (let [color (theme-colors/token-rgb "--ho-warn" alpha)]
    (.appendChild root (make-el document (str role "-line")
                                {"left" "0px" "width" line-width "top" (px* y) "height" "0px"
                                 "borderTop" (str "1px " (if dashed? "dotted" "solid") " " color)}))
    (.appendChild root (make-el document (str role "-label")
                                (assoc label-style "left" "8px" "top" (px* (- y 15)) "color" color
                                       "backgroundColor" (label-bg))
                                label))))

(defn- append-leg!
  [root document line-width {:keys [leg-id side y thickness label label-y]}]
  (let [color (theme-colors/token-rgb (side-token side) 0.9)
        line (make-el document "leg-line"
                      {"left" "0px" "width" line-width "top" (px* (- y (/ thickness 2))) "height" "0px"
                       "borderTop" (str (px* thickness) " dashed " color)})
        text (make-el document "leg-label"
                      (assoc label-style "right" "8px" "top" (px* label-y) "color" color
                                     "backgroundColor" (label-bg))
                      label)]
    (.setAttribute line "data-leg-id" leg-id)
    (.setAttribute text "data-leg-id" leg-id)
    (.setAttribute text "data-side" (name side))
    (.appendChild root line)
    (.appendChild root text)))

(defn render!
  "Rebuild the overlay DOM from the current model and chart coordinates."
  [chart-obj]
  (let [{:keys [root chart main-series chart-model document]} (state-of chart-obj)]
    (when root
      (support/clear-children! root)
      (when chart-model
        (let [pane-size (support/invoke-method chart "paneSize" 0)
              pane-width (some-> pane-size (aget "width"))
              pane-height (some-> pane-size (aget "height"))
              pane-sized? (and (support/finite-number? pane-width) (pos? pane-width)
                               (support/finite-number? pane-height) (pos? pane-height))
              ;; Clip the root to the main price pane so labels sit left of the
              ;; price axis and nothing is drawn over lower indicator panes.
              _ (support/apply-inline-style!
                 root (if pane-sized?
                        {"inset" "auto" "left" "0px" "top" "0px"
                         "width" (px* pane-width) "height" (px* pane-height)}
                        {"inset" "0px" "width" "" "height" ""}))
              height (if pane-sized? pane-height (or (.-clientHeight root) 0))
              width (if pane-sized? pane-width (or (.-clientWidth root) 0))
              line-width "100%"
              price->y (fn [price] (support/invoke-method main-series "priceToCoordinate" price))
              layout (model/overlay-layout chart-model price->y height width)]
          (doseq [band (:bands layout)]
            (append-band! root document line-width band))
          (when-let [anchor (:anchor layout)]
            (append-anchor! root document line-width "anchor" anchor {:dashed? false :alpha 0.9}))
          (when-let [shifted (:shifted-anchor layout)]
            (append-anchor! root document line-width "shifted-anchor" shifted {:dashed? true :alpha 0.9}))
          (doseq [leg (:legs layout)]
            (append-leg! root document line-width leg))
          (.appendChild root
                        (make-el document "stage-label"
                                 (assoc label-style "right" "8px" "top" "6px"
                                        "color" (theme-colors/token-rgb "--ho-warn" 0.9)
                                        "backgroundColor" (label-bg))
                                 (:stage-label layout))))))))

(defn rerender!
  "Re-render with the current model, e.g. after a theme change."
  [chart-obj]
  (when (and chart-obj (:root (state-of chart-obj)))
    (cancel-scheduled-repaint! chart-obj)
    (render! chart-obj)))

(defn clear-vol-range-overlays!
  [chart-obj]
  (when chart-obj
    (let [{:keys [root subscription]} (state-of chart-obj)]
      (cancel-scheduled-repaint! chart-obj)
      (teardown-subscription! subscription)
      (when root
        (support/clear-children! root)
        (when-let [parent (.-parentNode root)]
          (.removeChild parent root)))
      (when chart-obj (.delete vol-range-overlays-sidecar chart-obj)))))

(defn sync-vol-range-overlays!
  ([chart-obj container model] (sync-vol-range-overlays! chart-obj container model {}))
  ([chart-obj container model {:keys [document]}]
   (if-not (and chart-obj container (map? model))
     (clear-vol-range-overlays! chart-obj)
     (let [chart (.-chart ^js chart-obj)
           main-series (.-mainSeries ^js chart-obj)
           document* (support/resolve-document document)]
       (if (and chart main-series document*)
         (let [state (state-of chart-obj)
               root (ensure-root! state container document*)
               current-subscription (:subscription state)
               resubscribe? (or (nil? current-subscription)
                                (not (identical? chart (:chart current-subscription)))
                                (not (identical? main-series (:main-series current-subscription))))
               subscription (if resubscribe?
                              (do (teardown-subscription! current-subscription)
                                  (subscribe-repaint! chart-obj chart main-series))
                              current-subscription)
               unchanged? (and (not resubscribe?)
                               (identical? root (:root state))
                               (identical? chart (:chart state))
                               (identical? main-series (:main-series state))
                               (identical? model (:chart-model state)))]
           (if unchanged?
             state
             (do
               (cancel-scheduled-repaint! chart-obj)
               (set-state! chart-obj (assoc (state-of chart-obj)
                                            :root root :chart chart :main-series main-series
                                            :document document* :chart-model model
                                            :subscription subscription))
               (render! chart-obj))))
         (clear-vol-range-overlays! chart-obj))))))
