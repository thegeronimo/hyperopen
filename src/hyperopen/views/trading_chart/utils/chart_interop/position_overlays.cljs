(ns hyperopen.views.trading-chart.utils.chart-interop.position-overlays
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.shared :as account-shared]))

(defonce ^:private position-overlays-sidecar (js/WeakMap.))

(def ^:private long-line-color "rgba(34, 201, 151, 0.9)")
(def ^:private short-line-color "rgba(227, 95, 120, 0.9)")
(def ^:private long-badge-color "rgba(34, 201, 151, 0.14)")
(def ^:private short-badge-color "rgba(227, 95, 120, 0.14)")
(def ^:private liq-line-color "rgba(227, 95, 120, 0.88)")
(def ^:private liq-badge-color "rgba(227, 95, 120, 0.14)")
(def ^:private dark-badge-bg "rgba(7, 17, 25, 0.82)")
(def ^:private long-text-color "rgb(151, 252, 228)")
(def ^:private short-text-color "rgb(244, 187, 198)")
(def ^:private liq-text-color "rgb(255, 196, 203)")
(def ^:private liq-drag-text-color "rgb(252, 222, 157)")
(def ^:private badge-char-width-px 6.2)
(def ^:private chart-edge-padding-px 12)
(def ^:private min-visible-pnl-segment-px 24)
(def ^:private pnl-badge-left-anchor-ratio 0.17)
(def ^:private pnl-badge-left-anchor-min-px 96)
(def ^:private pnl-badge-left-anchor-max-px 180)
(def ^:private min-liquidation-drag-margin-amount 0.000001)

(declare begin-liquidation-drag!)

(defn- overlay-state
  [chart-obj]
  (if chart-obj
    (or (.get position-overlays-sidecar chart-obj) {})
    {}))

(defn- set-overlay-state!
  [chart-obj state]
  (when chart-obj
    (.set position-overlays-sidecar chart-obj state))
  state)

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))))

(defn- non-negative-number
  [value fallback]
  (if (and (finite-number? value)
           (not (neg? value)))
    value
    fallback))

(defn- parse-number
  [value]
  (account-shared/parse-optional-num value))

(defn- clamp
  [value min-value max-value]
  (-> value
      (max min-value)
      (min max-value)))

(defn- right-label-reserve-px
  [width]
  (clamp (* (non-negative-number width 0) 0.14)
         88
         168))

(defn- estimate-badge-width-px
  [base-width text]
  (let [chars (count (or (some-> text str) ""))]
    (clamp (+ base-width (* chars badge-char-width-px))
           72
           300)))

(defn- clamp-badge-center-x
  [width preferred-x badge-width]
  (let [safe-width (non-negative-number width 0)
        half-width (/ badge-width 2)
        min-x (+ chart-edge-padding-px half-width)
        max-x (- safe-width
                 (right-label-reserve-px safe-width)
                 half-width)
        fallback (/ safe-width 2)]
    (if (<= max-x min-x)
      fallback
      (clamp (non-negative-number preferred-x fallback) min-x max-x))))

(defn- preferred-pnl-badge-x
  [width]
  (let [safe-width (non-negative-number width 0)]
    (clamp (* safe-width pnl-badge-left-anchor-ratio)
           pnl-badge-left-anchor-min-px
           pnl-badge-left-anchor-max-px)))

(defn- apply-inline-style!
  [el style-map]
  (doseq [[k v] style-map]
    (aset (.-style el) k v))
  el)

(defn- invoke-method
  [target method-name & args]
  (let [method (when target
                 (aget target method-name))]
    (when (fn? method)
      (.apply method target (to-array args)))))

(defn- clear-children!
  [el]
  (loop []
    (when-let [child (.-firstChild el)]
      (.removeChild el child)
      (recur))))

(defn- resolve-document
  [document]
  (or document
      (some-> js/globalThis .-document)))

(defn- side-key
  [overlay]
  (if (= :short (:side overlay)) :short :long))

(defn- side-line-color
  [overlay]
  (if (= :short (side-key overlay)) short-line-color long-line-color))

(defn- side-badge-color
  [overlay]
  (if (= :short (side-key overlay)) short-badge-color long-badge-color))

(defn- side-text-color
  [overlay]
  (if (= :short (side-key overlay)) short-text-color long-text-color))

