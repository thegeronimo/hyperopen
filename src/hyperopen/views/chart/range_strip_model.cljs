(ns hyperopen.views.chart.range-strip-model
  "Pure view-model for the custom-range context strip (design 1c).

  The strip is an all-time sparkline with a draggable selection over it. Both the
  portfolio equity chart and the vault detail chart render the same model, so
  nothing here knows which surface it is on — callers pass the all-time rows, the
  current custom range and the drag state, and get back plain numbers and
  strings.

  Coordinates come out in two flavours because the strip is drawn in two lanes:
  the sparkline is an SVG stretched with `preserveAspectRatio=\"none\"` (hence a
  fixed `viewbox-width`/`viewbox-height` user space), while the selection band and
  its handles are absolutely-positioned HTML in PERCENTAGES. Keeping the handles
  out of the stretched SVG is what stops them from being smeared into unusable
  slabs on a wide strip."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.custom-range :as custom-range]))

(def viewbox-width 1000)
(def viewbox-height 100)

(def ^:private sample-target
  "Upper bound on plotted samples. The strip is a context ribbon a few dozen
  pixels tall, so more points than this buy nothing and cost a longer path
  string on every pointer sample during a drag."
  200)

(def ^:private min-selection-fraction
  "Floor on the selection width so a single-day range stays visible and its
  handles stay far enough apart to grab individually."
  0.004)

(defn- finite-number?
  [value]
  (and (number? value)
       (js/Number.isFinite value)))

(defn- normalized-rows
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [time-ms (:time-ms row)
                     value (:value row)]
                 (when (and (finite-number? time-ms)
                            (finite-number? value))
                   {:time-ms time-ms
                    :value value}))))
       (sort-by :time-ms)
       vec))

(defn- downsample
  "Even stride down to `sample-target`, always keeping the last row so the strip
  ends where the data ends."
  [rows]
  (let [total (count rows)]
    (if (<= total sample-target)
      rows
      (let [stride (js/Math.ceil (/ total sample-target))]
        (->> (range 0 total stride)
             (mapv #(nth rows %))
             (#(if (= (peek %) (peek rows))
                 %
                 (conj % (peek rows)))))))))

(defn- value-domain
  [rows]
  (when (seq rows)
    (let [values (mapv :value rows)
          lo (apply min values)
          hi (apply max values)]
      {:lo lo
       :hi hi
       :span (let [span (- hi lo)]
               (if (pos? span) span 1))})))

(defn- polyline
  "Rows → an SVG `points` string in the strip's user space, or nil when there is
  nothing to draw (a one-point polyline renders as an invisible dot)."
  [rows time-domain {:keys [lo span]} pad]
  (when (> (count rows) 1)
    (let [inner (- viewbox-height (* 2 pad))]
      (->> rows
           (keep (fn [{:keys [time-ms value]}]
                   (when-let [fraction (custom-range/time->fraction time-ms time-domain)]
                     (let [x (* fraction viewbox-width)
                           y (+ pad (* (- 1 (/ (- value lo) span)) inner))]
                       (str (.toFixed x 1) "," (.toFixed y 1))))))
           (str/join " ")))))

(defn rows-domain
  "Full time extent of the rows — the strip always shows all of it, which is what
  makes it a context strip rather than a second copy of the chart."
  [rows]
  (let [rows* (normalized-rows rows)]
    (when (seq rows*)
      (let [from (:time-ms (first rows*))
            to (:time-ms (peek rows*))]
        (when (and from to)
          ;; The widening test has to run on DAY-SNAPPED bounds, because that is
          ;; what every consumer sees. A history spanning 02:00-20:00 of one day
          ;; passes a raw `to > from` test but collapses to a zero-width domain
          ;; once snapped: every fraction becomes 0, the sparkline pins itself to
          ;; the left edge, both handles land on top of each other and dragging
          ;; does nothing at all.
          (let [from-day (custom-range/utc-day-start from)
                to-day (custom-range/utc-day-start to)]
            {:from from
             :to (if (> to-day from-day)
                   to
                   (+ from custom-range/day-ms))}))))))

(defn build-model
  "Assemble the strip model.

  `rows` are the all-time series (`{:time-ms :value}`); `range` is the custom
  range currently applied (may be nil before the first drag); `drag-mode` is
  `:start`, `:end` or nil."
  [{:keys [rows range strip-open? drag-mode now-ms handle-only?]}]
  (let [now-ms (if (finite-number? now-ms) now-ms (.now js/Date))
        rows* (downsample (normalized-rows rows))
        domain (rows-domain rows*)
        range* (custom-range/clamp-to-domain (or range domain) domain)
        values (value-domain rows*)
        pad 6
        selected-rows (custom-range/clip-rows rows* range*)
        geometry (custom-range/selection-geometry range* domain min-selection-fraction)
        dragging? (some? drag-mode)
        pct (fn [fraction]
              (str (.toFixed (* 100 (or fraction 0)) 3) "%"))]
    {:available? (boolean (and domain values (seq rows*)))
     :open? (boolean strip-open?)
     :dragging? dragging?
     :drag-mode drag-mode
     :handle-only? (boolean handle-only?)
     :domain domain
     :range range*
     :viewbox (str "0 0 " viewbox-width " " viewbox-height)
     :sparkline-points (polyline rows* domain values pad)
     ;; Drawn over the base sparkline in the accent colour so the chosen window
     ;; reads as "this part of the history", not as a separate series.
     :selected-points (polyline selected-rows domain values pad)
     :selection {:left (pct (:x geometry))
                 :width (pct (:width geometry))}
     :handles {:from (pct (:from-fraction geometry))
               :to (pct (:to-fraction geometry))}
     :hint (if dragging?
             "Release to set the range"
             "Drag across the strip, or grab a handle")
     :draft-label (custom-range/format-draft-label range* now-ms)
     :span-label (custom-range/format-span range* now-ms)
     :day-label (some-> (custom-range/day-count range*) (str "D"))
     :domain-from-label (custom-range/format-full-date (:from domain))
     :domain-to-label (custom-range/format-full-date (:to domain))}))
