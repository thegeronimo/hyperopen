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
(def ^:private tp-line-color "rgba(45, 212, 191, 0.9)")
(def ^:private sl-line-color "rgba(251, 146, 60, 0.9)")
(def ^:private tp-badge-color "rgba(45, 212, 191, 0.16)")
(def ^:private sl-badge-color "rgba(251, 146, 60, 0.16)")
(def ^:private tp-text-color "rgb(153, 246, 228)")
(def ^:private sl-text-color "rgb(254, 215, 170)")
(def ^:private tp-chip-bg "rgba(45, 212, 191, 0.28)")
(def ^:private sl-chip-bg "rgba(251, 146, 60, 0.28)")
(def ^:private neutral-chip-bg "rgba(148, 163, 184, 0.24)")
(def ^:private neutral-chip-text "rgb(203, 213, 225)")
(def ^:private badge-stack-gap-px 24)
(def ^:private badge-overlap-threshold-px 18)
(def ^:private badge-horizontal-step-px 18)
(def ^:private badge-horizontal-max-offset-px 36)
(def ^:private badge-edge-padding-px 14)
(def ^:private tp-side-markers
  #{"tp" "takeprofit" "take-profit" "take profit"})
(def ^:private sl-side-markers
  #{"sl" "stoploss" "stop-loss" "stop loss"})
(def ^:private no-orders [])

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

(defn- clamp
  [value min-value max-value]
  (-> value
      (max min-value)
      (min max-value)))

(defn- buy-side?
  [side]
  (= "B" (some-> side str str/trim str/upper-case)))

(defn- side-label
  [side]
  (if (buy-side? side) "Buy" "Sell"))

(defn- normalize-order-text
  [value]
  (some-> value str str/trim str/lower-case))

(defn- includes-any-fragment?
  [text fragments]
  (boolean
   (some #(str/includes? text %)
         fragments)))

(defn- order-intent
  [order]
  (let [tpsl-text (normalize-order-text (:tpsl order))
        order-type-text (normalize-order-text (:type order))]
    (cond
      (contains? tp-side-markers tpsl-text) :tp
      (contains? sl-side-markers tpsl-text) :sl
      (and (seq order-type-text)
           (includes-any-fragment? order-type-text
                                   #{"takeprofit"
                                     "take-profit"
                                     "take profit"
                                     "take market"
                                     "take limit"}))
      :tp
      (and (seq order-type-text)
           (includes-any-fragment? order-type-text
                                   #{"stoploss"
                                     "stop-loss"
                                     "stop loss"
                                     "stop market"
                                     "stop limit"}))
      :sl
      :else :standard)))

(defn- intent-priority
  [intent]
  (case intent
    :tp 0
    :sl 1
    2))

(defn- intent-chip-label
  [intent]
  (case intent
    :tp "TP"
    :sl "SL"
    "ORD"))

(defn- intent-display-label
  [intent]
  (case intent
    :tp "take profit"
    :sl "stop loss"
    "order"))

(defn- intent-chip-bg
  [intent]
  (case intent
    :tp tp-chip-bg
    :sl sl-chip-bg
    neutral-chip-bg))

(defn- intent-chip-text-color
  [intent]
  (case intent
    :tp tp-text-color
    :sl sl-text-color
    neutral-chip-text))

(defn- order-execution-label
  [order]
  (let [order-type-text (normalize-order-text (:type order))]
    (cond
      (and (seq order-type-text)
           (str/includes? order-type-text "market"))
      "MKT"

      (and (seq order-type-text)
           (str/includes? order-type-text "limit"))
      "LMT"

      :else nil)))

(defn- order-type-label
  [order]
  (let [order-type (some-> (:type order) str str/trim)]
    (if (seq order-type)
      (account-shared/title-case-label order-type)
      "Order")))

(defn- order-line-color
  [side intent]
  (case intent
    :tp tp-line-color
    :sl sl-line-color
    (if (buy-side? side) buy-line-color sell-line-color)))

(defn- order-badge-color
  [side intent]
  (case intent
    :tp tp-badge-color
    :sl sl-badge-color
    (if (buy-side? side) buy-badge-color sell-badge-color)))

(defn- order-text-color
  [side intent]
  (case intent
    :tp tp-text-color
    :sl sl-text-color
    (if (buy-side? side) buy-text-color sell-text-color)))

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
    (when (not= v (aget (.-style el) k))
      (aset (.-style el) k v)))
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

