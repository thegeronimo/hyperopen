(ns hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay
  (:require [hyperopen.platform :as platform]))

(defonce ^:private chart-navigation-overlay-sidecar (js/WeakMap.))

(def ^:private min-visible-bars 24)
(def ^:private zoom-step-fraction 0.20)
(def ^:private pan-step-bars 4)
(def ^:private navigation-animation-duration-ms 180)
(def ^:private hover-activation-fraction 0.55)
(def ^:private editable-tag-names #{"INPUT" "TEXTAREA" "SELECT"})
(def ^:private shortcut-labels
  {:zoom-in "Ctrl/Cmd + Up"
   :zoom-out "Ctrl/Cmd + Down"
   :scroll-left "Left Arrow"
   :scroll-right "Right Arrow"})

(declare reset-view!)

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

(defn- default-now-ms []
  (if-let [performance* (some-> js/globalThis .-performance)]
    (.now performance*)
    (platform/now-ms)))

(defn- default-cancel-animation-frame!
  [frame-id]
  (when (some? frame-id)
    (if (fn? (.-cancelAnimationFrame js/globalThis))
      (.cancelAnimationFrame js/globalThis frame-id)
      (js/clearTimeout frame-id))))

(defn- set-style-value!
  [style prop value]
  (when (and style
             (not= (aget style prop) value))
    (aset style prop value)
    true))

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

(defn- lerp
  [start end progress]
  (+ start (* (- end start) progress)))

(defn- ease-out-cubic
  [progress]
  (let [t (- 1 progress)]
    (- 1 (* t t t))))

(defn- ranges-close?
  [a b]
  (and a
       b
       (< (js/Math.abs (- (:from a) (:from b))) 0.0001)
       (< (js/Math.abs (- (:to a) (:to b))) 0.0001)))

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
  (let [step pan-step-bars
        delta (if (= direction :left)
                (- step)
                step)]
    {:from (+ (:from range-data) delta)
     :to (+ (:to range-data) delta)}))

(defn- overlay-interactive?
  [chart-obj]
  (let [{:keys [hovered? focus-within?]} (overlay-state chart-obj)]
    (or hovered? focus-within?)))

(defn- container-hover-active?
  [container event]
  (let [rect (when (fn? (.-getBoundingClientRect container))
               (.getBoundingClientRect container))
        height (cond
                 (finite-number? (some-> rect .-height))
                 (.-height rect)

                 (finite-number? (.-clientHeight container))
                 (.-clientHeight container)

                 :else nil)
        pointer-y (cond
                    (and rect
                         (finite-number? (some-> rect .-top))
                         (finite-number? (.-clientY event)))
                    (- (.-clientY event) (.-top rect))

                    (finite-number? (.-offsetY event))
                    (.-offsetY event)

                    :else nil)]
    (and (finite-number? height)
         (pos? height)
         (finite-number? pointer-y)
         (>= pointer-y (* height hover-activation-fraction)))))

(defn- event-target-editable?
  [event]
  (let [target (.-target event)
        tag-name (some-> target .-tagName)]
    (or (true? (some-> target .-isContentEditable))
        (contains? editable-tag-names tag-name))))

(defn- navigation-shortcut-action
  [event]
  (when (and (not (.-altKey event))
             (not (.-shiftKey event))
             (not (event-target-editable? event)))
    (case (.-key event)
      "ArrowUp" (when (or (.-metaKey event)
                          (.-ctrlKey event))
                  :zoom-in)
      "ArrowDown" (when (or (.-metaKey event)
                            (.-ctrlKey event))
                    :zoom-out)
      "ArrowLeft" :scroll-left
      "ArrowRight" :scroll-right
      nil)))

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

(defn- clear-active-animation!
  [chart-obj]
  (when-let [{:keys [frame-id cancel-animation-frame-fn]} (:active-animation (overlay-state chart-obj))]
    (when (and frame-id (fn? cancel-animation-frame-fn))
      (cancel-animation-frame-fn frame-id)))
  (update-overlay-state! chart-obj dissoc :active-animation))

(defn- animation-frame-now-ms
  [timestamp now-ms-fn]
  (if (finite-number? timestamp)
    timestamp
    (now-ms-fn)))

