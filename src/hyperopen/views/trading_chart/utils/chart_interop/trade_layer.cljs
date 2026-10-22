(ns hyperopen.views.trading-chart.utils.chart-interop.trade-layer
  "Owns the trader's-own-trades layer on a mounted chart: the canvas
  primitive on the main series (re-attached when a chart-type change swaps
  that series) and the DOM fill-details card that follows hover and pins on
  click. State lives in a WeakMap keyed by the chart handle, like the other
  chart overlays."
  (:require [hyperopen.views.trading-chart.utils.chart-interop.trade-card :as trade-card]
            [hyperopen.views.trading-chart.utils.chart-interop.trade-markers-primitive :as primitive]))

(defonce ^:private trade-layer-sidecar (js/WeakMap.))
;; the chart handle last synced, for the QA oracle only
(defonce ^:private last-chart-obj (atom nil))

(def ^:private card-width-px 296)
(def ^:private card-offset-px 24)
(def ^:private card-margin-px 8)

(defn- layer-state
  [chart-obj]
  (if chart-obj
    (or (.get trade-layer-sidecar chart-obj) {})
    {}))

(defn- swap-layer-state!
  [chart-obj f & args]
  (let [next-state (apply f (layer-state chart-obj) args)]
    (.set trade-layer-sidecar chart-obj next-state)
    next-state))

(defn- resolve-document
  [document]
  (or document (some-> js/globalThis .-document)))

(defn- schedule-frame!
  [callback]
  (if-let [raf (some-> js/globalThis (unchecked-get "requestAnimationFrame"))]
    (raf callback)
    (callback 0)))

;; ---------------------------------------------------------------------------
;; Card

(defn- ensure-card-root!
  [chart-obj ^js container ^js doc]
  (let [{:keys [root]} (layer-state chart-obj)]
    (if (and root (identical? container (.-parentNode ^js root)))
      root
      (let [^js node (.createElement doc "div")]
        (set! (.-className node)
              (str "absolute flex flex-col gap-2 rounded-lg border border-ho-border "
                   "bg-ho-surface p-3 text-xs text-ho-text shadow-xl"))
        (.setAttribute node "role" "dialog")
        (.setAttribute node "aria-label" "Fill details")
        (.setAttribute node "data-role" "chart-trade-card")
        (set! (.. node -style -width) (str card-width-px "px"))
        ;; above the chart's navigation controls (z 116), which otherwise
        ;; cover a pinned card's history link near the bottom of the pane
        (set! (.. node -style -zIndex) "117")
        (set! (.. node -style -display) "none")
        (set! (.. node -style -overflowY) "auto")
        ;; keep chart gestures from starting under the card
        (.addEventListener node "mousedown" (fn [^js e] (.stopPropagation e)))
        (.appendChild container node)
        (swap-layer-state! chart-obj assoc :root node)
        node))))

(defn- hide-card!
  [chart-obj]
  (let [{:keys [root primitive]} (layer-state chart-obj)]
    (when root
      (set! (.. ^js root -style -display) "none"))
    (primitive/set-selected-id! primitive nil)
    (swap-layer-state! chart-obj assoc :shown-id nil :shown-group nil)))

(defn- price-scale-width
  [^js chart]
  (try
    (or (some-> chart (.priceScale "right") (.width)) 0)
    (catch :default _ 0)))

(defn- visible-height
  "Height of the container that is actually on screen. On phones the chart
  container is taller than the panel that clips it, so a card sized to the
  container would hide its lower rows (and the history link) under the
  clip. Falls back to clientHeight where layout is unavailable."
  [^js container]
  (let [ch (or (.-clientHeight container) 0)
        rect-of (fn [^js node]
                  (when (fn? (unchecked-get node "getBoundingClientRect"))
                    (.getBoundingClientRect node)))
        ^js own (rect-of container)
        view (some-> js/globalThis .-getComputedStyle)]
    (if-not (and own view)
      ch
      (loop [^js node (.-parentElement container)
             bottom (.-bottom own)
             depth 0]
        (if (or (nil? node) (> depth 12))
          (-> (- bottom (.-top own)) (max 0) (min ch))
          (let [clips? (not= "visible" (.-overflowY (view node)))
                ^js rect (when clips? (rect-of node))]
            (recur (.-parentElement node)
                   (if rect (min bottom (.-bottom rect)) bottom)
                   (inc depth))))))))

