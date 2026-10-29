(ns hyperopen.vol-range.domain.report-intervals
  "Half-open time interval algebra for Vol Range reporting coverage
  (HO-SPEC-014 v2.1 sections 12.4, 12.6).

  An interval is `[from to)` with integer ms, written as a two-element
  vector. Empty or malformed intervals (to <= from, non-numbers) are dropped.
  A normalized set is sorted and has no two intervals that overlap or touch:
  `[[0 5) [5 9)]` normalizes to `[[0 9)]`. Pure.")

(defn- valid? [iv]
  (and (sequential? iv) (= 2 (count iv))
       (number? (first iv)) (number? (second iv))
       (< (first iv) (second iv))))

(defn normalize
  "Sorted, merged, non-empty intervals."
  [ivs]
  (->> ivs
       (filter valid?)
       (map vec)
       (sort-by first)
       (reduce (fn [acc [a b]]
                 (let [[pa pb] (peek acc)]
                   (if (and pa (<= a pb))
                     (conj (pop acc) [pa (max pb b)])
                     (conj acc [a b]))))
               [])))

(defn union
  "The union of interval sets."
  [& sets]
  (normalize (apply concat sets)))

(defn intersect
  "Points in both `xs` and `ys`."
  [xs ys]
  (let [xs (normalize xs) ys (normalize ys)]
    (normalize (for [[a b] xs [c d] ys
                     :let [lo (max a c) hi (min b d)]
                     :when (< lo hi)]
                 [lo hi]))))

(defn subtract
  "Points in `xs` that are not in `ys`."
  [xs ys]
  (let [ys (normalize ys)]
    (normalize
     (mapcat (fn [[a b]]
               (loop [from a [[c d] & more :as remaining] ys out []]
                 (cond
                   (>= from b) out
                   (empty? remaining) (conj out [from b])
                   (<= d from) (recur from more out)
                   (>= c b) (conj out [from b])
                   :else (recur (max from d) more (cond-> out (< from c) (conj [from c]))))))
             (normalize xs)))))

(defn uncovered
  "The parts of `[from to)` that `ivs` does not cover."
  [ivs [from to]]
  (subtract [[from to]] ivs))

(defn covers?
  "True when every point of `[from to)` is in `ivs`. An empty interval is
  trivially covered."
  [ivs [from to]]
  (or (not (valid? [from to]))
      (empty? (uncovered ivs [from to]))))

(defn contains-point?
  "True when `t` lies in some interval of `ivs` (half-open)."
  [ivs t]
  (boolean (some (fn [[a b]] (and (<= a t) (< t b))) (normalize ivs))))
