(ns hyperopen.views.trading-chart.utils.chart-interop.trade-markers-primitive
  "lightweight-charts series primitive that paints the trader's own trades on
  the main price series: an execution tick at the average fill price, a stem,
  a B/S badge (L diamond for liquidations) and a count bubble, plus the
  long/short position ribbon along the bottom of the price pane.

  A primitive is painted by the chart in the same frame as the candles, so it
  pans, zooms and screenshots with them. Its `hitTest` reports the hovered
  group id, which the chart forwards as `hoveredObjectId` to crosshair-move
  and click subscribers (see trade-tooltip-overlay)."
  (:require [hyperopen.views.trading-chart.utils.chart-options :as chart-options]
            [hyperopen.views.trading-chart.utils.theme-colors :as theme-colors]))

(def ^:private badge-diameter {:small 16 :typical 20 :large 24})
(def ^:private badge-font-px {:small 9 :typical 10 :large 11})
(def ^:private badge-gap-px 8)
(def ^:private tick-width-px 14)
(def ^:private dense-dot-diameter 7)
;; badges are 16-24 px wide: below ~12 px per bar neighbouring candles' badges
;; pile up, so draw dots on the execution ticks instead
(def ^:private dense-bar-spacing-px 12)
(def ^:private dense-visible-group-limit 60)
(def ^:private min-hit-radius-px 16)
(def ^:private min-touch-hit-radius-px 22)
(def ^:private count-bubble-diameter 14)
(def ^:private ribbon-height-px 6)
;; badges just outside the pane still overlap its edge
(def ^:private offscreen-margin-px 24)

(defn- finite? [v]
  (and (number? v) (js/isFinite v)))

(defn- side-color
  [{:keys [side-kind]} colors]
  (case side-kind
    :buy (:long colors)
    :sell (:short colors)
    (:liquidation colors)))

(defn- badge-text
  [{:keys [side-kind]}]
  (case side-kind :buy "B" :sell "S" "L"))

(defn layout-groups
  "Pure geometry for drawing and hit-testing, in CSS pixels.
  `time->x` and `price->y` return nil for unmappable values."
  [groups {:keys [time->x price->y bar-spacing pane-width]}]
  (let [placed (keep (fn [{:keys [time avg-price candle-high candle-low sign size-step] :as group}]
                       (let [x (time->x time)
                             tick-y (when (and (finite? x)
                                               (<= (- offscreen-margin-px) x
                                                   (+ (or pane-width js/Infinity) offscreen-margin-px)))
                                      (price->y avg-price))]
                         (when (finite? tick-y)
                           (let [below? (not= -1 sign)
                                 diameter (get badge-diameter size-step 20)
                                 r (/ diameter 2)
                                 edge-y (if below?
                                          (or (price->y candle-low) tick-y)
                                          (or (price->y candle-high) tick-y))
                                 cy (if below?
                                      (+ (max edge-y tick-y) badge-gap-px r)
                                      (- (min edge-y tick-y) badge-gap-px r))]
                             {:group group
                              :id (:id group)
                              :x x
                              :tick-y tick-y
                              :cy cy
                              :r r
                              :below? below?
                              :font-px (get badge-font-px size-step 10)}))))
                     groups)
        visible-count (count placed)
        dense? (or (and (finite? bar-spacing) (< bar-spacing dense-bar-spacing-px))
                   (> visible-count dense-visible-group-limit))]
    (mapv (fn [item]
            (if dense?
              (assoc item :dense? true :cy (:tick-y item) :r (/ dense-dot-diameter 2))
              (assoc item :dense? false)))
          placed)))

(defn hit-test-layout
  "Id of the nearest drawn badge within its hit radius of (x, y), or nil."
  [layout x y touch?]
  (let [min-r (if touch? min-touch-hit-radius-px min-hit-radius-px)]
    (some->> layout
             (keep (fn [{:keys [id r cy] bx :x}]
                     (let [dx (- x bx)
                           dy (- y cy)
                           d2 (+ (* dx dx) (* dy dy))
                           hit-r (max r min-r)]
                       (when (<= d2 (* hit-r hit-r))
                         [d2 id]))))
             seq
             (apply min-key first)
             second)))

(defn- circle! [^js ctx cx cy r]
  (.beginPath ctx)
  (.arc ctx cx cy r 0 (* 2 js/Math.PI))
  (.fill ctx))

(defn- diamond! [^js ctx cx cy r]
  (.beginPath ctx)
  (.moveTo ctx cx (- cy r))
  (.lineTo ctx (+ cx r) cy)
  (.lineTo ctx cx (+ cy r))
  (.lineTo ctx (- cx r) cy)
  (.closePath ctx)
  (.fill ctx))

