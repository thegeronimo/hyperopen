(ns hyperopen.views.chart.range-strip
  "The custom-range context strip (design 1c), shared by the portfolio equity
  chart and the vault detail chart.

  Drag anywhere on the strip to sweep a new window, or grab either handle to
  nudge one edge. The surface supplies its own action ids; everything else —
  geometry, labels, hit-testing — is computed upstream in
  `hyperopen.views.chart.range-strip-model` and
  `hyperopen.portfolio.custom-range`, so this namespace is pure hiccup.

  Two structural rules are load-bearing:

  - The strip renders as an ALWAYS-PRESENT slot that hides itself, never as a
    `(when open? ...)`. Both chart bodies already end in nil-able siblings, and a
    keyed child landing on a nil hole is the shape that throws inside Replicant's
    renderer and latches it as permanently rendering.
  - Every pointer handler lives on the SAME container element, so
    `:event.currentTarget/bounds` resolves against one rect for the whole
    gesture. Per-handle listeners would measure different rects between
    pointer-down and pointer-move and the selection would drift under the cursor."
  (:require [hyperopen.views.chart.range-strip-model :as model]))

(defn- pointer-handlers
  "Placeholders MUST be wrapped in vectors — Nexus only interpolates
  `[:event/clientX]`, and a bare keyword would arrive at the action as the
  keyword itself."
  [{:keys [start-action update-action end-action]} domain-from domain-to]
  (cond-> {}
    start-action
    (assoc :pointerdown [[start-action
                          [:event/clientX]
                          [:event.currentTarget/bounds]
                          domain-from
                          domain-to]])

    update-action
    (assoc :pointermove [[update-action
                          [:event/clientX]
                          [:event.currentTarget/bounds]
                          [:event/pointer-buttons]
                          domain-from
                          domain-to]])

    end-action
    (assoc :pointerup [[end-action]]
           ;; Without pointer capture, leaving the strip mid-drag simply stops
           ;; the move events — so treat it as the end of the gesture rather
           ;; than leaving a drag latched open.
           :pointerleave [[end-action]]
           :pointercancel [[end-action]])))

(defn- handle
  [position label]
  [:div {:class ["ho-range-strip__handle"]
         :style {:left position}
         :aria-hidden "true"
         :title label}])

(defn range-strip
  "`model` comes from `range-strip-model/build-model`. `actions` is
  `{:start-action :update-action :end-action :done-action}` — the ids differ per
  surface, which is the only thing that does."
  [{:keys [model actions data-role-prefix label]}]
  (let [{:keys [available? open? dragging? domain selection handles
                sparkline-points selected-points hint draft-label
                domain-from-label domain-to-label viewbox]} model
        prefix (or data-role-prefix "chart-range-strip")
        visible? (boolean (and available? open?))]
    [:div {:class (cond-> ["ho-range-strip"]
                    dragging? (conj "ho-range-strip--dragging")
                    (not visible?) (conj "hidden"))
           :data-role prefix}
     [:div {:class ["ho-range-strip__head"]}
      [:span {:class (cond-> ["ho-range-strip__hint"]
                       dragging? (conj "ho-range-strip__hint--active"))
              :data-role (str prefix "-hint")}
       hint]
      [:div {:class ["ho-range-strip__head-right"]}
       [:span {:class ["num" "ho-range-strip__draft"]
               :data-role (str prefix "-draft")}
        (or draft-label "")]
       (when (:done-action actions)
         [:button {:type "button"
                   :class ["ho-range-strip__done"]
                   :data-role (str prefix "-done")
                   :on {:click [[(:done-action actions)]]}}
          "Done"])]]
     [:div {:class ["ho-range-strip__track"]
            :data-role (str prefix "-track")
            :role "group"
            :aria-label (str (or label "Chart") " custom range. Drag across the strip to"
                             " choose a window, or drag either edge handle."
                             (when draft-label (str " Currently " draft-label ".")))
            :on (pointer-handlers actions (:from domain) (:to domain))}
      [:svg {:class ["ho-range-strip__spark"]
             :viewBox viewbox
             :preserveAspectRatio "none"
             :aria-hidden "true"
             :focusable "false"}
       (when sparkline-points
         [:polyline {:class ["ho-range-strip__spark-line"]
                     :points sparkline-points
                     :fill "none"
                     ;; The SVG is stretched to the strip's box, so without this
                     ;; the stroke would be squashed to a hairline horizontally.
                     :vector-effect "non-scaling-stroke"}])
       (when selected-points
         [:polyline {:class ["ho-range-strip__spark-line"
                             "ho-range-strip__spark-line--selected"]
                     :points selected-points
                     :fill "none"
                     :vector-effect "non-scaling-stroke"}])]
      [:div {:class ["ho-range-strip__selection"]
             :data-role (str prefix "-selection")
             :style {:left (:left selection)
                     :width (:width selection)}}]
      (handle (:from handles) "Range start")
      (handle (:to handles) "Range end")]
     [:div {:class ["ho-range-strip__scale"]}
      [:span (or domain-from-label "")]
      [:span (or domain-to-label "")]]]))

(defn build-model
  "Re-exported so a surface only has to require this namespace."
  [options]
  (model/build-model options))
