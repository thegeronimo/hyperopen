(ns hyperopen.views.portfolio.optimize.setup-exposure-map
  "The 2D exposure-map pieces. A trader drags one point on a small pad: the vertical axis is
  gross leverage, the horizontal axis is net (long/short) bias. A shaded box around the point is
  the exact min/max band sent to the solver. A large stacked readout beside the pad echoes the
  targets live. The band sliders, current-portfolio line, and remembered-profile row are exported
  separately — setup-constraint-controls composes them inside its Fine-tune drawer (2026-07-10
  simplified default view; the preset chips, drag caption, and legend were removed with it).

  The pad scale is FIXED while dragging: values clamp to the visible axis, and only the explicit
  zoom buttons (or a profile/reset re-fit) change the scale, so the mapping under the pointer can
  never shift mid-gesture.

  All dispatch goes through the atomic exposure actions; the pad's pointer coordinates are
  resolved by the :event/clientX, :event/clientY, :event.currentTarget/bounds, and
  :event/pointer-buttons placeholders and converted to targets purely in the action handler."
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.setup-controls :as controls]))

;; --- formatting ---------------------------------------------------------------------------

(defn- fmt-mult
  [x]
  (if (number? x) (str (.toFixed x 2) "×") "--"))

(defn- fmt-signed-mult
  "Net-bias multiple with an explicit sign so long/short is unambiguous (+1.00×, −0.50×)."
  [x]
  (if (number? x)
    (str (when (pos? x) "+") (.toFixed x 2) "×")
    "--"))

(defn- fmt-axis
  "Compact tick label: whole numbers show without decimals (10×), else one decimal (2.5×)."
  [x]
  (if (number? x)
    (if (== x (js/Math.round x))
      (str (js/Math.round x) "×")
      (str (.toFixed x 1) "×"))
    "--"))

(defn- pct
  "Fraction (0..1) → SVG user-space coordinate on the 0..100 pad."
  [f]
  (* 100 (or f 0)))

(defn- fmt-band-pct
  "Net-band fraction (0..1) → display percentage with one decimal (0.05 → \"5.0%\")."
  [q]
  (if (number? q) (str (.toFixed (* 100 q) 1) "%") "--"))

(defn- fmt-signed-pct
  "Signed ratio → display percentage (0.997 → \"+99.7%\"), or — when nil (zero gross)."
  [r]
  (if (number? r)
    (str (when-not (neg? r) "+") (.toFixed (* 100 r) 1) "%")
    "—"))

(defn- polygon-points
  "Fractional [[x y] ...] vertices → SVG points string on the 0..100 pad."
  [points]
  (->> points
       (map (fn [[x y]] (str (.toFixed (pct x) 2) "," (.toFixed (pct y) 2))))
       (str/join " ")))

(defn- gross-echo
  [{:keys [gross-min gross-max gross-floored?]}]
  (if gross-floored?
    (str "gross " (fmt-mult gross-min) "–" (fmt-mult gross-max))
    (str "gross ≤ " (fmt-mult gross-max))))

(defn- net-echo
  [{:keys [net-min net-max net-band-pct]}]
  (let [band-suffix (when (and (number? net-band-pct) (pos? net-band-pct))
                      (str " ± " (fmt-band-pct net-band-pct) " of gross"))]
    (cond
      (and (number? net-min) (number? net-max) (== net-min net-max))
      (str "net " (fmt-mult net-min) band-suffix)

      (and (number? net-min) (number? net-max))
      (str "net " (fmt-mult net-min) "–" (fmt-mult net-max) band-suffix)

      (number? net-max) (str "net ≤ " (fmt-mult net-max) band-suffix)
      (number? net-min) (str "net ≥ " (fmt-mult net-min) band-suffix)
      :else "net unbounded")))

;; --- the SVG pad --------------------------------------------------------------------------

(defn- pad-pointer-action
  "Bake the current fixed axis scale AND its zoom level into the drag dispatch: the pointer maps
  to exactly the values the axis shows, and the handler pins the stored zoom to that level so
  the scale cannot shrink under the pointer when the policy re-fits smaller mid-gesture."
  [{:keys [axis zoom]}]
  [[:actions/set-portfolio-optimizer-exposure-point
    [:event/clientX]
    [:event/clientY]
    [:event.currentTarget/bounds]
    [:event/pointer-buttons]
    (:gross-max axis)
    (:net-extent axis)
    (:level zoom)]])