(defn- create-text-node!
  [document text]
  (.createTextNode document (or (some-> text str) "")))

(defn- set-text-node-value!
  [text-node text]
  (let [next-text (or (some-> text str) "")
        current-text (or (some-> text-node .-data)
                         (some-> text-node .-nodeValue)
                         "")]
    (when (not= next-text current-text)
      (aset text-node "data" next-text)
      (aset text-node "nodeValue" next-text)))
  text-node)

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

(defn- layout-sort-key
  [{:keys [line-y intent order]}]
  [line-y
   (intent-priority intent)
   (str (or (:oid order) ""))])

(defn- split-overlap-clusters
  [rows]
  (reduce (fn [clusters row]
            (if-let [last-cluster (peek clusters)]
              (let [last-row (peek last-cluster)
                    last-y (:line-y last-row)
                    row-y (:line-y row)]
                (if (<= (js/Math.abs (- row-y last-y))
                        badge-overlap-threshold-px)
                  (conj (pop clusters) (conj last-cluster row))
                  (conj clusters [row])))
              [[row]]))
          []
          rows))

(defn- cluster-horizontal-offset
  [idx cluster-size]
  (let [center (/ (dec cluster-size) 2)
        raw (* badge-horizontal-step-px
               (- idx center))]
    (clamp raw
           (- badge-horizontal-max-offset-px)
           badge-horizontal-max-offset-px)))

(defn- cluster-start-y
  [cluster height]
  (let [cluster-size (count cluster)
        center-y (/ (reduce + (map :line-y cluster))
                    (max cluster-size 1))
        span (* badge-stack-gap-px
                (max 0 (dec cluster-size)))
        raw-start (- center-y (/ span 2))]
    (if (pos? height)
      (let [top-bound badge-edge-padding-px
            bottom-bound (max top-bound
                              (- height badge-edge-padding-px))
            max-start (- bottom-bound span)]
        (if (< max-start top-bound)
          raw-start
          (clamp raw-start top-bound max-start)))
      raw-start)))

(defn- layout-overlapping-badges
  [rows width height]
  (let [anchor-x (/ width 2)
        sorted-rows (sort-by layout-sort-key rows)
        clusters (split-overlap-clusters sorted-rows)]
    (->> clusters
         (mapcat (fn [cluster]
                   (let [cluster-size (count cluster)
                         start-y (cluster-start-y cluster height)]
                     (map-indexed (fn [idx row]
                                    (assoc row
                                           :badge-y (+ start-y
                                                       (* idx badge-stack-gap-px))
                                           :badge-x (+ anchor-x
                                                       (cluster-horizontal-offset idx cluster-size))))
                                  cluster))))
         vec)))

(defn- overlay-label-text
  [order intent order-type side-text sz-text px-label]
  (if (= intent :standard)
    (str order-type " " sz-text " @ " px-label)
    (str side-text " " sz-text " @ " px-label
         (when-let [execution-label (order-execution-label order)]
           (str " | " execution-label)))))

(defn- order-row-key
  [order]
  (str (or (:coin order) "")
       "::"
       (or (:oid order) "")))

(defn- overlay-row-presentation
  [order intent format-price format-size]
  (let [side (:side order)
        side-text (side-label side)
        order-type (order-type-label order)
        px-text (format-order-price format-price order)
        px-label (ensure-dollar-prefixed-price px-text)
        sz-text (format-order-size format-size order)
        line-color (order-line-color side intent)
        badge-color (order-badge-color side intent)
        text-color (order-text-color side intent)
        kind-text (intent-display-label intent)
        chip-label (intent-chip-label intent)
        label-text (overlay-label-text order intent order-type side-text sz-text px-label)
        cancel-target (if (= intent :standard)
                        (str/lower-case side-text)
                        (str kind-text " " (str/lower-case side-text)))]
    {:chip-label chip-label
     :label-text label-text
     :line-color line-color
     :badge-color badge-color
     :text-color text-color
     :kind-attr (case intent
                  :tp "tp"
                  :sl "sl"
                  "order")
     :title-text (str chip-label " | " label-text)
     :chip-bg (intent-chip-bg intent)
     :chip-text-color (intent-chip-text-color intent)
     :cancel-aria-label (str "Cancel "
                             cancel-target
                             " order at "
                             px-label)}))

