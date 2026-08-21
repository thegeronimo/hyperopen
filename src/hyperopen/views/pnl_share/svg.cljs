(ns hyperopen.views.pnl-share.svg
  "Primitives shared by the share-card templates.

   The card is set in JetBrains Mono, which is monospaced at exactly 0.6em per
   advance. That makes every text width computable without touching the DOM, so
   the templates can lay themselves out arithmetically and stay pure functions
   testable in the node suite.")

(def mono-family
  "JetBrains Mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace")

(def ^:private mono-advance-ratio
  0.6)

(defn text-width
  "Rendered width of `text` at `font-size`, including optional letter spacing
   expressed as a fraction of the font size (the CSS `em` convention)."
  ([text font-size] (text-width text font-size 0))
  ([text font-size tracking-em]
   (let [n (count (or text ""))]
     (if (zero? n)
       0
       (+ (* n font-size mono-advance-ratio)
          (* (dec n) font-size (or tracking-em 0)))))))

(defn text
  "A `<text>` node. `:tracking` is in em, matching the CSS letter-spacing values
   the design specifies."
  [{:keys [x y size weight fill anchor tracking opacity]} content]
  [:text (cond-> {:x x
                  :y y
                  :font-family mono-family
                  :font-size size
                  :font-weight (or weight 400)
                  :fill fill}
           anchor (assoc :text-anchor anchor)
           opacity (assoc :fill-opacity opacity)
           (and tracking (not (zero? tracking)))
           (assoc :letter-spacing (* tracking size)))
   content])

(defn linear-gradient
  "A `<linearGradient>` over the gradient object's own bounding box.
   `stops` is a sequence of [offset colour] or [offset colour opacity]."
  [id [x1 y1 x2 y2] stops]
  (into [:linearGradient {:id id :x1 x1 :y1 y1 :x2 x2 :y2 y2}]
        (map (fn [[offset colour opacity]]
               [:stop (cond-> {:offset offset :stop-color colour}
                        opacity (assoc :stop-opacity opacity))]))
        stops))

(defn radial-wash
  "A `<radialGradient>` in user space, squashed into an ellipse by a gradient
   transform. `cx`/`cy` are the centre, `rx`/`ry` the radii."
  [id {:keys [cx cy rx ry inner outer stop]}]
  [:radialGradient {:id id
                    :gradientUnits "userSpaceOnUse"
                    :cx cx
                    :cy cy
                    :r rx
                    :gradientTransform (str "translate(0," cy ") scale(1," (/ ry rx) ") translate(0," (- cy) ")")}
   [:stop {:offset "0%" :stop-color inner}]
   [:stop {:offset (or stop "62%") :stop-color outer}]])

(defn rounded-plate
  [{:keys [width height radius fill stroke]}]
  [:rect (cond-> {:x 0.5
                  :y 0.5
                  :width (dec width)
                  :height (dec height)
                  :rx radius
                  :fill fill}
           stroke (assoc :stroke stroke :stroke-width 1))])

(defn hairline
  [{:keys [x1 x2 y stroke opacity]}]
  [:line (cond-> {:x1 x1 :y1 y :x2 x2 :y2 y :stroke stroke :stroke-width 1}
           opacity (assoc :stroke-opacity opacity))])

(defn vertical-rule
  [{:keys [x y1 y2 stroke]}]
  [:line {:x1 x :y1 y1 :x2 x :y2 y2 :stroke stroke :stroke-width 1}])

(defn monogram-disc
  "The coin's initial on a deterministic gradient disc. The card never fetches a
   coin icon: the icon CDN sends no CORS header, so drawing one would taint the
   canvas and make the PNG export throw. Colours arrive resolved from
   hyperopen.views.pnl-share.palette -- this namespace holds no colour literals."
  [{:keys [cx cy r letter gradient-id label-size rim letter-fill]}]
  [:g
   [:circle {:cx cx :cy cy :r r :fill (str "url(#" gradient-id ")")}]
   [:circle {:cx cx :cy cy :r r :fill "none"
             :stroke rim :stroke-width 1}]
   (text {:x cx
          :y (+ cy (* label-size 0.35))
          :size label-size
          :weight 500
          :fill letter-fill
          :anchor "middle"}
         letter)])

(defn chip
  "A rounded label chip. Returns [node next-x] so a row of chips can lay itself
   out left to right without measuring the DOM."
  [{:keys [x y height size fill stroke colour tracking pad]} label]
  (let [pad* (or pad 18)
        width (+ (text-width label size (or tracking 0)) (* 2 pad*))]
    [[:g
      [:rect {:x x :y y :width width :height height :rx 12
              :fill fill :stroke stroke :stroke-width 1}]
      (text {:x (+ x pad*)
             :y (+ y (/ height 2) (* size 0.34))
             :size size
             :weight 500
             :fill colour
             :tracking (or tracking 0)}
            label)]
     (+ x width)]))

(defn card-root
  "The outermost `<svg>`. Explicit width and height matter: Firefox rasterizes a
   viewBox-only SVG at zero size, which silently produces an empty PNG."
  [{:keys [width height title]} & children]
  (into [:svg {:xmlns "http://www.w3.org/2000/svg"
               :width width
               :height height
               :viewBox (str "0 0 " width " " height)
               :role "img"
               :aria-label title
               :data-role "pnl-share-card-svg"}]
        (remove nil? children)))
