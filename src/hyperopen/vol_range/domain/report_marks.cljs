(ns hyperopen.vol-range.domain.report-marks
  "Hourly marks for Vol Range reporting (HO-SPEC-014 v2.1 section 12.4).

  Reports end at the latest completed UTC hour after a settle:
  `b = floor((now − :mark-settle-ms) / 1h) × 1h`. The mark at a boundary `x`
  is the close of the COMPLETED 1h candle whose open time is `x − 1h`. The
  local clock alone never proves a candle completed, since it can run ahead
  of the venue's: a candle is kept only when the same read returned a
  candle of a LATER hour (the venue had started that hour), or, for an
  illiquid next hour without a candle, when the read was requested at least
  `:mark-clock-margin-ms` after the hour ended; and only with a positive
  plain decimal close. A missing close is never carried forward and never
  zero: the window's headline is unavailable instead.

  The stored marks of one coin are {:closes {open-ms close} :covered
  [[from to)] :fetched-at-ms :error}: `:covered` holds the candle OPEN
  times that complete reads proved (a missing close inside it is really
  missing, outside it is not fetched yet). Pure."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.report-intervals :as iv]))

(defn- policy [k] (get contracts/report-policy k))
(defn hour-ms [] (policy :hour-ms))

(defn hour-floor
  "The UTC hour boundary at or before `t`."
  [t]
  (* (hour-ms) (js/Math.floor (/ t (hour-ms)))))

(defn hour-ceil
  "The UTC hour boundary at or after `t`."
  [t]
  (* (hour-ms) (js/Math.ceil (/ t (hour-ms)))))

(defn window-end
  "The report end `b` for `now-ms`: the latest completed UTC hour after the
  mark settle."
  [now-ms]
  (hour-floor (- now-ms (policy :mark-settle-ms))))

(defn- close-text [x]
  (let [s (some-> x str str/trim)
        dec (precision/parse-decimal s)]
    (when (and dec (precision/pos-dec? dec)) (precision/decimal->string dec))))

(defn- open-ms [x]
  (let [n (cond (number? x) x
                (and (string? x) (re-matches #"\d+" x)) (js/parseInt x 10)
                :else nil)]
    (when (and (number? n) (js/Number.isSafeInteger n) (zero? (mod n (hour-ms)))) n)))

(defn- latest-open
  "The latest hour-aligned candle open among `rows`: the venue had started
  that hour when it answered."
  [rows]
  (let [opens (keep #(when (map? %) (open-ms (:t %))) rows)]
    (when (seq opens) (apply max opens))))

(defn completed-before
  "The exclusive bound on candle opens that a read of `rows` requested at
  `requested-at-ms` proves completed: every open before the latest open the
  venue returned, and every open whose hour ended `:mark-clock-margin-ms`
  before the request. nil when the read proves nothing."
  [rows requested-at-ms]
  (let [by-venue (latest-open rows)
        by-clock (when (number? requested-at-ms)
                   (inc (- requested-at-ms (hour-ms) (policy :mark-clock-margin-ms))))]
    (some->> [by-venue by-clock] (remove nil?) seq (apply max))))

(defn normalize-candles
  "{open-ms close} for raw `candleSnapshot` 1h rows ({:t open :c close}) that
  the read requested at `requested-at-ms` proves completed
  (`completed-before`). Rows with an unaligned open, a non-positive or
  non-decimal close are dropped."
  [rows requested-at-ms]
  (let [bound (completed-before rows requested-at-ms)]
    (into {}
          (keep (fn [row]
                  (when (map? row)
                    (let [t (open-ms (:t row))
                          c (close-text (:c row))]
                      (when (and t c bound (< t bound))
                        [t c])))))
          rows)))

(defn needed-opens
  "The candle open times `[from to)` that marks of the window `[a,b)` and
  every hourly checkpoint inside it need: from the candle before `a`'s
  boundary to the one before `b`."
  [a-ms b-ms]
  [(hour-floor (dec a-ms)) b-ms])

(defn missing-opens
  "The parts of `[from to)` (candle opens) that `entry` has not proved."
  [entry [from to]]
  (iv/uncovered (:covered entry) [from to]))

(defn merge-read
  "`entry` (or nil) with a `report-reads/read-hourly-candles!` result over
  candle opens `[from to)` merged: completed closes are added; a complete
  read proves the opens it shows completed (`completed-before`), so an
  hour the venue had not finished stays unproven and is read again."
  [entry {:keys [candles complete? requested-at-ms received-at-ms error]} [from to]]
  (let [closes (merge (:closes entry)
                      (into {} (filter (fn [[t _]] (and (<= from t) (< t to))))
                            (normalize-candles candles requested-at-ms)))
        bound (completed-before candles requested-at-ms)
        proven (when (and complete? bound (< from (min to bound)))
                 [[from (min to bound)]])]
    {:closes closes
     :covered (iv/union (:covered entry) proven)
     :fetched-at-ms (or received-at-ms requested-at-ms)
     :error (when-not complete? (or error "Couldn't read hourly prices"))}))

(defn mark-at
  "The mark at boundary `x`: {:status :ok :price :source :hourly-close
  :candle-open-ms}, or {:status :missing} (a complete read had no completed
  close for that hour), {:status :error} (the last read failed and the hour
  is unproven) or {:status :pending} (not read yet)."
  [entry x]
  (let [open (- x (hour-ms))
        price (get-in entry [:closes open])]
    (cond
      price {:status :ok :price price :source :hourly-close :candle-open-ms open}
      (iv/contains-point? (:covered entry) open) {:status :missing :candle-open-ms open}
      (:error entry) {:status :error :candle-open-ms open}
      :else {:status :pending :candle-open-ms open})))

(defn hour-boundaries
  "The hourly checkpoint boundaries `k` with `a < k <= b`."
  [a-ms b-ms]
  (let [h (hour-ms)]
    (vec (take-while #(<= % b-ms) (iterate #(+ % h) (hour-floor (+ a-ms h)))))))