(defn- position-card!
  [chart-obj]
  (let [{:keys [root primitive shown-id container chart]} (layer-state chart-obj)
        cw (or (some-> ^js container .-clientWidth) 0)
        item (some #(when (and (= shown-id (:id %))
                               (<= 0 (:x %) (- cw (price-scale-width chart))))
                      %)
                   (primitive/layout-of primitive))]
    (when (and root container)
      (if-not item
        ;; the badge scrolled off screen: keep a pinned card hidden until it returns
        (set! (.. ^js root -style -visibility) "hidden")
        (let [ch (visible-height container)
              ;; a short chart pane scrolls the card instead of clipping it
              _ (set! (.. ^js root -style -maxHeight) (str (max 120 (- ch (* 2 card-margin-px))) "px"))
              card-h (or (.-offsetHeight ^js root) 0)
              right-limit (- cw (price-scale-width chart) card-margin-px)
              left (let [right-side (+ (:x item) card-offset-px)]
                     (if (> (+ right-side card-width-px) right-limit)
                       (- (:x item) card-offset-px card-width-px)
                       right-side))
              top (-> (- (:cy item) 40)
                      (min (- ch card-h card-margin-px))
                      (max card-margin-px))]
          (set! (.. ^js root -style -visibility) "visible")
          (set! (.. ^js root -style -left) (str (max card-margin-px left) "px"))
          (set! (.. ^js root -style -top) (str top "px")))))))

(declare refresh-card! request-older-fills!)

(defn- show-card!
  [chart-obj id]
  (let [{:keys [root groups-by-id doc opts pinned-id shown-id shown-group shown-pinned? primitive]}
        (layer-state chart-obj)
        group (get groups-by-id id)]
    (if-not (and root group)
      (hide-card! chart-obj)
      (let [pinned? (= id pinned-id)]
        (when-not (and (= id shown-id)
                       (= group shown-group)
                       (= pinned? shown-pinned?))
          (while (.-firstChild ^js root)
            (.removeChild ^js root (.-firstChild ^js root)))
          (doseq [node (trade-card/build-card-content
                        doc group
                        (assoc opts
                               :pinned? pinned?
                               :on-close (fn []
                                           (swap-layer-state! chart-obj assoc :pinned-id nil :hover-id nil)
                                           (refresh-card! chart-obj))))]
            (.appendChild ^js root node))
          (set! (.. ^js root -style -display) "flex")
          (swap-layer-state! chart-obj assoc :shown-id id :shown-group group :shown-pinned? pinned?))
        (primitive/set-selected-id! primitive id)
        (position-card! chart-obj)))))

(defn- refresh-card!
  [chart-obj]
  (let [{:keys [pinned-id hover-id groups-by-id]} (layer-state chart-obj)
        pinned-id* (when (contains? groups-by-id pinned-id) pinned-id)
        active (or pinned-id* (when (contains? groups-by-id hover-id) hover-id))]
    (when (not= pinned-id pinned-id*)
      (swap-layer-state! chart-obj assoc :pinned-id nil))
    (if active
      (show-card! chart-obj active)
      (hide-card! chart-obj))))

(defn- hovered-id
  [^js param]
  (let [id (when param (.-hoveredObjectId param))]
    (when (string? id) id)))

(defn- tapped-id
  "lightweight-charts does not hit-test primitives on touch taps (the click
  param carries no hoveredObjectId), so hit-test the painted layout at the
  tap point ourselves. Only the price pane (index 0) holds badges."
  [chart-obj ^js param]
  (let [^js point (when param (.-point param))
        pane-index (when param (.-paneIndex param))]
    (when (and point (or (nil? pane-index) (zero? pane-index)))
      (primitive/hit-test-layout (primitive/layout-of (:primitive (layer-state chart-obj)))
                                 (.-x point)
                                 (.-y point)
                                 true))))