(defn- format-price-text
  [format-price value]
  (let [formatted (or (when (fn? format-price)
                         (or (try
                               (format-price value value)
                               (catch :default _ nil))
                             (try
                               (format-price value)
                               (catch :default _ nil))))
                       (account-shared/format-trade-price value)
                       "0.00")
        text (some-> formatted str str/trim)]
    (cond
      (not (seq text)) "$0.00"
      (or (str/starts-with? text "$")
          (str/starts-with? text "<$"))
      text
      :else
      (str "$" text))))

(defn- format-size-text
  [format-size value]
  (or (when (fn? format-size)
        (format-size value))
      (account-shared/format-currency value)
      "0.00"))

(defn- format-pnl-text
  [pnl]
  (let [pnl* (or (parse-number pnl) 0)
        sign (cond
               (pos? pnl*) "+"
               (neg? pnl*) "-"
               :else "")]
    (str sign "$" (account-shared/format-currency (js/Math.abs pnl*)))))

(defn- event-client-coordinate
  [event k]
  (when event
    (parse-number (aget event k))))

(defn- event-client-y
  [event]
  (event-client-coordinate event "clientY"))

(defn- event-client-x
  [event]
  (event-client-coordinate event "clientX"))

(defn- number->px
  [value fallback]
  (if (finite-number? value)
    value
    fallback))

(defn- event-anchor
  [overlay source-node event]
  (let [window* (:window overlay)
        viewport-width (or (some-> window* .-innerWidth parse-number)
                           (some-> js/globalThis .-innerWidth parse-number)
                           1280)
        viewport-height (or (some-> window* .-innerHeight parse-number)
                            (some-> js/globalThis .-innerHeight parse-number)
                            800)
        rect (try
               (when-let [method (some-> source-node (aget "getBoundingClientRect"))]
                 (when (fn? method)
                   (.call method source-node)))
               (catch :default _ nil))
        fallback-left (event-client-x event)
        fallback-top (event-client-y event)
        left (number->px (some-> rect (aget "left") parse-number) (or fallback-left 0))
        top (number->px (some-> rect (aget "top") parse-number) (or fallback-top 0))
        width (max 0 (number->px (some-> rect (aget "width") parse-number) 0))
        height (max 0 (number->px (some-> rect (aget "height") parse-number) 0))
        right (+ left width)
        bottom (+ top height)]
    {:left left
     :right right
     :top top
     :bottom bottom
     :width width
     :height height
     :viewport-width viewport-width
     :viewport-height viewport-height}))

