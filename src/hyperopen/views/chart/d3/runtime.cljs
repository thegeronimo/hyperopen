(ns hyperopen.views.chart.d3.runtime
  (:require ["d3" :as d3]
            [clojure.string :as str]
            [hyperopen.views.chart.d3.model :as model]))

(def ^:private svg-namespace
  "http://www.w3.org/2000/svg")

(defn- class-string
  [classes]
  (->> (cond
         (string? classes) (str/split classes #"\s+")
         (sequential? classes) (mapcat (fn [value]
                                         (str/split (str (or value "")) #"\s+"))
                                       classes)
         :else [])
       (remove str/blank?)
       (str/join " ")))

(defn- clear-node!
  [node]
  (when node
    (loop []
      (when-let [child (.-firstChild node)]
        (.removeChild node child)
        (recur)))))

(defn- set-style-map!
  [node style-map]
  (doseq [[k v] (or style-map {})]
    (.setProperty (.-style node) (name k) (str v))))

(defn- set-attrs!
  [node attrs]
  (doseq [[k v] attrs]
    (when (some? v)
      (.setAttribute node (name k) (str v)))))

(defn- create-svg-node
  [doc tag attrs]
  (let [node (.createElementNS doc svg-namespace tag)]
    (set-attrs! node attrs)
    node))

(defn- create-html-node
  [doc tag attrs style]
  (let [node (.createElement doc tag)]
    (doseq [[k v] attrs]
      (cond
        (= k :class) (set! (.-className node) (class-string v))
        (= k :text) (set! (.-textContent node) (str (or v "")))
        :else (.setAttribute node (name k) (str v))))
    (set-style-map! node style)
    node))

(defn- strategy-path-data-role
  [prefix series-id]
  (if (= :strategy series-id)
    (str prefix "-path")
    (str prefix "-path-" (name series-id))))

(defn- strategy-area-data-role
  [prefix series-id suffix]
  (if (= :strategy series-id)
    (str prefix "-area" suffix)
    (str prefix "-area-" (name series-id) suffix)))

(defn- measure-host
  [node]
  (let [rect (when (fn? (.-getBoundingClientRect node))
               (.getBoundingClientRect node))
        width (or (some-> rect .-width)
                  (.-clientWidth node)
                  0)
        height (or (some-> rect .-height)
                   (.-clientHeight node)
                   0)]
    {:width (max 1 width)
     :height (max 1 height)
     :left (or (some-> rect .-left) 0)}))

(defn- set-node-display!
  [node visible?]
  (aset (.-style node) "display" (if visible? "" "none")))

(defn- update-html-text!
  [node text]
  (set! (.-textContent node) (str (or text ""))))

(defn- reset-class-name!
  [node classes]
  (set! (.-className node) (class-string classes)))

(defn- remove-node!
  [node]
  (when-let [parent (some-> node .-parentNode)]
    (.removeChild parent node)))

(defn- clip-id
  [runtime series-id suffix]
  (str (:clip-prefix runtime) "-" (name series-id) "-" suffix))

(defn- current-spec
  [runtime]
  @(:spec* runtime))

(defn- current-size
  [runtime]
  @(:size* runtime))

(defn- line-path
  [runtime points]
  (let [{:keys [width]} (current-size runtime)
        points* (model/extend-single-point width points)
        generator (doto (.line d3)
                    (.x (fn [point]
                          (.-x point)))
                    (.y (fn [point]
                          (.-y point))))]
    (generator (clj->js points*))))

(defn- area-path
  [runtime points baseline-y]
  (let [{:keys [width]} (current-size runtime)
        points* (model/extend-single-point width points)
        generator (doto (.area d3)
                    (.x (fn [point]
                          (.-x point)))
                    (.y0 (fn [_point]
                           baseline-y))
                    (.y1 (fn [point]
                           (.-y point))))]
    (generator (clj->js points*))))

(defn- ensure-series-root!
  [runtime series]
  (let [series-id (:id series)
        area-type (model/area-type series)
        data-role-prefix (get-in (current-spec runtime) [:theme :data-role-prefix])
        doc (:doc runtime)
        line-node (create-svg-node doc
                                   "path"
                                   {:fill "none"
                                    :data-role (strategy-path-data-role data-role-prefix series-id)})
        series-root (create-svg-node doc
                                     "g"
                                     {:data-series-id (name series-id)
                                      :data-area-type (name area-type)})]
    (when (= :split-zero area-type)
      (let [defs-node (create-svg-node doc "defs" {})
            positive-clip (create-svg-node doc "clipPath" {:id (clip-id runtime series-id "positive")})
            positive-rect (create-svg-node doc "rect" {:x 0 :y 0 :width 0 :height 0})
            negative-clip (create-svg-node doc "clipPath" {:id (clip-id runtime series-id "negative")})
            negative-rect (create-svg-node doc "rect" {:x 0 :y 0 :width 0 :height 0})
            positive-area (create-svg-node doc
                                           "path"
                                           {:data-role (strategy-area-data-role data-role-prefix series-id "-positive")})
            negative-area (create-svg-node doc
                                           "path"
                                           {:data-role (strategy-area-data-role data-role-prefix series-id "-negative")})]
        (.appendChild positive-clip positive-rect)
        (.appendChild negative-clip negative-rect)
        (.appendChild defs-node positive-clip)
        (.appendChild defs-node negative-clip)
        (.appendChild series-root defs-node)
        (.appendChild series-root positive-area)
        (.appendChild series-root negative-area)))
    (when (= :solid area-type)
      (let [area-node (create-svg-node doc
                                       "path"
                                       {:data-role (strategy-area-data-role data-role-prefix series-id "")})]
        (.appendChild series-root area-node)))
    (.appendChild series-root line-node)
    series-root))

(defn- ensure-series-roots!
  [runtime]
  (let [series-layer (:series-layer runtime)
        current-roots (into {}
                            (map (fn [node]
                                   [(keyword (.getAttribute node "data-series-id")) node]))
                            (array-seq (.-children series-layer)))
        desired-series (vec (or (:series (current-spec runtime)) []))
        desired-ids (set (map :id desired-series))]
    (doseq [[series-id node] current-roots]
      (when-not (contains? desired-ids series-id)
        (remove-node! node)))
    (doseq [series desired-series]
      (let [series-id (:id series)
            desired-area-type (name (model/area-type series))
            existing-root (get current-roots series-id)
            existing-area-type (some-> existing-root (.getAttribute "data-area-type"))
            series-root (if (and existing-root
                                 (= desired-area-type existing-area-type))
                          existing-root
                          (do
                            (remove-node! existing-root)
                            (ensure-series-root! runtime series)))]
        ;; Re-append every desired node so keyed updates also preserve visual stacking order.
        (.appendChild series-layer series-root))))
  runtime)

(defn- series-root-by-id
  [runtime series-id]
  (some (fn [node]
          (when (= (name series-id) (.getAttribute node "data-series-id"))
            node))
        (array-seq (.-children (:series-layer runtime)))))

(defn- update-solid-area!
  [runtime series root pixel-points]
  (let [area-node (some (fn [node]
                          (when (str/includes? (.getAttribute node "data-role") "-area")
                            node))
                        (array-seq (.-children root)))]
    (when area-node
      (set-attrs! area-node {:d (area-path runtime pixel-points (:height (current-size runtime)))
                             :fill (:area-fill series)}))))

(defn- update-split-area!
  [runtime series root pixel-points]
  (let [positive-path (some (fn [node]
                              (when (str/ends-with? (or (.getAttribute node "data-role") "")
                                                    "-positive")
                                node))
                            (array-seq (.-children root)))
        negative-path (some (fn [node]
                              (when (str/ends-with? (or (.getAttribute node "data-role") "")
                                                    "-negative")
                                node))
                            (array-seq (.-children root)))
        defs-node (some (fn [node]
                          (when (= "defs" (some-> node .-tagName str/lower-case))
                            node))
                        (array-seq (.-children root)))
        clip-paths (when defs-node
                     (array-seq (.-children defs-node)))
        positive-rect (some (fn [clip-node]
                              (when (= (clip-id runtime (:id series) "positive")
                                       (.getAttribute clip-node "id"))
                                (aget (.-children clip-node) 0)))
                            clip-paths)
        negative-rect (some (fn [clip-node]
                              (when (= (clip-id runtime (:id series) "negative")
                                       (.getAttribute clip-node "id"))
                                (aget (.-children clip-node) 0)))
                            clip-paths)
        {:keys [width height]} (current-size runtime)
        clip-height (model/positive-clip-height height (:zero-y-ratio series))
        base-path (area-path runtime pixel-points height)]
    (when (and positive-path negative-path positive-rect negative-rect)
      (set-attrs! positive-rect {:x 0 :y 0 :width width :height clip-height})
      (set-attrs! negative-rect {:x 0
                                 :y clip-height
                                 :width width
                                 :height (- height clip-height)})
      (set-attrs! positive-path {:d base-path
                                 :fill (:area-positive-fill series)
                                 :clip-path (str "url(#" (clip-id runtime (:id series) "positive") ")")})
      (set-attrs! negative-path {:d base-path
                                 :fill (:area-negative-fill series)
                                 :clip-path (str "url(#" (clip-id runtime (:id series) "negative") ")")}))))

(defn- update-series-root!
  [runtime series]
  (when-let [root (series-root-by-id runtime (:id series))]
    (let [{:keys [width height]} (current-size runtime)
          pixel-points (model/points->pixel-points width height (:points series))
          line-node (some (fn [node]
                            (when (= (strategy-path-data-role (get-in (current-spec runtime) [:theme :data-role-prefix])
                                                             (:id series))
                                     (.getAttribute node "data-role"))
                              node))
                          (array-seq (.-children root)))
          theme (:theme (current-spec runtime))]
      (case (model/area-type series)
        :solid (update-solid-area! runtime series root pixel-points)
        :split-zero (update-split-area! runtime series root pixel-points)
        nil)
      (when line-node
        (set-attrs! line-node {:d (line-path runtime pixel-points)
                               :stroke (:stroke series)
                               :stroke-width (:line-stroke-width theme)
                               :stroke-linecap (:line-linecap theme)
                               :stroke-linejoin (:line-linejoin theme)
                               :vector-effect "non-scaling-stroke"})))))

(defn- update-tooltip-rows!
  [runtime benchmark-values]
  (let [rows-node (:tooltip-benchmark-rows runtime)
        doc (:doc runtime)]
    (clear-node! rows-node)
    (doseq [{:keys [coin label value stroke]} (or benchmark-values [])]
      (let [row (create-html-node doc
                                  "div"
                                  {:data-role (str (get-in (current-spec runtime) [:theme :data-role-prefix])
                                                   "-hover-tooltip-benchmark-row-"
                                                   coin)
                                   :class ["grid"
                                           "grid-cols-[1fr_auto]"
                                           "items-center"
                                           "gap-3"]}
                                  nil)
            label-node (create-html-node doc
                                         "span"
                                         {:class ["text-[12px]"
                                                  "font-medium"
                                                  "leading-4"
                                                  "text-[#909fac]"]
                                          :text label}
                                         nil)
            value-node (create-html-node doc
                                         "span"
                                         {:data-role (str (get-in (current-spec runtime) [:theme :data-role-prefix])
                                                          "-hover-tooltip-benchmark-value-"
                                                          coin)
                                          :class ["num"
                                                  "text-sm"
                                                  "font-semibold"
                                                  "leading-[1.1]"
                                                  "tracking-tight"]
                                          :text value}
                                         {:color stroke})]
        (.appendChild row label-node)
        (.appendChild row value-node)
        (.appendChild rows-node row)))))

(defn- hide-hover!
  [runtime]
  (reset! (:hover-index* runtime) nil)
  (set-node-display! (:tooltip-root runtime) false)
  (set-node-display! (:hover-line runtime) false))

(defn- show-hover!
  [runtime hover-index]
  (let [spec (current-spec runtime)
        points (vec (or (:points spec) []))
        series (vec (or (:series spec) []))
        hovered-point (nth points hover-index nil)
        tooltip-builder (:build-tooltip spec)
        {:keys [width height]} (current-size runtime)]
    (if (and hovered-point (fn? tooltip-builder))
      (let [tooltip-model (tooltip-builder {:index hover-index
                                            :point hovered-point
                                            :active? true}
                                           series)
            {:keys [left-px top-px right-side?]} (model/tooltip-layout width
                                                                       height
                                                                       hovered-point)]
        (reset! (:hover-index* runtime) hover-index)
        (update-html-text! (:tooltip-timestamp runtime) (:timestamp tooltip-model))
        (update-html-text! (:tooltip-label runtime) (:metric-label tooltip-model))
        (update-html-text! (:tooltip-value runtime) (:metric-value tooltip-model))
        (reset-class-name! (:tooltip-value runtime)
                           (into ["num"
                                  "text-[16px]"
                                  "font-semibold"
                                  "leading-[1]"
                                  "tracking-tight"]
                                 (:value-classes tooltip-model)))
        (update-tooltip-rows! runtime (:benchmark-values tooltip-model))
        (set-style-map! (:tooltip-root runtime)
                        {:left (str left-px "px")
                         :top (str top-px "px")
                         :transform (if right-side?
                                      "translate(calc(-100% - 8px), -50%)"
                                      "translate(8px, -50%)")})
        (set-attrs! (:hover-line runtime)
                    {:x1 (* width (:x-ratio hovered-point))
                     :x2 (* width (:x-ratio hovered-point))
                     :y1 0
                     :y2 height})
        (set-node-display! (:hover-line runtime) true)
        (set-node-display! (:tooltip-root runtime) true))
      (hide-hover! runtime))))

(defn- handle-pointer-move!
  [runtime event]
  (let [{:keys [left width]} (measure-host (:host runtime))
        next-index (model/hover-index (.-clientX event)
                                      left
                                      width
                                      (count (or (:points (current-spec runtime)) [])))]
    (if (number? next-index)
      (show-hover! runtime next-index)
      (hide-hover! runtime))))

(defn- create-runtime
  [node spec]
  (let [doc (or (.-ownerDocument node) js/document)
        theme (:theme spec)
        svg (create-svg-node doc
                             "svg"
                             {:data-role (str (:data-role-prefix theme) "-svg")})
        baseline (create-svg-node doc
                                  "line"
                                  {:data-role (str (:data-role-prefix theme) "-baseline")})
        series-layer (create-svg-node doc "g" {:data-role (str (:data-role-prefix theme) "-series-layer")})
        hover-line (create-svg-node doc
                                    "line"
                                    {:data-role (str (:data-role-prefix theme) "-hover-line")})
        tooltip-root (create-html-node doc
                                       "div"
                                       {:data-role (str (:data-role-prefix theme) "-hover-tooltip")
                                        :class ["absolute"
                                                "pointer-events-none"
                                                "min-w-[188px]"
                                                "rounded-xl"
                                                "border"
                                                "px-3"
                                                "py-2"
                                                "spectate-lg"
                                                "z-20"]}
                                       {:white-space "nowrap"
                                        :border-color (:tooltip-border-color theme)
                                        :background (:tooltip-background theme)})
        tooltip-timestamp (create-html-node doc
                                            "div"
                                            {:class ["num"
                                                     "text-[12px]"
                                                     "font-medium"
                                                     "leading-4"
                                                     "text-[#8ea1b3]"]}
                                            nil)
        tooltip-metric-row (create-html-node doc
                                             "div"
                                             {:class ["mt-2"
                                                      "grid"
                                                      "grid-cols-[1fr_auto]"
                                                      "items-center"
                                                      "gap-3"]}
                                             nil)
        tooltip-label (create-html-node doc
                                        "span"
                                        {:class ["text-[12px]"
                                                 "font-medium"
                                                 "leading-4"
                                                 "text-[#909fac]"]}
                                        nil)
        tooltip-value (create-html-node doc
                                        "span"
                                        {:class ["num"
                                                 "text-[16px]"
                                                 "font-semibold"
                                                 "leading-[1]"
                                                 "tracking-tight"]}
                                        nil)
        tooltip-benchmark-rows (create-html-node doc
                                                 "div"
                                                 {:class ["mt-1.5" "space-y-1"]}
                                                 nil)
        runtime {:doc doc
                 :host node
                 :spec* (atom spec)
                 :size* (atom {:width 1
                               :height 1})
                 :clip-prefix (str (:data-role-prefix theme) "-" (random-uuid))
                 :svg svg
                 :baseline baseline
                 :series-layer series-layer
                 :hover-line hover-line
                 :tooltip-root tooltip-root
                 :tooltip-timestamp tooltip-timestamp
                 :tooltip-label tooltip-label
                 :tooltip-value tooltip-value
                 :tooltip-benchmark-rows tooltip-benchmark-rows
                 :hover-index* (atom nil)}]
    (set! (.-position (.-style node)) "relative")
    (set! (.-width (.-style node)) "100%")
    (set! (.-height (.-style node)) "100%")
    (set-attrs! svg {:width "100%" :height "100%"})
    (set-style-map! svg {:display "block"
                         :overflow "visible"})
    (set-attrs! hover-line {:stroke (:hover-line-stroke theme)
                            :stroke-width 1})
    (.appendChild tooltip-metric-row tooltip-label)
    (.appendChild tooltip-metric-row tooltip-value)
    (.appendChild tooltip-root tooltip-timestamp)
    (.appendChild tooltip-root tooltip-metric-row)
    (.appendChild tooltip-root tooltip-benchmark-rows)
    (.appendChild svg baseline)
    (.appendChild svg series-layer)
    (.appendChild svg hover-line)
    (.appendChild node svg)
    (.appendChild node tooltip-root)
    (set-node-display! tooltip-root false)
    (set-node-display! hover-line false)
    runtime))

(defn- render-runtime!
  [runtime]
  (let [{:keys [width height]} (measure-host (:host runtime))
        theme (:theme (current-spec runtime))]
    (reset! (:size* runtime) {:width width
                              :height height})
    (set-attrs! (:svg runtime) {:viewBox (str "0 0 " width " " height)})
    (set-attrs! (:baseline runtime) {:x1 0
                                     :x2 width
                                     :y1 height
                                     :y2 height
                                     :stroke (:baseline-stroke theme)
                                     :stroke-width (:baseline-stroke-width theme)
                                     :vector-effect "non-scaling-stroke"})
    (ensure-series-roots! runtime)
    (doseq [series (or (:series (current-spec runtime)) [])]
      (update-series-root! runtime series))
    (if-let [hover-index @(:hover-index* runtime)]
      (show-hover! runtime hover-index)
      (hide-hover! runtime))))

(defn- update-runtime!
  [runtime spec]
  (reset! (:spec* runtime) spec)
  (render-runtime! runtime))

(defn- attach-listeners!
  [runtime]
  (let [host (:host runtime)
        on-pointer-move (fn [event]
                          (handle-pointer-move! runtime event))
        on-pointer-leave (fn [_event]
                           (hide-hover! runtime))
        resize-observer (when-let [ctor (.-ResizeObserver js/globalThis)]
                          (new ctor (fn [_entries _observer]
                                      (render-runtime! runtime))))]
    (.addEventListener host "pointermove" on-pointer-move)
    (.addEventListener host "pointerleave" on-pointer-leave)
    (.addEventListener host "mouseleave" on-pointer-leave)
    (when resize-observer
      (.observe resize-observer host))
    {:pointermove on-pointer-move
     :pointerleave on-pointer-leave
     :mouseleave on-pointer-leave
     :resize-observer resize-observer}))

(defn- cleanup-runtime!
  [runtime]
  (when runtime
    (when-let [listeners (:listeners runtime)]
      (when-let [host (:host runtime)]
        (when-let [pointermove (:pointermove listeners)]
          (.removeEventListener host "pointermove" pointermove))
        (when-let [pointerleave (:pointerleave listeners)]
          (.removeEventListener host "pointerleave" pointerleave))
        (when-let [mouseleave (:mouseleave listeners)]
          (.removeEventListener host "mouseleave" mouseleave)))
      (when-let [resize-observer (:resize-observer listeners)]
        (.disconnect resize-observer)))
    (clear-node! (:host runtime))))

(defn on-render
  [spec]
  (fn [{:keys [:replicant/life-cycle :replicant/node :replicant/memory :replicant/remember]}]
    (case life-cycle
      :replicant.life-cycle/mount
      (let [runtime (create-runtime node spec)
            listeners (attach-listeners! runtime)
            runtime* (assoc runtime :listeners listeners)]
        (render-runtime! runtime*)
        (remember {:runtime runtime*}))

      :replicant.life-cycle/update
      (if-let [runtime (:runtime memory)]
        (update-runtime! runtime spec)
        (let [runtime (create-runtime node spec)
              listeners (attach-listeners! runtime)
              runtime* (assoc runtime :listeners listeners)]
          (render-runtime! runtime*)
          (remember {:runtime runtime*})))

      :replicant.life-cycle/unmount
      (cleanup-runtime! (:runtime memory))

      nil)))
