(ns hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay)

(defonce ^:private chart-navigation-overlay-sidecar (js/WeakMap.))

(def ^:private min-visible-bars 24)
(def ^:private zoom-step-fraction 0.20)
(def ^:private pan-step-fraction 0.25)

(defn- overlay-state
  [chart-obj]
  (if chart-obj
    (or (.get chart-navigation-overlay-sidecar chart-obj) {})
    {}))

(defn- set-overlay-state!
  [chart-obj state]
  (when chart-obj
    (.set chart-navigation-overlay-sidecar chart-obj state))
  state)

(defn- update-overlay-state!
  [chart-obj f & args]
  (let [next-state (apply f (overlay-state chart-obj) args)]
    (set-overlay-state! chart-obj next-state)))

(defn- resolve-document
  [document]
  (or document
      (some-> js/globalThis .-document)))

(defn- finite-number?
  [value]
  (and (number? value)
       (js/isFinite value)))

(defn- invoke-method
  [target method-name & args]
  (let [method (when target
                 (aget target method-name))]
    (when (fn? method)
      (.apply method target (to-array args)))))

(defn- normalize-range
  [range-data]
  (let [range* (cond
                 (map? range-data) range-data
                 (some? range-data) (js->clj range-data :keywordize-keys true)
                 :else nil)
        from (:from range*)
        to (:to range*)]
    (when (and (finite-number? from)
               (finite-number? to)
               (< from to))
      {:from from
       :to to})))

(defn- current-logical-range
  [time-scale]
  (some-> (invoke-method time-scale "getVisibleLogicalRange")
          normalize-range))

(defn- set-logical-range!
  [time-scale {:keys [from to] :as range-data}]
  (when (and time-scale (normalize-range range-data))
    (when (fn? (aget time-scale "setVisibleLogicalRange"))
      (invoke-method time-scale
                     "setVisibleLogicalRange"
                     (clj->js {:from from :to to}))
      true)))

(defn- range-span
  [{:keys [from to]}]
  (- to from))

(defn- clamp
  [value min-value max-value]
  (-> value
      (max min-value)
      (min max-value)))

(defn- max-visible-bars
  [candle-count]
  (let [count* (if (and (number? candle-count)
                        (pos? candle-count))
                 candle-count
                 5000)]
    (+ count* 128)))

(defn- zoom-range
  [range-data direction candle-count]
  (let [span (range-span range-data)
        center (/ (+ (:from range-data) (:to range-data)) 2)
        raw-span (if (= direction :in)
                   (* span (- 1 zoom-step-fraction))
                   (* span (+ 1 zoom-step-fraction)))
        next-span (clamp raw-span
                         min-visible-bars
                         (max-visible-bars candle-count))
        half-span (/ next-span 2)]
    {:from (- center half-span)
     :to (+ center half-span)}))

(defn- pan-range
  [range-data direction]
  (let [span (range-span range-data)
        step (max 1 (* span pan-step-fraction))
        delta (if (= direction :left)
                (- step)
                step)]
    {:from (+ (:from range-data) delta)
     :to (+ (:to range-data) delta)}))

(defn- default-reset!
  [chart]
  (or (true? (invoke-method chart "resetTimeScale"))
      (let [time-scale (invoke-method chart "timeScale")]
        (when time-scale
          (invoke-method time-scale "fitContent")
          (invoke-method time-scale "scrollToRealTime")
          true))))

(defn- notify-interaction!
  [chart-obj]
  (when-let [on-interaction (:on-interaction (overlay-state chart-obj))]
    (when (fn? on-interaction)
      (on-interaction))))

(defn- apply-navigation-range!
  [chart-obj transform-fn]
  (let [{:keys [chart candle-count]} (overlay-state chart-obj)
        time-scale (invoke-method chart "timeScale")
        current-range (current-logical-range time-scale)]
    (when (and current-range
               time-scale)
      (when (set-logical-range! time-scale
                                (transform-fn current-range candle-count))
        (notify-interaction! chart-obj)
        true))))

(defn- reset-view!
  [chart-obj]
  (let [{:keys [chart candles on-reset]} (overlay-state chart-obj)]
    (when chart
      (let [handled? (if (fn? on-reset)
                       (do
                         (on-reset chart candles)
                         true)
                       (default-reset! chart))]
        (when handled?
          (notify-interaction! chart-obj))
        handled?))))

(defn- set-control-button-visual-state!
  [button hovered?]
  (let [style (.-style button)
        border-color (if hovered?
                       "rgba(96, 165, 250, 0.86)"
                       "rgba(148, 163, 184, 0.48)")
        background (if hovered?
                     "linear-gradient(180deg, rgba(32, 53, 78, 0.96) 0%, rgba(21, 38, 57, 0.94) 100%)"
                     "linear-gradient(180deg, rgba(16, 27, 40, 0.94) 0%, rgba(11, 22, 34, 0.92) 100%)")
        color (if hovered? "#f8fafc" "#e2e8f0")
        box-shadow (if hovered?
                     "inset 0 1px 0 rgba(255,255,255,0.22), 0 2px 8px rgba(2,6,23,0.58)"
                     "inset 0 1px 0 rgba(255,255,255,0.08), 0 1px 3px rgba(2,6,23,0.52)")]
    (set! (.-borderColor style) border-color)
    (set! (.-background style) background)
    (set! (.-color style) color)
    (set! (.-boxShadow style) box-shadow)
    (set! (.-transform style) (if hovered? "translateY(-1px)" "translateY(0)"))))

