(ns hyperopen.vol-range.application.report-headline
  "The headline card of the Vol Range report view model (HO-SPEC-014 v2.1
  sections 12.3, 12.4, 12.6, 12.7): the three dollar figures in USDC with
  two decimals, thousands separators and a U+2212 minus, the benchmark
  label, the exact window and valuation, or the message that says why the
  figures are hidden, plus the All tracked markets total note and the
  covered-subset offer. Formatting only: the figures come from
  `report-windows` and `report-aggregate`. Pure."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.display :as display]
            [hyperopen.vol-range.application.report-copy :as copy]
            [hyperopen.vol-range.domain.report-equations :as equations]))

(def ^:private hour-ms 3600000)

(defn usdc-text
  "{:display :tone} for a USDC decimal string: \"+2,652.00 USDC\",
  \"−1,544.80 USDC\" or \"0.00 USDC\" (by the rounded value)."
  [x]
  (when-let [d (display/parse x)]
    (let [rounded (precision/quantize d 2 :half-up)
          text (display/fixed x 2)]
      (cond (precision/pos-dec? rounded) {:display (str "+" text " USDC") :tone :positive}
            (precision/neg-dec? rounded) {:display (str text " USDC") :tone :negative}
            :else {:display "0.00 USDC" :tone :neutral}))))

(defn- figures-view [figures]
  (when figures
    (into {} (for [k equations/figure-keys]
               [k (assoc (usdc-text (get figures k)) :label (get copy/figure-labels k))]))))

(defn- hh-mm [ms]
  (let [d (js/Date. ms) pad #(if (< % 10) (str "0" %) (str %))]
    (str (pad (.getUTCHours d)) ":" (pad (.getUTCMinutes d)))))

(defn- first-close-ms [t0] (+ (* hour-ms (quot t0 hour-ms)) hour-ms))

(defn- first-close-message [t0s]
  (copy/first-close-message (hh-mm (apply min (map first-close-ms t0s)))))

(defn- boundary-of [mark] (some-> (:candle-open-ms mark) (+ hour-ms) display/utc-date-minute))

(defn- coverage-message
  [{:keys [coverage]} break-ctx]
  (case (:state coverage)
    :attribution-break
    (let [brk (first (:breaks coverage))]
      (copy/break-message (display/utc-date-minute (:at-ms brk)) (copy/break-text (:reason brk) break-ctx)))
    :gap
    (let [g (first (filter #(= :gap (:state %)) (:intervals coverage)))]
      (copy/gap-message (display/utc-date-minute (:from-ms g)) (display/utc-date-minute (:to-ms g))))
    :unavailable (copy/unavailable-message (:reason coverage))
    copy/partial-message))

(defn- single-message
  "Why one campaign's figures are hidden, or nil."
  [{:keys [result campaign break-ctx]}]
  (let [{:keys [status reason window a-ms a-mark b-mark]} result]
    (when-not (= :ok status)
      (case reason
        :window/no-completed-mark (first-close-message [(:t0-ms campaign)])
        :window/before-tracking (copy/before-tracking-message (get copy/window-labels window)
                                                              (display/utc-date-minute a-ms)
                                                              (display/utc-date-minute (:t0-ms campaign)))
        :marks/missing-boundary-close
        (copy/missing-mark-message (boundary-of (if (= :ok (:status a-mark)) b-mark (or a-mark b-mark))))
        :marks/read-error (copy/mark-error-message (boundary-of (if (= :ok (:status a-mark)) b-mark a-mark)))
        :marks/pending copy/marks-loading
        (coverage-message result break-ctx)))))

(defn- range-text [{:keys [status reason window a-ms b-ms]}]
  (when (and (number? a-ms) (number? b-ms)
             (not (contains? #{:window/no-completed-mark :window/before-tracking} reason))
             (some? status))
    (copy/range-text (display/utc-date-minute a-ms) (display/utc-date-minute b-ms) (= :since-start window))))

(defn- all-message [{:keys [status reason]} items window]
  (cond
    (= :window/no-completed-mark reason) (first-close-message (map #(get-in % [:campaign :t0-ms]) items))
    (and (= :no-total status)
         (every? #(= :window/before-tracking (get-in % [:result :reason])) items))
    (let [latest (apply max-key #(get-in % [:campaign :t0-ms]) items)]
      (single-message (assoc latest :result (assoc (:result latest) :window window))))
    :else nil))

(defn headline-view
  "The headline view model: {:status :title :figures :benchmark-label
  :range-text :message :no-total :subset-offer}. `agg` is
  `report-aggregate/aggregate` over `items` [{:symbol :market :campaign
  :result :break-ctx}]."
  [{:keys [agg items window]}]
  (let [{:keys [status scope figures subset? subset-offer? included excluded]} agg
        single (when (= :single scope) (first items))
        shown-result (or (:result agg)
                         (when (and (= :all scope) (not= :since-start window)) (:result (first items))))
        before-all? (and (= :all scope) (every? #(= :window/before-tracking (get-in % [:result :reason])) items))]
    {:status (cond (= :none scope) :unavailable
                   (= :single scope) (get #{:ok :pending :suppressed :unavailable} status :unavailable)
                   before-all? :unavailable
                   :else (get #{:ok :pending :no-total} status :no-total))
     :title (cond (= :single scope) (:market single)
                  subset? (copy/covered-title included)
                  :else copy/headline-title-all)
     :figures (when (= :ok status) (figures-view figures))
     :benchmark-label (when (= :ok status) copy/benchmark-label)
     :range-text (when (and shown-result (not before-all?)) (range-text shown-result))
     :message (case scope
                :single (single-message single)
                :all (all-message agg items window)
                nil)
     :no-total (when (and (= :all scope) (not before-all?))
                 (case (:reason agg)
                   :aggregate/different-windows copy/no-total-different-windows
                   :aggregate/incomplete (copy/no-total-incomplete excluded)
                   nil))
     :subset-offer (when subset-offer?
                     {:label (copy/subset-offer-label included) :active? (boolean subset?)})}))