(defn- exposure-pad
  [{:keys [target-marker band-rect band-wedge band-wedge-stripe band-edges
           current-marker highlighted policy net-output-only?] :as model}]
  (let [{tx :x ty :y} target-marker
        {by :y bh :h} band-rect
        y-top (pct by)
        y-bot (pct (+ by bh))
        range-x0 (pct (:left-x band-edges))
        range-x1 (pct (:right-x band-edges))
        target-x (pct tx)
        target-y (pct ty)
        gross-warn? (:gross highlighted)
        net-warn? (:net highlighted)
        aria (if net-output-only?
               (str "Exposure map. Gross target " (fmt-mult (:gross-target policy))
                    ". Equal Risk determines resulting net from covariance and selected sides.")
               (str "Exposure map. Gross target " (fmt-mult (:gross-target policy))
                    ", net target " (fmt-mult (:net-target policy))
                    ". Drag the point to set the target, or use the zoom buttons, the"
                    " Fine-tune band sliders, and the advanced fields."))]
    [:svg {:class ["optimizer-exposure-map__pad"]
           :viewBox "0 0 100 100"
           :preserveAspectRatio "none"
           :role "img"
           :aria-label aria
           :data-role "portfolio-optimizer-exposure-pad"
           :data-gross-infeasible (when gross-warn? "true")
           :data-net-infeasible (when net-warn? "true")
           :on {:pointerdown (pad-pointer-action model)
                :pointermove (pad-pointer-action model)}}
     ;; surface + gridlines: horizontals at the quartiles so the mid tick label lines up with a
     ;; drawn line; the vertical centre line is net 0.
     [:rect {:class ["optimizer-exposure-map__surface"] :x 0 :y 0 :width 100 :height 100}]
     [:line {:class ["optimizer-exposure-map__grid"] :x1 50 :y1 0 :x2 50 :y2 100}]
     [:line {:class ["optimizer-exposure-map__grid"] :x1 0 :y1 25 :x2 100 :y2 25}]
     [:line {:class ["optimizer-exposure-map__grid" "optimizer-exposure-map__grid--mid"]
             :x1 0 :y1 50 :x2 100 :y2 50}]
     [:line {:class ["optimizer-exposure-map__grid"] :x1 0 :y1 75 :x2 100 :y2 75}]
     ;; full-length band regions: the gross range as a full-width horizontal
     ;; stripe, the net band as a SLOPED WEDGE (its absolute width is a
     ;; percentage of gross, so the boundaries are net = target ± pct·gross,
     ;; never fixed verticals). At a wide axis the band box around the dot is
     ;; smaller than the drag handle itself, so these are what make a band
     ;; slider drag visibly expand/contract the allowed region at ANY zoom.
     [:rect {:class ["optimizer-exposure-map__band-stripe"]
             :data-role "portfolio-optimizer-exposure-gross-stripe"
             :x 0 :y y-top :width 100 :height (max 0 (- y-bot y-top))}]
     [:polygon {:class ["optimizer-exposure-map__band-stripe"]
                :data-role "portfolio-optimizer-exposure-net-stripe"
                :points (polygon-points (:points band-wedge-stripe))}]
     ;; the allowed-region band wedge (the regions' intersection, emphasized)
     [:polygon {:class ["optimizer-exposure-map__band"]
                :data-role "portfolio-optimizer-exposure-band-box"
                :points (polygon-points (:points band-wedge))}]
     ;; net range bar (horizontal, at the target gross) and gross range bar (vertical)
     [:line {:class ["optimizer-exposure-map__range"]
             :x1 range-x0 :y1 target-y :x2 range-x1 :y2 target-y}]
     [:line {:class ["optimizer-exposure-map__range"] :x1 target-x :y1 y-top :x2 target-x :y2 y-bot}]
     ;; current portfolio dot
     (when current-marker
       [:circle {:class ["optimizer-exposure-map__current"]
                 :data-role "portfolio-optimizer-exposure-current"
                 :cx (pct (:x current-marker)) :cy (pct (:y current-marker)) :r 2.2}])
     ;; target handle: an outer grab ring around the dot so it reads as a
     ;; draggable control, not a plotted marker.
     [:circle {:class ["optimizer-exposure-map__handle-ring"]
               :data-role "portfolio-optimizer-exposure-handle-ring"
               :cx target-x :cy target-y :r 5.6}]
     [:circle {:class ["optimizer-exposure-map__handle"]
               :data-role "portfolio-optimizer-exposure-handle"
               :cx target-x :cy target-y :r 3.4}]]))

;; --- axis frame + zoom + readout ----------------------------------------------------------

(defn- zoom-button
  "One step of the explicit scale control. `level` is the exact zoom level this button selects,
  baked into the dispatch by the view model; nil means the step is unavailable (disabled)."
  [{:keys [label level role aria-label]}]
  [:button (cond-> {:type "button"
                    :class ["optimizer-exposure-map__zoom-btn"]
                    :aria-label aria-label
                    :data-role role
                    :disabled (nil? level)}
             (some? level)
             (assoc :on {:click [[:actions/set-portfolio-optimizer-exposure-zoom-level level]]}))
   label])

(defn- axis-frame
  [axis zoom pad]
  (let [g-max (:gross-max axis)
        n-ext (:net-extent axis)]
    [:div {:class ["optimizer-exposure-map__frame"]}
     ;; Header row: just the explicit zoom control (the axis titles live ON
     ;; their axes — gross rotated along the y ticks, net centered under the
     ;; x ticks). The scale NEVER changes from dragging; − widens the visible
     ;; range, + tightens it back down to the policy's fit.
     [:div {:class ["optimizer-exposure-map__axis-header"]}
      [:span {:class ["optimizer-exposure-map__zoom"]
              :data-role "portfolio-optimizer-exposure-zoom"}
       (zoom-button {:label "−"
                     :level (:zoom-out-level zoom)
                     :role "portfolio-optimizer-exposure-zoom-out"
                     :aria-label "Zoom out to a higher leverage range"})
       (zoom-button {:label "+"
                     :level (:zoom-in-level zoom)
                     :role "portfolio-optimizer-exposure-zoom-in"
                     :aria-label "Zoom in to a tighter leverage range"})]]
     ;; Gross exposure IS leverage — :gross-max renames to :gross-leverage for
     ;; the solver.
     [:span {:class ["optimizer-exposure-map__y-axis-label"
                     "optimizer-exposure-map__axis-title"]
             :data-role "portfolio-optimizer-exposure-y-title"}
      "Gross leverage"]
     [:div {:class ["optimizer-exposure-map__yticks"]}
      [:span {:data-role "portfolio-optimizer-exposure-y-max"} (fmt-axis g-max)]
      [:span (fmt-axis (/ g-max 2))]
      [:span "0×"]]
     pad
     ;; Colored end ticks (short red, long green) carry direction on their own —
     ;; the "◄ Short / Long ►" words were chrome (designer mock, 2026-07-10).
     [:div {:class ["optimizer-exposure-map__xaxis"]}
      [:span {:class ["optimizer-exposure-map__axis-end"
                      "optimizer-exposure-map__axis-end--short"]}
       (str "−" (fmt-axis n-ext))]
      [:span {:class ["optimizer-exposure-map__axis-title"
                      "optimizer-exposure-map__axis-title--x"]
              :data-role "portfolio-optimizer-exposure-x-title"}
       "Net bias"]
      [:span {:class ["optimizer-exposure-map__axis-end"
                      "optimizer-exposure-map__axis-end--long"]}
       (str "+" (fmt-axis n-ext))]]]))

(defn readout
  "The large stacked echo of the dragged targets beside the pad — the primary
  numbers of the section (designer mock, 2026-07-10): gross over its
  \"Leverage\" label (crypto vocabulary — the gross figure IS the leverage
  multiple), then the net figure tinted by direction."
  [{:keys [policy net-direction net-output-only?]}]
  [:div {:class ["flex" "shrink-0" "flex-col" "justify-center" "gap-4" "pl-1"]
         :data-role "portfolio-optimizer-exposure-readout"}
   [:div
    [:p {:class ["font-mono" "text-[1.375rem]" "font-semibold" "leading-none"
                 "text-trading-text"]
         :data-role "portfolio-optimizer-exposure-readout-gross"}
     (fmt-mult (:gross-target policy))]
    [:p {:class ["mt-1" "font-mono" "text-[0.6875rem]" "uppercase"
                 "tracking-[0.08em]" "text-trading-muted"]}
     "leverage"]]
   [:div
    [:p {:class ["font-mono" "text-[1.375rem]" "font-semibold" "leading-none"
                 (case net-direction
                   :long "text-success"
                   :short "text-error"
                   "text-trading-text")]
         :data-role "portfolio-optimizer-exposure-readout-net"
         :data-net-direction (name (or net-direction :neutral))}
     (if net-output-only? "--" (fmt-signed-mult (:net-target policy)))]
    [:p {:class ["mt-1" "font-mono" "text-[0.6875rem]" "uppercase"
                 "tracking-[0.08em]" "text-trading-muted"]}
     (if net-output-only?
       "resulting net"
       (str "net" (case net-direction
                    :long " long"
                    :short " short"
                    "")))]]])

