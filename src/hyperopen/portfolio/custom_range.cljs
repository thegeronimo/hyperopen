(ns hyperopen.portfolio.custom-range
  "Pure domain for the custom chart date range (design 1c, \"drag on the chart\").

  A custom range is `{:from <ms> :to <ms>}` with both bounds snapped to a UTC day
  start, `:from` never after `:to`. It is shared by the portfolio equity chart and
  the vault detail chart, so this namespace stays free of any surface specifics.

  Two rules keep the custom range from re-plumbing the whole windowing pipeline:

  - `:from` is fed to the EXISTING cutoff machinery, so the anchor-point and
    complete-window logic that presets rely on behaves identically.
  - `:to` is applied by clipping the source history rows before that machinery
    runs. Every downstream consumer already treats \"window end\" as the last
    sample, so an end-clipped series needs no end-bound plumbing at all.

  The drag math is pure: the view dispatches raw pointer coordinates through the
  `:event/clientX`, `:event.currentTarget/bounds` and `:event/pointer-buttons`
  placeholders and the mapping happens here, mirroring the optimizer exposure pad
  (`hyperopen.portfolio.optimizer.domain.exposure-policy/point->targets`)."
  (:require [clojure.string :as str]))

(def day-ms
  86400000)

(def ^:private utc-month-names
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(def ^:private default-handle-grab-px
  "Pointer distance from a selection edge that grabs that handle instead of
  starting a fresh sweep. Sized for touch, which is the tightest target."
  14)

(defn finite-number?
  [value]
  (and (number? value)
       (js/Number.isFinite value)))

(defn utc-day-start
  "Snap a timestamp down to the start of its UTC day. UTC (not local) so a range
  means the same window regardless of where it is read or which TZ CI runs in."
  [ms]
  (when (finite-number? ms)
    (* day-ms (js/Math.floor (/ ms day-ms)))))

(defn utc-day-end
  "Last millisecond of the UTC day containing `ms`. Used for the `:to` bound so a
  custom range that ends \"on Jun 12\" includes everything recorded that day."
  [ms]
  (when-let [start (utc-day-start ms)]
    (+ start (dec day-ms))))

;; --- the range value ----------------------------------------------------------------------

(defn normalize
  "Coerce anything into a valid custom range, or nil. Accepts a map with
  `:from`/`:to` (or string keys, as parsed from a URL). Bounds are day-snapped and
  reordered, so a backwards drag is still a valid range."
  [range]
  (when (map? range)
    (let [from (or (:from range) (get range "from"))
          to (or (:to range) (get range "to"))]
      (when (and (finite-number? from)
                 (finite-number? to))
        (let [a (utc-day-start (min from to))
              b (utc-day-start (max from to))]
          {:from a
           :to b})))))

(defn active?
  "True when `range` is a usable custom range."
  [range]
  (some? (normalize range)))

(defn day-count
  "Inclusive day span, so a range whose bounds are the same day reads as 1D."
  [range]
  (when-let [{:keys [from to]} (normalize range)]
    (inc (js/Math.round (/ (- to from) day-ms)))))

(defn span-preset
  "Nearest preset bucket for a custom span.

  Benchmark candles are requested per preset (interval + bar count). A custom
  range has no preset of its own, so it borrows the request of the smallest
  preset that still covers its span — otherwise a two-year custom window would
  be fetched at the 30-day resolution and the benchmark line would stop short."
  [range]
  (when-let [days (day-count range)]
    (cond
      (<= days 1) :day
      (<= days 7) :week
      (<= days 30) :month
      (<= days 91) :three-month
      (<= days 182) :six-month
      (<= days 365) :one-year
      (<= days 730) :two-year
      :else :all-time)))

(defn clamp-to-domain
  "Pull a range inside the data domain. A range entirely outside the domain
  collapses onto the nearest edge rather than vanishing, so the strip always shows
  a grabbable selection."
  [range domain]
  (let [range* (normalize range)
        {domain-from :from domain-to :to} (normalize domain)]
    (cond
      (nil? range*) nil
      (nil? domain-from) range*
      :else {:from (min (max (:from range*) domain-from) domain-to)
             :to (min (max (:to range*) domain-from) domain-to)})))

(defn covers-domain?
  "True when the range spans the whole available domain — the point at which a
  custom range stops being a narrowing and is really just \"all-time\"."
  [range domain]
  (let [{:keys [from to]} (normalize range)
        {domain-from :from domain-to :to} (normalize domain)]
    (boolean (and from domain-from
                  (<= from domain-from)
                  (>= to domain-to)))))

;; --- windowing ----------------------------------------------------------------------------

(defn cutoff-ms
  "Start bound to hand to the existing preset cutoff machinery."
  [range]
  (:from (normalize range)))

(defn end-ms
  "Inclusive end bound, expanded to the end of the `:to` day."
  [range]
  (some-> (normalize range) :to utc-day-end))

(defn clip-rows-to-end
  "Drop rows after the range end. `rows` are maps carrying `:time-ms`.

  This is the ONLY end-bound enforcement in the pipeline: every consumer already
  reads the window end off the last sample, so clipping here makes them correct
  without a single new parameter."
  [rows range]
  (let [rows* (vec (or rows []))]
    (if-let [end (end-ms range)]
      (vec (filter (fn [row]
                     (let [t (:time-ms row)]
                       (and (finite-number? t)
                            (<= t end))))
                   rows*))
      rows*)))

(defn clip-pairs-to-end
  "`clip-rows-to-end` for the `[time-ms value]` tuple shape the vault detail
  performance model uses."
  [pairs range]
  (let [pairs* (vec (or pairs []))]
    (if-let [end (end-ms range)]
      (vec (filter (fn [pair]
                     (let [t (first pair)]
                       (and (finite-number? t)
                            (<= t end))))
                   pairs*))
      pairs*)))

(defn clip-rows
  "Clip rows to both bounds. Used for the strip's own highlighted segment, where
  there is no downstream cutoff machinery to lean on."
  [rows range]
  (let [{:keys [from]} (normalize range)]
    (cond->> (clip-rows-to-end rows range)
      from (filterv (fn [row]
                      (let [t (:time-ms row)]
                        (and (finite-number? t)
                             (>= t from)))))
      true vec)))

;; --- formatting ---------------------------------------------------------------------------

(defn- pad2
  [value]
  (let [text (str value)]
    (if (= 1 (count text))
      (str "0" text)
      text)))

(defn format-iso
  "`ms` → \"YYYY-MM-DD\" (UTC). The URL wire format."
  [ms]
  (when (finite-number? ms)
    (let [date (js/Date. ms)]
      (str (.getUTCFullYear date)
           "-" (pad2 (inc (.getUTCMonth date)))
           "-" (pad2 (.getUTCDate date))))))

(defn parse-iso
  "\"YYYY-MM-DD\" → UTC day-start ms, or nil. Deliberately strict: a malformed
  share link falls back to the preset rather than showing an invented window."
  [text]
  (let [text* (some-> text str str/trim)]
    (when (and (seq text*)
               (re-matches #"\d{4}-\d{2}-\d{2}" text*))
      (let [[y m d] (map #(js/parseInt % 10) (str/split text* #"-"))
            ms (js/Date.UTC y (dec m) d)]
        (when (and (finite-number? ms)
                   (<= 1 m 12)
                   (<= 1 d 31))
          ms)))))

(defn- short-date
  [ms]
  (let [date (js/Date. ms)]
    (str (get utc-month-names (.getUTCMonth date))
         " "
         (.getUTCDate date))))

(defn- year-suffix
  [ms]
  (let [year (.getUTCFullYear (js/Date. ms))]
    (str "'" (subs (str year) 2))))

(defn format-span
  "Range → chip label. The year only appears when it is actually ambiguous:
  \"Mar 3 – Jun 12\" within the current year, \"Mar 3 – Jun 12 '25\" for another
  single year, \"Mar 3 '24 – Jun 12 '25\" across a boundary."
  ([range]
   (format-span range (.now js/Date)))
  ([range now-ms]
   (when-let [{:keys [from to]} (normalize range)]
     (let [from-year (.getUTCFullYear (js/Date. from))
           to-year (.getUTCFullYear (js/Date. to))
           current-year (when (finite-number? now-ms)
                          (.getUTCFullYear (js/Date. now-ms)))]
       (cond
         (not= from-year to-year)
         (str (short-date from) " " (year-suffix from)
              " – " (short-date to) " " (year-suffix to))

         (= from-year current-year)
         (str (short-date from) " – " (short-date to))

         :else
         (str (short-date from) " – " (short-date to) " " (year-suffix to)))))))

(defn format-draft-label
  "Strip header readout: \"Mar 3 – Jun 12 · 102D\"."
  ([range]
   (format-draft-label range (.now js/Date)))
  ([range now-ms]
   (when-let [span (format-span range now-ms)]
     (str span " · " (day-count range) "D"))))

(defn format-full-date
  "\"Mar 3, 2026\" — the strip's domain end labels."
  [ms]
  (when (finite-number? ms)
    (str (short-date ms) ", " (.getUTCFullYear (js/Date. ms)))))

;; --- strip geometry (pure; plain numbers only, never the DOM) -----------------------------

(defn time->fraction
  "Timestamp → 0..1 position across the domain, clamped."
  [ms domain]
  (let [{:keys [from to]} (normalize domain)]
    (when (and (finite-number? ms) from)
      (let [span (- to from)]
        (if (pos? span)
          (min 1.0 (max 0.0 (/ (- ms from) span)))
          0.0)))))

(defn fraction->time
  "0..1 position across the domain → day-snapped timestamp."
  [fraction domain]
  (let [{:keys [from to]} (normalize domain)]
    (when (and (finite-number? fraction) from)
      (let [f (min 1.0 (max 0.0 fraction))]
        (utc-day-start (+ from (* f (- to from))))))))

(defn pointer-fraction
  "Pointer clientX + the strip's bounding rect → 0..1, or nil when the geometry is
  degenerate (a strip in a hidden pane measures zero wide)."
  [client-x bounds]
  (let [{:keys [left width]} bounds]
    (when (and (finite-number? client-x)
               (finite-number? left)
               (finite-number? width)
               (pos? width))
      (min 1.0 (max 0.0 (/ (- client-x left) width))))))

(defn pointer-time
  [client-x bounds domain]
  (some-> (pointer-fraction client-x bounds)
          (fraction->time domain)))

;; --- drag ---------------------------------------------------------------------------------

(defn- grab-fraction
  [bounds handle-px]
  (let [{:keys [width]} bounds
        px (if (finite-number? handle-px) handle-px default-handle-grab-px)]
    (if (and (finite-number? width) (pos? width))
      (/ px width)
      0.0)))

(defn drag-begin
  "Pointer-down on the strip → `{:mode :start|:end :range {...}}`, or nil when the
  geometry is unusable.

  Hit-testing happens here rather than via separate per-handle DOM handlers so
  that every pointer sample is measured against the SAME element's rect — mixing
  rects between down and move is what makes a brush drift under the pointer.

  Landing on a handle nudges that edge; landing anywhere else starts a fresh
  sweep anchored where the pointer went down. `handle-only?` (the touch variant)
  refuses the fresh sweep so the gesture cannot be started by a stray tap."
  [{:keys [client-x bounds domain range handle-px handle-only?]}]
  (when-let [fraction (pointer-fraction client-x bounds)]
    (let [ts (fraction->time fraction domain)
          current (clamp-to-domain range domain)
          grab (grab-fraction bounds handle-px)
          from-fraction (some-> current :from (time->fraction domain))
          to-fraction (some-> current :to (time->fraction domain))
          on-start? (and from-fraction (<= (js/Math.abs (- fraction from-fraction)) grab))
          on-end? (and to-fraction (<= (js/Math.abs (- fraction to-fraction)) grab))]
      (cond
        (and on-start? on-end?)
        (let [to-start (js/Math.abs (- fraction from-fraction))
              to-end (js/Math.abs (- fraction to-fraction))]
          (cond
            (< to-start to-end) {:mode :start :range current}
            (> to-start to-end) {:mode :end :range current}
            ;; Exact tie means the selection is collapsed — both edges sit on the
            ;; same fraction, so distance can never separate them. The side the
            ;; pointer is on decides, which is what lets a collapsed range be
            ;; reopened in either direction instead of only ever leftwards.
            (< fraction from-fraction) {:mode :start :range current}
            :else {:mode :end :range current}))

        on-start? {:mode :start :range current}
        on-end? {:mode :end :range current}
        handle-only? nil
        :else {:mode :end :range {:from ts :to ts}}))))

(defn drag-move
  "Pointer-move while dragging → the next `{:mode :range}`, or nil to end the drag.

  Returns nil when no button is pressed: pointer-up outside the strip never
  reaches us, so a released button is the signal that the gesture is over.

  Dragging one edge past the other SWAPS which edge is live, so the selection
  keeps following the pointer instead of sticking at zero width."
  [{:keys [mode client-x bounds buttons domain range]}]
  (let [pressed? (and (number? buttons) (pos? buttons))]
    (when pressed?
      (let [current (normalize range)
            ts (pointer-time client-x bounds domain)]
        (when (and current ts)
          (case mode
            :start (if (> ts (:to current))
                     {:mode :end :range {:from (:to current) :to ts}}
                     {:mode :start :range (assoc current :from ts)})
            :end (if (< ts (:from current))
                   {:mode :start :range {:from ts :to (:from current)}}
                   {:mode :end :range (assoc current :to ts)})
            nil))))))

(defn selection-geometry
  "Range + domain → `{:x :width}` in 0..1 fractions for the strip's selection
  rect, with a floor so a single-day selection is still visible and grabbable."
  [range domain min-width]
  (let [from-fraction (some-> range :from (time->fraction domain))
        to-fraction (some-> range :to (time->fraction domain))
        floor (if (finite-number? min-width) min-width 0.0)]
    (when (and from-fraction to-fraction)
      (let [x (min from-fraction to-fraction)
            width (max floor (js/Math.abs (- to-fraction from-fraction)))]
        {:x x
         :width (min width (- 1.0 x))
         :from-fraction from-fraction
         :to-fraction to-fraction}))))