(defn- make-icon!
  [document path-d]
  (let [icon (.createElementNS document "http://www.w3.org/2000/svg" "svg")
        path (.createElementNS document "http://www.w3.org/2000/svg" "path")]
    (.setAttribute icon "viewBox" "0 0 20 20")
    (.setAttribute icon "width" "15")
    (.setAttribute icon "height" "15")
    (.setAttribute icon "aria-hidden" "true")
    (.setAttribute icon "focusable" "false")
    (.setAttribute icon "fill" "none")
    (.setAttribute icon "stroke" "currentColor")
    (.setAttribute icon "stroke-width" "2.2")
    (.setAttribute icon "stroke-linecap" "round")
    (.setAttribute icon "stroke-linejoin" "round")
    (.setAttribute path "d" path-d)
    (.appendChild icon path)
    icon))

(defn- build-control-button!
  [document {:keys [aria-label title icon-path on-click]}]
  (let [button (.createElement document "button")]
    (.setAttribute button "type" "button")
    (.setAttribute button "aria-label" aria-label)
    (.setAttribute button "title" title)
    (let [style (.-style button)]
      (set! (.-width style) "30px")
      (set! (.-height style) "28px")
      (set! (.-padding style) "0")
      (set! (.-display style) "inline-flex")
      (set! (.-alignItems style) "center")
      (set! (.-justifyContent style) "center")
      (set! (.-borderRadius style) "7px")
      (set! (.-border style) "1px solid transparent")
      (set! (.-cursor style) "pointer")
      (set! (.-outline style) "none")
      (set! (.-transition style)
            "background 120ms ease,border-color 120ms ease,color 120ms ease,transform 120ms ease,box-shadow 120ms ease"))
    (set-control-button-visual-state! button false)
    (.addEventListener button "mouseenter"
                       (fn [_]
                         (set-control-button-visual-state! button true)))
    (.addEventListener button "mouseleave"
                       (fn [_]
                         (set-control-button-visual-state! button false)))
    (.addEventListener button "focus"
                       (fn [_]
                         (set-control-button-visual-state! button true)))
    (.addEventListener button "blur"
                       (fn [_]
                         (set-control-button-visual-state! button false)))
    (.addEventListener button "click"
                       (fn [event]
                         (.preventDefault event)
                         (.stopPropagation event)
                         (on-click)))
    (.appendChild button (make-icon! document icon-path))
    button))

(defn- set-root-visibility!
  [root visible?]
  (let [style (.-style root)]
    (set! (.-opacity style) (if visible? "1" "0"))
    (set! (.-pointerEvents style) (if visible? "auto" "none"))))

(defn- sync-root-visibility!
  [chart-obj]
  (let [{:keys [root hovered? focus-within?]} (overlay-state chart-obj)]
    (when root
      (set-root-visibility! root (or hovered? focus-within?)))))

(defn- ensure-relative-container!
  [container]
  (let [style (.-style container)]
    (when (or (not (.-position style))
              (= (.-position style) "static"))
      (set! (.-position style) "relative"))))

(defn- attach-container-listeners!
  [chart-obj container]
  (let [on-pointer-enter (fn [_]
                           (update-overlay-state! chart-obj assoc :hovered? true)
                           (sync-root-visibility! chart-obj))
        on-pointer-leave (fn [_]
                           (update-overlay-state! chart-obj assoc :hovered? false)
                           (sync-root-visibility! chart-obj))]
    (.addEventListener container "pointerenter" on-pointer-enter)
    (.addEventListener container "pointerleave" on-pointer-leave)
    {:container container
     :on-pointer-enter on-pointer-enter
     :on-pointer-leave on-pointer-leave}))

(defn- teardown-container-listeners!
  [{:keys [container on-pointer-enter on-pointer-leave]}]
  (when container
    (when on-pointer-enter
      (.removeEventListener container "pointerenter" on-pointer-enter))
    (when on-pointer-leave
      (.removeEventListener container "pointerleave" on-pointer-leave))))

