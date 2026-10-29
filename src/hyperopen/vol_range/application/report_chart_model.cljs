(ns hyperopen.vol-range.application.report-chart-model
  "The cumulative P&L chart of the Vol Range report (HO-SPEC-014 v2.1
  section 12.4): Net tracked asset P&L and Hold P&L over `[a,k)` at each
  checkpoint `k` of the selected window, in USDC. Dollar contributions of
  the tracked inventory, never account value or a return.

  Checkpoints are the hourly boundaries `a < k <= b`. Since tracking began
  switches to one per UTC day (the hourly close at each midnight, plus `b`)
  once its window is longer than `:daily-checkpoints-after-ms` (30 days).
  The series starts at `a` with zero. A checkpoint is drawn only when its
  completed hourly close exists and the coverage of `[T0 − skew, k)` is
  complete with no break before `k` (the caller's coverage to `b`, from the
  headline). A missing close is a gap; an unconfirmed interval or a break
  ends the series there. Gaps split each series into contiguous segment
  series with the same stroke, because the shared d3 chart runtime draws
  every series as one line. All tracked markets sums the included
  campaigns' figures, a checkpoint only when every one has it.

  The values here are display ratios for the chart host only; every figure
  is computed with exact decimals first. Pure."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.display :as display]
            [hyperopen.vol-range.application.report-copy :as copy]
            [hyperopen.vol-range.application.report-headline :as headline]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.report-equations :as equations]
            [hyperopen.vol-range.domain.report-ledger :as ledger]
            [hyperopen.vol-range.domain.report-marks :as marks]))

(def ^:private day-ms 86400000)
(def ^:private zero (precision/parse-decimal "0"))
(defn- d [x] (or (some-> x str str/trim precision/parse-decimal) zero))
(defn- ds [x] (precision/decimal->string x))

;; ---------------------------------------------------------------------------
;; Checkpoints
;; ---------------------------------------------------------------------------

