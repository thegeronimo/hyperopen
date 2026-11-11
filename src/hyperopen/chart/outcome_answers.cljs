(ns hyperopen.chart.outcome-answers
  "Actions behind the chart's \"Chart shows\" control on a multi-answer
   outcome event: the selected answer's candles, or every answer as a line.

   The mode is session state (`[:chart-options :outcome-chart-mode]`, never
   persisted). The plan — which answers' Yes coins are drawn for which event
   and timeframe — is stored beside it, so selecting another answer only
   moves the emphasis and never refetches."
  (:require [hyperopen.outcomes.domain.answer-chart :as answer-chart]
            [hyperopen.outcomes.domain.price-unit :as price-unit]
            [hyperopen.platform :as platform]))

(def ^:private mode-path [:chart-options :outcome-chart-mode])

(def ^:private plan-path [:chart-options :outcome-answers])

(defn- active-question
  "The active market when it is a qualifying event of the active coin."
  [state]
  (let [market (:active-market state)]
    (when (and (price-unit/outcome-market? (:active-asset state) market)
               (answer-chart/qualifies? market))
      market)))

(defn all-answers?
  "True when the chart draws every answer: the mode is on and the active
   market qualifies."
  [state]
  (boolean (and (= :all (get-in state mode-path))
                (active-question state))))

(defn- timeframe
  [state]
  (get-in state [:chart-options :selected-timeframe] :1d))

(defn- fetch-effects
  [requests interval]
  (mapv (fn [{:keys [coin bars]}]
          [:effects/fetch-candle-snapshot
           :coin coin
           :interval interval
           :bars bars
           :priority :low])
        requests))

(defn- fetchable
  "Planned coins this chart loads itself. The selected answer's coin is the
   active asset: the candle chart path already fetches it on asset and
   timeframe selection and its websocket candle stream keeps it live, so
   fetching it here would only duplicate that request."
  [state coins]
  (remove #{(:active-asset state)} coins))

(defn- stamped
  "`requests` plus the active coin when it is planned: the candle chart path
   keeps that slot current, so it counts as freshly requested too."
  [state coins requests]
  (let [active (:active-asset state)]
    (cond-> (vec requests)
      (some #{active} coins) (conj {:coin active}))))

(defn- plan-effects
  "Projection of the plan (and `extra-path-values`), then one candle fetch per
   planned coin that needs one (the info client runs at most four at a time).
   `requested-at` carries across plans so a fresh slot is not fetched again."
  [state market extra-path-values now-ms]
  (let [interval (timeframe state)
        stored (get-in state plan-path)
        plan (answer-chart/plan market interval)
        requests (answer-chart/fetch-requests (fetchable state (:coins plan)) (:candles state) interval
                                              {:requested-at (:requested-at stored)
                                               :now-ms now-ms})
        plan* (assoc plan :requested-at (answer-chart/note-requests (:requested-at stored)
                                                                    (stamped state (:coins plan) requests)
                                                                    interval now-ms))]
    (into [[:effects/save-many (conj (vec extra-path-values) [plan-path plan*])]]
          (fetch-effects requests interval))))

(defn set-outcome-chart-mode*
  [state mode now-ms]
  (let [mode* (cond (keyword? mode) mode
                    (string? mode) (keyword mode))
        market (active-question state)]
    (cond
      (= :single mode*)
      (if (= :all (get-in state mode-path))
        [[:effects/save mode-path :single]]
        [])

      (and (= :all mode*) market (not (all-answers? state)))
      (plan-effects state market [[mode-path :all]] now-ms)

      :else
      [])))

(defn set-outcome-chart-mode
  "`:all` draws every answer (only on a qualifying event), `:single` the
   selected answer's candles. Turning All answers on plans and loads the
   lines in the same action."
  [state mode]
  (set-outcome-chart-mode* state mode (platform/now-ms)))

(defn sync-outcome-answer-chart*
  [state {:keys [refresh?]} now-ms]
  (let [market (active-question state)
        interval (timeframe state)
        stored (get-in state plan-path)
        current? (= (:key stored) (answer-chart/plan-key market interval))]
    (cond
      (not (all-answers? state))
      []

      (and refresh? current?)
      (let [requests (answer-chart/fetch-requests (fetchable state (:coins stored)) (:candles state) interval
                                                  {:refresh? true
                                                   :requested-at (:requested-at stored)
                                                   :now-ms now-ms})]
        (if (seq requests)
          (into [[:effects/save plan-path
                  (update stored :requested-at answer-chart/note-requests requests interval now-ms)]]
                (fetch-effects requests interval))
          []))

      (not current?)
      (plan-effects state market [] now-ms)

      :else
      [])))

(defn sync-outcome-answer-chart
  "Keep the All answers lines loaded. Without options: plan again when the
   event, its drawable answers or the timeframe changed since the stored
   plan, loading what is missing or stale. `{:refresh? true}`: the view's
   slow timer while the tab is visible — refresh drawn lines, retry failed
   ones once. No-op unless the mode is on."
  ([state] (sync-outcome-answer-chart state nil))
  ([state opts] (sync-outcome-answer-chart* state opts (platform/now-ms))))

(defn reset-path-value
  "The save-many entry `select-asset` adds when it opens a market the All
   answers chart cannot draw while the mode is on; nil otherwise."
  [state next-market]
  (when (and (= :all (get-in state mode-path))
             (not (answer-chart/qualifies? next-market)))
    [mode-path :single]))
