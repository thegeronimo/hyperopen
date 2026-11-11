(ns hyperopen.outcomes.domain.answer-chart
  "\"All answers\" chart: every named answer of one outcome question drawn as
   a line of its Yes price on one 0–100% scale.

   Answers of one question are mutually exclusive, so their chances compare on
   one axis. Ladder rungs are not (each rung is its own question) and plain
   two-sided markets have nothing to compare, so neither qualifies.

   Pure: no state, no storage. Prices stay in dollars (0..1); labels read
   them as percent."
  (:require [clojure.string :as str]
            [hyperopen.outcomes.domain.quotes :as quotes]))

(def max-lines
  "Lines drawn at most; more answers are named in the legend as not drawn."
  8)

(def palette
  "Line colour tokens, in plan order (`--ho-chart-answer-N`, resolved by the
   chart's theme bridge; default values there differ in lightness as well as
   hue)."
  (mapv #(str "--ho-chart-answer-" %) (range 1 (inc max-lines))))

(def selected-line-width 3)

(def line-width 2)

(def refresh-bars
  "Bars a background refresh asks for: enough to replace the last candles."
  5)

(def full-bars
  "Bars a first load asks for (the chart's own default)."
  330)

(def refresh-interval-ms
  "The drawn lines' background refresh, and how long a fetch stays fresh: a
   re-plan (timeframe flip, event switch) skips coins fetched more recently."
  60000)

(defn- non-blank
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn qualifies?
  "True for an outcome question with two or more named answers. Ladders,
   single contracts and two-sided games never qualify."
  [market]
  (boolean (and (map? market)
                (= :outcome (:market-type market))
                (= :question (:outcome-kind market))
                (not= :ladder (:outcome-shape market))
                (>= (count (:question-options market)) 2))))

(defn event-key
  [market]
  (:key market))

(defn- yes-side
  "The answer's Yes side: side index 0, or the side holding the option's
   `:yes-coin`. Nil when neither identifies it — such an answer is not drawn
   (guessing could plot the No side as the answer's chance)."
  [option]
  (let [yes-coin (:yes-coin option)]
    (or (some #(when (= 0 (:side-index %)) %) (:sides option))
        (when (string? yes-coin)
          (some #(when (= yes-coin (:coin %)) %) (:sides option))))))

(defn answers
  "Named answers in feed order: [{:outcome-id :label :coin :never-traded?}]."
  [market]
  (->> (:question-options market)
       (keep (fn [option]
               (let [side (yes-side option)
                     coin (:coin side)]
                 (when (string? coin)
                   {:outcome-id (:outcome-id option)
                    :label (or (non-blank (:label option)) coin)
                    :coin coin
                    :never-traded? (quotes/never-traded? side)
                    :side side}))))
       vec))

(defn drawable
  "Answers that can be drawn: named and traded at least once."
  [market]
  (vec (remove :never-traded? (answers market))))

(defn short-label
  "The label printed on a line's price-axis tag: up to 8 characters."
  [label]
  (let [text (or (non-blank label) "")]
    (if (> (count text) 8)
      (str (subs text 0 7) "…")
      text)))

(defn- default-rank
  "Current chance of an answer's Yes side under the one chance rule; -1 when
   it has no rankable price."
  [{:keys [side]}]
  (let [quote (quotes/side-quote side)]
    (if (:rankable? quote) (:chance quote) -1)))

(defn- volume
  [{:keys [side]}]
  (let [v (js/parseFloat (or (:volume24h side) 0))]
    (if (js/isFinite v) v 0)))

(defn plan-key
  "What a plan is for: the event, the timeframe and the set of drawable coins
   (so an answer that is listed, delisted or trades for the first time
   re-plans; quotes never change it)."
  [market timeframe]
  [(event-key market) timeframe (vec (sort (map :coin (drawable market))))])

(defn plan
  "Which answers to draw for `market` at `timeframe`: up to `max-lines`
   drawable answers, likeliest first (`rank-fn`, default the one chance rule),
   volume breaking ties. {:key (plan-key …) :coins [...]}."
  ([market timeframe] (plan market timeframe nil))
  ([market timeframe rank-fn]
   (let [rank (or rank-fn default-rank)
         ranked (sort-by (fn [answer] [(- (rank answer)) (- (volume answer))])
                         (drawable market))]
     {:key (plan-key market timeframe)
      :coins (mapv :coin (take max-lines ranked))})))

;; ---------------------------------------------------------------------------
;; Candle slots

(defn candle-rows
  "Rows of a `[:candles coin interval]` slot: a bare vector or a map holding
   `:rows`/`:data`."
  [entry]
  (cond
    (vector? entry) entry
    (sequential? entry) (vec entry)
    (map? entry) (let [rows (or (:rows entry) (:data entry))]
                   (if (sequential? rows) (vec rows) []))
    :else []))

(defn candle-status
  "`:loading` (no slot yet), `:failed` (the last fetch failed and nothing is
   cached), `:empty` (fetched, no candles) or `:ready`."
  [entry]
  (let [rows (candle-rows entry)]
    (cond
      (nil? entry) :loading
      (seq rows) :ready
      (and (map? entry) (:error entry)) :failed
      :else :empty)))

(defn fetch-requests
  "Candle fetches for the planned `coins` at `timeframe`. Inputs: the cached
   `candles` map ({coin {interval slot}}), `requested-at` ({coin {interval
   ms}}, when this chart last asked for each slot) and `now-ms`.

   A re-plan (`refresh?` false): a full load for a coin with nothing usable
   cached, a 5-bar tail refresh for a cached one — each skipped when the slot
   was requested less than `refresh-interval-ms` ago (fresh, or in flight).
   The periodic refresh (`refresh?` true): a tail refresh for every cached
   line and one full retry for each failed line, skipping anything requested
   in the last half interval. [{:coin :bars}]"
  [coins candles timeframe {:keys [refresh? requested-at now-ms]}]
  (let [window (if refresh? (/ refresh-interval-ms 2) refresh-interval-ms)
        recent? (fn [coin]
                  (when-let [at (get-in requested-at [coin timeframe])]
                    (and (number? now-ms) (< (- now-ms at) window))))]
    (vec (keep (fn [coin]
                 (let [status (candle-status (get-in candles [coin timeframe]))]
                   (cond
                     (recent? coin) nil
                     (contains? #{:ready :empty} status) {:coin coin :bars refresh-bars}
                     (and refresh? (= :loading status)) nil
                     :else {:coin coin :bars full-bars})))
               coins))))

(defn note-requests
  "`requested-at` with `requests` stamped at `now-ms` for `timeframe`."
  [requested-at requests timeframe now-ms]
  (reduce (fn [acc {:keys [coin]}] (assoc-in acc [coin timeframe] now-ms))
          (or requested-at {})
          requests))

;; ---------------------------------------------------------------------------
;; Labels on the 0–100% scale

(defn- finite
  [value]
  (let [n (cond (number? value) value
                (string? value) (js/parseFloat value)
                :else js/NaN)]
    (when (js/isFinite n) n)))

(defn format-chance
  "A Yes price as a chance: 0.5 -> \"50%\", 0.503 -> \"50.3%\" (one decimal
   at most, none when whole)."
  [price]
  (when-let [p (finite price)]
    (let [text (.toFixed (* 100 p) 1)
          text (if (re-matches #"-0(\.0)?" text) "0.0" text)]
      (str (str/replace text #"\.0$" "") "%"))))

(defn tick-labels
  "Axis ticks on the 0–100% scale: whole percents (0.25 -> \"25%\"), one
   decimal only when a tick is not whole."
  [prices]
  (mapv (fn [price] (or (format-chance price) (str price))) prices))

(defn- last-close
  [rows]
  (when-let [row (peek rows)]
    (finite (or (:c row) (:close row) (get row "c")))))

;; ---------------------------------------------------------------------------
;; Legend

(defn- not-drawn-note
  [labels reason]
  (when (seq labels)
    (str (str/join ", " labels) " · " reason)))

(defn legend-model
  "What the legend says. Inputs: the market, the stored plan's `coins`, the
   `candles` map, `timeframe`, the selected answer's outcome id and
   `color-fn` (palette token -> CSS colour; identity by default).
   {:lines [{:outcome-id :label :short-label :coin :color :width :selected?
             :status :last-price :percent-text}] (likeliest last close first)
    :notes [string]}"
  [{:keys [market coins candles timeframe selected-outcome-id color-fn]}]
  (let [by-coin (into {} (map (juxt :coin identity)) (answers market))
        planned (keep-indexed (fn [index coin]
                                (when-let [answer (get by-coin coin)]
                                  (assoc answer :color ((or color-fn identity)
                                                        (get palette (mod index (count palette)))))))
                              coins)
        lines (->> planned
                   (map (fn [{:keys [outcome-id label coin color]}]
                          (let [entry (get-in candles [coin timeframe])
                                rows (candle-rows entry)
                                last-price (last-close rows)
                                selected? (= outcome-id selected-outcome-id)]
                            {:outcome-id outcome-id
                             :label label
                             :short-label (short-label label)
                             :coin coin
                             :color color
                             :width (if selected? selected-line-width line-width)
                             :selected? selected?
                             :status (candle-status entry)
                             :rows rows
                             :last-price last-price
                             :percent-text (format-chance last-price)})))
                   (sort-by (fn [line] [(- (or (:last-price line) -1))]))
                   vec)
        drawable-count (count (drawable market))
        more (max 0 (- drawable-count (count lines)))
        never (->> (answers market) (filter :never-traded?) (map :label))
        fallback (:fallback-option market)
        fallback-traded? (and fallback
                              (not (quotes/never-traded? (yes-side fallback))))
        not-traded (cond-> (vec never)
                     (and fallback (not fallback-traded?)) (conj (or (non-blank (:label fallback)) "Other")))
        failed (->> lines (filter #(= :failed (:status %))) (map :label))
        empty (->> lines (filter #(= :empty (:status %))) (map :label))]
    {:lines lines
     :notes (vec (keep identity
                       [(when (pos? more) (str "+" more " more not drawn"))
                        (not-drawn-note failed "failed to load, not drawn")
                        (not-drawn-note empty "no trades in this range")
                        (not-drawn-note not-traded "not traded, not drawn")
                        (when fallback-traded?
                          (not-drawn-note [(or (non-blank (:label fallback)) "Other")] "not drawn"))]))}))