;; --- band sliders + echo + presets + memory ------------------------------------------------

(defn- band-slider
  [{:keys [label axis value max-band role level disabled?]}]
  [:label {:class ["optimizer-exposure-map__band-row"]}
   [:span {:class controls/eyebrow-class} label]
   [:input (cond-> {:type "range"
                    :min 0
                    :max max-band
                    :step 0.01
                    :value (str value)
                    :class ["optimizer-exposure-band" "w-full" "accent-warning"]
                    :aria-label (str label " band")
                    :data-role role
                    :disabled (boolean disabled?)}
             (not disabled?)
             (assoc :on {:input [[:actions/set-portfolio-optimizer-exposure-band
                                  axis [:event.target/value] level]]}))]
   [:span {:class ["optimizer-exposure-map__band-value"]
           :data-role (str role "-value")}
    (str "± " (fmt-mult value))]])

(defn- net-off-policy-sentence
  "The net side of an off-policy explanation. With an active percentage band the
  honest framing is net-to-gross: around a ~zero net target the band IS a
  net/gross limit; around a nonzero target it is a tolerance of ±pct·gross
  AROUND that target, and the copy must say so."
  [{:keys [net-gross-ratio net-band-pct net-target]}]
  (let [zero-target? (and (number? net-target) (< (js/Math.abs net-target) 0.005))]
    (cond
      (and (number? net-band-pct) (pos? net-band-pct) zero-target?)
      (str "Current portfolio net-to-gross exposure is " (fmt-signed-pct net-gross-ratio)
           ", outside the allowed ±" (fmt-band-pct net-band-pct) " band.")

      (and (number? net-band-pct) (pos? net-band-pct))
      (str "Current portfolio net is outside the allowed band of the "
           (fmt-signed-mult net-target) " net target ±" (fmt-band-pct net-band-pct)
           " of gross (net/gross is " (fmt-signed-pct net-gross-ratio) ").")

      :else
      "Current portfolio is outside this exposure policy: net is out of range.")))

