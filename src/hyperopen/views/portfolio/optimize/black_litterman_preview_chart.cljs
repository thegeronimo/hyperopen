(ns hyperopen.views.portfolio.optimize.black-litterman-preview-chart
  (:require [hyperopen.portfolio.optimizer.application.black-litterman-preview :as preview]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(def ^:private eyebrow-class
  ["font-mono" "text-[0.625rem]" "font-semibold" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"])

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- row-bar-width
  [value max-abs]
  (if (and (finite-number? value)
           (pos? max-abs))
    (str (max 2 (* 100 (/ (js/Math.abs value) max-abs))) "%")
    "2%"))

(defn- preview-row
  [max-abs row]
  (let [prior (:prior-return row)
        posterior (:posterior-return row)
        delta (when (and (finite-number? prior)
                         (finite-number? posterior))
                (- posterior prior))]
    [:div {:class ["grid" "grid-cols-[72px_minmax(0,1fr)_76px]" "items-center" "gap-3"
                   "border-b" "border-base-300" "py-2" "last:border-b-0"]}
     [:span {:class ["font-mono" "text-[0.6875rem]" "font-semibold"]}
      (:instrument-id row)]
     [:div {:class ["min-w-0" "space-y-1"]}
      [:div {:class ["h-1.5" "bg-base-300"]}
       [:div {:class ["h-full" "bg-info"]
              :style {:width (row-bar-width prior max-abs)}}]]
      [:div {:class ["h-1.5" "bg-base-300"]}
       [:div {:class ["h-full" "bg-warning"]
              :style {:width (row-bar-width posterior max-abs)}}]]]
     [:span {:class ["text-right" "font-mono" "text-[0.65625rem]" "text-trading-muted"]}
      (if (finite-number? delta)
        (opt-format/format-pct-delta delta {:suffix ""})
        "N/A")]]))

(defn black-litterman-preview-panel
  [readiness]
  (let [preview (preview/build-preview readiness)]
    [:section {:class ["border" "border-base-300" "bg-base-100/90" "p-4"]
               :data-role "portfolio-optimizer-black-litterman-preview-panel"}
     [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
      [:div
       [:p {:class eyebrow-class} "Expected return preview"]
       [:h3 {:class ["mt-2" "text-[0.875rem]" "font-medium"]}
        "Market reference vs combined output"]]
      [:div {:class ["flex" "items-center" "gap-3" "font-mono" "text-[0.625rem]"
                     "uppercase" "tracking-[0.08em]" "text-trading-muted"]}
       [:span {:class ["inline-flex" "items-center" "gap-1.5"]}
        [:span {:class ["h-2" "w-2" "bg-info"]}]
        "Prior"]
       [:span {:class ["inline-flex" "items-center" "gap-1.5"]}
        [:span {:class ["h-2" "w-2" "bg-warning"]}]
        "Posterior"]]]
     (case (:status preview)
       :ready
       (let [rows (:rows preview)
             max-abs (or (seq (map js/Math.abs
                                    (filter finite-number?
                                            (mapcat (juxt :prior-return :posterior-return)
                                                    rows))))
                         [1])
             max-abs* (apply max max-abs)]
         (into [:div {:class ["mt-4"]}]
               (map (partial preview-row max-abs*) rows)))

       :empty
       [:p {:class ["mt-4" "border" "border-base-300" "bg-base-200/30" "p-3"
                    "text-[0.6875rem]" "text-trading-muted"]}
        "No active views yet. Add a view to compare posterior expected returns against the market reference."]

       [:p {:class ["mt-4" "border" "border-base-300" "bg-base-200/30" "p-3"
                    "text-[0.6875rem]" "text-trading-muted"]}
        "Posterior preview will appear once the universe has eligible history. Views still save with the scenario."])]))
