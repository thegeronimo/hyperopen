(ns hyperopen.portfolio.optimizer.bit-parity
  "Bit-exact comparison for numeric-kernel parity tests.

  Several optimizer kernels have been ported from persistent-vector matrix
  helpers to flat Float64Array loops, and each port is only safe if it is
  identical rather than close. Those tests keep a frozen copy of the pre-port
  implementation and compare against it.

  Plain `=` is the wrong comparator for that job, in both directions:

    - It reports two NaNs as different, so a fixture whose correct answer
      contains NaN fails even when the two implementations agree perfectly.
      That is not hypothetical -- the pairwise sample covariance propagates NaN
      by design when a series carries a NaN observation, and comparing that
      function to ITSELF with `=` fails.
    - It reports 0.0 and -0.0 as the same, so a port that flips the sign of a
      zero passes. That matters here because a signed zero survives into later
      arithmetic: 1/0.0 is Infinity and 1/-0.0 is -Infinity.

  `js/Object.is` has exactly the semantics wanted -- NaN equals NaN, and +0 and
  -0 differ -- so it is the primitive these tests should be built on."
  (:require [clojure.string :as str]))

(defn bit=
  "True when `expected` and `actual` are the same shape and every number in
  them is bit-identical. Walks nested maps and sequentials; falls back to `=`
  for anything else.

  Map KEYS are compared with ordinary `=`, not bitwise. Numeric map keys are
  vanishingly rare in these payloads and a NaN key could not be looked up
  anyway. Sets fall through to `=` for the same reason -- membership is decided
  by hash, so a set is the wrong place to be asserting bit-level identity."
  [expected actual]
  (cond
    (and (number? expected) (number? actual))
    (js/Object.is expected actual)

    (and (map? expected) (map? actual))
    (and (= (set (keys expected)) (set (keys actual)))
         (every? (fn [key] (bit= (get expected key) (get actual key)))
                 (keys expected)))

    (and (sequential? expected) (sequential? actual))
    (and (= (count expected) (count actual))
         (every? true? (map bit= expected actual)))

    :else
    (= expected actual)))

(defn- describe
  [path]
  (if (seq path)
    (str " at index " (str/join "," path))
    ""))

(defn first-difference
  "A human-readable description of where `expected` and `actual` first differ
  bitwise, or nil when they are bit-identical. Assertion messages that only say
  'not equal' are useless when both sides print the same, which is precisely
  what happens with NaN and signed zero."
  ([expected actual] (first-difference expected actual []))
  ([expected actual path]
   (cond
     (and (map? expected) (map? actual))
     (if (not= (set (keys expected)) (set (keys actual)))
       (str "keys " (pr-str (sort (map str (keys expected))))
            " vs " (pr-str (sort (map str (keys actual))))
            (describe path))
       (some (fn [key]
               (first-difference (get expected key) (get actual key)
                                 (conj path key)))
             (keys expected)))

     (and (sequential? expected) (sequential? actual))
     (if (not= (count expected) (count actual))
       (str "length " (count expected) " vs " (count actual) (describe path))
       (some (fn [[idx e a]] (first-difference e a (conj path idx)))
             (map vector (range) expected actual)))

     (bit= expected actual)
     nil

     :else
     (str (pr-str expected) " vs " (pr-str actual) (describe path)
          (when (and (number? expected) (number? actual))
            (str " (same printed form; differing bits)"))))))