(defn- off-policy-sentence
  [{:keys [gross-ok? net-ok?] :as preview}]
  (cond
    (and (not gross-ok?) (not net-ok?))
    (str "Current portfolio is outside this exposure policy: gross is out of range and "
         (let [s (net-off-policy-sentence preview)]
           (str (.toLowerCase (subs s 0 1)) (subs s 1))))
    (not gross-ok?)
    "Current portfolio is outside this exposure policy: gross is out of range."
    :else
    (net-off-policy-sentence preview)))

(defn preview-block
  [{:keys [current-exposure on-policy? net-gross-ratio] :as preview}]
  (when current-exposure
    [:p {:class ["optimizer-exposure-map__preview"]
         :data-role "portfolio-optimizer-exposure-preview"
         :data-on-policy (str (boolean on-policy?))}
     [:span {:class controls/eyebrow-class} "Current"]
     [:span {:class ["optimizer-exposure-map__preview-value"]}
      (str (fmt-mult (:gross current-exposure)) " gross · "
           (fmt-mult (:net current-exposure)) " net · "
           (fmt-signed-pct net-gross-ratio) " net/gross")]
     [:span {:class ["optimizer-exposure-map__preview-verdict"]}
      ;; Inside policy is the quiet state: chip-length, no sentence. Only the
      ;; off-policy states earn a full explanation.
      (if on-policy? "Inside policy" (off-policy-sentence preview))]]))

