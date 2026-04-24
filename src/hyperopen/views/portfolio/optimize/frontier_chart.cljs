(ns hyperopen.views.portfolio.optimize.frontier-chart)

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

(defn- frontier-point-button
  [draft idx point]
  (let [target (objective-target draft point)]
    [:button {:type "button"
              :class ["grid"
                      "grid-cols-[minmax(4rem,0.8fr)_repeat(3,minmax(5rem,1fr))]"
                      "gap-3"
                      "rounded-lg"
                      "border"
                      "border-base-300"
                      "bg-base-200/40"
                      "p-3"
                      "text-left"
                      "text-xs"
                      "tabular-nums"
                      "transition-colors"
                      "hover:border-primary/60"
                      "hover:bg-primary/10"]
              :data-role (str "portfolio-optimizer-frontier-point-" idx)
              :on {:click (point-actions target)}}
     [:span {:class ["font-semibold" "text-trading-text"]}
      (str "#" idx)]
     [:span (format-pct (:expected-return point))]
     [:span (format-pct (:volatility point))]
     [:span (format-decimal (:sharpe point))]]))

(defn frontier-chart
  [draft result]
  (let [points (vec (:frontier result))
        target (objective-target draft (first points))]
    (when (seq points)
      [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
                 :data-role "portfolio-optimizer-frontier-panel"}
       [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
        [:div
         [:p {:class ["text-[0.65rem]"
                      "font-semibold"
                      "uppercase"
                      "tracking-[0.24em]"
                      "text-trading-muted"]}
          "Efficient Frontier"]
         [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
          (str "Click a point to set " (:label target) " and rerun.")]]
        [:p {:class ["rounded-full"
                     "border"
                     "border-primary/40"
                     "bg-primary/10"
                     "px-3"
                     "py-1"
                     "text-xs"
                     "font-semibold"
                     "text-primary"]}
         (str (count points) " points")]]
       [:div {:class ["mt-4" "space-y-2"]}
        [:div {:class ["grid"
                       "grid-cols-[minmax(4rem,0.8fr)_repeat(3,minmax(5rem,1fr))]"
                       "gap-3"
                       "rounded-lg"
                       "border"
                       "border-base-300"
                       "bg-base-200/40"
                       "p-3"
                       "text-xs"
                       "font-semibold"
                       "uppercase"
                       "tracking-[0.14em]"
                       "text-trading-muted"]}
         [:span "Point"]
         [:span "Return"]
         [:span "Volatility"]
         [:span "Sharpe"]]
        (map-indexed (fn [idx point]
                       (frontier-point-button draft idx point))
                     points)]])))
