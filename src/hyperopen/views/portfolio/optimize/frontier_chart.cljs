(ns hyperopen.views.portfolio.optimize.frontier-chart
  (:require [clojure.string :as str]))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- format-pct
  [value]
  (if (finite-number? value)
    (str (.toLocaleString (* 100 value)
                          "en-US"
                          #js {:minimumFractionDigits 2
                               :maximumFractionDigits 2})
         "%")
    "N/A"))

(defn- format-decimal
  [value]
  (if (finite-number? value)
    (.toLocaleString value "en-US" #js {:maximumFractionDigits 3})
    "N/A"))

(defn- objective-target
  [draft point]
  (if (= :target-volatility (get-in draft [:objective :kind]))
    {:kind :target-volatility
     :parameter-key :target-volatility
     :parameter-value (:volatility point)
     :label "Target Volatility"}
    {:kind :target-return
     :parameter-key :target-return
     :parameter-value (:expected-return point)
     :label "Target Return"}))

(defn- point-actions
  [target]
  [[:actions/set-portfolio-optimizer-objective-kind (:kind target)]
   [:actions/set-portfolio-optimizer-objective-parameter
    (:parameter-key target)
    (:parameter-value target)]])

(def ^:private chart-width 640)
(def ^:private chart-height 280)
(def ^:private chart-pad 42)

(defn- numeric-values
  [points key]
  (keep (fn [point]
          (let [value (get point key)]
            (when (finite-number? value) value)))
        points))

(defn- domain
  [values fallback-min fallback-max]
  (let [values* (seq values)
        min* (if values* (apply min values*) fallback-min)
        max* (if values* (apply max values*) fallback-max)]
    (if (= min* max*)
      [(- min* 0.01) (+ max* 0.01)]
      (let [span (- max* min*)]
        [(- min* (* span 0.08))
         (+ max* (* span 0.08))]))))

(defn- scale-value
  [domain-min domain-max range-min range-max value]
  (if (and (finite-number? value)
           (not= domain-min domain-max))
    (+ range-min
       (* (/ (- value domain-min)
             (- domain-max domain-min))
          (- range-max range-min)))
    (/ (+ range-min range-max) 2)))

(defn- point-position
  [x-domain y-domain point]
  {:x (scale-value (first x-domain)
                   (second x-domain)
                   chart-pad
                   (- chart-width chart-pad)
                   (:volatility point))
   :y (scale-value (first y-domain)
                   (second y-domain)
                   (- chart-height chart-pad)
                   chart-pad
                   (:expected-return point))})

(defn- path-data
  [positions]
  (when (seq positions)
    (str/join " "
              (map-indexed
               (fn [idx {:keys [x y]}]
                 (str (if (zero? idx) "M" "L")
                      " "
                      (format-decimal x)
                      " "
                      (format-decimal y)))
               positions))))

(defn- grid-line
  [orientation idx ratio]
  (let [x (+ chart-pad (* ratio (- chart-width (* 2 chart-pad))))
        y (+ chart-pad (* ratio (- chart-height (* 2 chart-pad))))]
    (case orientation
      :vertical
      [:line {:key (str "v-" idx)
              :x1 x
              :x2 x
              :y1 chart-pad
              :y2 (- chart-height chart-pad)
              :stroke "currentColor"
              :strokeOpacity "0.08"}]
      [:line {:key (str "h-" idx)
              :x1 chart-pad
              :x2 (- chart-width chart-pad)
              :y1 y
              :y2 y
              :stroke "currentColor"
              :strokeOpacity "0.08"}])))

(defn- frontier-point
  [draft idx point x-domain y-domain]
  (let [target (objective-target draft point)
        {:keys [x y]} (point-position x-domain y-domain point)]
    [:g {:role "button"
         :tabIndex 0
         :class ["cursor-pointer" "text-primary" "outline-none"]
         :data-role (str "portfolio-optimizer-frontier-point-" idx)
         :data-frontier-drag-target "true"
         :data-return (format-pct (:expected-return point))
         :data-volatility (format-pct (:volatility point))
         :data-sharpe (format-decimal (:sharpe point))
         :draggable true
         :on {:click (point-actions target)
              :drag-start (point-actions target)
              :drag-enter (point-actions target)}}
     [:circle {:cx x
               :cy y
               :r 4
               :fill "currentColor"}]
     [:circle {:cx x
               :cy y
               :r 11
               :fill "transparent"
               :stroke "currentColor"
               :strokeOpacity "0.16"}]
     [:title
      (str "Return " (format-pct (:expected-return point))
           ", volatility " (format-pct (:volatility point))
           ", Sharpe " (format-decimal (:sharpe point)))]]))

(defn- target-marker
  [result x-domain y-domain]
  (let [point {:expected-return (:expected-return result)
               :volatility (:volatility result)}
        {:keys [x y]} (point-position x-domain y-domain point)]
    [:g {:class ["text-primary"]
         :data-role "portfolio-optimizer-frontier-target-marker"}
     [:circle {:cx x
               :cy y
               :r 6
               :fill "currentColor"
               :stroke "currentColor"
               :strokeWidth 2}]
     [:circle {:cx x
               :cy y
               :r 12
               :fill "transparent"
               :stroke "currentColor"
               :strokeOpacity "0.45"}]
     [:text {:x (+ x 10)
             :y (- y 10)
             :fill "currentColor"
             :fontSize 10
             :fontWeight 700}
      "Target"]]))

(defn- current-marker
  [result x-domain y-domain]
  (when (and (finite-number? (:current-expected-return result))
             (finite-number? (:current-volatility result)))
    (let [point {:expected-return (:current-expected-return result)
                 :volatility (:current-volatility result)}
          {:keys [x y]} (point-position x-domain y-domain point)]
      [:g {:class ["text-info"]
           :data-role "portfolio-optimizer-frontier-current-marker"}
       [:circle {:cx x
                 :cy y
                 :r 5
                 :fill "currentColor"
                 :stroke "currentColor"
                 :strokeWidth 2}]
       [:circle {:cx x
                 :cy y
                 :r 11
                 :fill "transparent"
                 :stroke "currentColor"
                 :strokeOpacity "0.45"}]
       [:text {:x (+ x 10)
               :y (+ y 16)
               :fill "currentColor"
               :fontSize 10
               :fontWeight 700}
        "Current"]])))

(defn frontier-chart
  [draft result]
  (let [points (->> (:frontier result)
                    (filter #(and (finite-number? (:volatility %))
                                  (finite-number? (:expected-return %))))
                    (sort-by :volatility)
                    vec)
        x-domain (domain (concat (numeric-values points :volatility)
                                 (when (finite-number? (:volatility result))
                                   [(:volatility result)])
                                 (when (finite-number? (:current-volatility result))
                                   [(:current-volatility result)]))
                         0
                         1)
        y-domain (domain (concat (numeric-values points :expected-return)
                                 (when (finite-number? (:expected-return result))
                                   [(:expected-return result)])
                                 (when (finite-number? (:current-expected-return result))
                                   [(:current-expected-return result)]))
                         0
                         1)
        positions (map #(point-position x-domain y-domain %) points)
        target (objective-target draft (first points))]
    (when (seq points)
      [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
                 :data-role "portfolio-optimizer-frontier-panel"}
       [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
        [:div
         [:p {:class ["font-mono"
                      "text-[0.62rem]"
                      "uppercase"
                      "tracking-[0.08em]"
                      "text-trading-muted/70"]}
          "Efficient Frontier"]
         [:p {:class ["mt-1" "text-xs" "text-trading-muted"]}
          "Risk vs return — annualized"]]
        [:p {:class ["font-mono" "text-[0.62rem]" "text-trading-muted/70"]}
         (str (count points) " points")]]
       [:div {:class ["relative" "mt-4" "border" "border-base-300" "bg-base-200/30" "p-4"]}
        [:div {:class ["absolute" "right-6" "top-6" "z-10" "border" "border-base-300" "bg-base-200/80" "px-3" "py-2" "text-[0.65rem]"]
               :data-role "portfolio-optimizer-frontier-legend"}
         [:div {:class ["flex" "items-center" "gap-2" "text-trading-muted"]}
          [:span {:class ["h-2" "w-2" "rounded-full" "bg-info"]}]
          "Where you are now"]
         [:div {:class ["mt-1" "flex" "items-center" "gap-2" "text-trading-text"]}
          [:span {:class ["h-2" "w-2" "rounded-full" "bg-primary"]}]
          "Recommended target"]]
        [:svg {:viewBox (str "0 0 " chart-width " " chart-height)
               :class ["h-[23rem]" "w-full" "overflow-visible" "text-trading-text"]
               :data-role "portfolio-optimizer-frontier-svg"}
         [:g {:data-role "portfolio-optimizer-frontier-grid"}
          (map-indexed (partial grid-line :vertical) [0 0.25 0.5 0.75 1])
          (map-indexed (partial grid-line :horizontal) [0 0.25 0.5 0.75 1])]
         [:line {:x1 chart-pad
                 :x2 (- chart-width chart-pad)
                 :y1 (- chart-height chart-pad)
                 :y2 (- chart-height chart-pad)
                 :stroke "currentColor"
                 :strokeOpacity "0.22"}]
         [:line {:x1 chart-pad
                 :x2 chart-pad
                 :y1 chart-pad
                 :y2 (- chart-height chart-pad)
                 :stroke "currentColor"
                 :strokeOpacity "0.22"}]
         [:path {:d (path-data positions)
                 :fill "none"
                 :stroke "currentColor"
                 :strokeWidth 2
                 :strokeLinecap "round"
                 :strokeLinejoin "round"
                 :class ["text-primary"]
                 :data-role "portfolio-optimizer-frontier-path"}]
         (map-indexed (fn [idx point]
                        (frontier-point draft idx point x-domain y-domain))
                      points)
         (current-marker result x-domain y-domain)
         (target-marker result x-domain y-domain)
         [:text {:x chart-pad
                 :y (- chart-height 8)
                 :fill "currentColor"
                 :fontSize 11
                 :opacity 0.65}
          (str "Vol " (format-pct (first x-domain)))]
         [:text {:x (- chart-width chart-pad)
                 :y (- chart-height 8)
                 :fill "currentColor"
                 :fontSize 11
                 :opacity 0.65
                 :textAnchor "end"}
          (str "Vol " (format-pct (second x-domain)))]
         [:text {:x 10
                 :y chart-pad
                 :fill "currentColor"
                 :fontSize 11
                 :opacity 0.65}
          (str "Ret " (format-pct (second y-domain)))]
         [:text {:x 10
                 :y (- chart-height chart-pad)
                 :fill "currentColor"
                 :fontSize 11
                 :opacity 0.65}
          (str "Ret " (format-pct (first y-domain)))]]]
       [:div {:class ["mt-4" "flex" "gap-3" "text-[0.7rem]" "text-trading-muted"]}
        [:span {:class ["font-mono" "text-[0.62rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
         "Reading this"]
        [:span "·"]
        [:span
         (str "Each point is a feasible portfolio. Click or drag a point to set "
              (:label target)
              " and rerun.")]]])))