;; ---------------------------------------------------------------------------
;; Subscriptions

(defn- subscribe!
  [chart-obj ^js chart ^js doc]
  (let [^js time-scale (.timeScale chart)
        crosshair (fn [param]
                    (swap-layer-state! chart-obj assoc :hover-id (hovered-id param))
                    (refresh-card! chart-obj))
        click (fn [param]
                (let [id (or (hovered-id param) (tapped-id chart-obj param))
                      {:keys [pinned-id]} (layer-state chart-obj)]
                  (swap-layer-state! chart-obj assoc
                                     :pinned-id (when (and id (not= id pinned-id)) id)
                                     :hover-id id)
                  (refresh-card! chart-obj)))
        range-change (fn [_]
                       (schedule-frame! (fn [_]
                                          (when (:shown-id (layer-state chart-obj))
                                            (position-card! chart-obj))
                                          ;; scrolling back may reach the covered edge
                                          (request-older-fills! chart-obj))))
        keydown (fn [^js e]
                  (when (and (= "Escape" (.-key e))
                             (not (.-defaultPrevented e))
                             (:pinned-id (layer-state chart-obj)))
                    (swap-layer-state! chart-obj assoc :pinned-id nil :hover-id nil)
                    (refresh-card! chart-obj)))]
    (.subscribeCrosshairMove chart crosshair)
    (.subscribeClick chart click)
    (.subscribeVisibleLogicalRangeChange time-scale range-change)
    (.addEventListener doc "keydown" keydown)
    {:chart chart
     :time-scale time-scale
     :doc doc
     :crosshair crosshair
     :click click
     :range-change range-change
     :keydown keydown}))

(defn- unsubscribe!
  [{:keys [chart time-scale doc crosshair click range-change keydown]}]
  (try
    (when chart
      (.unsubscribeCrosshairMove ^js chart crosshair)
      (.unsubscribeClick ^js chart click))
    (when time-scale
      (.unsubscribeVisibleLogicalRangeChange ^js time-scale range-change))
    (catch :default _ nil))
  (when doc
    (.removeEventListener ^js doc "keydown" keydown)))

;; ---------------------------------------------------------------------------
;; Primitive

(defn- ensure-primitive!
  "Attach the primitive to the current main series. A chart-type change
  replaces the main series (removing the old one detaches its primitives), so
  re-attach whenever the series identity changes."
  [chart-obj create-primitive]
  (let [{:keys [primitive series]} (layer-state chart-obj)
        ^js main-series (.-mainSeries ^js chart-obj)]
    (cond
      (nil? main-series) nil
      (and primitive (identical? series main-series)) primitive
      :else (let [primitive* (or primitive (create-primitive))]
              (when (and series (not (identical? series main-series)))
                (try (.detachPrimitive ^js series primitive*) (catch :default _ nil)))
              (.attachPrimitive main-series primitive*)
              (swap-layer-state! chart-obj assoc :primitive primitive* :series main-series)
              primitive*))))

(defn- visible-time-range-ms
  "[from to] of the visible candles in epoch ms, or nil before data exists."
  [^js chart]
  (try
    (let [^js time-scale (.timeScale chart)
          ^js visible (when (fn? (unchecked-get time-scale "getVisibleRange"))
                        (.getVisibleRange time-scale))
          from (some-> visible .-from)
          to (some-> visible .-to)]
      (when (and (number? from) (number? to))
        [(* 1000 from) (* 1000 to)]))
    (catch :default _ nil)))

(defn- older-fills-needed?
  "Older fills are needed once the visible range, plus one more screen of
  lookahead to the left, reaches past the covered history. Paging follows
  what the trader looks at rather than every loaded candle: on a daily chart
  the loaded candles go back years, and fetching that far for an account
  that never scrolls back spends Hyperliquid request weight for nothing."
  [chart end-ms]
  (when-let [[from to] (visible-time-range-ms chart)]
    (<= (- from (- to from)) end-ms)))

