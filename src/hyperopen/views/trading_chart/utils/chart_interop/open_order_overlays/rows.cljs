(ns hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays.rows
  (:require [hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays.presentation :as presentation]
            [hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays.support :as support]))

(defn create-overlay-root!
  [document]
  (let [root (.createElement document "div")]
    (set! (.-className root) "chart-open-order-overlays")
    (support/apply-inline-style!
     root
     {"position" "absolute"
      "inset" "0px"
      "pointerEvents" "none"
      "zIndex" "12"
      "overflow" "hidden"})
    root))

(defn ensure-overlay-root!
  [state container document]
  (let [{:keys [root]} state
        mounted-root? (and root (identical? (.-parentNode root) container))
        next-root (if mounted-root?
                    root
                    (create-overlay-root! document))]
    (when (and root (not mounted-root?))
      (when-let [parent (.-parentNode root)]
        (.removeChild parent root)))
    (support/append-child! container next-root)
    next-root))

(defn order-row-key
  [order]
  (str (or (:coin order) "")
       "::"
       (or (:oid order) "")))

(declare patch-overlay-row!)

(defn build-overlay-row!
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
        intent-chip-text-node (support/create-text-node! document "")
        label (.createElement document "span")
        label-text-node (support/create-text-node! document "")
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

(defn patch-overlay-row!
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
        (presentation/overlay-row-presentation order intent format-price format-size)
        badge-offset-y (- badge-y line-y)
        connector-visible? (> (js/Math.abs badge-offset-y) 1)
        connector-top (min 0 badge-offset-y)
        connector-height (js/Math.abs badge-offset-y)]
    (reset! cancel-state {:order order
                          :on-cancel-order on-cancel-order})
    (support/apply-inline-style!
     row
     {"position" "absolute"
      "left" "0px"
      "right" "0px"
      "top" (str line-y "px")
      "height" "0px"
      "pointerEvents" "none"})
    (support/apply-inline-style!
     line
     {"position" "absolute"
      "left" "0px"
      "right" "0px"
      "top" "0px"
      "borderTop" (str "1px dashed " line-color)
      "opacity" "0.72"
      "pointerEvents" "none"})
    (support/apply-inline-style!
     connector
     {"position" "absolute"
      "left" (str badge-x "px")
      "top" (str connector-top "px")
      "height" (str connector-height "px")
      "borderLeft" (str "1px dashed " line-color)
      "opacity" "0.72"
      "pointerEvents" "none"
      "display" (if connector-visible? "block" "none")})
    (support/apply-inline-style!
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
    (support/apply-inline-style!
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
    (support/set-text-node-value! intent-chip-text-node chip-label)
    (support/apply-inline-style!
     label
     {"whiteSpace" "nowrap"
      "userSelect" "none"
      "pointerEvents" "none"})
    (support/set-text-node-value! label-text-node label-text)
    (.setAttribute cancel-button "aria-label" cancel-aria-label)
    (support/apply-inline-style!
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

(defn patch-visible-order-rows!
  [root document current-row-dom-by-key laid-out-rows on-cancel-order format-price format-size]
  (let [{:keys [row-dom-by-key visible-keys]}
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
                {:row-dom-by-key (or current-row-dom-by-key {})
                 :visible-keys #{}}
                laid-out-rows)]
    (reduce (fn [cache stale-key]
              (when-let [stale-row (get cache stale-key)]
                (when-let [parent (.-parentNode (:row stale-row))]
                  (.removeChild parent (:row stale-row))))
              (dissoc cache stale-key))
            row-dom-by-key
            (remove visible-keys (keys row-dom-by-key)))))