(defn- animate-logical-range!
  [chart-obj time-scale current-range target-range]
  (let [{:keys [request-animation-frame-fn
                cancel-animation-frame-fn
                now-ms-fn
                animation-duration-ms]}
        (overlay-state chart-obj)
        duration-ms (max 1 (or animation-duration-ms navigation-animation-duration-ms))
        started-at-ms (now-ms-fn)]
    (clear-active-animation! chart-obj)
    (notify-interaction! chart-obj)
    (letfn [(queue-frame! [step-fn]
              (let [frame-id (request-animation-frame-fn step-fn)]
                (update-overlay-state! chart-obj
                                       assoc
                                       :active-animation {:frame-id frame-id
                                                          :cancel-animation-frame-fn cancel-animation-frame-fn})
                frame-id))
            (step [timestamp]
              (let [current-state (overlay-state chart-obj)
                    active-animation (:active-animation current-state)]
                (when (and active-animation
                           (identical? time-scale
                                       (invoke-method (:chart current-state) "timeScale")))
                  (let [elapsed-ms (- (animation-frame-now-ms timestamp now-ms-fn) started-at-ms)
                        progress (clamp (/ elapsed-ms duration-ms) 0 1)
                        eased-progress (ease-out-cubic progress)
                        next-range {:from (lerp (:from current-range)
                                                (:from target-range)
                                                eased-progress)
                                    :to (lerp (:to current-range)
                                              (:to target-range)
                                              eased-progress)}]
                    (set-logical-range! time-scale next-range)
                    (if (< progress 1)
                      (queue-frame! step)
                      (update-overlay-state! chart-obj
                                             dissoc
                                             :active-animation))))))]
      (queue-frame! step)
      true)))

(defn- apply-navigation-range!
  [chart-obj transform-fn]
  (let [{:keys [chart candle-count]} (overlay-state chart-obj)
        time-scale (invoke-method chart "timeScale")
        current-range (current-logical-range time-scale)]
    (when (and current-range
               time-scale)
      (when-let [target-range (some-> (transform-fn current-range candle-count)
                                      normalize-range)]
        (when-not (ranges-close? current-range target-range)
          (animate-logical-range! chart-obj time-scale current-range target-range))))))

(defn- zoom-chart!
  [chart-obj direction]
  (apply-navigation-range! chart-obj
                           (fn [range-data candle-count]
                             (zoom-range range-data direction candle-count))))

(defn- pan-chart!
  [chart-obj direction]
  (apply-navigation-range! chart-obj
                           (fn [range-data _]
                             (pan-range range-data direction))))

(defn- perform-navigation-action!
  [chart-obj action]
  (case action
    :zoom-in (zoom-chart! chart-obj :in)
    :zoom-out (zoom-chart! chart-obj :out)
    :scroll-left (pan-chart! chart-obj :left)
    :scroll-right (pan-chart! chart-obj :right)
    :reset-view (reset-view! chart-obj)
    nil))

(defn- reset-view!
  [chart-obj]
  (let [{:keys [chart candles on-reset]} (overlay-state chart-obj)]
    (when chart
      (clear-active-animation! chart-obj)
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
        background (if hovered?
                     "rgba(88, 97, 111, 0.95)"
                     "rgba(58, 66, 79, 0.94)")
        box-shadow (if hovered?
                     "0 0 0 2px rgba(226,232,240,0.2)"
                     "0 1px 2px rgba(2,6,23,0.28)")]
    (set-style-value! style "background" background)
    (set-style-value! style "boxShadow" box-shadow)))

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
  [document {:keys [aria-label title icon-path on-click shortcut-key]}]
  (let [button (.createElement document "button")]
    (.setAttribute button "type" "button")
    (.setAttribute button "aria-label" aria-label)
    (.setAttribute button
                   "title"
                   (if-let [shortcut-label (get shortcut-labels shortcut-key)]
                     (str title " (" shortcut-label ")")
                     title))
    (let [style (.-style button)]
      (set! (.-width style) "28px")
      (set! (.-height style) "26px")
      (set! (.-padding style) "0")
      (set! (.-display style) "inline-flex")
      (set! (.-alignItems style) "center")
      (set! (.-justifyContent style) "center")
      (set! (.-borderRadius style) "4px")
      (set! (.-border style) "none")
      (set! (.-cursor style) "pointer")
      (set! (.-outline style) "none")
      (set! (.-color style) "#f8fafc")
      (set! (.-transition style)
            "background 120ms ease,box-shadow 120ms ease"))
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
  (let [style (.-style root)
        opacity (if visible? "1" "0")
        pointer-events (if visible? "auto" "none")]
    (set-style-value! style "opacity" opacity)
    (set-style-value! style "pointerEvents" pointer-events)))

