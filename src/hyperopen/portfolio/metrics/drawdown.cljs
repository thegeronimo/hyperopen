(ns hyperopen.portfolio.metrics.drawdown
  (:require [hyperopen.portfolio.metrics.history :as history]
            [hyperopen.portfolio.metrics.returns :as returns]))

(defn to-drawdown-series
  [returns]
  (if (seq returns)
    (loop [remaining returns
           equity 1
           peak 1
           output []]
      (if (empty? remaining)
        output
        (let [next-equity (* equity (+ 1 (first remaining)))
              peak* (max peak next-equity)
              drawdown (if (pos? peak*)
                         (- (/ next-equity peak*) 1)
                         0)
              drawdown* (history/clamp-near-zero drawdown)]
          (recur (rest remaining)
                 next-equity
                 peak*
                 (conj output drawdown*)))))
    []))

(defn max-drawdown
  [returns]
  (if-let [drawdowns (seq (to-drawdown-series returns))]
    (apply min drawdowns)
    0))

(defn- drawdown-period-entry
  [rows drawdowns start-idx end-idx]
  (let [[valley-idx valley-dd]
        (reduce (fn [[best-idx best-dd] j]
                  (let [candidate (nth drawdowns j)]
                    (if (< candidate best-dd)
                      [j candidate]
                      [best-idx best-dd])))
                [start-idx (nth drawdowns start-idx)]
                (range start-idx (inc end-idx)))
        start-day (:day (nth rows start-idx))
        end-day (:day (nth rows end-idx))
        valley-day (:day (nth rows valley-idx))
        start-ms (history/parse-day-ms start-day)
        end-ms (history/parse-day-ms end-day)
        days (if (and (number? start-ms)
                      (number? end-ms))
               (inc (js/Math.round (/ (- end-ms start-ms) history/day-ms)))
               1)]
    {:start start-day
     :valley valley-day
     :end end-day
     :days days
     :max-drawdown (* 100 valley-dd)}))

(defn drawdown-details
  [daily-rows]
  (let [rows (history/normalize-daily-rows daily-rows)
        drawdowns (vec (to-drawdown-series (history/returns-values rows)))
        n (count drawdowns)]
    (if (zero? n)
      []
      (loop [idx 0
             current-start nil
             details []]
        (if (>= idx n)
          (if (number? current-start)
            (conj details
                  (drawdown-period-entry rows drawdowns current-start (dec n)))
            details)
          (let [dd (nth drawdowns idx)
                in-drawdown? (neg? dd)
                recovered? (and (number? current-start)
                                (zero? dd))]
            (cond
              (and in-drawdown?
                   (nil? current-start))
              (recur (inc idx) idx details)

              recovered?
              (recur (inc idx)
                     nil
                     (conj details
                           (drawdown-period-entry rows
                                                  drawdowns
                                                  current-start
                                                  (dec idx))))

              :else
              (recur (inc idx) current-start details))))))))

(defn max-drawdown-stats
  [daily-rows]
  (let [details (drawdown-details daily-rows)]
    (when (seq details)
      (let [worst (apply min-key :max-drawdown details)
            longest (apply max-key :days details)]
        {:max-drawdown (/ (:max-drawdown worst) 100)
         :max-dd-date (:valley worst)
         :max-dd-period-start (:start worst)
         :max-dd-period-end (:end worst)
         :longest-dd-days (:days longest)}))))

(defn calmar
  ([returns*]
   (calmar returns* {}))
  ([returns* {:keys [periods-per-year years]
              :or {periods-per-year returns/default-periods-per-year}}]
   (let [growth (returns/cagr returns* {:periods-per-year periods-per-year
                                        :years years})
         drawdown (max-drawdown returns*)]
     (when (and (number? growth)
                (number? drawdown)
                (neg? drawdown))
       (/ growth (js/Math.abs drawdown))))))