(declare patch-overlay-row!)

(defn- build-overlay-row!
  [document
   order
   {:keys [line-y badge-y badge-x intent]}
   on-cancel-order
   format-price
   format-size]
  (let [row (.createElement document "div")
        line (.createElement document "div")
        connector (.createElement document "div")
        badge (.createElement document "div")
        intent-chip (.createElement document "span")
        intent-chip-text-node (create-text-node! document "")
        label (.createElement document "span")
        label-text-node (create-text-node! document "")
        cancel-button (.createElement document "button")
        cancel-state (atom {:order order
                            :on-cancel-order on-cancel-order})]
    (.setAttribute cancel-button "type" "button")
    (set! (.-textContent cancel-button) "x")
    (let [cancel-dispatched? (atom false)
          emit-cancel!
          (fn [event]
            (when event
              (.preventDefault event)
              (.stopPropagation event))
            (let [{:keys [order on-cancel-order]} @cancel-state]
              (when (and (not @cancel-dispatched?)
                         (fn? on-cancel-order))
                (reset! cancel-dispatched? true)
                (on-cancel-order order))))]
      ;; Pointer-first handling avoids chart repaint hooks consuming the first click.
      (.addEventListener cancel-button "pointerdown" emit-cancel!)
      (.addEventListener cancel-button "touchstart" emit-cancel!)
      (.addEventListener cancel-button "mousedown" emit-cancel!)
      (.addEventListener cancel-button "click" emit-cancel!))
    (.appendChild intent-chip intent-chip-text-node)
    (.appendChild label label-text-node)
    (.appendChild badge intent-chip)
    (.appendChild badge label)
    (.appendChild badge cancel-button)
    (.appendChild row line)
    (.appendChild row connector)
    (.appendChild row badge)
    (patch-overlay-row!
     {:row row
      :line line
      :connector connector
      :badge badge
      :intent-chip intent-chip
      :intent-chip-text-node intent-chip-text-node
      :label label
      :label-text-node label-text-node
      :cancel-button cancel-button
      :cancel-state cancel-state}
     order
     {:line-y line-y
      :badge-y badge-y
      :badge-x badge-x
      :intent intent}
     on-cancel-order
     format-price
     format-size)))