(defn- liquidation-margin-delta
  [overlay current-liq-price target-liq-price]
  (let [abs-size (parse-number (:abs-size overlay))
        side (:side overlay)
        current* (parse-number current-liq-price)
        target* (parse-number target-liq-price)]
    (when (and (finite-number? abs-size)
               (pos? abs-size)
               (finite-number? current*)
               (pos? current*)
               (finite-number? target*)
               (pos? target*)
               (contains? #{:long :short} side))
      (case side
        :long (* abs-size (- current* target*))
        :short (* abs-size (- target* current*))
        nil))))

(defn- liquidation-drag-suggestion
  [overlay current-liq-price target-liq-price]
  (let [margin-delta (liquidation-margin-delta overlay current-liq-price target-liq-price)
        margin-delta* (if (finite-number? margin-delta) margin-delta 0)
        abs-delta (when (finite-number? margin-delta)
                    (js/Math.abs margin-delta))]
    (when (and (finite-number? abs-delta)
               (>= abs-delta min-liquidation-drag-margin-amount))
      {:mode (if (neg? margin-delta*)
               :remove
               :add)
       :amount abs-delta
       :current-liquidation-price current-liq-price
       :target-liquidation-price target-liq-price})))

(defn- liquidation-drag-label
  [overlay current-liq-price target-liq-price]
  (when-let [{:keys [mode amount]} (liquidation-drag-suggestion overlay
                                                                current-liq-price
                                                                target-liq-price)]
    (let [mode-label (if (= mode :remove) "Remove" "Add")
          amount-text (account-shared/format-currency amount)]
      (str mode-label " $" amount-text " Margin"))))

(defn- create-overlay-root!
  [document]
  (let [root (.createElement document "div")]
    (set! (.-className root) "chart-position-overlays")
    (apply-inline-style!
     root
     {"position" "absolute"
      "inset" "0px"
      "pointerEvents" "none"
      "zIndex" "13"
      "overflow" "hidden"})
    root))

(defn- ensure-overlay-root!
  [chart-obj container document]
  (let [{:keys [root]} (overlay-state chart-obj)
        mounted-root? (and root (identical? (.-parentNode root) container))
        next-root (if mounted-root?
                    root
                    (create-overlay-root! document))]
    (when (and root (not mounted-root?))
      (when-let [parent (.-parentNode root)]
        (.removeChild parent root)))
    (when (and next-root
               (not (identical? (.-parentNode next-root) container)))
      (.appendChild container next-root))
    next-root))

(defn- render-pnl-badge!
  [document row overlay center-x pnl-label-text]
  (let [badge (.createElement document "div")
        text-node (.createElement document "span")]
    (apply-inline-style!
     badge
     {"position" "absolute"
      "left" (str center-x "px")
      "top" "0px"
      "transform" "translate(-50%, -50%)"
      "display" "inline-flex"
      "alignItems" "center"
      "padding" "2px 7px"
      "fontSize" "11px"
      "lineHeight" "16px"
      "fontWeight" "600"
      "borderRadius" "3px"
      "border" (str "1px solid " (side-line-color overlay))
      "background" (side-badge-color overlay)
      "backdropFilter" "blur(0.5px)"
      "color" (side-text-color overlay)
      "pointerEvents" "none"})
    (set! (.-textContent text-node) pnl-label-text)
    (apply-inline-style!
     text-node
     {"whiteSpace" "nowrap"
      "userSelect" "none"})
    (.appendChild badge text-node)
    (.appendChild row badge)
    row))

(defn- build-pnl-row!
  [document overlay start-x end-x y width]
  (let [segment-left (min start-x end-x)
        segment-right (max start-x end-x)
        raw-segment-width (max 0 (- segment-right segment-left))
        show-segment-line? (>= raw-segment-width min-visible-pnl-segment-px)
        row (.createElement document "div")
        line (.createElement document "div")
        pnl-text (format-pnl-text (:unrealized-pnl overlay))
        size-text (format-size-text (:format-size overlay) (:abs-size overlay))
        pnl-label-text (str "PNL " pnl-text " | " size-text)
        estimated-badge-width (estimate-badge-width-px 56 pnl-label-text)
        center-x (clamp-badge-center-x width
                                       (preferred-pnl-badge-x width)
                                       estimated-badge-width)]
    (apply-inline-style!
     row
     {"position" "absolute"
      "left" "0px"
      "right" "0px"
      "top" (str y "px")
      "height" "0px"
      "pointerEvents" "none"})
    (when show-segment-line?
      (apply-inline-style!
       line
       {"position" "absolute"
        "left" (str segment-left "px")
        "width" (str raw-segment-width "px")
        "top" "0px"
        "borderTop" (str "2px solid " (side-line-color overlay))
        "opacity" "0.92"
        "pointerEvents" "none"})
      (.appendChild row line))
    (render-pnl-badge! document row overlay center-x pnl-label-text)
    row))

(defn- build-liquidation-row!
  [chart-obj document overlay y width]
  (let [row (.createElement document "div")
        line (.createElement document "div")
        badge (.createElement document "div")
        label (.createElement document "span")
        price (.createElement document "span")
        drag-note (.createElement document "span")
        liq-price-text (format-price-text (:format-price overlay)
                                          (:liquidation-price overlay))
        drag-label (liquidation-drag-label overlay
                                           (:current-liquidation-price overlay)
                                           (:liquidation-price overlay))
        liq-label-text (str "Liq. Price " liq-price-text)
        full-label-text (if (seq drag-label)
                          (str liq-label-text " | " drag-label)
                          liq-label-text)
        estimated-badge-width (estimate-badge-width-px 52 full-label-text)
        badge-x (clamp-badge-center-x width
                                      (+ chart-edge-padding-px
                                         (/ estimated-badge-width 2)
                                         10)
                                      estimated-badge-width)]
    (apply-inline-style!
     row
     {"position" "absolute"
      "left" "0px"
      "right" "0px"
      "top" (str y "px")
      "height" "0px"
      "pointerEvents" "none"})
    (apply-inline-style!
     line
     {"position" "absolute"
      "left" "0px"
      "right" "0px"
      "top" "0px"
      "borderTop" (str "1px dashed " liq-line-color)
      "opacity" "0.84"
      "pointerEvents" "none"})
    (apply-inline-style!
     badge
     {"position" "absolute"
      "left" (str badge-x "px")
      "top" "0px"
      "transform" "translate(-50%, -50%)"
      "display" "inline-flex"
      "alignItems" "center"
      "gap" "6px"
      "padding" "2px 6px"
      "fontSize" "11px"
      "lineHeight" "16px"
     "fontWeight" "600"
      "borderRadius" "3px"
      "border" (str "1px solid " liq-line-color)
      "background" liq-badge-color
      "backdropFilter" "blur(0.5px)"
      "color" liq-text-color
      "cursor" "ns-resize"
      "pointerEvents" "auto"})
    (set! (.-textContent label) "Liq. Price")
    (set! (.-textContent price) liq-price-text)
    (set! (.-textContent drag-note) (or drag-label ""))
    (apply-inline-style!
     drag-note
     {"color" liq-drag-text-color
      "whiteSpace" "nowrap"
      "display" (if (seq drag-label) "inline" "none")})
    (.setAttribute badge "title" "Drag to adjust liquidation target")
    (.setAttribute badge "data-position-liq-drag-handle" "true")
    (let [drag-started? (atom false)
          on-pointer-down
          (fn [event]
            (when event
              (.preventDefault event)
              (.stopPropagation event))
            (when-not @drag-started?
              (reset! drag-started? true)
              (begin-liquidation-drag! chart-obj overlay badge event)))]
      (.addEventListener badge "pointerdown" on-pointer-down)
      (.addEventListener badge "mousedown" on-pointer-down)
      (.addEventListener badge "touchstart" on-pointer-down))
    (.appendChild badge label)
    (.appendChild badge price)
    (.appendChild badge drag-note)
    (.appendChild row line)
    (.appendChild row badge)
    row))

(declare render-overlays!)

(defn- teardown-subscription!
  [{:keys [time-scale main-series repaint]}]
  (when repaint
    (invoke-method time-scale "unsubscribeVisibleTimeRangeChange" repaint)
    (invoke-method time-scale "unsubscribeVisibleLogicalRangeChange" repaint)
    (invoke-method time-scale "unsubscribeSizeChange" repaint)
    (invoke-method main-series "unsubscribeDataChanged" repaint)))

(defn- subscribe-overlay-repaint!
  [chart-obj chart main-series]
  (let [time-scale (invoke-method chart "timeScale")
        repaint (fn [_]
                  (render-overlays! chart-obj))]
    (invoke-method time-scale "subscribeVisibleTimeRangeChange" repaint)
    (invoke-method time-scale "subscribeVisibleLogicalRangeChange" repaint)
    (invoke-method time-scale "subscribeSizeChange" repaint)
    (invoke-method main-series "subscribeDataChanged" repaint)
    {:chart chart
     :main-series main-series
     :time-scale time-scale
     :repaint repaint}))

(defn- resolve-time-coordinate
  [chart entry-time]
  (when (finite-number? entry-time)
    (let [time-scale (invoke-method chart "timeScale")]
      (invoke-method time-scale "timeToCoordinate" entry-time))))

(defn- remove-event-listener!
  [target event-name handler]
  (when (and target
             (fn? (aget target "removeEventListener"))
             (fn? handler))
    (.removeEventListener target event-name handler)))

(defn- add-event-listener!
  [target event-name handler]
  (when (and target
             (fn? (aget target "addEventListener"))
             (fn? handler))
    (.addEventListener target event-name handler)))

(defn- teardown-drag-listeners!
  [state]
  (let [{:keys [target on-move on-up on-cancel]} (:drag-listeners state)]
    (remove-event-listener! target "pointermove" on-move)
    (remove-event-listener! target "pointerup" on-up)
    (remove-event-listener! target "pointercancel" on-cancel)
    (remove-event-listener! target "mousemove" on-move)
    (remove-event-listener! target "mouseup" on-up)
    (remove-event-listener! target "touchmove" on-move)
    (remove-event-listener! target "touchend" on-up)
    (remove-event-listener! target "touchcancel" on-cancel))
  (dissoc state :drag-listeners))

(defn- event-root-y
  [root event]
  (let [client-y (event-client-y event)
        rect (try
               (when-let [method (some-> root (aget "getBoundingClientRect"))]
                 (when (fn? method)
                   (.call method root)))
               (catch :default _ nil))
        top (some-> rect (aget "top") parse-number)]
    (when (finite-number? client-y)
      (if (finite-number? top)
        (- client-y top)
        client-y))))

(defn- y->liq-price
  [main-series y]
  (when (finite-number? y)
    (let [price (parse-number (invoke-method main-series "coordinateToPrice" y))]
      (when (and (finite-number? price)
                 (pos? price))
        price))))

(defn- liq-price-from-event
  [state event]
  (let [root (:root state)
        main-series (:main-series state)
        y (event-root-y root event)]
    (y->liq-price main-series y)))

(defn- update-liquidation-drag-preview!
  [chart-obj event]
  (let [state (overlay-state chart-obj)]
    (when (and (map? (:drag state))
               (:root state)
               (:main-series state))
      (when-let [preview-price (liq-price-from-event state event)]
        (set-overlay-state! chart-obj (assoc-in state [:drag :preview-liquidation-price] preview-price))
        (render-overlays! chart-obj)))))

(defn- finalize-liquidation-drag!
  [chart-obj event canceled?]
  (let [state (overlay-state chart-obj)
        drag (:drag state)
        start-price (parse-number (:start-liquidation-price drag))
        preview-price (or (liq-price-from-event state event)
                          (parse-number (:preview-liquidation-price drag))
                          start-price)
        on-liquidation-drag-confirm (:on-liquidation-drag-confirm state)
        overlay-for-confirm (:overlay-for-confirm drag)
        source-node (:source-node drag)
        next-state (-> state
                       teardown-drag-listeners!
                       (dissoc :drag))]
    (set-overlay-state! chart-obj next-state)
    (render-overlays! chart-obj)
    (when (and (not canceled?)
               (finite-number? start-price)
               (finite-number? preview-price)
               (fn? on-liquidation-drag-confirm)
               (map? overlay-for-confirm))
      (when-let [suggestion (liquidation-drag-suggestion overlay-for-confirm
                                                         start-price
                                                         preview-price)]
        (on-liquidation-drag-confirm
         (assoc suggestion
                :anchor (event-anchor (:overlay next-state)
                                      source-node
                                      event)))))))

(defn begin-liquidation-drag!
  [chart-obj overlay-for-confirm source-node event]
  (let [state (overlay-state chart-obj)
        start-price (parse-number (:liquidation-price overlay-for-confirm))
        target (or (:window state)
                   (some-> (:document state) .-defaultView)
                   (:document state)
                   js/globalThis)]
    (when (and (finite-number? start-price)
               (pos? start-price)
               (map? overlay-for-confirm))
      (let [on-move (fn [drag-event]
                      (when drag-event
                        (.preventDefault drag-event))
                      (update-liquidation-drag-preview! chart-obj drag-event))
            on-up (fn [drag-event]
                    (when drag-event
                      (.preventDefault drag-event)
                      (.stopPropagation drag-event))
                    (finalize-liquidation-drag! chart-obj drag-event false))
            on-cancel (fn [drag-event]
                        (when drag-event
                          (.preventDefault drag-event))
                        (finalize-liquidation-drag! chart-obj drag-event true))
            state* (-> state
                       teardown-drag-listeners!
                       (assoc :drag {:source-node source-node
                                     :overlay-for-confirm overlay-for-confirm
                                     :start-liquidation-price start-price
                                     :preview-liquidation-price start-price}
                              :drag-listeners {:target target
                                               :on-move on-move
                                               :on-up on-up
                                               :on-cancel on-cancel}))]
        (add-event-listener! target "pointermove" on-move)
        (add-event-listener! target "pointerup" on-up)
        (add-event-listener! target "pointercancel" on-cancel)
        (add-event-listener! target "mousemove" on-move)
        (add-event-listener! target "mouseup" on-up)
        (add-event-listener! target "touchmove" on-move)
        (add-event-listener! target "touchend" on-up)
        (add-event-listener! target "touchcancel" on-cancel)
        (set-overlay-state! chart-obj state*)
        (render-overlays! chart-obj)))))

(defn render-overlays!
  [chart-obj]
  (let [{:keys [root chart main-series overlay drag]} (overlay-state chart-obj)
        document (:document overlay)
        format-price (:format-price overlay)
        format-size (:format-size overlay)]
    (when root
      (clear-children! root)
      (when (and (map? overlay)
                 main-series
                 document)
        (let [entry-price (parse-number (:entry-price overlay))
              entry-y (when (and (finite-number? entry-price)
                                 (pos? entry-price))
                        (invoke-method main-series "priceToCoordinate" entry-price))
              base-liq-price (parse-number (:liquidation-price overlay))
              drag-preview-price (some-> drag :preview-liquidation-price parse-number)
              current-liq-price (or (some-> drag :start-liquidation-price parse-number)
                                    base-liq-price)
              liq-price (if (finite-number? drag-preview-price)
                          drag-preview-price
                          base-liq-price)
              liq-y (when (and (finite-number? liq-price)
                               (pos? liq-price))
                      (invoke-method main-series "priceToCoordinate" liq-price))
              pane-size (invoke-method chart "paneSize" 0)
              pane-width (some-> pane-size (aget "width"))
              width (non-negative-number pane-width
                                         (non-negative-number (.-clientWidth root) 0))
              latest-time (parse-number (:latest-time overlay))
              entry-time (parse-number (:entry-time overlay))
              latest-x (or (resolve-time-coordinate chart latest-time)
                           (- width 8))
              entry-x (or (resolve-time-coordinate chart entry-time)
                          (max 0 (- latest-x 260)))
              start-x (clamp (non-negative-number entry-x 0) 0 (max 0 width))
              end-x (clamp (non-negative-number latest-x width) 0 (max 0 width))
              height (or (.-clientHeight root) 0)
              overlay* (assoc overlay
                              :document document
                              :format-price format-price
                              :format-size format-size
                              :current-liquidation-price current-liq-price)]
          (when (and (finite-number? entry-y)
                     (or (zero? height)
                         (and (> entry-y -30)
                              (< entry-y (+ height 30)))))
            (.appendChild root
                          (build-pnl-row! document overlay* start-x end-x entry-y width)))
          (when (and (finite-number? liq-y)
                     (or (zero? height)
                         (and (> liq-y -30)
                              (< liq-y (+ height 30)))))
            (.appendChild root
                          (build-liquidation-row! chart-obj document overlay* liq-y width))))))))

(defn clear-position-overlays!
  [chart-obj]
  (when chart-obj
    (let [{:keys [root subscription] :as state} (overlay-state chart-obj)]
      (teardown-subscription! subscription)
      (teardown-drag-listeners! state)
      (when root
        (clear-children! root)
        (when-let [parent (.-parentNode root)]
          (.removeChild parent root)))
      (.delete position-overlays-sidecar chart-obj))))

(defn sync-position-overlays!
  ([chart-obj container overlay]
   (sync-position-overlays! chart-obj container overlay {}))
  ([chart-obj container overlay {:keys [document window format-price format-size on-liquidation-drag-confirm]
                                 :or {format-price account-shared/format-trade-price
                                      format-size account-shared/format-currency}}]
   (if-not (and chart-obj container)
     (clear-position-overlays! chart-obj)
     (let [chart (.-chart ^js chart-obj)
           main-series (.-mainSeries ^js chart-obj)
           document* (resolve-document document)
           window* (or window
                       (some-> document* .-defaultView)
                       (some-> js/globalThis .-window)
                       js/globalThis)
           overlay-ref (when (map? overlay) overlay)]
       (if (and chart main-series document* overlay-ref)
         (let [root (ensure-overlay-root! chart-obj container document*)
               state (overlay-state chart-obj)
               current-subscription (:subscription state)
               needs-resubscribe?
               (or (nil? current-subscription)
                   (not (identical? chart (:chart current-subscription)))
                   (not (identical? main-series (:main-series current-subscription))))
               next-subscription (if needs-resubscribe?
                                   (do
                                     (teardown-subscription! current-subscription)
                                     (subscribe-overlay-repaint! chart-obj chart main-series))
                                   current-subscription)
               unchanged-inputs?
               (and (not needs-resubscribe?)
                    (identical? root (:root state))
                    (identical? chart (:chart state))
                    (identical? main-series (:main-series state))
                    (identical? document* (:document state))
                    (identical? window* (:window state))
                    (identical? format-price (:format-price state))
                    (identical? format-size (:format-size state))
                    (identical? on-liquidation-drag-confirm (:on-liquidation-drag-confirm state))
                    (identical? overlay-ref (:overlay-ref state)))]
           (if unchanged-inputs?
             state
             (do
               (set-overlay-state!
                chart-obj
                (assoc state
                       :root root
                       :chart chart
                       :main-series main-series
                       :overlay-ref overlay-ref
                       :overlay (assoc overlay-ref
                                       :document document*
                                       :window window*
                                       :format-price format-price
                                       :format-size format-size)
                       :document document*
                       :window window*
                       :format-price format-price
                       :format-size format-size
                       :on-liquidation-drag-confirm on-liquidation-drag-confirm
                       :subscription next-subscription))
               (render-overlays! chart-obj))))
         (clear-position-overlays! chart-obj))))))