(defn- request-older-fills!
  "Ask once per page for fills older than the loaded ones, when the chart's
  candles reach further back (the vm computes `:older-fills-end-ms`) and the
  visible range is near that edge. Re-checked as the trader scrolls."
  [chart-obj]
  (let [{:keys [chart paging requested-older-end-ms]} (layer-state chart-obj)
        {:keys [older-fills-end-ms on-request-older-fills]} paging]
    (when (and (number? older-fills-end-ms)
               (fn? on-request-older-fills)
               (not= older-fills-end-ms requested-older-end-ms)
               chart
               (older-fills-needed? chart older-fills-end-ms))
      (swap-layer-state! chart-obj assoc :requested-older-end-ms older-fills-end-ms)
      (on-request-older-fills older-fills-end-ms))))

(defn clear-chart-trades!
  "Detach the primitive, drop the card and every listener."
  [chart-obj]
  (when chart-obj
    (let [{:keys [primitive series root subscription]} (layer-state chart-obj)]
      (unsubscribe! subscription)
      (when (and primitive series)
        ;; empty the primitive before detaching so the frame painted right
        ;; after (by its own request or the detach) cannot show stale badges
        (primitive/set-selected-id! primitive nil)
        (primitive/set-trade-data! primitive {:groups [] :ribbon []})
        (try (.detachPrimitive ^js series primitive) (catch :default _ nil)))
      (when-let [parent (some-> ^js root .-parentNode)]
        (.removeChild ^js parent root))
      (.delete trade-layer-sidecar chart-obj)
      (when (identical? chart-obj @last-chart-obj)
        (reset! last-chart-obj nil)))))

(defn sync-chart-trades!
  "Show `chart-trades` ({:groups :groups-by-id :ribbon}) on the chart, or clear
  the layer when it is nil (setting off).
  opts: {:perp? :coin-label :format-price :on-open-history :document
         :create-primitive}"
  [chart-obj container chart-trades {:keys [document create-primitive] :as opts}]
  (let [chart (when chart-obj (.-chart ^js chart-obj))
        doc (resolve-document document)]
    (if-not (and chart container doc (map? chart-trades))
      (clear-chart-trades! chart-obj)
      (let [primitive* (ensure-primitive! chart-obj (or create-primitive primitive/create-trade-markers-primitive))
            {:keys [subscription]} (layer-state chart-obj)]
        (when-not (identical? chart (:chart subscription))
          (unsubscribe! subscription)
          (swap-layer-state! chart-obj assoc :subscription (subscribe! chart-obj chart doc)))
        (ensure-card-root! chart-obj container doc)
        (swap-layer-state! chart-obj assoc
                           :container container
                           :chart chart
                           :doc doc
                           :groups-by-id (or (:groups-by-id chart-trades) {})
                           :opts (select-keys opts [:perp? :coin-label :format-price :on-open-history])
                           :paging (select-keys opts [:older-fills-end-ms :on-request-older-fills]))
        (primitive/set-trade-data! primitive* chart-trades)
        (reset! last-chart-obj chart-obj)
        (refresh-card! chart-obj)
        (request-older-fills! chart-obj)))))

(defn debug-snapshot
  "The painted badges in viewport CSS px, plus the shown/pinned ids, for the
  QA oracle: badges live on a canvas, so browser tests cannot locate them in
  the DOM."
  []
  (let [chart-obj @last-chart-obj
        {:keys [primitive container groups-by-id shown-id pinned-id chart paging requested-older-end-ms]}
        (layer-state chart-obj)
        ^js rect (some-> ^js container .getBoundingClientRect)
        [visible-from visible-to] (when chart (visible-time-range-ms chart))]
    {:present (some? primitive)
     :visibleFromMs visible-from
     :visibleToMs visible-to
     :olderFillsEndMs (:older-fills-end-ms paging)
     :requestedOlderEndMs requested-older-end-ms
     :groupCount (count groups-by-id)
     :shownId shown-id
     :pinnedId pinned-id
     :badges (mapv (fn [{:keys [id x cy r dense?]}]
                     {:id id
                      :x (+ x (if rect (.-left rect) 0))
                      :y (+ cy (if rect (.-top rect) 0))
                      :r r
                      :dense (boolean dense?)})
                   (primitive/layout-of primitive))}))
