(ns hyperopen.views.trading-chart.utils.chart-interop.chart-context-menu-overlay
  (:require [hyperopen.domain.trading :as trading-domain]
            [hyperopen.utils.formatting :as fmt]))

(defonce ^:private chart-context-menu-overlay-sidecar (js/WeakMap.))

(def ^:private panel-width-px 196)
(def ^:private panel-padding-px 6)
(def ^:private row-height-px 32)
(def ^:private divider-height-px 1)
(def ^:private anchor-offset-px 8)
(def ^:private edge-padding-px 8)
(def ^:private copy-feedback-duration-ms 900)

(defn- overlay-state
  [chart-obj]
  (if chart-obj
    (or (.get chart-context-menu-overlay-sidecar chart-obj) {})
    {}))

(defn- set-overlay-state!
  [chart-obj state]
  (when chart-obj
    (.set chart-context-menu-overlay-sidecar chart-obj state))
  state)

(defn- update-overlay-state!
  [chart-obj f & args]
  (let [next-state (apply f (overlay-state chart-obj) args)]
    (set-overlay-state! chart-obj next-state)))

(defn- resolve-document
  [document]
  (or document
      (some-> js/globalThis .-document)))

(defn- resolve-window
  [document]
  (or (some-> document .-defaultView)
      js/globalThis))

(defn- resolve-clipboard
  [clipboard]
  (or clipboard
      (some-> js/globalThis .-navigator .-clipboard)))

(defn- default-set-timeout!
  [callback ms]
  (js/setTimeout callback ms))

(defn- default-clear-timeout!
  [timeout-id]
  (js/clearTimeout timeout-id))

(defn- invoke-method
  [target method-name & args]
  (let [method (when target
                 (aget target method-name))]
    (when (fn? method)
      (.apply method target (to-array args)))))

(defn- parse-number
  [value]
  (cond
    (number? value) (when (js/isFinite value) value)
    (string? value) (let [num (js/parseFloat value)]
                      (when (js/isFinite num) num))
    :else nil))

(defn- finite-number?
  [value]
  (boolean (parse-number value)))

(defn- set-style-value!
  [style prop value]
  (when (and style
             (not= (aget style prop) value))
    (aset style prop value)
    true))

(defn- ensure-relative-container!
  [container]
  (let [style (.-style container)]
    (when (or (not (.-position style))
              (= (.-position style) "static"))
      (set! (.-position style) "relative"))))

(defn- node-inside?
  [ancestor node]
  (loop [current node]
    (cond
      (nil? ancestor) false
      (nil? current) false
      (identical? ancestor current) true
      :else (recur (.-parentNode current)))))

(defn- event-target-inside-root?
  [root event]
  (node-inside? root (some-> event .-target)))

(defn- event-client-x
  [event]
  (or (parse-number (.-clientX event))
      (parse-number (.-pageX event))
      (parse-number (.-x event))
      (parse-number (.-offsetX event))))

(defn- event-client-y
  [event]
  (or (parse-number (.-clientY event))
      (parse-number (.-pageY event))
      (parse-number (.-y event))
      (parse-number (.-offsetY event))))

(defn- container-rect
  [container]
  (try
    (when-let [rect-fn (some-> container (aget "getBoundingClientRect"))]
      (when (fn? rect-fn)
        (.call rect-fn container)))
    (catch :default _ nil)))

(defn- container-width
  [container rect]
  (or (some-> rect .-width parse-number)
      (parse-number (.-clientWidth container))
      0))

(defn- container-height
  [container rect]
  (or (some-> rect .-height parse-number)
      (parse-number (.-clientHeight container))
      0))