(defn profile-row
  [{:keys [has-default?]}]
  [:div {:class ["optimizer-exposure-map__profile"]
         :data-role "portfolio-optimizer-exposure-profile"}
   [:span {:class controls/eyebrow-class}
    (if has-default? "Saved default for this universe" "Memory")]
   [:div {:class ["optimizer-exposure-map__profile-actions"]}
    [:button {:type "button"
              :class ["optimizer-exposure-map__profile-btn"]
              :data-role "portfolio-optimizer-exposure-save-default"
              :on {:click [[:actions/save-portfolio-optimizer-constraint-default]]}}
     "Save as default"]
    (when has-default?
      [:button {:type "button"
                :class ["optimizer-exposure-map__profile-btn"]
                :data-role "portfolio-optimizer-exposure-apply-default"
                :on {:click [[:actions/apply-portfolio-optimizer-constraint-default]]}}
       "Use saved"])
    [:button {:type "button"
              :class ["optimizer-exposure-map__profile-btn"]
              :data-role "portfolio-optimizer-exposure-reset-default"
              :on {:click [[:actions/reset-portfolio-optimizer-constraints-to-system]]}}
     "Reset"]]])

(defn solver-echo
  "The exact generated-constraints line ('Sent to solver gross … · net …'). An
  implementation-facing audit detail, so it is rendered inside the Advanced
  solver limits drawer (setup-constraint-controls), not in the primary column."
  [{:keys [echo net-output-only?]}]
  [:p {:class ["optimizer-exposure-map__echo"]
       :data-role "portfolio-optimizer-exposure-echo"}
   [:span {:class controls/eyebrow-class} "Sent to solver"]
   [:span {:class ["optimizer-exposure-map__echo-value"]}
    (str (gross-echo echo) " · "
         (if net-output-only?
           "net determined by Equal Risk"
           (net-echo echo)))]])

(defn pad-frame
  "The bounded pad with its axis frame — the one always-visible exposure
  control. Carries the section's exposure-map role."
  [model]
  ;; The pad is aspect-ratio 1:1, so capping its width caps its height — the
  ;; right column's fine-tune + two cards close at the same bottom edge.
  [:div {:class ["optimizer-exposure-map" "min-w-0" "flex-1" "max-w-[19rem]"]
         :data-role "portfolio-optimizer-exposure-map"}
   (axis-frame (:axis model) (:zoom model) (exposure-pad model))])

(def ^:private net-band-help
  "Allows net exposure to vary above or below the net target by this percentage of realized gross exposure.")

(def ^:private net-band-preset-pcts
  [0 2.5 5 10 20])

