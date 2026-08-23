(ns hyperopen.portfolio.optimizer.infrastructure.osqp-static-cache-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.infrastructure.osqp :as osqp]
            [hyperopen.portfolio.optimizer.infrastructure.problem-adapter :as problem-adapter]))

(defn- covariance
  [n]
  (mapv (fn [i]
          (mapv (fn [j] (if (= i j) 0.04 (/ 0.01 (inc (js/Math.abs (- i j)))))) (range n)))
        (range n)))

(defn- problem
  "A production-shaped problem: long-only bounds, a net equality and a turnover
  L1 constraint, which is what forces the 4N variable split."
  [n return-tilt]
  (let [cov (covariance n)]
    {:kind :quadratic-program
     :objective-kind :max-sharpe
     :instrument-ids (mapv #(str "perp:" %) (range n))
     :quadratic cov
     :linear (mapv #(* -1 return-tilt (/ (inc %) n)) (range n))
     :return-tilt return-tilt
     :equalities [{:coefficients (vec (repeat n 1)) :target 1}]
     :inequalities []
     :l1-constraints [{:code :turnover
                       :requires-split-variables? true
                       :limit 0.5
                       :current-weights (vec (repeat n (/ 1.0 n)))}]
     :lower-bounds (vec (repeat n 0))
     :upper-bounds (vec (repeat n 0.8))}))

(defn- typed->vec [a] (vec (array-seq a)))

(defn- csc->vecs
  [csc]
  {:data (typed->vec (.-data csc))
   :row-indices (typed->vec (.-row_indices csc))
   :column-pointers (typed->vec (.-column_pointers csc))})

(deftest cached-static-parts-are-identical-to-a-fresh-build-test
  ;; The whole point of the cache is that it changes nothing. A frontier sweep
  ;; reuses the same covariance and bound objects while varying only the return
  ;; tilt, so every point must get byte-identical P, A, l and u.
  (let [baseline (osqp/build-static-parts-uncached (problem 12 0.0))]
    (doseq [tilt [0.0 0.25 0.5 1.0 2.0]]
      (let [cached (osqp/static-parts (problem 12 tilt))]
        (is (= (csc->vecs (:P baseline)) (csc->vecs (:P cached)))
            (str "P must be identical at tilt " tilt))
        (is (= (csc->vecs (:A baseline)) (csc->vecs (:A cached)))
            (str "A must be identical at tilt " tilt))
        (is (= (typed->vec (:l baseline)) (typed->vec (:l cached))))
        (is (= (typed->vec (:u baseline)) (typed->vec (:u cached))))
        (is (= (:var-count baseline) (:var-count cached)))))))

(deftest a-different-structure-does-not-reuse-a-cached-entry-test
  ;; A stale hit here would silently solve the wrong problem, so the key has to
  ;; separate universes, bounds and constraint rows.
  (let [base (problem 8 0.5)
        wider (assoc base :upper-bounds (vec (repeat 8 0.4)))
        bigger (problem 9 0.5)
        base-parts (osqp/static-parts base)
        wider-parts (osqp/static-parts wider)
        bigger-parts (osqp/static-parts bigger)]
    (is (not= (typed->vec (:u base-parts)) (typed->vec (:u wider-parts)))
        "a changed upper bound must produce different u")
    (is (not= (count (:data (csc->vecs (:P base-parts))))
              (count (:data (csc->vecs (:P bigger-parts)))))
        "a bigger universe must produce a bigger P")))

(deftest the-linear-term-is-rebuilt-per-solve-test
  ;; q is the one thing a sweep varies; caching it would make every frontier
  ;; point solve the same problem.
  (let [p0 (problem 6 0.0)
        p1 (problem 6 1.0)
        {var-count :var-count} (osqp/static-parts p0)
        q0 (problem-adapter/adapt-linear p0 var-count)
        q1 (problem-adapter/adapt-linear p1 var-count)]
    (is (some? var-count) "a turnover constraint forces the split layout")
    (is (= var-count (count q0)))
    (is (not= q0 q1) "different return tilts must produce different linear terms")))

(deftest adapt-linear-passes-through-when-no-split-is-required-test
  (let [p (-> (problem 5 0.5) (assoc :l1-constraints []))
        {var-count :var-count} (osqp/static-parts p)]
    (is (nil? var-count))
    (is (= (:linear p) (problem-adapter/adapt-linear p var-count)))))