(defn- relative-anchor-point
  [container event]
  (let [rect (container-rect container)
        left (some-> rect .-left parse-number)
        top (some-> rect .-top parse-number)
        client-x (event-client-x event)
        client-y (event-client-y event)
        offset-x (parse-number (.-offsetX event))
        offset-y (parse-number (.-offsetY event))
        x (cond
            (and (finite-number? client-x)
                 (finite-number? left))
            (- client-x left)

            (finite-number? offset-x)
            offset-x

            :else nil)
        y (cond
            (and (finite-number? client-y)
                 (finite-number? top))
            (- client-y top)

            (finite-number? offset-y)
            offset-y

            :else nil)]
    (when (and (finite-number? x)
               (finite-number? y))
      {:x x
       :y y})))

(defn- keyboard-anchor-point
  [container]
  (let [rect (container-rect container)
        width (container-width container rect)
        height (container-height container rect)]
    {:x (max edge-padding-px
             (min (- width edge-padding-px) (/ width 2)))
     :y (max edge-padding-px
             (min (- height edge-padding-px) (/ height 2)))}))

(defn- menu-height-px []
  (+ (* row-height-px 2)
     divider-height-px
     (* panel-padding-px 2)))

(defn- clamp
  [value min-value max-value]
  (-> value
      (max min-value)
      (min max-value)))

(defn- menu-position
  [container anchor]
  (let [rect (container-rect container)
        width (container-width container rect)
        height (container-height container rect)
        menu-height (menu-height-px)
        preferred-left (+ (:x anchor) anchor-offset-px)
        preferred-top (+ (:y anchor) anchor-offset-px)
        flipped-left (- (:x anchor) panel-width-px anchor-offset-px)
        flipped-top (- (:y anchor) menu-height anchor-offset-px)
        max-left (max edge-padding-px (- width panel-width-px edge-padding-px))
        max-top (max edge-padding-px (- height menu-height edge-padding-px))
        left (if (> (+ preferred-left panel-width-px edge-padding-px) width)
               flipped-left
               preferred-left)
        top (if (> (+ preferred-top menu-height edge-padding-px) height)
              flipped-top
              preferred-top)]
    {:left (clamp left edge-padding-px max-left)
     :top (clamp top edge-padding-px max-top)}))

(defn- last-visible-price
  [candles]
  (let [last-candle (when (sequential? candles)
                      (last candles))]
    (or (parse-number (:close last-candle))
        (parse-number (:value last-candle))
        (parse-number (:price last-candle))
        (parse-number (:open last-candle)))))

(defn- y->price
  [chart-obj y]
  (let [main-series (some-> chart-obj (aget "mainSeries"))]
    (when (finite-number? y)
      (parse-number (invoke-method main-series "coordinateToPrice" y)))))

(defn- price-from-anchor
  [chart-obj anchor]
  (when-let [y (some-> anchor :y parse-number)]
    (y->price chart-obj y)))

(defn- normalize-price-decimals
  [value]
  (when-let [parsed (parse-number value)]
    (-> parsed
        js/Math.floor
        int
        (max 0))))

(defn- format-price-label
  [format-price price price-decimals]
  (let [normalized-decimals (normalize-price-decimals price-decimals)]
    (or (when (and (finite-number? price)
                   (some? normalized-decimals))
          (trading-domain/number->clean-string price normalized-decimals))
        (when (fn? format-price)
          (format-price price))
        (fmt/format-trade-price-plain price))))

(defn- resolve-copy-price-data
  [chart-obj candles anchor format-price price-decimals]
  (let [price (or (price-from-anchor chart-obj anchor)
                  (last-visible-price candles))
        label (when (finite-number? price)
                (format-price-label format-price price price-decimals))]
    {:price price
     :label label
     :copy-enabled? (boolean (seq label))}))

(defn- sync-root-visibility!
  [chart-obj]
  (let [{:keys [root menu-open?]} (overlay-state chart-obj)
        visible? (boolean menu-open?)]
    (when root
      (let [style (.-style root)]
        (set-style-value! style "opacity" (if visible? "1" "0"))
        (set-style-value! style "pointerEvents" (if visible? "auto" "none"))
        (set-style-value! style "visibility" (if visible? "visible" "hidden"))))))

