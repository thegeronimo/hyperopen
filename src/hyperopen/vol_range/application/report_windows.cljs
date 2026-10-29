(ns hyperopen.vol-range.application.report-windows
  "Report windows and per-campaign window figures for Vol Range reporting
  (HO-SPEC-014 v2.1 sections 12.3, 12.4, 12.6, 13.5 rows 1 to 3, 6 and 7).

  Windows are half-open `[a,b)` with `b = report-marks/window-end now`:

  - Since tracking began: `a = T0`, `P_a = P0` (the inception book
    midpoint), `q_a = q0`, `C_a = 0`; available once `b > T0`.
  - 7D and 30D: `a = b − 7d` or `b − 30d`, valued at hourly closes, and
    UNAVAILABLE (never clipped) while `a < T0`.

  `q_x`/`C_x` count the campaign's tracked events strictly before `x`; the
  window's activity counts `a <= time < b`. Figures appear only when the
  coverage of `[T0 − skew, b)` (computed by the caller with
  `report-coverage/campaign-coverage` to `b`) allows the headline and both
  boundary marks exist. Otherwise `:figures` is nil and `:status`/`:reason`
  say why, in this order: the window itself, coverage, then marks. Pure."
  (:require [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.report-equations :as equations]
            [hyperopen.vol-range.domain.report-ledger :as ledger]
            [hyperopen.vol-range.domain.report-marks :as marks]))

(def window-ids (:windows contracts/report-policy))
(def default-window :since-start)
(def ^:private window-days {:7d 7 :30d 30})
(def ^:private day-ms 86400000)

(defn normalize-window
  "A window id from a keyword or string, or nil."
  [x]
  (let [k (cond (keyword? x) x (string? x) (keyword x) :else nil)]
    (some #{k} window-ids)))

(defn selected-window
  "The report's selected window (`[:vol-range :report :ui :window]`), or the
  default."
  [ui]
  (or (normalize-window (:window ui)) default-window))

(defn resolve-window
  "{:window :a-ms :b-ms :a-source :status :reason} for `campaign` at
  `now-ms`. `:status` is :ok; :pending with `:window/no-completed-mark`
  (Since tracking began before the first completed hour); or :unavailable
  with `:window/before-tracking` (a fixed window starting before T0)."
  [{:keys [window campaign now-ms]}]
  (let [window (or (normalize-window window) default-window)
        t0 (:t0-ms campaign)
        b (marks/window-end now-ms)]
    (if (= :since-start window)
      (cond-> {:window window :a-ms t0 :b-ms b :a-source :inception-midpoint :status :ok :reason nil}
        (not (and (number? t0) (> b t0))) (assoc :status :pending :reason :window/no-completed-mark))
      (let [a (- b (* day-ms (get window-days window)))]
        (cond-> {:window window :a-ms a :b-ms b :a-source :hourly-close :status :ok :reason nil}
          (not (and (number? t0) (>= a t0))) (assoc :status :unavailable :reason :window/before-tracking))))))

(defn- a-mark [campaign {:keys [a-ms a-source]} coin-marks]
  (if (= :inception-midpoint a-source)
    (let [p0 (get-in campaign [:baseline :p0])]
      (if p0
        {:status :ok :price p0 :source :inception-midpoint
         :observed-at-ms (get-in campaign [:baseline :p0-observed-at-ms])}
        {:status :missing}))
    (marks/mark-at coin-marks a-ms)))

(def ^:private coverage-reasons
  {:unavailable :unavailable :attribution-break :suppressed :gap :suppressed :partial :pending})

(defn- status-of
  "[status reason] in precedence order: window, coverage, marks."
  [range coverage pa pb]
  (let [state (:state coverage)
        mark-problem (first (remove #(= :ok (:status %)) [pa pb]))]
    (cond
      (not= :ok (:status range)) [(:status range) (:reason range)]
      (nil? coverage) [:pending :coverage/backfill-pending]
      (not= :complete state) [(get coverage-reasons state :pending) (:reason coverage)]
      (not (:headline-allowed? coverage)) [:pending :coverage/backfill-pending]
      (nil? mark-problem) [:ok nil]
      :else (case (:status mark-problem)
              :missing [:unavailable :marks/missing-boundary-close]
              :error [:unavailable :marks/read-error]
              [:pending :marks/pending]))))

(defn campaign-window-figures
  "The window figures of `campaign` for `range` (from `resolve-window`, or
  any {:window :a-ms :b-ms :a-source :status}): {:campaign-id :window :a-ms
  :b-ms :a-mark :b-mark :q-a :c-a :q-b :c-b :coverage :figures :activity
  :status :reason}. `events` are the campaign's tracked events
  (`report-attribution/campaign-findings`), `coverage` its
  `campaign-coverage` to `b` (nil when not computed), `coin-marks` the
  stored marks of its coin."
  [{:keys [campaign range events coverage coin-marks]}]
  (let [{:keys [a-ms b-ms]} range
        ok-range? (= :ok (:status range))
        pa (when ok-range? (a-mark campaign range coin-marks))
        pb (when ok-range? (marks/mark-at coin-marks b-ms))
        sa (when ok-range? (ledger/state-before campaign events a-ms))
        sb (when ok-range? (ledger/state-before campaign events b-ms))
        [status reason] (status-of range coverage pa pb)
        figures (when (= :ok status)
                  (equations/window-figures {:q-a (:q sa) :c-a (:c sa) :p-a (:price pa)
                                             :q-b (:q sb) :c-b (:c sb) :p-b (:price pb)}))]
    {:campaign-id (:campaign-id campaign)
     :window (:window range)
     :a-ms a-ms
     :b-ms b-ms
     :a-mark pa
     :b-mark pb
     :q-a (:q sa) :c-a (:c sa) :q-b (:q sb) :c-b (:c sb)
     :coverage coverage
     :figures figures
     :activity (when ok-range? (ledger/activity (ledger/in-window events [a-ms b-ms])))
     :status (if (and (= :ok status) (nil? figures)) :unavailable status)
     :reason (if (and (= :ok status) (nil? figures)) :marks/missing-boundary-close reason)}))

;; ---------------------------------------------------------------------------
;; Marks the selected window needs
;; ---------------------------------------------------------------------------

(defn open-campaigns
  "The open campaigns of `campaigns` ({id campaign}) for `selected-key` (nil
  = every market), sorted by key."
  [campaigns closures selected-key]
  (->> (vals campaigns)
       (remove #(contains? closures (:campaign-id %)))
       (filter #(or (nil? selected-key) (= selected-key (:campaign-key %))))
       (sort-by :campaign-key)))

(defn mark-requests
  "[{:coin :from-ms :to-ms}] of candle opens the selected window still needs
  for the shown open campaigns: what their stored marks (`marks`, {coin
  entry}) have not proved, one request per coin spanning its missing
  parts. [] when nothing is missing."
  [{:keys [campaigns closures selected-key window marks now-ms]}]
  (->> (open-campaigns campaigns closures selected-key)
       (keep (fn [c]
               (let [coin (get-in c [:scope :coin])
                     r (resolve-window {:window window :campaign c :now-ms now-ms})]
                 (when (and (string? coin) (= :ok (:status r)))
                   [coin (marks/needed-opens (:a-ms r) (:b-ms r))]))))
       (group-by first)
       (keep (fn [[coin needs]]
               (let [missing (mapcat #(marks/missing-opens (get marks coin) (second %)) needs)]
                 (when (seq missing)
                   {:coin coin :from-ms (apply min (map first missing)) :to-ms (apply max (map second missing))}))))
       (sort-by :coin)
       vec))
