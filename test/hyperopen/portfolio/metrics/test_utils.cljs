(ns hyperopen.portfolio.metrics.test-utils)

(defn approx=
  [left right tolerance]
  (and (number? left)
       (number? right)
       (<= (js/Math.abs (- left right)) tolerance)))

(def quantstats-returns
  [0.01
   -0.02
   0.015
   0
   -0.005
   0.03
   -0.01
   0.02
   -0.015
   0.005
   0.012
   -0.008
   0.004
   -0.003
   0.018])

(def quantstats-benchmark
  [0.008
   -0.015
   0.01
   -0.002
   0.001
   0.02
   -0.012
   0.011
   -0.01
   0.004
   0.009
   -0.006
   0.003
   -0.002
   0.012])

(def day-ms
  (* 24 60 60 1000))

(def fixture-start-ms
  (.getTime (js/Date. "2024-01-01T00:00:00.000Z")))

(defn fixture-daily-rows
  [returns]
  (mapv (fn [idx return]
          (let [time-ms (+ fixture-start-ms (* idx day-ms))]
            {:day (subs (.toISOString (js/Date. time-ms)) 0 10)
             :time-ms time-ms
             :return return}))
        (range (count returns))
        returns))

(defn day->ms
  [day]
  (.getTime (js/Date. (str day "T00:00:00.000Z"))))