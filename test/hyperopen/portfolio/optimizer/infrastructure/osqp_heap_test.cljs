(ns hyperopen.portfolio.optimizer.infrastructure.osqp-heap-test
  "The published `osqp` package leaks WebAssembly heap on every solve and its
  heap is fixed at 16 MiB, so a long enough frontier sweep exhausts it and every
  remaining solve silently falls back to quadprog. vendor/osqp carries a patched
  copy whose cleanup() frees the six CSC arrays the package abandons.

  This is the end-to-end regression for that: it proves the build actually
  resolved the patched copy, and that the frees are correct rather than merely
  present -- a double free would abort here just as loudly as the leak did.
  tools/optimizer/patch_osqp.test.mjs covers the artifact and the wiring; only a
  real run of the real solver covers this."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.infrastructure.osqp :as osqp]))

(def ^:private universe-size
  ;; Chosen to make the test expensive enough to be meaningful and no more. The
  ;; leak scales with the number of nonzeros in the split quadratic (2N^2+3N),
  ;; so the bigger the universe the fewer solves are needed to prove the point,
  ;; but the more each one costs. Measured first-abort on the unpatched package:
  ;; 36 solves at N=100, 19 at N=130, 11 at N=160, 5 at N=200. N=130 minimises
  ;; total wall clock across that range at roughly two seconds.
  130)

(def ^:private solve-count
  ;; Comfortably past the unpatched budget of 19 at this size.
  24)

(defn- covariance
  [n]
  (mapv (fn [i]
          (mapv (fn [j]
                  (if (= i j) 0.04 (/ 0.01 (inc (js/Math.abs (- i j))))))
                (range n)))
        (range n)))

(defn- problem
  "Production-shaped: long-only bounds, a net equality and a turnover L1
  constraint. The turnover constraint is what forces problem-adapter down the
  split-variable path to 4N variables, which is where the dense quadratic -- and
  so the great majority of the leak -- comes from. Without it this test would
  allocate a quarter as much per solve and prove nothing."
  [n cov return-tilt]
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
   :upper-bounds (vec (repeat n 0.8))})

(defn- solve-sweep
  "Solves `solve-count` points sequentially, as a frontier sweep does, varying
  only the return tilt. Returns a promise of the results in order."
  [cov]
  (reduce (fn [chain idx]
            (.then chain
                   (fn [acc]
                     (-> (osqp/solve (problem universe-size cov (* 0.1 (inc idx))))
                         (.then (fn [result] (conj acc result)))))))
          (js/Promise.resolve [])
          (range solve-count)))

(deftest a-long-sweep-never-exhausts-the-solver-heap-test
  (async done
    (-> (solve-sweep (covariance universe-size))
        (.then
         (fn [results]
           (let [fallbacks (filter #(= :quadprog-fallback (:solver %)) results)]
             (is (= solve-count (count results))
                 "every planned point should have produced a result")
             (is (empty? fallbacks)
                 (str "expected every solve to stay on the WebAssembly solver, but "
                      (count fallbacks) " of " (count results) " fell back to quadprog."
                      " The first failure was: "
                      (pr-str (:fallback-message (first fallbacks)))
                      ". On the unpatched osqp package this aborts after 19 solves at"
                      " N=" universe-size "; check that the build still resolves"
                      " vendor/osqp/osqp_patched.mjs."))
             (is (every? #(= :solved (:status %)) results)
                 "every solve should have converged")
             ;; A leak-free run is not worth much if it stopped solving
             ;; correctly. The net equality is the cheapest end-to-end check
             ;; that real answers are still coming back.
             (is (every? (fn [result]
                           (< (js/Math.abs (- 1.0 (reduce + 0 (:weights result)))) 1.0e-4))
                         results)
                 "every solution should still satisfy the net-exposure equality"))
           (done)))
        (.catch (fn [err]
                  (is false (str "the sweep threw rather than falling back: " err))
                  (done))))))