(defn- sync-root-visibility!
  [chart-obj]
  (let [{:keys [root hovered? focus-within?]} (overlay-state chart-obj)]
    (when root
      (set-root-visibility! root (or hovered? focus-within?)))))

(defn- sync-overlay-interactive-flag!
  [chart-obj state-key active?]
  (let [next-active? (boolean active?)
        current-active? (boolean (get (overlay-state chart-obj) state-key))]
    (when (not= current-active? next-active?)
      (update-overlay-state! chart-obj assoc state-key next-active?)
      (sync-root-visibility! chart-obj)
      true)))

(defn- ensure-relative-container!
  [container]
  (let [style (.-style container)]
    (when (or (not (.-position style))
              (= (.-position style) "static"))
      (set! (.-position style) "relative"))))

(defn- attach-container-listeners!
  [chart-obj container]
  (let [sync-hover-state! (fn [event]
                            (sync-overlay-interactive-flag! chart-obj
                                                            :hovered?
                                                            (container-hover-active? container event)))
        on-pointer-enter (fn [event]
                           (sync-hover-state! event))
        on-pointer-move (fn [event]
                          (sync-hover-state! event))
        on-pointer-leave (fn [_]
                           (sync-overlay-interactive-flag! chart-obj :hovered? false))]
    (.addEventListener container "pointerenter" on-pointer-enter)
    (.addEventListener container "pointermove" on-pointer-move)
    (.addEventListener container "pointerleave" on-pointer-leave)
    {:container container
     :on-pointer-enter on-pointer-enter
     :on-pointer-move on-pointer-move
     :on-pointer-leave on-pointer-leave}))

(defn- teardown-container-listeners!
  [{:keys [container on-pointer-enter on-pointer-move on-pointer-leave]}]
  (when container
    (when on-pointer-enter
      (.removeEventListener container "pointerenter" on-pointer-enter))
    (when on-pointer-move
      (.removeEventListener container "pointermove" on-pointer-move))
    (when on-pointer-leave
      (.removeEventListener container "pointerleave" on-pointer-leave))))

(defn- attach-document-listeners!
  [chart-obj document]
  (let [on-key-down (fn [event]
                      (when (overlay-interactive? chart-obj)
                        (when-let [action (navigation-shortcut-action event)]
                          (.preventDefault event)
                          (.stopPropagation event)
                          (perform-navigation-action! chart-obj action))))]
    (.addEventListener document "keydown" on-key-down)
    {:document document
     :on-key-down on-key-down}))

(defn- teardown-document-listeners!
  [{:keys [document on-key-down]}]
  (when (and document on-key-down)
    (.removeEventListener document "keydown" on-key-down)))

