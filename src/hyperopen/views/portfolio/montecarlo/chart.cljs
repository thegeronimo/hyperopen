(ns hyperopen.views.portfolio.montecarlo.chart
  "Canvas renderers for the Monte Carlo surface, attached through Replicant's
  `:replicant/on-render` lifecycle hook. The spaghetti/confidence-band chart can
  draw ~120 paths over hundreds of days, which is far too many nodes for SVG, so
  it draws to a `<canvas>` created imperatively inside the host element — a port
  of the designer prototype `charts.js`.

  All animation state (a `requestAnimationFrame` reveal sweep) is kept local to
  the renderer in a per-element holder atom, never in app state, consistent with
  the project's rule that live objects and timers stay out of core state."
  (:require [hyperopen.portfolio.montecarlo.engine :as engine]
            [hyperopen.views.portfolio.montecarlo.chart.model :as chart-model]
            [hyperopen.views.portfolio.montecarlo.format :as fmt]))

(def ^:private palette chart-model/palette)
(def ^:private mono-font chart-model/mono-font)
(def ^:private point chart-model/point)

(defn- hex->rgba
  [hex a]
  (let [h (subs hex 1)
        r (js/parseInt (subs h 0 2) 16)
        g (js/parseInt (subs h 2 4) 16)
        b (js/parseInt (subs h 4 6) 16)]
    (str "rgba(" r "," g "," b "," a ")")))

(defn- setup!
  "Size the canvas for the device pixel ratio and return its 2d context plus CSS
  width/height."
  [canvas css-h]
  (let [dpr (or (.-devicePixelRatio js/window) 1)
        cw (.-clientWidth canvas)
        w (if (pos? cw) cw (.. canvas -parentElement -clientWidth))
        ctx (.getContext canvas "2d")]
    (set! (.-width canvas) (js/Math.round (* w dpr)))
    (set! (.-height canvas) (js/Math.round (* css-h dpr)))
    (set! (.. canvas -style -height) (str css-h "px"))
    (.setTransform ctx dpr 0 0 dpr 0 0)
    {:ctx ctx :w w :h css-h}))

(defn- stroke-segment!
  [ctx from to]
  (.beginPath ctx)
  (.moveTo ctx (:x from) (:y from))
  (.lineTo ctx (:x to) (:y to))
  (.stroke ctx))

(defn- stroke-polyline!
  [ctx points]
  (.beginPath ctx)
  (doseq [[idx p] (map-indexed vector points)]
    (if (zero? idx)
      (.moveTo ctx (:x p) (:y p))
      (.lineTo ctx (:x p) (:y p))))
  (.stroke ctx))

(defn- fill-polygon!
  [ctx points color]
  (.beginPath ctx)
  (doseq [p points]
    (.lineTo ctx (:x p) (:y p)))
  (.closePath ctx)
  (set! (.-fillStyle ctx) color)
  (.fill ctx))

