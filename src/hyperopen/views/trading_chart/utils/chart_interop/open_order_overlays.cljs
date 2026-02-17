(ns hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.shared :as account-shared]))

(defonce ^:private open-order-overlays-sidecar (js/WeakMap.))

(def ^:private buy-line-color "rgba(34, 201, 151, 0.85)")
(def ^:private sell-line-color "rgba(227, 95, 120, 0.85)")
(def ^:private buy-badge-color "rgba(34, 201, 151, 0.14)")
(def ^:private sell-badge-color "rgba(227, 95, 120, 0.14)")
(def ^:private buy-text-color "rgb(151, 252, 228)")
(def ^:private sell-text-color "rgb(244, 187, 198)")

(defn- overlay-state
  [chart-obj]
  (if chart-obj
    (or (.get open-order-overlays-sidecar chart-obj) {})
    {}))

(defn- set-overlay-state!
  [chart-obj state]
  (when chart-obj
    (.set open-order-overlays-sidecar chart-obj state))
  state)

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))))

(defn- non-negative-number
  [value fallback]
  (if (and (finite-number? value) (not (neg? value)))
    value
    fallback))

(defn- parse-order-number
  [value]
  (account-shared/parse-optional-num value))

(defn- buy-side?
  [side]
  (= "B" (some-> side str str/trim str/upper-case)))

(defn- side-label
  [side]
  (if (buy-side? side) "Buy" "Sell"))

(defn- order-type-label
  [order]
  (let [order-type (some-> (:type order) str str/trim)]
    (if (seq order-type)
      (account-shared/title-case-label order-type)
      "Order")))

(defn- order-line-color
  [side]
  (if (buy-side? side) buy-line-color sell-line-color))

(defn- order-badge-color
  [side]
  (if (buy-side? side) buy-badge-color sell-badge-color))

(defn- order-text-color
  [side]
  (if (buy-side? side) buy-text-color sell-text-color))

(defn- format-order-price
  [format-price order]
  (or (when (fn? format-price)
        (or (try
              (format-price (:px order) (:px order))
              (catch :default _ nil))
            (try
              (format-price (:px order))
              (catch :default _ nil))))
      (account-shared/format-trade-price (:px order))
      "0.00"))

(defn- ensure-dollar-prefixed-price
  [price-text]
  (let [text (some-> price-text str str/trim)]
    (cond
      (not (seq text)) "$0.00"
      (or (str/starts-with? text "$")
          (str/starts-with? text "<$"))
      text
      :else (str "$" text))))

(defn- format-order-size
  [format-size order]
  (or (when (fn? format-size)
        (format-size (:sz order)))
      (account-shared/format-currency (:sz order))
      "0.00"))

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

(defn- create-overlay-root!
  [document]
  (let [root (.createElement document "div")]
    (set! (.-className root) "chart-open-order-overlays")
    (apply-inline-style!
     root
     {"position" "absolute"
      "inset" "0px"
      "pointerEvents" "none"
      "zIndex" "12"
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
    (when (and next-root (not (identical? (.-parentNode next-root) container)))
      (.appendChild container next-root))
    next-root))

(defn- build-overlay-row!
  [document order x y on-cancel-order format-price format-size]
  (let [side (:side order)
        side-text (side-label side)
        order-type (order-type-label order)
        px-text (format-order-price format-price order)
        px-label (ensure-dollar-prefixed-price px-text)
        sz-text (format-order-size format-size order)
        line-color (order-line-color side)
        badge-color (order-badge-color side)
        text-color (order-text-color side)
        row (.createElement document "div")
        line (.createElement document "div")
        badge (.createElement document "div")
        label (.createElement document "span")
        cancel-button (.createElement document "button")]
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
      "borderTop" (str "1px dashed " line-color)
      "opacity" "0.72"
      "pointerEvents" "none"})
    (apply-inline-style!
     badge
     {"position" "absolute"
      "left" (str x "px")
      "top" "0px"
      "transform" "translate(-50%, -50%)"
      "display" "inline-flex"
      "alignItems" "center"
      "gap" "6px"
      "padding" "2px 4px 2px 6px"
      "fontSize" "11px"
      "lineHeight" "16px"
      "fontWeight" "600"
      "borderRadius" "3px"
      "border" (str "1px solid " line-color)
      "background" badge-color
      "backdropFilter" "blur(0.5px)"
      "color" text-color
      "pointerEvents" "auto"})
    (set! (.-textContent label)
          (str order-type " " sz-text " at " px-label))
    (apply-inline-style!
     label
     {"whiteSpace" "nowrap"
      "userSelect" "none"
      "pointerEvents" "none"})
    (.setAttribute cancel-button "type" "button")
    (.setAttribute cancel-button
                   "aria-label"
                   (str "Cancel " (str/lower-case side-text) " order at " px-label))
    (set! (.-textContent cancel-button) "x")
    (apply-inline-style!
     cancel-button
     {"width" "24px"
      "height" "24px"
      "padding" "0px"
      "display" "inline-flex"
      "alignItems" "center"
      "justifyContent" "center"
      "fontSize" "12px"
      "lineHeight" "12px"
      "fontWeight" "700"
      "borderRadius" "3px"
      "border" (str "1px solid " line-color)
      "background" "rgba(7, 17, 25, 0.82)"
      "color" text-color
      "cursor" "pointer"
      "pointerEvents" "auto"})
    (.addEventListener
     cancel-button
     "click"
     (fn [event]
       (.preventDefault event)
       (.stopPropagation event)
       (when (fn? on-cancel-order)
         (on-cancel-order order))))
    (.appendChild badge label)
    (.appendChild badge cancel-button)
    (.appendChild row line)
    (.appendChild row badge)
    row))