(defn- menu-items
  [chart-obj]
  (let [{:keys [reset-button copy-button copy-enabled?]} (overlay-state chart-obj)]
    (cond-> [reset-button]
      copy-enabled? (conj copy-button))))

(defn- focus-node!
  [node]
  (when node
    (let [owner-document (.-ownerDocument node)
          focus-fn (aget node "focus")
          prior-active-element (some-> owner-document .-activeElement)]
      (when (fn? focus-fn)
        (.call focus-fn node))
      ;; Fake DOM nodes do not always implement native focus semantics, so keep
      ;; tests deterministic without overriding focus on real browser elements.
      (when (and owner-document
                 (not (identical? (.-activeElement owner-document) node))
                 (not (identical? prior-active-element node)))
        (try
          (aset owner-document "activeElement" node)
          (catch :default _ nil))))))

(defn- blur-node!
  [node]
  (when node
    (let [owner-document (.-ownerDocument node)
          blur-fn (aget node "blur")]
      (when (fn? blur-fn)
        (.call blur-fn node))
      (when (and owner-document
                 (identical? (.-activeElement owner-document) node))
        (try
          (aset owner-document "activeElement" nil)
          (catch :default _ nil))))))

(defn- sync-button-presentation!
  [button {:keys [disabled? copied?]}]
  (when button
    (let [style (.-style button)]
      (set-style-value! style "opacity" (if disabled? "0.45" "1"))
      (set-style-value! style "cursor" (if disabled? "default" "pointer"))
      (set-style-value! style "color" (if copied? "#f8fafc" "#d1d5db"))
      (set-style-value! style "background" "transparent"))
    (.setAttribute button "aria-disabled" (if disabled? "true" "false"))
    (aset button "disabled" (boolean disabled?))))

(defn- sync-copy-button-label!
  [chart-obj]
  (let [{:keys [copy-button copy-label copied? copy-enabled?]} (overlay-state chart-obj)
        label-text (cond
                     copied? "Copied"
                     (seq copy-label) (str "Copy price " copy-label)
                     :else "Copy price --")]
    (when copy-button
      (set! (.-textContent copy-button) "")
      (.appendChild copy-button (aget copy-button "iconNode"))
      (let [label-node (aget copy-button "labelNode")]
        (set! (.-textContent label-node) label-text)
        (.appendChild copy-button label-node))
      (sync-button-presentation! copy-button {:disabled? (not copy-enabled?)
                                              :copied? copied?}))))

(defn- sync-reset-button!
  [chart-obj]
  (when-let [reset-button (:reset-button (overlay-state chart-obj))]
    (sync-button-presentation! reset-button {:disabled? false
                                             :copied? false})))

(defn- set-button-highlight!
  [button highlighted?]
  (when button
    (let [style (.-style button)]
      (set-style-value! style "background" (if highlighted?
                                             "rgba(30, 41, 59, 0.9)"
                                             "transparent"))
      (set-style-value! style "color" (if highlighted?
                                        "#f8fafc"
                                        (or (aget button "baseColor") "#d1d5db"))))))

(defn- focus-menu-item!
  [chart-obj item-id]
  (let [button (case item-id
                 :reset-view (:reset-button (overlay-state chart-obj))
                 :copy-price (:copy-button (overlay-state chart-obj))
                 nil)]
    (when button
      (doseq [item (remove nil? [(:reset-button (overlay-state chart-obj))
                                 (:copy-button (overlay-state chart-obj))])]
        (set-button-highlight! item false))
      (set-button-highlight! button true)
      (focus-node! button)
      (update-overlay-state! chart-obj assoc :focused-item item-id))))

(defn- clear-copy-feedback-timeout!
  [chart-obj]
  (let [{:keys [copy-feedback-timeout-id clear-timeout-fn]} (overlay-state chart-obj)]
    (when (and copy-feedback-timeout-id (fn? clear-timeout-fn))
      (clear-timeout-fn copy-feedback-timeout-id))
    (update-overlay-state! chart-obj dissoc :copy-feedback-timeout-id)))