(defn- create-overlay-root!
  [chart-obj document]
  (let [root (.createElement document "div")
        panel (.createElement document "div")
        controls [{:id :zoom-out
                   :aria-label "Zoom out"
                   :title "Zoom out"
                   :shortcut-key :zoom-out
                   :icon-path "M5 10h10"
                   :on-click #(zoom-chart! chart-obj :out)}
                  {:id :zoom-in
                   :aria-label "Zoom in"
                   :title "Zoom in"
                   :shortcut-key :zoom-in
                   :icon-path "M10 5v10M5 10h10"
                   :on-click #(zoom-chart! chart-obj :in)}
                  {:id :scroll-left
                   :aria-label "Scroll left"
                   :title "Scroll to the left"
                   :shortcut-key :scroll-left
                   :icon-path "M12.5 5L7.5 10l5 5"
                   :on-click #(pan-chart! chart-obj :left)}
                  {:id :scroll-right
                   :aria-label "Scroll right"
                   :title "Scroll to the right"
                   :shortcut-key :scroll-right
                   :icon-path "M7.5 5l5 5-5 5"
                   :on-click #(pan-chart! chart-obj :right)}
                  {:id :reset-view
                   :aria-label "Reset chart view"
                   :title "Reset chart view"
                   :icon-path "M15 6.8V4.5h-2.3M15 4.5l-2.4 2.3M14.7 10a4.7 4.7 0 1 1-1.3-3.2"
                   :on-click #(reset-view! chart-obj)}]]
    (.setAttribute root "data-role" "chart-navigation-overlay")
    (let [style (.-style root)]
      (set! (.-position style) "absolute")
      (set! (.-left style) "50%")
      (set! (.-bottom style) "42px")
      (set! (.-transform style) "translateX(-50%)")
      (set! (.-zIndex style) "116")
      (set! (.-opacity style) "0")
      (set! (.-pointerEvents style) "none")
      (set! (.-transition style) "opacity 120ms ease"))
    (.setAttribute panel "data-role" "chart-navigation-controls")
    (let [style (.-style panel)]
      (set! (.-display style) "inline-flex")
      (set! (.-alignItems style) "center")
      (set! (.-gap style) "4px")
      (set! (.-padding style) "0")
      (set! (.-background style) "transparent")
      (set! (.-border style) "none")
      (set! (.-boxShadow style) "none")
      (set! (.-pointerEvents style) "auto"))
    (.addEventListener panel "focusin"
                       (fn [_]
                         (sync-overlay-interactive-flag! chart-obj :focus-within? true)))
    (.addEventListener panel "focusout"
                       (fn [event]
                         (let [next-focused (.-relatedTarget event)]
                           (when (or (nil? next-focused)
                                     (not (.contains panel next-focused)))
                             (sync-overlay-interactive-flag! chart-obj :focus-within? false)))))
    (doseq [{:keys [aria-label title icon-path on-click shortcut-key]} controls]
      (.appendChild panel
                    (build-control-button!
                     document
                     {:aria-label aria-label
                      :title title
                      :shortcut-key shortcut-key
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
    (clear-active-animation! chart-obj)
    (teardown-container-listeners! (:container-listeners (overlay-state chart-obj)))
    (teardown-document-listeners! (:document-listeners (overlay-state chart-obj)))
    (when-let [root (:root (overlay-state chart-obj))]
      (when-let [parent (.-parentNode root)]
        (.removeChild parent root)))
    (.delete chart-navigation-overlay-sidecar chart-obj)))

(defn sync-chart-navigation-overlay!
  ([chart-obj container candles]
   (sync-chart-navigation-overlay! chart-obj container candles {}))
  ([chart-obj container candles {:keys [document
                                        on-interaction
                                        on-reset
                                        now-ms-fn
                                        request-animation-frame-fn
                                        cancel-animation-frame-fn
                                        animation-duration-ms]
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
               current-document-listeners (:document-listeners state)
               listeners-reused? (and current-listeners
                                      (identical? container (:container current-listeners)))
               document-listeners-reused? (and current-document-listeners
                                              (identical? document* (:document current-document-listeners)))
               next-listeners (if listeners-reused?
                                current-listeners
                                (do
                                  (teardown-container-listeners! current-listeners)
                                  (attach-container-listeners! chart-obj container)))
               next-document-listeners (if document-listeners-reused?
                                         current-document-listeners
                                         (do
                                           (teardown-document-listeners! current-document-listeners)
                                           (attach-document-listeners! chart-obj document*)))
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
                   :now-ms-fn (or now-ms-fn
                                  (:now-ms-fn state)
                                  default-now-ms)
                   :request-animation-frame-fn (or request-animation-frame-fn
                                                   (:request-animation-frame-fn state)
                                                   platform/request-animation-frame!)
                   :cancel-animation-frame-fn (or cancel-animation-frame-fn
                                                  (:cancel-animation-frame-fn state)
                                                  default-cancel-animation-frame!)
                   :animation-duration-ms (or animation-duration-ms
                                              (:animation-duration-ms state)
                                              navigation-animation-duration-ms)
                   :document-listeners next-document-listeners
                   :container-listeners next-listeners))
           (sync-root-visibility! chart-obj))
         (clear-chart-navigation-overlay! chart-obj))))))