(declare render-overlays!)

(defn- teardown-subscription!
  [{:keys [chart main-series time-scale repaint]}]
  (when repaint
    (invoke-method time-scale "unsubscribeVisibleTimeRangeChange" repaint)
    (invoke-method time-scale "unsubscribeVisibleLogicalRangeChange" repaint)
    (invoke-method time-scale "unsubscribeSizeChange" repaint)
    (invoke-method main-series "unsubscribeDataChanged" repaint)
    (invoke-method chart "unsubscribeCrosshairMove" repaint)
    (invoke-method chart "unsubscribeClick" repaint)))

(defn- subscribe-overlay-repaint!
  [chart-obj chart main-series]
  (let [time-scale (invoke-method chart "timeScale")
        repaint (fn [_]
                  (render-overlays! chart-obj))]
    (invoke-method time-scale "subscribeVisibleTimeRangeChange" repaint)
    (invoke-method time-scale "subscribeVisibleLogicalRangeChange" repaint)
    (invoke-method time-scale "subscribeSizeChange" repaint)
    (invoke-method main-series "subscribeDataChanged" repaint)
    (invoke-method chart "subscribeCrosshairMove" repaint)
    (invoke-method chart "subscribeClick" repaint)
    {:chart chart
     :main-series main-series
     :time-scale time-scale
     :repaint repaint}))

(defn render-overlays!
  [chart-obj]
  (let [{:keys [root
                chart
                main-series
                orders
                on-cancel-order
                format-price
                format-size
                document]}
        (overlay-state chart-obj)]
    (when root
      (clear-children! root)
      (when (and main-series (sequential? orders) (seq orders) document)
        (let [pane-size (invoke-method chart "paneSize" 0)
              pane-width (some-> pane-size (aget "width"))
              width (non-negative-number pane-width
                                         (non-negative-number (.-clientWidth root) 0))
              center-x (/ width 2)
              height (or (.-clientHeight root) 0)]
          (doseq [order (sort-by (fn [o]
                                   (or (parse-order-number (:px o)) 0))
                                 >
                                 orders)]
            (let [price (parse-order-number (:px order))
                  y (when (and (finite-number? price)
                               (pos? price)
                               (:oid order)
                               (:coin order))
                      (invoke-method main-series "priceToCoordinate" price))]
              (when (and (finite-number? y)
                         (or (zero? height)
                             (and (> y -30)
                                  (< y (+ height 30)))))
                (.appendChild root
                              (build-overlay-row! document
                                                  order
                                                  center-x
                                                  y
                                                  on-cancel-order
                                                  format-price
                                                  format-size))))))))))

(defn clear-open-order-overlays!
  [chart-obj]
  (when chart-obj
    (let [{:keys [root subscription]} (overlay-state chart-obj)]
      (teardown-subscription! subscription)
      (when root
        (clear-children! root)
        (when-let [parent (.-parentNode root)]
          (.removeChild parent root)))
      (.delete open-order-overlays-sidecar chart-obj))))

(defn sync-open-order-overlays!
  ([chart-obj container orders]
   (sync-open-order-overlays! chart-obj container orders {}))
  ([chart-obj container orders {:keys [document on-cancel-order format-price format-size]
                                :or {format-price account-shared/format-trade-price
                                     format-size account-shared/format-currency}}]
   (if-not (and chart-obj container)
     (clear-open-order-overlays! chart-obj)
     (let [chart (.-chart ^js chart-obj)
           main-series (.-mainSeries ^js chart-obj)
           document* (resolve-document document)]
       (if (and chart main-series document*)
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
               orders* (if (sequential? orders)
                         (vec orders)
                         [])]
           (set-overlay-state!
            chart-obj
            (assoc state
                   :root root
                   :chart chart
                   :main-series main-series
                   :orders orders*
                   :document document*
                   :format-price format-price
                   :format-size format-size
                   :on-cancel-order on-cancel-order
                   :subscription next-subscription))
           (render-overlays! chart-obj))
         (clear-open-order-overlays! chart-obj))))))