(defn- daily-boundaries [a b]
  (let [first-midnight (+ (* day-ms (js/Math.floor (/ a day-ms))) day-ms)
        days (vec (take-while #(<= % b) (iterate #(+ % day-ms) first-midnight)))]
    (if (= (peek days) b) days (conj days b))))

(defn checkpoints
  "{:cadence :hourly|:daily :ks [k ...]}: the checkpoint boundaries `a < k <=
  b` of a window."
  [a-ms b-ms]
  (if (> (- b-ms a-ms) (get contracts/report-policy :daily-checkpoints-after-ms))
    {:cadence :daily :ks (daily-boundaries a-ms b-ms)}
    {:cadence :hourly :ks (marks/hour-boundaries a-ms b-ms)}))

(defn clean-until
  "The latest `k` whose `[T0 − skew, k)` the coverage result (to `b`) shows
  complete with no break before it, or nil when coverage is unavailable."
  [coverage]
  (when (and (map? coverage) (not= :unavailable (:state coverage)) (number? (:to-ms coverage)))
    (let [bad (concat (keep #(when-not (= :complete (:state %)) (:from-ms %)) (:intervals coverage))
                      (keep :at-ms (:breaks coverage)))]
      (if (seq bad) (apply min (:to-ms coverage) bad) (:to-ms coverage)))))

(defn states-at
  "[{:q :c}] at each ascending `ks`, as `report-ledger/state-before` would
  give, in one pass over the sorted events."
  [campaign events ks]
  (let [cid (:campaign-id campaign)
        t0 (:t0-ms campaign)
        mine (->> (ledger/events events)
                  (filter #(and (:tracked? %) (number? (:time %)) (>= (:time %) t0)))
                  (filter #(or (nil? cid) (nil? (:campaign-id %)) (= cid (:campaign-id %)))))]
    (loop [ks ks es mine q (d (get-in campaign [:baseline :q0])) c zero out []]
      (if (empty? ks)
        out
        (let [k (first ks)
              [before after] (split-with #(< (:time %) k) es)
              q (reduce precision/add q (map (comp d :net-base-delta) before))
              c (reduce precision/add c (map (comp d :net-cash-delta) before))]
          (recur (rest ks) after q c (conj out {:q (ds q) :c (ds c)})))))))

(def ^:private zero-figures {:net-pnl "0" :hold-pnl "0" :contribution "0"})

(defn item-figures
  "[figures-or-nil] of one campaign item {:campaign :result :events :coverage
  :coin-marks} at `ks` (`result` from `report-windows/campaign-window-figures`)."
  [{:keys [campaign result events coverage coin-marks]} ks]
  (let [{:keys [q-a c-a a-mark]} result
        until (clean-until coverage)]
    (mapv (fn [k {:keys [q c]}]
            (let [m (marks/mark-at coin-marks k)]
              (when (and (number? until) (<= k until) (= :ok (:status m)))
                (equations/window-figures {:q-a q-a :c-a c-a :p-a (:price a-mark) :q-b q :c-b c :p-b (:price m)}))))
          ks (states-at campaign events ks))))

;; ---------------------------------------------------------------------------
;; Series
;; ---------------------------------------------------------------------------

(defn- num [x] (js/Number x))

(defn- y-scale [values]
  (let [lo (apply min 0 values)
        hi (apply max 0 values)
        [lo hi] (if (= lo hi) [(- lo 1) (+ hi 1)] [lo hi])]
    {:lo lo :hi hi :y (fn [v] (/ (- hi v) (- hi lo)))}))

(defn- tick-text [n]
  (or (display/fixed (if (zero? n) 0 n) 2) "0.00"))

(defn- segment-series [kind idx pts]
  {:id (keyword (str (name kind) "-" idx))
   :kind kind
   :points (if (= 1 (count pts)) [(first pts) (first pts)] (vec pts))})

(defn- valid-runs [cps]
  (->> (partition-by #(some? (:figures %)) cps)
       (filter #(some? (:figures (first %))))))

(defn- build-chart [{:keys [a-ms b-ms cadence cps]}]
  (let [valid (filterv :figures cps)
        values (mapcat (fn [{:keys [figures]}] [(num (:net-pnl figures)) (num (:hold-pnl figures))]) valid)
        {:keys [lo hi y]} (y-scale values)
        x (fn [k] (/ (- k a-ms) (- b-ms a-ms)))
        point (fn [kind {:keys [k figures]}]
                {:x-ratio (x k) :y-ratio (y (num (get figures (if (= :net kind) :net-pnl :hold-pnl))))})
        runs (valid-runs cps)
        last-k (:k (peek valid))]
    {:title copy/chart-title
     :caption (copy/chart-caption cadence)
     :cadence cadence
     :legend (mapv (fn [kind] {:id kind :label (get copy/chart-series-labels kind)}) [:net :hold])
     :y-ticks (mapv (fn [n] {:value (tick-text n) :y-ratio (y n)}) [hi (/ (+ hi lo) 2) lo])
     :x-labels {:start (display/utc-date-minute a-ms) :end (display/utc-date-minute b-ms)}
     :points (mapv #(point :net %) valid)
     :series (vec (concat (map-indexed (fn [i run] (segment-series :net i (map #(point :net %) run))) runs)
                          (map-indexed (fn [i run] (segment-series :hold i (map #(point :hold %) run))) runs)))
     :tooltips (mapv (fn [{:keys [k figures]}]
                       {:time-text (display/utc-date-minute k)
                        :net (headline/usdc-text (:net-pnl figures))
                        :hold (headline/usdc-text (:hold-pnl figures))})
                     valid)
     :end-text (when (< last-k b-ms) (copy/chart-ends-text (display/utc-date-minute last-k)))}))

(defn- ready? [{:keys [result]}]
  (and (= :ok (get-in result [:a-mark :status]))
       (number? (:a-ms result)) (number? (:b-ms result)) (< (:a-ms result) (:b-ms result))))

(defn chart-view
  "The chart view model for the shown campaign items (one market, or the
  included markets of an All tracked markets total, all with the same
  `[a,b)`), or nil when nothing past the window start can be drawn."
  [{:keys [items]}]
  (let [items (vec items)
        a (get-in items [0 :result :a-ms])
        b (get-in items [0 :result :b-ms])]
    (when (and (seq items) (every? ready? items)
               (every? #(and (= a (get-in % [:result :a-ms])) (= b (get-in % [:result :b-ms]))) items))
      (let [{:keys [cadence ks]} (checkpoints a b)
            per-item (mapv #(item-figures % ks) items)
            sums (apply mapv (fn [& fs] (equations/sum-figures fs)) per-item)
            origin? (every? #(some-> (clean-until (:coverage %)) (>= a)) items)
            cps (into [{:k a :figures (when origin? zero-figures)}]
                      (map (fn [k f] {:k k :figures f}) ks sums))]
        (when (and origin? (some :figures (rest cps)))
          (build-chart {:a-ms a :b-ms b :cadence cadence :cps cps}))))))