(defn- net-band-row
  "The percentage net band control: a slider stopping at the model's
  :net-band-pct-slider-max, numeric entry to 100%, presets, and an absolute
  preview. A band ABOVE the slider ceiling is reachable — numeric entry and the
  infeasible panel's Widen-net-band fix write up to max-net-band-pct."
  [{:keys [net-band-pct net-band-abs-preview gross-max level net-editable?
           net-band-pct-slider-max]}]
  (let [pct-value (* 100 (or net-band-pct 0))
        slider-max-pct (* 100 (or net-band-pct-slider-max 0.5))
        pinned? (> pct-value slider-max-pct)
        role "portfolio-optimizer-exposure-net-band"
        editable? (not (false? net-editable?))]
    [:div {:class ["optimizer-exposure-map__band-group"]
           :title net-band-help}
     [:label {:class ["optimizer-exposure-map__band-row"]}
      [:span {:class controls/eyebrow-class} "Net band"]
      [:input (cond-> {:type "range"
                       :min 0
                       :max slider-max-pct
                       :step 0.5
                       ;; The browser sanitizes a range value past its own :max, so render
                       ;; the PINNED number (vdom == DOM); field/label/aria-valuetext keep
                       ;; the TRUE percent, and only a real drag ever replaces the band.
                       :value (str (min pct-value slider-max-pct))
                       :aria-valuetext (str (fmt-band-pct net-band-pct) " of gross")
                       :class ["optimizer-exposure-band" "w-full" "accent-warning"]
                       :aria-label "Net band, percent of gross"
                       :data-role role
                       :data-pinned (str pinned?)
                       :disabled (not editable?)}
                editable?
                (assoc :on {:input [[:actions/set-portfolio-optimizer-exposure-band
                                     :net-pct [:event.target/value] level]]}))]
      [:span {:class ["optimizer-exposure-map__band-value"]
              :data-role (str role "-value")}
       (str "± " (fmt-band-pct net-band-pct) " of gross")]]
     [:div {:class ["flex" "items-center" "gap-2" "pl-1"]}
      ;; type="text" + inputmode="decimal", the codebase-wide convention
      ;; (setup_controls.cljs number-input/percent-input): type="number" fights
      ;; mid-typing decimals on a controlled re-render and its spinner isn't
      ;; themeable. clamp-net-band-pct enforces the bounds, so dropping
      ;; min/max/step (number-input-only hints) loses nothing.
      [:input (cond-> {:type "text"
                       :inputmode "decimal"
                       :value (str pct-value)
                       :replicant/key (str "net-band-pct:" pct-value)
                       :class ["optimizer-exposure-map__band-input" "input" "input-xs"
                               "w-16" "font-mono"]
                       :aria-label "Net band percent, direct entry"
                       :data-role (str role "-input")
                       :disabled (not editable?)}
                editable?
                (assoc :on {:change [[:actions/set-portfolio-optimizer-exposure-band
                                      :net-pct [:event.target/value] level]]}))]
      [:span {:class ["font-mono" "text-[0.625rem]" "text-trading-muted"]} "%"]
      (into [:span {:class ["flex" "gap-1"]
                    :data-role (str role "-presets")}]
            (map (fn [p]
                   [:button {:type "button"
                             :class ["optimizer-exposure-map__profile-btn"]
                             :data-role (str role "-preset-" p)
                             :disabled (not editable?)
                             :on (when editable?
                                   {:click [[:actions/set-portfolio-optimizer-exposure-band
                                             :net-pct p level]]})}
                    (str p "%")]))
            net-band-preset-pcts)]
     (when pinned?
       [:p {:class ["pl-1" "font-mono" "text-[0.625rem]" "text-warning"]
            :data-role (str role "-pinned")}
        (str "Band is " (fmt-band-pct net-band-pct) ", past the slider's "
             (fmt-band-pct net-band-pct-slider-max) " ceiling — moving the pinned "
             "slider replaces it. Type to keep a wider band.")])
     (when (and (number? net-band-pct) (pos? net-band-pct)
                (number? gross-max) (pos? gross-max))
       [:p {:class ["pl-1" "font-mono" "text-[0.625rem]" "text-trading-muted"]
            :data-role (str role "-abs-preview")}
        (str "≈ ±" (fmt-mult net-band-abs-preview) " at " (fmt-mult gross-max)
             " gross (preview — the solver scales with realized gross)")])]))

(defn bands-block
  "Both band-tightness controls, for the Fine-tune drawer: the gross band stays
  an absolute leverage half-width; the net band is a percentage of gross."
  [{:keys [gross-band net-band-pct net-band-abs-preview max-band zoom echo
           net-editable? net-band-pct-slider-max]}]
  [:div {:class ["optimizer-exposure-map__bands"]}
   (band-slider {:label "Gross band" :axis :gross :value gross-band
                 :max-band max-band
                 :level (:level zoom)
                 :role "portfolio-optimizer-exposure-gross-band"})
   (net-band-row {:net-band-pct net-band-pct
                  :net-band-abs-preview net-band-abs-preview
                  :net-band-pct-slider-max net-band-pct-slider-max
                  :gross-max (:gross-max echo)
                  :net-editable? net-editable?
                  :level (:level zoom)})])

(defn policy-warning
  "Off-policy sentence for the DEFAULT view — an actionable violation must not
  hide behind the Fine-tune drawer (the rail's Review-warning anchor lands on
  this panel). The quiet CURRENT/'Inside policy' line stays in preview-block."
  [{:keys [current-exposure on-policy?] :as preview}]
  (when (and preview current-exposure (false? on-policy?))
    [:p {:class ["font-mono" "text-[0.6875rem]" "leading-[1.5]" "text-warning"]
         :data-role "portfolio-optimizer-exposure-policy-line"}
     (off-policy-sentence preview)]))
