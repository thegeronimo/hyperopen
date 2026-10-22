(ns hyperopen.views.trading-chart.utils.chart-interop.trade-layer
  "Owns the trader's-own-trades layer on a mounted chart: the canvas
  primitive on the main series (re-attached when a chart-type change swaps
  that series) and the DOM fill-details card that follows hover and pins on
  click. State lives in a WeakMap keyed by the chart handle, like the other
  chart overlays."
  (:require [hyperopen.views.trading-chart.utils.chart-interop.trade-card :as trade-card]
            [hyperopen.views.trading-chart.utils.chart-interop.trade-markers-primitive :as primitive]))

(defonce ^:private trade-layer-sidecar (js/WeakMap.))

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
              (str "absolute z-30 flex flex-col gap-2 rounded-lg border border-ho-border "
                   "bg-ho-surface p-3 text-xs text-ho-text shadow-xl"))
        (.setAttribute node "role" "dialog")
        (.setAttribute node "aria-label" "Fill details")
        (.setAttribute node "data-role" "chart-trade-card")
        (set! (.. node -style -width) (str card-width-px "px"))
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
        (let [ch (or (.-clientHeight ^js container) 0)
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
          (doseq [node (trade-card/build-card-content doc group (assoc opts :pinned? pinned?))]
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
                                            (position-card! chart-obj)))))
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

(defn- request-older-fills!
  "Ask once per page for fills older than the loaded ones when the chart's
  candles reach further back (the vm computes `:older-fills-end-ms`)."
  [chart-obj {:keys [older-fills-end-ms on-request-older-fills]}]
  (when (and (number? older-fills-end-ms)
             (fn? on-request-older-fills)
             (not= older-fills-end-ms (:requested-older-end-ms (layer-state chart-obj))))
    (swap-layer-state! chart-obj assoc :requested-older-end-ms older-fills-end-ms)
    (on-request-older-fills older-fills-end-ms)))

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
      (.delete trade-layer-sidecar chart-obj))))

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
                           :opts (select-keys opts [:perp? :coin-label :format-price :on-open-history]))
        (primitive/set-trade-data! primitive* chart-trades)
        (refresh-card! chart-obj)
        (request-older-fills! chart-obj opts)))))