(defn- draw-bust-zone!
  [ctx {:keys [plot colors bust-y]}]
  (let [{:keys [pad-l pad-t plot-w plot-h]} plot]
    (when (< bust-y (+ pad-t plot-h))
      (set! (.-fillStyle ctx) (hex->rgba (:bust-col colors) 0.07))
      (.fillRect ctx pad-l bust-y plot-w (- (+ pad-t plot-h) bust-y))
      (set! (.-strokeStyle ctx) (hex->rgba (:bust-col colors) 0.55))
      (.setLineDash ctx #js [4 4])
      (set! (.-lineWidth ctx) 1)
      (stroke-segment! ctx
                       (point pad-l bust-y)
                       (point (+ pad-l plot-w) bust-y))
      (.setLineDash ctx #js []))))

(defn- draw-grid!
  [ctx {:keys [plot grid-lines]}]
  (let [{:keys [pad-l plot-w]} plot]
    (set! (.-font ctx) (str "10px " mono-font))
    (set! (.-textBaseline ctx) "middle")
    (doseq [{:keys [y label]} grid-lines]
      (set! (.-strokeStyle ctx) (hex->rgba (:border palette) 0.6))
      (set! (.-lineWidth ctx) 1)
      (stroke-segment! ctx
                       (point pad-l y)
                       (point (+ pad-l plot-w) y))
      (set! (.-fillStyle ctx) (:text-3 palette))
      (set! (.-textAlign ctx) "right")
      (.fillText ctx label (- pad-l 10) y))))

(defn- draw-x-axis!
  [ctx {:keys [x-axis]}]
  (set! (.-textAlign ctx) "center")
  (set! (.-textBaseline ctx) "top")
  (doseq [{:keys [x y label]} x-axis]
    (set! (.-fillStyle ctx) (:text-3 palette))
    (.fillText ctx label x y)))

(defn- draw-sampled-paths!
  [ctx {:keys [sampled-paths colors]}]
  (set! (.-lineWidth ctx) 0.8)
  (doseq [path sampled-paths]
    (set! (.-strokeStyle ctx) (hex->rgba (:accent colors) 0.05))
    (stroke-polyline! ctx path)))

(defn- draw-confidence-bands!
  [ctx {:keys [colors outer-band-points inner-band-points]}]
  (fill-polygon! ctx outer-band-points (hex->rgba (:band-col colors) 0.10))
  (fill-polygon! ctx inner-band-points (hex->rgba (:band-col colors) 0.16)))

(defn- draw-median!
  [ctx {:keys [colors median-points]}]
  (set! (.-lineWidth ctx) 2)
  (set! (.-strokeStyle ctx) (:accent colors))
  (stroke-polyline! ctx median-points))

(defn- draw-band-edges!
  [ctx {:keys [colors p95-points p5-points]}]
  (set! (.-lineWidth ctx) 1)
  (.setLineDash ctx #js [5 4])
  (doseq [edge [p95-points p5-points]]
    (set! (.-strokeStyle ctx) (hex->rgba (:band-col colors) 0.5))
    (stroke-polyline! ctx edge))
  (.setLineDash ctx #js []))

(defn- draw-start-marker!
  [ctx {:keys [plot start-y]}]
  (let [{:keys [pad-l plot-w]} plot]
    (set! (.-strokeStyle ctx) (hex->rgba (:text-2 palette) 0.4))
    (set! (.-lineWidth ctx) 1)
    (stroke-segment! ctx
                     (point pad-l start-y)
                     (point (+ pad-l plot-w) start-y))))

(defn- draw-goal-line!
  [ctx {:keys [plot domain goal-y]}]
  (let [{:keys [pad-l plot-w]} plot]
    (when (<= (:goal-eq domain) (:hi domain))
      (set! (.-strokeStyle ctx) (hex->rgba (:gold palette) 0.6))
      (.setLineDash ctx #js [4 4])
      (stroke-segment! ctx
                       (point pad-l goal-y)
                       (point (+ pad-l plot-w) goal-y))
      (.setLineDash ctx #js [])
      (set! (.-fillStyle ctx) (:gold palette))
      (set! (.-font ctx) (str "9px " mono-font))
      (set! (.-textAlign ctx) "left")
      (set! (.-textBaseline ctx) "bottom")
      (.fillText ctx "GOAL" (+ pad-l 4) (- goal-y 3)))))

(defn- draw-bust-label!
  [ctx {:keys [plot colors bust-y]}]
  (let [{:keys [pad-l pad-t plot-h]} plot]
    (when (< bust-y (+ pad-t plot-h))
      (set! (.-fillStyle ctx) (:bust-col colors))
      (set! (.-font ctx) (str "9px " mono-font))
      (set! (.-textAlign ctx) "left")
      (set! (.-textBaseline ctx) "top")
      (.fillText ctx "BUST" (+ pad-l 4) (+ bust-y 3)))))

(defn- draw-endpoint-dots!
  [ctx {:keys [endpoint-dots]}]
  (doseq [{:keys [x y color radius]} endpoint-dots]
    (set! (.-fillStyle ctx) color)
    (.beginPath ctx)
    (.arc ctx x y radius 0 (* 2 js/Math.PI))
    (.fill ctx)))

(defn draw-spaghetti!
  [canvas {:keys [result accent band bust-color show-paths? path-count height progress]}]
  (let [{:keys [ctx w h]} (setup! canvas (or height 420))
        model (chart-model/spaghetti-model {:w w :h h}
                                            {:result result
                                             :accent accent
                                             :band band
                                             :bust-color bust-color
                                             :show-paths? show-paths?
                                             :path-count path-count
                                             :progress progress})]
    (.clearRect ctx 0 0 w h)
    (draw-bust-zone! ctx model)
    (draw-grid! ctx model)
    (draw-x-axis! ctx model)
    (draw-confidence-bands! ctx model)
    (draw-sampled-paths! ctx model)
    (draw-median! ctx model)
    (draw-band-edges! ctx model)
    (draw-start-marker! ctx model)
    (draw-goal-line! ctx model)
    (draw-bust-label! ctx model)
    (draw-endpoint-dots! ctx model)
    (:hover-geo model)))

(defn draw-histogram!
  [canvas {:keys [dist fmt accent threshold sign-by-value? median height bins progress]}]
  (let [{:keys [ctx w h]} (setup! canvas (or height 92))
        bins (or bins 34)
        sorted (:sorted dist)
        clip-lo (engine/percentile sorted 1)
        clip-hi (engine/percentile sorted 99)
        hist (engine/histogram (:raw dist) bins {:domain [clip-lo clip-hi]})
        counts (:counts hist)
        max-c (max 1 (reduce max counts))
        accent (or accent (:accent palette))
        neg (:red palette)
        is-pct (= fmt :pct)
        progress (if (nil? progress) 1 progress)
        padb 16
        plot-h (- h padb)
        bw (/ w bins)
        mn (:min hist)
        span (let [s (:span hist)] (if (zero? s) 1 s))
        bin-width (:bin-width hist)
        fmt-fn (fn [v]
                 (if is-pct
                   (str (when (>= v 0) "+") (.toFixed (* v 100) 0) "%")
                   (.toFixed v 2)))]
    (.clearRect ctx 0 0 w h)
    (dotimes [i bins]
      (let [bin-val (+ mn (* (+ i 0.5) bin-width))
            bh (* (/ (nth counts i) max-c) plot-h progress)
            x (* i bw)
            col (cond
                  (and (some? threshold) (<= bin-val threshold)) neg
                  (and sign-by-value? (< bin-val 0)) neg
                  :else accent)]
        (set! (.-fillStyle ctx) (hex->rgba col 0.62))
        (.fillRect ctx (+ x 0.5) (- plot-h bh) (max 1 (- bw 1)) bh)))
    (when (number? median)
      (let [mx (if (:degenerate? hist)
                 (/ w 2)
                 (* (/ (- median mn) span) w))]
        (set! (.-strokeStyle ctx) "#ffffff")
        (set! (.-globalAlpha ctx) 0.8)
        (set! (.-lineWidth ctx) 1.5)
        (.beginPath ctx)
        (.moveTo ctx mx 0)
        (.lineTo ctx mx plot-h)
        (.stroke ctx)
        (set! (.-globalAlpha ctx) 1)))
    (set! (.-font ctx) (str "9px " mono-font))
    (set! (.-fillStyle ctx) (:text-3 palette))
    (set! (.-textBaseline ctx) "top")
    (set! (.-textAlign ctx) "left")
    (.fillText ctx (str (when (:overflow-lo? hist) "‹ ") (fmt-fn (:min hist))) 2 (+ plot-h 3))
    (set! (.-textAlign ctx) "right")
    (.fillText ctx (str (fmt-fn (:max hist)) (when (:overflow-hi? hist) " ›")) (- w 2) (+ plot-h 3))
    (set! (.-strokeStyle ctx) (hex->rgba (:border palette) 0.8))
    (set! (.-lineWidth ctx) 1)
    (.beginPath ctx)
    (.moveTo ctx 0 (+ plot-h 0.5))
    (.lineTo ctx w (+ plot-h 0.5))
    (.stroke ctx)))

(defn- nearest-grid-index
  "Index of the grid point whose day value is closest to `day`."
  [times day]
  (let [n (count times)]
    (loop [i 1 best 0]
      (if (< i n)
        (recur (inc i)
               (if (< (js/Math.abs (- (nth times i) day))
                      (js/Math.abs (- (nth times best) day)))
                 i best))
        best))))

(defn- tip-row
  [label value color]
  (str "<div class=\"mc-tip-row\"><span class=\"mc-tip-key\">" label "</span>"
       "<span" (if color (str " style=\"color:" color "\"") "") ">" value "</span></div>"))

(defn- show-spaghetti-tip!
  "Populate and position the tooltip from the pointer event, reading pixel
  geometry off the canvas and the percentile band off the holder's spec."
  [canvas tip holder e]
  (let [{:keys [geo spec]} @holder
        {:keys [pad-l plot-w horizon start span-years]} geo
        result (:result spec)
        times (:times result)
        band (:band result)]
    (when (and geo result (seq times) (seq (:p50 band)))
      (let [rect (.getBoundingClientRect canvas)
            x (- (.-clientX e) (.-left rect))
            frac (max 0 (min 1 (/ (- x pad-l) plot-w)))
            day (js/Math.round (* frac horizon))
            gi (nearest-grid-index times day)
            ret (fn [v] (fmt/signed-pct (- (/ v start) 1) 1))
            elapsed (if (pos? horizon) (* span-years (/ (nth times gi) horizon)) 0)]
        (set! (.-innerHTML tip)
              (str (tip-row "Time" (chart-model/axis-time-label elapsed) nil)
                   (tip-row "P95" (ret (nth (:p95 band) gi)) "var(--mc-accent)")
                   (tip-row "Median" (ret (nth (:p50 band) gi)) nil)
                   (tip-row "P5" (ret (nth (:p5 band) gi)) "var(--mc-red)")))
        (set! (.. tip -style -left)
              (str (min (- (.-innerWidth js/window) 150) (+ (.-clientX e) 16)) "px"))
        (set! (.. tip -style -top) (str (- (.-clientY e) 10) "px"))
        (.add (.-classList tip) "show")))))

(defn- start-animation!
  [canvas draw-fn holder]
  (when-let [raf (:raf @holder)]
    (js/cancelAnimationFrame raf))
  (let [dur 700
        t0 (.now js/performance)
        spec (:spec @holder)]
    (letfn [(tick [now]
              (let [p (min 1 (/ (- now t0) dur))
                    eased (- 1 (js/Math.pow (- 1 p) 3))
                    geo (draw-fn canvas (assoc spec :progress eased))]
                (swap! holder assoc :geo geo)
                (if (< p 1)
                  (swap! holder assoc :raf (js/requestAnimationFrame tick))
                  (swap! holder assoc :raf nil))))]
      (swap! holder assoc :raf (js/requestAnimationFrame tick)))))

(defn- draw-final!
  [canvas draw-fn holder]
  (swap! holder assoc :geo (draw-fn canvas (assoc (:spec @holder) :progress 1))))

(defn- render-hook
  "Build a `:replicant/on-render` handler that owns a `<canvas>` child of the
  host node and draws `spec` with `draw-fn`. When `spec` carries `:animate?`,
  a fresh `:update-key` triggers a one-shot reveal sweep; otherwise it draws the
  final frame."
  [draw-fn spec]
  (fn [{:keys [replicant/life-cycle replicant/node replicant/memory replicant/remember]}]
    (case life-cycle
      :replicant.life-cycle/mount
      (let [canvas (.createElement js/document "canvas")
            holder (atom {:spec spec})
            on-resize (fn [] (draw-final! canvas draw-fn holder))
            tip (when (:hover-tooltip? spec) (.createElement js/document "div"))
            on-move (when tip #(show-spaghetti-tip! canvas tip holder %))
            on-leave (when tip (fn [_] (.remove (.-classList tip) "show")))]
        (set! (.. canvas -style -display) "block")
        (set! (.. canvas -style -width) "100%")
        (.appendChild node canvas)
        (.addEventListener js/window "resize" on-resize)
        (when tip
          (set! (.-className tip) "mc-tip")
          (.appendChild node tip)
          (.addEventListener canvas "mousemove" on-move)
          (.addEventListener canvas "mouseleave" on-leave))
        (if (:animate? spec)
          (start-animation! canvas draw-fn holder)
          (draw-final! canvas draw-fn holder))
        (remember {:canvas canvas :holder holder :on-resize on-resize
                   :tip tip :on-move on-move :on-leave on-leave}))

      :replicant.life-cycle/update
      (let [{:keys [canvas holder]} memory]
        (when (and canvas holder)
          (let [prev-key (:update-key (:spec @holder))]
            (swap! holder assoc :spec spec)
            (when (not= prev-key (:update-key spec))
              (if (:animate? spec)
                (start-animation! canvas draw-fn holder)
                (draw-final! canvas draw-fn holder)))))
        (remember memory))

      :replicant.life-cycle/unmount
      (let [{:keys [canvas holder on-resize tip on-move on-leave]} memory]
        (when (and holder (:raf @holder))
          (js/cancelAnimationFrame (:raf @holder)))
        (when on-resize
          (.removeEventListener js/window "resize" on-resize))
        (when (and canvas on-move)
          (.removeEventListener canvas "mousemove" on-move)
          (.removeEventListener canvas "mouseleave" on-leave))
        (when (and tip (.-parentNode tip))
          (.removeChild (.-parentNode tip) tip)))

      nil)))

(defn spaghetti-on-render
  "`:replicant/on-render` handler for the equity-paths chart."
  [spec]
  (render-hook draw-spaghetti! (assoc spec :animate? true :hover-tooltip? true)))

(defn histogram-on-render
  "`:replicant/on-render` handler for a distribution histogram."
  [spec]
  (render-hook draw-histogram! spec))