(defn- text! [^js ctx text cx cy px pr font-family color]
  (set! (.-fillStyle ctx) color)
  (set! (.-font ctx) (str "700 " (js/Math.round (* px pr)) "px " font-family))
  (set! (.-textAlign ctx) "center")
  (set! (.-textBaseline ctx) "middle")
  (.fillText ctx text cx cy))

(defn- draw-item!
  [^js ctx {:keys [group x tick-y cy r dense? below? font-px]} {:keys [hpr vpr colors font-family selected-id
                                                                    pane-width]}]
  (let [color (side-color group colors)
        bg (:bg colors)
        X (* x hpr)
        tick-Y (* tick-y vpr)
        CY (* cy vpr)
        R (* r vpr)
        selected? (= selected-id (:id group))]
    (if dense?
      ;; a light ring keeps dots legible on candles of the same color
      (do (set! (.-fillStyle ctx) bg)
          (circle! ctx X CY (+ R (* 2.5 vpr)))
          (set! (.-fillStyle ctx) (:ring colors))
          (circle! ctx X CY (+ R (* 1.5 vpr)))
          (set! (.-fillStyle ctx) color)
          (circle! ctx X CY R))
      (let [stem-from (if below? tick-Y (+ CY R))
            stem-to (if below? (- CY R) tick-Y)]
        ;; selected: dotted guide from the tick to the price scale
        (when selected?
          (.save ctx)
          (set! (.-strokeStyle ctx) color)
          (set! (.-lineWidth ctx) (max 1 hpr))
          (.setLineDash ctx #js [(* 2 hpr) (* 3 hpr)])
          (.beginPath ctx)
          (.moveTo ctx X tick-Y)
          (.lineTo ctx (* pane-width hpr) tick-Y)
          (.stroke ctx)
          (.restore ctx))
        ;; stem
        (set! (.-globalAlpha ctx) 0.75)
        (set! (.-fillStyle ctx) color)
        (.fillRect ctx (- X (/ hpr 2)) (min stem-from stem-to) (max 1 hpr) (js/Math.abs (- stem-to stem-from)))
        (set! (.-globalAlpha ctx) 1)
        ;; execution tick with a background outline
        (let [tw (* tick-width-px hpr)
              th (* 2 vpr)]
          (set! (.-fillStyle ctx) bg)
          (.fillRect ctx (- X (/ tw 2) hpr) (- tick-Y (/ th 2) vpr) (+ tw (* 2 hpr)) (+ th (* 2 vpr)))
          (set! (.-fillStyle ctx) color)
          (.fillRect ctx (- X (/ tw 2)) (- tick-Y (/ th 2)) tw th))
        ;; selection ring
        (when selected?
          (set! (.-fillStyle ctx) color)
          (circle! ctx X CY (+ R (* 4 vpr)))
          (set! (.-fillStyle ctx) bg)
          (circle! ctx X CY (+ R (* 2 vpr))))
        ;; badge
        (set! (.-fillStyle ctx) color)
        (if (= :liquidation (:side-kind group))
          (diamond! ctx X CY (* R 1.15))
          (circle! ctx X CY R))
        (text! ctx (badge-text group) X (+ CY (* 0.5 vpr)) font-px vpr font-family bg)
        ;; count bubble
        (when (> (:count group) 1)
          (let [bx (+ X (* 0.75 R))
                by (- CY (* 0.75 R))
                br (* (/ count-bubble-diameter 2) vpr)]
            (set! (.-fillStyle ctx) color)
            (circle! ctx bx by br)
            (set! (.-fillStyle ctx) bg)
            (circle! ctx bx by (- br vpr))
            (text! ctx (str (:count group)) bx (+ by (* 0.5 vpr)) 9 vpr font-family color)))))))

(defn- draw-ribbon!
  [^js ctx {:keys [ribbon time->x bar-spacing pane-width pane-height hpr vpr colors]}]
  (let [h (* ribbon-height-px vpr)
        top (- (* pane-height vpr) h)
        half-bar (/ (or bar-spacing 0) 2)]
    (doseq [{:keys [from-time to-time direction open-ended?]} ribbon]
      (let [x1 (time->x from-time)
            x2* (time->x to-time)
            x2 (when (finite? x2*) (if open-ended? (+ x2* half-bar) x2*))]
        (when (and (finite? x1) (finite? x2) (> x2 x1))
          (let [l (* (max 0 x1) hpr)
                r (* (min pane-width x2) hpr)
                color (if (= :long direction) (:long colors) (:short colors))]
            (when (> r l)
              (set! (.-fillStyle ctx) color)
              (set! (.-globalAlpha ctx) 0.25)
              (.fillRect ctx l top (- r l) h)
              (set! (.-globalAlpha ctx) 1)
              (.fillRect ctx l top (- r l) (* 2 vpr)))))))))

(defn- current-colors []
  {:long (theme-colors/token "--ho-chart-long")
   :short (theme-colors/token "--ho-chart-short")
   :liquidation (theme-colors/token "--ho-chart-liquidation")
   :bg (theme-colors/token "--ho-chart-bg")
   :ring (theme-colors/token "--ho-chart-text")})

(defn- mappers
  [^js chart ^js series]
  (let [^js time-scale (.timeScale chart)]
    {:time->x (fn [t] (when (some? t) (.timeToCoordinate time-scale t)))
     :price->y (fn [p] (when (finite? p) (.priceToCoordinate series p)))
     :bar-spacing (some-> (.options time-scale) (unchecked-get "barSpacing"))}))

(defn- touch-device? []
  (boolean (and (exists? js/navigator)
                (pos? (or (.-maxTouchPoints js/navigator) 0)))))

(defn create-trade-markers-primitive
  "Returns a JS object implementing ISeriesPrimitive. Update it with
  `set-trade-data!`; it repaints through the chart's own request-update."
  ([] (create-trade-markers-primitive {}))
  ([{:keys [font-family touch?]}]
   (let [state (atom {:groups [] :ribbon [] :selected-id nil :layout []})
         font-family* (or font-family (chart-options/resolve-chart-font-family))
         touch?* (if (some? touch?) touch? (touch-device?))
         paint! (fn [^js target draw-fn]
                  (.useBitmapCoordinateSpace
                   target
                   (fn [^js scope]
                     (let [{:keys [chart series]} @state]
                       (when (and chart series)
                         (let [media (.-mediaSize scope)]
                           (draw-fn (.-context scope)
                                    (merge (mappers chart series)
                                           {:hpr (.-horizontalPixelRatio scope)
                                            :vpr (.-verticalPixelRatio scope)
                                            :pane-width (.-width media)
                                            :pane-height (.-height media)
                                            :colors (current-colors)
                                            :font-family font-family*}))))))))
         ;; "normal" paints on the main canvas with the candles (drawn after
         ;; them). "top" would paint on the crosshair canvas, which repaints on
         ;; every mouse move and keeps stale badges after a detach until the
         ;; cursor moves.
         marker-view #js {:zOrder (fn [] "normal")
                          :renderer
                          (fn []
                            #js {:draw
                                 (fn [target]
                                   (paint! target
                                           (fn [ctx env]
                                             (let [{:keys [groups selected-id]} @state
                                                   layout (layout-groups groups env)]
                                               (swap! state assoc :layout layout)
                                               (doseq [item layout]
                                                 (draw-item! ctx item (assoc env :selected-id selected-id)))))))})}
         ribbon-view #js {:zOrder (fn [] "bottom")
                          :renderer
                          (fn []
                            #js {:draw
                                 (fn [target]
                                   (paint! target
                                           (fn [ctx env]
                                             (draw-ribbon! ctx (assoc env :ribbon (:ribbon @state))))))})}
         views #js [ribbon-view marker-view]]
     #js {:attached (fn [^js params]
                      (swap! state assoc
                             :chart (.-chart params)
                             :series (.-series params)
                             :request-update (.-requestUpdate params)))
          :detached (fn []
                      (swap! state assoc :chart nil :series nil :request-update nil :layout []))
          :updateAllViews (fn [] nil)
          :paneViews (fn [] views)
          :hitTest (fn [x y]
                     (when-let [id (hit-test-layout (:layout @state) x y touch?*)]
                       #js {:externalId id :zOrder "normal" :cursorStyle "pointer"}))
          "__state" state})))

(defn set-trade-data!
  "Replace the primitive's groups and ribbon; repaints only when they changed.
  Selection is owned by the hover card (`set-selected-id!`)."
  [^js primitive {:keys [groups ribbon]}]
  (when-let [state (when primitive (unchecked-get primitive "__state"))]
    (let [prev @state
          next-groups (or groups [])
          next-ribbon (or ribbon [])]
      (when-not (and (identical? next-groups (:groups prev))
                     (identical? next-ribbon (:ribbon prev)))
        (swap! state assoc :groups next-groups :ribbon next-ribbon)
        (when-let [request-update (:request-update @state)]
          (request-update))))))

(defn layout-of
  "Last painted layout (CSS px), for positioning DOM next to a badge."
  [^js primitive]
  (some-> primitive (unchecked-get "__state") deref :layout))

(defn set-selected-id!
  "Highlight one group (ring + price guide), or none with nil."
  [^js primitive selected-id]
  (when-let [state (when primitive (unchecked-get primitive "__state"))]
    (when (not= selected-id (:selected-id @state))
      (swap! state assoc :selected-id selected-id)
      (when-let [request-update (:request-update @state)]
        (request-update)))))
