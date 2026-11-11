(ns hyperopen.views.trading-chart.answers-vm
  "View models for the outcome chart's \"Chart shows\" control and its All
   answers chart. Reads the chart slice only: the quote-stable active market
   (names, coins, never-traded evidence; no bid/ask), the stored plan, and the
   candle slots of the planned coins — so a quote batch never re-renders it."
  (:require [hyperopen.chart.outcome-answers :as outcome-answers]
            [hyperopen.outcomes.domain.answer-chart :as answer-chart]
            [hyperopen.outcomes.domain.price-unit :as price-unit]))

(defn- memoize-last
  [f]
  (let [cache (atom nil)]
    (fn [& args]
      (let [cached @cache]
        (if (and (map? cached) (= args (:args cached)))
          (:result cached)
          (let [result (apply f args)]
            (reset! cache {:args args :result result})
            result))))))

(defn chart-mode-control
  "The \"Chart shows\" control's model, or nil when the active market is not
   a multi-answer event: {:mode :single|:all :selected-label}."
  [state]
  (let [market (:active-market state)]
    (when (and (price-unit/outcome-market? (:active-asset state) market)
               (answer-chart/qualifies? market))
      {:mode (if (outcome-answers/all-answers? state) :all :single)
       :selected-label (or (:selected-outcome-option-label market) "Selected")})))

(defn all-answers?
  [state]
  (outcome-answers/all-answers? state))

(def ^:private memoized-legend
  (memoize-last
   (fn [market coins entries timeframe selected-outcome-id]
     (answer-chart/legend-model {:market market
                                 :coins coins
                                 :candles (zipmap coins (map (fn [entry] {timeframe entry}) entries))
                                 :timeframe timeframe
                                 :selected-outcome-id selected-outcome-id}))))

(defn answers-view-model
  "Everything the All answers chart draws. `:request-key` vs `:plan-key`
   tells the chart to ask for a new plan (event or timeframe changed)."
  [state dispatch-fn]
  (let [market (:active-market state)
        timeframe (get-in state [:chart-options :selected-timeframe] :1d)
        stored (get-in state [:chart-options :outcome-answers])
        request-key (answer-chart/plan-key market timeframe)
        current? (= request-key (:key stored))
        ;; Until the plan for this event exists, draw nothing (a stale plan
        ;; may name another event's coins).
        coins (if (or current? (= (first (:key stored)) (first request-key)))
                (:coins stored)
                [])
        entries (mapv #(get-in state [:candles % timeframe]) coins)
        selected-id (or (:outcome-option-id market) (:selected-outcome-id market))
        {:keys [lines notes]} (memoized-legend market coins entries timeframe selected-id)]
    {:event-key (answer-chart/event-key market)
     :title (or (:title market) (:symbol market))
     :timeframe timeframe
     :request-key request-key
     :plan-key (:key stored)
     :lines lines
     :notes notes
     :loading? (or (not current?) (some #(= :loading (:status %)) lines))
     :dispatch-fn dispatch-fn}))
