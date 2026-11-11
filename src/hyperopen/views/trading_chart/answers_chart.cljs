(ns hyperopen.views.trading-chart.answers-chart
  "The All answers chart: legend row, one-line caption and the line canvas.
   Keyed on the event, so selecting another answer of the same event only
   re-weights the lines."
  (:require [clojure.string :as str]
            [hyperopen.views.trading-chart.utils.chart-interop.answer-lines :as answer-lines]))

(defn- legend-entry
  [{:keys [label color percent-text selected? status coin]}]
  [:span {:class (cond-> ["inline-flex" "items-center" "gap-1.5" "whitespace-nowrap"]
                   selected? (conj "text-gray-100")
                   (not selected?) (conj "text-gray-300"))
          :replicant/key coin
          :data-role "outcome-answers-legend-entry"
          :data-coin coin
          :data-selected (str (boolean selected?))}
   [:span {:class ["inline-block" "w-3.5" "rounded-sm" (if selected? "h-1" "h-0.5")]
           ;; `color` is a --ho-chart-answer-N token: the swatch follows a
           ;; theme switch without a render
           :style {:background-color (str "var(" color ")")}
           :aria-hidden "true"}]
   [:span label]
   (when selected?
     [:span {:class ["sr-only"] :data-role "outcome-answers-legend-selected"} "(selected)"])
   [:strong {:class ["font-semibold" "num"]
             :data-role "outcome-answers-legend-percent"}
    (cond
      percent-text percent-text
      (= :loading status) "…"
      :else "—")]])

(defn- accessible-summary
  [title lines]
  (str (or title "Event") ", chance over time for each answer: "
       (str/join ", " (map (fn [{:keys [label percent-text]}]
                             (str label " " (or percent-text "no price")))
                           lines))))

(defn answers-chart
  [{:keys [event-key title lines notes loading?] :as vm}]
  [:div {:class ["w-full" "min-w-0" "flex" "flex-col" "flex-1" "min-h-0" "bg-base-100"]
         :data-role "outcome-answers-chart"
         :replicant/key (str "outcome-answers-" event-key)}
   [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-4" "gap-y-1" "px-4" "pt-2" "text-sm"]
          :data-role "outcome-answers-legend"}
    (for [line lines]
      (legend-entry line))
    (for [note notes]
      [:span {:replicant/key note
              :class ["text-xs" "text-gray-400" "whitespace-nowrap"]
              :data-role "outcome-answers-legend-note"}
       note])]
   [:div {:class ["px-4" "pt-0.5" "pb-1" "text-xs" "text-gray-400"]
          :data-role "outcome-answers-caption"}
    (if loading?
      "Lines are last traded prices. Loading answers…"
      "Lines are last traded prices.")]
   [:div {:class ["w-full" "min-w-0" "relative" "flex-1" "min-h-[320px]" "overflow-hidden" "bg-base-100"]
          :data-role "outcome-answers-canvas"
          :role "img"
          :aria-label (accessible-summary title lines)
          :replicant/on-render (answer-lines/on-render (select-keys vm [:lines :timeframe :request-key :plan-key :dispatch-fn]))}]])
