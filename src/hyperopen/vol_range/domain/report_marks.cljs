(ns hyperopen.vol-range.domain.report-marks
  "Hourly marks for Vol Range reporting (HO-SPEC-014 v2.1 section 12.4).

  Reports end at the latest completed UTC hour after a settle:
  `b = floor((now − :mark-settle-ms) / 1h) × 1h`. The mark at a boundary `x`
  is the close of the COMPLETED 1h candle whose open time is `x − 1h`. A
  candle is kept only when it had closed (`open + 1h <= requested-at`) when
  it was read, and only with a positive plain decimal close. A missing
  close is never carried forward and never zero: the window's headline is
  unavailable instead.

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

(defn normalize-candles
  "{open-ms close} for raw `candleSnapshot` 1h rows ({:t open :c close}) that
  had completed when the read was requested at `requested-at-ms`. Rows with
  an unaligned open, a non-positive or non-decimal close are dropped."
  [rows requested-at-ms]
  (into {}
        (keep (fn [row]
                (when (map? row)
                  (let [t (open-ms (:t row))
                        c (close-text (:c row))]
                    (when (and t c (number? requested-at-ms) (<= (+ t (hour-ms)) requested-at-ms))
                      [t c])))))
        rows))

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
  read proves the opens it could have seen completed."
  [entry {:keys [candles complete? requested-at-ms received-at-ms error]} [from to]]
  (let [closes (merge (:closes entry)
                      (into {} (filter (fn [[t _]] (and (<= from t) (< t to))))
                            (normalize-candles candles requested-at-ms)))
        proven (when (and complete? (number? requested-at-ms))
                 [[from (min to (inc (- requested-at-ms (hour-ms))))]])]
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