(declare close-menu!)

(defn- schedule-copy-feedback-close!
  [chart-obj]
  (clear-copy-feedback-timeout! chart-obj)
  (let [{:keys [set-timeout-fn]} (overlay-state chart-obj)
        timeout-id ((or set-timeout-fn default-set-timeout!)
                    (fn []
                      (update-overlay-state! chart-obj assoc :copied? false)
                      (sync-copy-button-label! chart-obj)
                      (close-menu! chart-obj))
                    copy-feedback-duration-ms)]
    (update-overlay-state! chart-obj assoc :copy-feedback-timeout-id timeout-id)))

(defn- close-menu!
  ([chart-obj]
   (close-menu! chart-obj {}))
  ([chart-obj {:keys [restore-focus?]
               :or {restore-focus? true}}]
   (when chart-obj
     (clear-copy-feedback-timeout! chart-obj)
     (let [{:keys [copy-button reset-button focus-return-target root]} (overlay-state chart-obj)
           active-element (some-> root .-ownerDocument .-activeElement)
           active-inside-menu? (node-inside? root active-element)]
       (update-overlay-state! chart-obj assoc
                              :menu-open? false
                              :anchor nil
                              :copy-label nil
                              :copy-enabled? false
                              :copied? false
                              :focused-item nil)
       (set-button-highlight! reset-button false)
       (set-button-highlight! copy-button false)
       (sync-copy-button-label! chart-obj)
       (sync-root-visibility! chart-obj)
       (cond
         restore-focus?
         (focus-node! focus-return-target)

         active-inside-menu?
         (blur-node! active-element))))))

(defn- open-menu!
  [chart-obj anchor]
  (let [{:keys [container format-price candles root panel context-key price-decimals]} (overlay-state chart-obj)
        {:keys [copy-enabled? label]} (resolve-copy-price-data chart-obj
                                                               candles
                                                               anchor
                                                               format-price
                                                               price-decimals)
        position (menu-position container anchor)]
    (update-overlay-state! chart-obj assoc
                           :menu-open? true
                           :anchor anchor
                           :copy-label label
                           :copy-enabled? copy-enabled?
                           :copied? false
                           :focus-return-target container
                           :open-context-key context-key)
    (when root
      (let [style (.-style root)]
        (set-style-value! style "left" (str (:left position) "px"))
        (set-style-value! style "top" (str (:top position) "px"))))
    (sync-reset-button! chart-obj)
    (sync-copy-button-label! chart-obj)
    (sync-root-visibility! chart-obj)
    (focus-menu-item! chart-obj (if copy-enabled? :reset-view :reset-view))
    (when (and panel (not copy-enabled?))
      (.setAttribute panel "data-copy-state" "disabled"))))

(defn- copy-price!
  [chart-obj]
  (let [{:keys [copy-label copy-enabled? clipboard]} (overlay-state chart-obj)
        clipboard* (resolve-clipboard clipboard)
        write-text-fn (some-> clipboard* .-writeText)]
    (when copy-enabled?
      (cond
        (not (and clipboard* (fn? write-text-fn)))
        (close-menu! chart-obj)

        :else
        (try
          (-> (.writeText clipboard* copy-label)
              (.then (fn []
                       (update-overlay-state! chart-obj assoc :copied? true)
                       (sync-copy-button-label! chart-obj)
                       (schedule-copy-feedback-close! chart-obj)))
              (.catch (fn [_]
                        (close-menu! chart-obj))))
          (catch :default _
            (close-menu! chart-obj)))))))

(defn- perform-action!
  [chart-obj action-id]
  (case action-id
    :reset-view (do
                  (when-let [on-reset (:on-reset (overlay-state chart-obj))]
                    (on-reset))
                  (close-menu! chart-obj))
    :copy-price (copy-price! chart-obj)
    nil))