(defn- patch-overlay-row!
  [{:keys [row
           line
           connector
           badge
           intent-chip
           intent-chip-text-node
           label
           label-text-node
           cancel-button
           cancel-state]
    :as row-dom}
   order
   {:keys [line-y badge-y badge-x intent]}
   on-cancel-order
   format-price
   format-size]
  (let [{:keys [chip-label
                label-text
                line-color
                badge-color
                text-color
                kind-attr
                title-text
                chip-bg
                chip-text-color
                cancel-aria-label]}
        (overlay-row-presentation order intent format-price format-size)
        badge-offset-y (- badge-y line-y)
        connector-visible? (> (js/Math.abs badge-offset-y) 1)
        connector-top (min 0 badge-offset-y)
        connector-height (js/Math.abs badge-offset-y)]
    (reset! cancel-state {:order order
                          :on-cancel-order on-cancel-order})
    (apply-inline-style!
     row
     {"position" "absolute"
      "left" "0px"
      "right" "0px"
      "top" (str line-y "px")
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
     connector
     {"position" "absolute"
      "left" (str badge-x "px")
      "top" (str connector-top "px")
      "height" (str connector-height "px")
      "borderLeft" (str "1px dashed " line-color)
      "opacity" "0.72"
      "pointerEvents" "none"
      "display" (if connector-visible? "block" "none")})
    (apply-inline-style!
     badge
     {"position" "absolute"
      "left" (str badge-x "px")
      "top" (str badge-offset-y "px")
      "transform" "translate(-50%, -50%)"
      "display" "inline-flex"
      "alignItems" "center"
      "gap" "6px"
      "padding" "2px 6px"
      "minHeight" "24px"
      "fontSize" "11px"
      "lineHeight" "16px"
      "fontWeight" "600"
      "borderRadius" "4px"
      "border" (str "1px solid " line-color)
      "background" badge-color
      "backdropFilter" "blur(0.5px)"
      "color" text-color
      "pointerEvents" "auto"})
    (.setAttribute badge "data-order-kind" kind-attr)
    (.setAttribute badge "title" title-text)
    (apply-inline-style!
     intent-chip
     {"display" "inline-flex"
      "alignItems" "center"
      "justifyContent" "center"
      "minWidth" "26px"
      "height" "16px"
      "padding" "0px 5px"
      "borderRadius" "999px"
      "fontSize" "10px"
      "lineHeight" "10px"
      "fontWeight" "700"
      "letterSpacing" "0.04em"
      "userSelect" "none"
      "textTransform" "uppercase"
      "background" chip-bg
      "color" chip-text-color
      "border" "1px solid rgba(148, 163, 184, 0.32)"
      "pointerEvents" "none"})
    (set-text-node-value! intent-chip-text-node chip-label)
    (apply-inline-style!
     label
     {"whiteSpace" "nowrap"
      "userSelect" "none"
      "pointerEvents" "none"})
    (set-text-node-value! label-text-node label-text)
    (.setAttribute cancel-button "aria-label" cancel-aria-label)
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
    row-dom))

(declare render-overlays!)

(defn- teardown-subscription!
  [{:keys [main-series time-scale repaint]}]
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
      (let [pane-size (invoke-method chart "paneSize" 0)
            pane-width (some-> pane-size (aget "width"))
            width (non-negative-number pane-width
                                       (non-negative-number (.-clientWidth root) 0))
            height (or (.-clientHeight root) 0)
            visible-rows
            (if (and main-series (sequential? orders) (seq orders) document)
              (->> orders
                   (sort-by (fn [o]
                              (or (parse-order-number (:px o)) 0))
                            >)
                   (keep (fn [order]
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
                               {:order order
                                :line-y y
                                :intent (order-intent order)}))))
                   vec)
              [])
            laid-out-rows (layout-overlapping-badges visible-rows width height)
            state (overlay-state chart-obj)
            current-row-dom-by-key (or (:row-dom-by-key state) {})
            {:keys [row-dom-by-key visible-keys]}
            (reduce (fn [{:keys [row-dom-by-key visible-keys]} row-data]
                      (let [order (:order row-data)
                            row-key (order-row-key order)
                            row-dom (or (get row-dom-by-key row-key)
                                        (build-overlay-row! document
                                                            order
                                                            row-data
                                                            on-cancel-order
                                                            format-price
                                                            format-size))
                            row-dom* (patch-overlay-row! row-dom
                                                         order
                                                         row-data
                                                         on-cancel-order
                                                         format-price
                                                         format-size)]
                        (.appendChild root (:row row-dom*))
                        {:row-dom-by-key (assoc row-dom-by-key row-key row-dom*)
                         :visible-keys (conj visible-keys row-key)}))
                    {:row-dom-by-key current-row-dom-by-key
                     :visible-keys #{}}
                    laid-out-rows)
            stale-keys (remove visible-keys (keys row-dom-by-key))
            next-row-dom-by-key (reduce (fn [cache stale-key]
                                          (when-let [stale-row (get cache stale-key)]
                                            (when-let [parent (.-parentNode (:row stale-row))]
                                              (.removeChild parent (:row stale-row))))
                                          (dissoc cache stale-key))
                                        row-dom-by-key
                                        stale-keys)]
        (set-overlay-state!
         chart-obj
         (assoc state :row-dom-by-key next-row-dom-by-key))))))

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
               orders-ref (when (sequential? orders) orders)
               orders* (cond
                         (vector? orders) orders
                         (sequential? orders) (vec orders)
                         :else no-orders)
               unchanged-inputs?
               (and (not needs-resubscribe?)
                    (identical? root (:root state))
                    (identical? chart (:chart state))
                    (identical? main-series (:main-series state))
                    (identical? document* (:document state))
                    (identical? format-price (:format-price state))
                    (identical? format-size (:format-size state))
                    (identical? on-cancel-order (:on-cancel-order state))
                    (identical? orders-ref (:orders-ref state)))]
           (if unchanged-inputs?
             state
             (do
               (set-overlay-state!
                chart-obj
                (assoc state
                       :root root
                       :chart chart
                       :main-series main-series
                       :orders-ref orders-ref
                       :orders orders*
                       :document document*
                       :format-price format-price
                       :format-size format-size
                       :on-cancel-order on-cancel-order
                       :subscription next-subscription))
               (render-overlays! chart-obj))))
         (clear-open-order-overlays! chart-obj))))))