(defn- create-overlay-root!
  [chart-obj document]
  (let [root (.createElement document "div")
        panel (.createElement document "div")
        controls [{:id :zoom-out
                   :aria-label "Zoom out"
                   :title "Zoom out"
                   :icon-path "M5 10h10"
                   :on-click #(apply-navigation-range! chart-obj (fn [range-data candle-count]
                                                                   (zoom-range range-data :out candle-count)))}
                  {:id :zoom-in
                   :aria-label "Zoom in"
                   :title "Zoom in"
                   :icon-path "M10 5v10M5 10h10"
                   :on-click #(apply-navigation-range! chart-obj (fn [range-data candle-count]
                                                                   (zoom-range range-data :in candle-count)))}
                  {:id :scroll-left
                   :aria-label "Scroll left"
                   :title "Scroll left"
                   :icon-path "M12.5 5L7.5 10l5 5"
                   :on-click #(apply-navigation-range! chart-obj (fn [range-data _]
                                                                   (pan-range range-data :left)))}
                  {:id :scroll-right
                   :aria-label "Scroll right"
                   :title "Scroll right"
                   :icon-path "M7.5 5l5 5-5 5"
                   :on-click #(apply-navigation-range! chart-obj (fn [range-data _]
                                                                   (pan-range range-data :right)))}
                  {:id :reset-view
                   :aria-label "Reset chart view"
                   :title "Reset chart view"
                   :icon-path "M15 6.8V4.5h-2.3M15 4.5l-2.4 2.3M14.7 10a4.7 4.7 0 1 1-1.3-3.2"
                   :on-click #(reset-view! chart-obj)}]]
    (.setAttribute root "data-role" "chart-navigation-overlay")
    (set! (.-cssText (.-style root))
          "position:absolute;left:10px;bottom:10px;z-index:116;opacity:0;pointer-events:none;transition:opacity 120ms ease;")
    (.setAttribute panel "data-role" "chart-navigation-controls")
    (set! (.-cssText (.-style panel))
          (str "display:inline-flex;align-items:center;gap:6px;"
               "padding:5px 6px;border-radius:8px;"
               "background:rgba(9,17,27,0.72);"
               "border:1px solid rgba(148,163,184,0.28);"
               "box-shadow:0 3px 10px rgba(2,6,23,0.36);"
               "backdrop-filter:blur(1.5px);"
               "pointer-events:auto;"))
    (.addEventListener panel "focusin"
                       (fn [_]
                         (update-overlay-state! chart-obj assoc :focus-within? true)
                         (sync-root-visibility! chart-obj)))
    (.addEventListener panel "focusout"
                       (fn [event]
                         (let [next-focused (.-relatedTarget event)]
                           (when (or (nil? next-focused)
                                     (not (.contains panel next-focused)))
                             (update-overlay-state! chart-obj assoc :focus-within? false)
                             (sync-root-visibility! chart-obj)))))
    (doseq [{:keys [aria-label title icon-path on-click]} controls]
      (.appendChild panel
                    (build-control-button!
                     document
                     {:aria-label aria-label
                      :title title
                      :icon-path icon-path
                      :on-click on-click})))
    (.appendChild root panel)
    {:root root
     :panel panel}))

(defn- ensure-overlay-root!
  [chart-obj container document]
  (ensure-relative-container! container)
  (let [{:keys [root panel]} (overlay-state chart-obj)
        mounted-root? (and root (identical? (.-parentNode root) container))
        next-root (if mounted-root?
                    {:root root :panel panel}
                    (create-overlay-root! chart-obj document))]
    (when (and root (not mounted-root?))
      (when-let [parent (.-parentNode root)]
        (.removeChild parent root)))
    (when-let [root-node (:root next-root)]
      (when (not (identical? (.-parentNode root-node) container))
        (.appendChild container root-node)))
    next-root))

(defn clear-chart-navigation-overlay!
  [chart-obj]
  (when chart-obj
    (teardown-container-listeners! (:container-listeners (overlay-state chart-obj)))
    (when-let [root (:root (overlay-state chart-obj))]
      (when-let [parent (.-parentNode root)]
        (.removeChild parent root)))
    (.delete chart-navigation-overlay-sidecar chart-obj)))

(defn sync-chart-navigation-overlay!
  ([chart-obj container candles]
   (sync-chart-navigation-overlay! chart-obj container candles {}))
  ([chart-obj container candles {:keys [document on-interaction on-reset]
                                 :or {on-interaction (fn [] nil)
                                      on-reset nil}}]
   (if-not (and chart-obj container)
     (clear-chart-navigation-overlay! chart-obj)
     (let [chart (.-chart ^js chart-obj)
           document* (resolve-document document)]
       (if (and chart document*)
         (let [{:keys [root panel]} (ensure-overlay-root! chart-obj container document*)
               state (overlay-state chart-obj)
               current-listeners (:container-listeners state)
               listeners-reused? (and current-listeners
                                      (identical? container (:container current-listeners)))
               next-listeners (if listeners-reused?
                                current-listeners
                                (do
                                  (teardown-container-listeners! current-listeners)
                                  (attach-container-listeners! chart-obj container)))
               candle-count (if (sequential? candles)
                              (count candles)
                              0)]
           (set-overlay-state!
            chart-obj
            (assoc state
                   :root root
                   :panel panel
                   :chart chart
                   :candles (if (sequential? candles) candles [])
                   :candle-count candle-count
                   :on-interaction on-interaction
                   :on-reset on-reset
                   :container-listeners next-listeners))
           (sync-root-visibility! chart-obj))
         (clear-chart-navigation-overlay! chart-obj))))))