(defn- icon-node
  [document path]
  (let [svg (.createElementNS document "http://www.w3.org/2000/svg" "svg")
        path-node (.createElementNS document "http://www.w3.org/2000/svg" "path")]
    (.setAttribute svg "viewBox" "0 0 20 20")
    (.setAttribute svg "aria-hidden" "true")
    (let [style (.-style svg)]
      (set-style-value! style "width" "14px")
      (set-style-value! style "height" "14px")
      (set-style-value! style "flex" "0 0 14px"))
    (.setAttribute path-node "d" path)
    (.setAttribute path-node "fill" "none")
    (.setAttribute path-node "stroke" "currentColor")
    (.setAttribute path-node "stroke-width" "1.5")
    (.setAttribute path-node "stroke-linecap" "round")
    (.setAttribute path-node "stroke-linejoin" "round")
    (.appendChild svg path-node)
    svg))

(defn- button-keydown-handler
  [chart-obj item-id]
  (fn [event]
    (let [key (.-key event)
          items (vec (remove nil? [{:id :reset-view :button (:reset-button (overlay-state chart-obj))}
                                   (when (:copy-enabled? (overlay-state chart-obj))
                                     {:id :copy-price :button (:copy-button (overlay-state chart-obj))})]))
          ids (mapv :id items)
          current-index (or (first (keep-indexed (fn [idx id]
                                                   (when (= id item-id)
                                                     idx))
                                                 ids))
                            0)
          next-id (fn [direction]
                    (when (seq ids)
                      (nth ids (mod (+ current-index direction) (count ids)))))]
      (case key
        "ArrowDown" (do
                      (.preventDefault event)
                      (.stopPropagation event)
                      (when-let [target-id (next-id 1)]
                        (focus-menu-item! chart-obj target-id)))
        "ArrowUp" (do
                    (.preventDefault event)
                    (.stopPropagation event)
                    (when-let [target-id (next-id -1)]
                      (focus-menu-item! chart-obj target-id)))
        "Escape" (do
                   (.preventDefault event)
                   (.stopPropagation event)
                   (close-menu! chart-obj))
        "Enter" (do
                  (.preventDefault event)
                  (.stopPropagation event)
                  (perform-action! chart-obj item-id))
        " " (do
              (.preventDefault event)
              (.stopPropagation event)
              (perform-action! chart-obj item-id))
        nil))))

(defn- touch-context-menu-event?
  [event]
  (or (= "touch" (aget event "pointerType"))
      (true? (some-> event (aget "sourceCapabilities") (aget "firesTouchEvents")))))

(defn- secondary-mouse-context-menu-event?
  [event]
  (and (not (touch-context-menu-event? event))
       (or (= 2 (parse-number (aget event "button")))
           (= 2 (parse-number (aget event "buttons")))
           (= 3 (parse-number (aget event "which"))))))

