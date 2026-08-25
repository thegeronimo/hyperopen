(ns hyperopen.portfolio.candle-coverage
  "Does the candle store already hold what a benchmark request would ask for?

  `hyperopen.portfolio.actions` used to emit a `:effects/fetch-candle-snapshot`
  for every selected benchmark on every range change, because its fetch helper
  took only the range and the coin list and so structurally could not consult
  the store. It was the one fetch helper in that namespace without a store
  guard, and it meant switching back to a preset you had just looked at paid a
  full round trip for data already in memory (measured against the live API:
  128KB / ~0.70s for the two-year preset, 111KB / ~0.56s for 30D, uncompressed)."
  (:require [hyperopen.platform :as platform]
            [hyperopen.utils.interval :as utils-interval]))

(def ^:dynamic *now-ms*
  "Clock seam. Coverage is the only thing in the portfolio action path that needs
  the current time; binding this keeps those actions deterministic under test."
  platform/now-ms)

(defn stored-rows
  "Rows out of a `[:candles coin interval]` slot, whichever shape it is in.

  A slot is a bare vector of rows normally, and a map carrying `:rows` alongside
  `:error`/`:error-category` once a fetch has failed against it."
  [slot]
  (cond
    (vector? slot) slot
    (sequential? slot) (vec slot)
    (map? slot) (let [rows (or (:rows slot) (:data slot) (:candles slot))]
                  (if (sequential? rows) (vec rows) []))
    :else []))

(defn row-time-ms
  [row]
  (cond
    (map? row) (let [t (or (:t row) (:time row) (get row "t") (get row "time"))]
                 (when (number? t) t))
    (sequential? row) (let [t (first row)]
                        (when (number? t) t))
    :else nil))

(defn covers-window?
  "True when the candles already stored for `coin` at `interval` reach across the
  window a fresh `bars`-long request would ask for, and are recent enough to be
  worth reusing.

  Presence alone is deliberately NOT the test. The all-time preset and the
  two-year preset both resolve to the `:1d` interval and therefore share one
  store slot while asking for very different spans, so a presence-only guard
  would silently draw a two-year benchmark inside an all-time window and report
  plausible-but-wrong alpha.

  Both ends are checked. The oldest stored bar must reach back to the start of
  the requested window, allowing one bar of slack because a series is aligned to
  interval boundaries rather than to the moment of the request. The newest must
  be current to within two bars, because the newest bar is the one still forming
  and so legitimately trails `now` by up to one whole interval."
  [state coin interval bars]
  (let [rows (stored-rows (get-in state [:candles coin interval]))
        interval-ms (utils-interval/interval-to-milliseconds interval)
        oldest (row-time-ms (first rows))
        newest (row-time-ms (peek rows))]
    (boolean
     (and (seq rows)
          (number? interval-ms)
          (pos? interval-ms)
          (number? oldest)
          (number? newest)
          (number? bars)
          (let [now (*now-ms*)
                window-start (- now (* bars interval-ms))]
            (and (<= oldest (+ window-start interval-ms))
                 (>= newest (- now (* 2 interval-ms)))))))))
