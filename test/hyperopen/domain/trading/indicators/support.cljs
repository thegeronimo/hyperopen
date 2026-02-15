(ns hyperopen.domain.trading.indicators.support)

(defn sample-candle
  [idx]
  {:time (+ 1700000000 (* idx 60))
   :open (+ 100 (* idx 0.11))
   :high (+ 101 (* idx 0.13))
   :low (+ 99 (* idx 0.09))
   :close (+ 100.5 (* idx 0.12))
   :volume (+ 1000 (* idx 8))})

(def sample-candles
  (mapv sample-candle (range 240)))

(def default-indicator-params
  {:period 14
   :fast 12
   :slow 26
   :signal 9
   :multiplier 2
   :step 0.02
   :max 0.2
   :short 9
   :medium 26
   :long 52
   :close 26
   :deviation 2
   :factor 3
   :threshold-percent 3})