(defn- menu-button!
  [chart-obj document {:keys [item-id data-role label icon-path title]}]
  (let [button (.createElement document "button")
        icon (icon-node document icon-path)
        label-node (.createElement document "span")
        on-keydown (button-keydown-handler chart-obj item-id)]
    (.setAttribute button "type" "button")
    (.setAttribute button "role" "menuitem")
    (.setAttribute button "data-role" data-role)
    (.setAttribute button "title" title)
    (let [style (.-style button)]
      (set-style-value! style "display" "flex")
      (set-style-value! style "alignItems" "center")
      (set-style-value! style "gap" "10px")
      (set-style-value! style "width" "100%")
      (set-style-value! style "height" (str row-height-px "px"))
      (set-style-value! style "padding" "0 10px")
      (set-style-value! style "border" "none")
      (set-style-value! style "background" "transparent")
      (set-style-value! style "color" "#d1d5db")
      (set-style-value! style "textAlign" "left")
      (set-style-value! style "fontSize" "14px")
      (set-style-value! style "lineHeight" "1")
      (set-style-value! style "cursor" "pointer")
      (set-style-value! style "outline" "none")
      (set-style-value! style "borderRadius" "6px"))
    (aset button "baseColor" "#d1d5db")
    (aset button "iconNode" icon)
    (aset button "labelNode" label-node)
    (set! (.-textContent label-node) label)
    (.appendChild button icon)
    (.appendChild button label-node)
    (.addEventListener button "mouseenter" (fn [_] (set-button-highlight! button true)))
    (.addEventListener button "mouseleave" (fn [_]
                                             (when (not= item-id (:focused-item (overlay-state chart-obj)))
                                               (set-button-highlight! button false))))
    (.addEventListener button "focus" (fn [_]
                                        (update-overlay-state! chart-obj assoc :focused-item item-id)
                                        (set-button-highlight! button true)))
    (.addEventListener button "blur" (fn [_]
                                       (when (not= item-id (:focused-item (overlay-state chart-obj)))
                                         (set-button-highlight! button false))))
    (.addEventListener button "keydown" on-keydown)
    (.addEventListener button "click"
                       (fn [event]
                         (.preventDefault event)
                         (.stopPropagation event)
                         (perform-action! chart-obj item-id)))
    button))

(defn- create-overlay-root!
  [chart-obj document]
  (let [root (.createElement document "div")
        panel (.createElement document "div")
        reset-button (menu-button! chart-obj
                                   document
                                   {:item-id :reset-view
                                    :data-role "chart-context-menu-reset"
                                    :label "Reset chart view"
                                    :title "Reset chart view"
                                    :icon-path "M15 6.8V4.5h-2.3M15 4.5l-2.4 2.3M14.7 10a4.7 4.7 0 1 1-1.3-3.2"})
        divider (.createElement document "div")
        copy-button (menu-button! chart-obj
                                  document
                                  {:item-id :copy-price
                                   :data-role "chart-context-menu-copy"
                                   :label "Copy price --"
                                   :title "Copy price"
                                   :icon-path "M7 7.5h6.5v8H7zM5 12H3.8A1.8 1.8 0 0 1 2 10.2V3.8A1.8 1.8 0 0 1 3.8 2h6.4A1.8 1.8 0 0 1 12 3.8V5"})]
    (.setAttribute root "data-role" "chart-context-menu")
    (.setAttribute panel "role" "menu")
    (.setAttribute panel "aria-label" "Chart context menu")
    (let [style (.-style root)]
      (set-style-value! style "position" "absolute")
      (set-style-value! style "zIndex" "118")
      (set-style-value! style "opacity" "0")
      (set-style-value! style "pointerEvents" "none")
      (set-style-value! style "visibility" "hidden"))
    (let [style (.-style panel)]
      (set-style-value! style "display" "flex")
      (set-style-value! style "flexDirection" "column")
      (set-style-value! style "width" (str panel-width-px "px"))
      (set-style-value! style "padding" (str panel-padding-px "px"))
      (set-style-value! style "background" "rgba(10, 14, 20, 0.98)")
      (set-style-value! style "border" "1px solid rgba(56, 65, 79, 0.95)")
      (set-style-value! style "boxShadow" "0 10px 28px rgba(0, 0, 0, 0.35)")
      (set-style-value! style "borderRadius" "10px")
      (set-style-value! style "pointerEvents" "auto"))
    (let [style (.-style divider)]
      (set-style-value! style "height" (str divider-height-px "px"))
      (set-style-value! style "margin" "4px 0")
      (set-style-value! style "background" "rgba(56, 65, 79, 0.9)"))
    (.addEventListener panel "keydown"
                       (fn [event]
                         (when (= "Escape" (.-key event))
                           (.preventDefault event)
                           (.stopPropagation event)
                           (close-menu! chart-obj))))
    (.appendChild panel reset-button)
    (.appendChild panel divider)
    (.appendChild panel copy-button)
    (.appendChild root panel)
    {:root root
     :panel panel
     :reset-button reset-button
     :copy-button copy-button}))

(defn- ensure-overlay-root!
  [chart-obj container document]
  (ensure-relative-container! container)
  (let [{:keys [root panel reset-button copy-button]} (overlay-state chart-obj)
        mounted-root? (and root (identical? (.-parentNode root) container))
        next-root (if mounted-root?
                    {:root root
                     :panel panel
                     :reset-button reset-button
                     :copy-button copy-button}
                    (create-overlay-root! chart-obj document))]
    (when (and root (not mounted-root?))
      (when-let [parent (.-parentNode root)]
        (.removeChild parent root)))
    (when-let [root-node (:root next-root)]
      (when (not (identical? (.-parentNode root-node) container))
        (.appendChild container root-node)))
    next-root))

(defn- attach-container-listeners!
  [chart-obj container]
  (let [on-context-menu (fn [event]
                          (when (secondary-mouse-context-menu-event? event)
                            (.preventDefault event)
                            (.stopPropagation event)
                            (when-not (event-target-inside-root? (:root (overlay-state chart-obj)) event)
                              (when-let [anchor (relative-anchor-point container event)]
                                (open-menu! chart-obj anchor)))))
        on-key-down (fn [event]
                      (let [context-menu-key? (= "ContextMenu" (.-key event))
                            shift-f10? (and (= "F10" (.-key event))
                                            (true? (.-shiftKey event))
                                            (not (.-altKey event))
                                            (not (.-ctrlKey event))
                                            (not (.-metaKey event)))]
                        (when (or context-menu-key? shift-f10?)
                          (.preventDefault event)
                          (.stopPropagation event)
                          (open-menu! chart-obj (keyboard-anchor-point container)))))
        on-wheel (fn [_]
                   (when (:menu-open? (overlay-state chart-obj))
                     (close-menu! chart-obj {:restore-focus? false})))
        on-pointer-down (fn [event]
                          (when (and (:menu-open? (overlay-state chart-obj))
                                     (not (event-target-inside-root? (:root (overlay-state chart-obj)) event)))
                            (close-menu! chart-obj {:restore-focus? false})))]
    (.addEventListener container "contextmenu" on-context-menu)
    (.addEventListener container "keydown" on-key-down)
    (.addEventListener container "wheel" on-wheel)
    (.addEventListener container "pointerdown" on-pointer-down)
    {:container container
     :on-context-menu on-context-menu
     :on-key-down on-key-down
     :on-wheel on-wheel
     :on-pointer-down on-pointer-down}))

(defn- teardown-container-listeners!
  [{:keys [container on-context-menu on-key-down on-wheel on-pointer-down]}]
  (when container
    (when on-context-menu
      (.removeEventListener container "contextmenu" on-context-menu))
    (when on-key-down
      (.removeEventListener container "keydown" on-key-down))
    (when on-wheel
      (.removeEventListener container "wheel" on-wheel))
    (when on-pointer-down
      (.removeEventListener container "pointerdown" on-pointer-down))))

(defn- attach-document-listeners!
  [chart-obj document]
  (let [on-pointer-down (fn [event]
                          (let [{:keys [root container menu-open?]} (overlay-state chart-obj)
                                target (.-target event)]
                            (when (and menu-open?
                                       (not (node-inside? root target))
                                       (not (node-inside? container target)))
                              (close-menu! chart-obj {:restore-focus? false}))))
        on-key-down (fn [event]
                      (when (and (:menu-open? (overlay-state chart-obj))
                                 (= "Escape" (.-key event)))
                        (.preventDefault event)
                        (.stopPropagation event)
                        (close-menu! chart-obj)))]
    (.addEventListener document "pointerdown" on-pointer-down)
    (.addEventListener document "keydown" on-key-down)
    {:document document
     :on-pointer-down on-pointer-down
     :on-key-down on-key-down}))

(defn- teardown-document-listeners!
  [{:keys [document on-pointer-down on-key-down]}]
  (when (and document on-pointer-down)
    (.removeEventListener document "pointerdown" on-pointer-down))
  (when (and document on-key-down)
    (.removeEventListener document "keydown" on-key-down)))

(defn clear-chart-context-menu-overlay!
  [chart-obj]
  (when chart-obj
    (clear-copy-feedback-timeout! chart-obj)
    (teardown-container-listeners! (:container-listeners (overlay-state chart-obj)))
    (teardown-document-listeners! (:document-listeners (overlay-state chart-obj)))
    (when-let [root (:root (overlay-state chart-obj))]
      (when-let [parent (.-parentNode root)]
        (.removeChild parent root)))
    (.delete chart-context-menu-overlay-sidecar chart-obj)))

(defn- sync-chart-context-menu-overlay-internal!
  [chart-obj container candles {:keys [document
                                       clipboard
                                       format-price
                                       price-decimals
                                       on-reset
                                       context-key
                                       set-timeout-fn
                                       clear-timeout-fn]}]
  (let [state (overlay-state chart-obj)
        current-container-listeners (:container-listeners state)
        reuse-container-listeners? (and current-container-listeners
                                        (identical? container (:container current-container-listeners)))
        next-container-listeners (if reuse-container-listeners?
                                   current-container-listeners
                                   (do
                                     (teardown-container-listeners! current-container-listeners)
                                     (attach-container-listeners! chart-obj container)))
        current-document-listeners (:document-listeners state)
        reuse-document-listeners? (and current-document-listeners
                                       (identical? document (:document current-document-listeners)))
        next-document-listeners (if reuse-document-listeners?
                                  current-document-listeners
                                  (do
                                    (teardown-document-listeners! current-document-listeners)
                                    (attach-document-listeners! chart-obj document)))
        root-state (ensure-overlay-root! chart-obj container document)]
    (set-overlay-state!
     chart-obj
     (assoc state
            :chart-obj chart-obj
            :container container
            :document document
            :window (resolve-window document)
            :candles (if (sequential? candles) candles [])
            :clipboard clipboard
            :format-price format-price
            :price-decimals price-decimals
            :on-reset on-reset
            :context-key context-key
            :set-timeout-fn (or set-timeout-fn
                                (:set-timeout-fn state)
                                default-set-timeout!)
            :clear-timeout-fn (or clear-timeout-fn
                                  (:clear-timeout-fn state)
                                  default-clear-timeout!)
            :root (:root root-state)
            :panel (:panel root-state)
            :reset-button (:reset-button root-state)
            :copy-button (:copy-button root-state)
            :container-listeners next-container-listeners
            :document-listeners next-document-listeners))
    (sync-reset-button! chart-obj)
    (sync-copy-button-label! chart-obj)
    (when (and (:menu-open? state)
               (not= (:open-context-key state) context-key))
      (close-menu! chart-obj {:restore-focus? false}))
    (sync-root-visibility! chart-obj)))

(defn sync-chart-context-menu-overlay!
  ([chart-obj container candles]
   (sync-chart-context-menu-overlay! chart-obj container candles {}))
  ([chart-obj container candles {:keys [document
                                        clipboard
                                        format-price
                                        price-decimals
                                        on-reset
                                        context-key
                                        set-timeout-fn
                                        clear-timeout-fn]
                                 :or {format-price fmt/format-trade-price-plain
                                      on-reset (fn [] nil)}}]
   (if (not (and chart-obj container))
     (clear-chart-context-menu-overlay! chart-obj)
     (let [document* (resolve-document document)]
       (if (not document*)
         (clear-chart-context-menu-overlay! chart-obj)
         (sync-chart-context-menu-overlay-internal!
          chart-obj
          container
          candles
          {:document document*
           :clipboard clipboard
           :format-price format-price
           :price-decimals price-decimals
           :on-reset on-reset
           :context-key context-key
           :set-timeout-fn set-timeout-fn
           :clear-timeout-fn clear-timeout-fn}))))))
