(ns hyperopen.portfolio.optimizer.application.engine.equal-risk-solve-test
  "Mathematical battery for the sequential Equal Risk solver, run through the
  synchronous quadprog adapter so every case is deterministic."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.portfolio.optimizer.application.engine.equal-risk-solve
             :as equal-risk-solve]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.equal-risk :as equal-risk]
            [hyperopen.portfolio.optimizer.domain.equal-risk-plan :as equal-risk-plan]
            [hyperopen.portfolio.optimizer.domain.risk-contributions :as risk-contributions]
            [hyperopen.portfolio.optimizer.infrastructure.solver-adapter :as solver-adapter]))

(defn- near?
  ([expected actual] (near? expected actual 1e-6))
  ([expected actual tolerance]
   (and (number? actual)
        (< (js/Math.abs (- expected actual)) tolerance))))

(defn- perp
  [id side & {:as extra}]
  (merge {:instrument-id id
          :market-type :perp
          :coin id
          :shortable? true
          :position-side side}
         extra))

(defn- build-problem
  ([universe engine-constraints covariance]
   (build-problem universe engine-constraints covariance {}))
  ([universe engine-constraints covariance current-weights]
   (let [encoded (constraints/encode-constraints
                  {:universe universe
                   :current-weights current-weights
                   :constraints engine-constraints})
         plan (equal-risk-plan/build-plan {:instrument-ids (mapv :instrument-id universe)
                                      :covariance covariance
                                      :encoded-constraints encoded})]
     (is (= :ok (:status plan))
         (str "plan should be feasible: " (pr-str (:details plan))))
     (first (:problems plan)))))

(defn- solve*
  [problem]
  (equal-risk-solve/solve problem solver-adapter/solve-with-quadprog))

(defn- contributions-of
  [problem weights]
  (:relative-contributions
   (risk-contributions/contributions (:covariance problem) weights)))

(defn- exposure-of
  [weights]
  {:gross (reduce + 0 (map js/Math.abs weights))
   :net (reduce + 0 weights)})

(def ^:private diag-covariance
  [[0.01 0.0]
   [0.0 0.04]])

(deftest diagonal-long-only-case-solves-to-inverse-vol-weights-test
  (let [problem (build-problem [(perp "perp:A" :long) (perp "perp:B" :long)]
                               {:gross-leverage 1.0
                                :net-exposure {:min 1.0 :max 1.0}}
                               diag-covariance)
        result (solve* problem)]
    (is (= :solved (:status result)))
    (is (near? (/ 2 3) (nth (:weights result) 0) 1e-3))
    (is (near? (/ 1 3) (nth (:weights result) 1) 1e-3))
    (let [rrc (contributions-of problem (:weights result))]
      (is (near? 0.5 (nth rrc 0) 1e-3))
      (is (near? 0.5 (nth rrc 1) 1e-3)))
    (is (true? (get-in result [:equal-risk :converged?])))
    (is (contains? (set equal-risk/seed-kinds)
                   (get-in result [:equal-risk :selected-initialization])))))

(deftest all-short-symmetry-mirrors-the-long-case-test
  (let [problem (build-problem [(perp "perp:A" :short) (perp "perp:B" :short)]
                               {:gross-leverage 1.0
                                :net-exposure {:min -1.0 :max -1.0}}
                               diag-covariance)
        result (solve* problem)]
    (is (= :solved (:status result)))
    (is (near? (- (/ 2 3)) (nth (:weights result) 0) 1e-3))
    (is (near? (- (/ 1 3)) (nth (:weights result) 1) 1e-3))
    (let [rrc (contributions-of problem (:weights result))]
      (is (near? 0.5 (nth rrc 0) 1e-3))
      (is (near? 0.5 (nth rrc 1) 1e-3)))))

(deftest exact-mixed-long-short-case-test
  (let [problem (build-problem [(perp "perp:A" :long) (perp "perp:B" :short)]
                               {:gross-leverage 2.0
                                :net-exposure {:min 0.0 :max 0.0}
                                :max-asset-weight 1.5}
                               [[0.04 0.02]
                                [0.02 0.04]])
        result (solve* problem)]
    (is (= :solved (:status result)))
    (is (near? 1.0 (nth (:weights result) 0) 1e-6))
    (is (near? -1.0 (nth (:weights result) 1) 1e-6))
    (let [rrc (contributions-of problem (:weights result))]
      (is (near? 0.5 (nth rrc 0) 1e-9))
      (is (near? 0.5 (nth rrc 1) 1e-9)))))

(deftest mixed-long-short-unequal-vols-ignore-stored-net-and-balance-contributions-test
  (let [universe [(perp "perp:A" :long) (perp "perp:B" :short)]
        constraints-a {:gross-leverage 2.0
                       :net-exposure {:min 0.0 :max 0.0}
                       :max-asset-weight 2.0}
        constraints-b {:gross-leverage 2.0
                       :net-exposure {:min -1.0 :max -1.0}
                       :max-asset-weight 2.0}
        problem-a (build-problem universe constraints-a diag-covariance)
        problem-b (build-problem universe constraints-b diag-covariance)
        result-a (solve* problem-a)
        result-b (solve* problem-b)]
    (doseq [[stored-net problem result] [[0.0 problem-a result-a]
                                         [-1.0 problem-b result-b]]]
      (let [weights (:weights result)
            {:keys [gross net]} (exposure-of weights)
            rrc (contributions-of problem weights)]
        (is (= :solved (:status result)))
        (is (near? (/ 4 3) (nth weights 0) 1e-3))
        (is (near? (- (/ 2 3)) (nth weights 1) 1e-3))
        (is (near? 2.0 gross 1e-6))
        (is (near? (/ 2 3) net 1e-3))
        (is (not (near? stored-net net 1e-3)))
        (is (near? 0.5 (nth rrc 0) 1e-3))
        (is (near? 0.5 (nth rrc 1) 1e-3))
        (is (true? (get-in result [:equal-risk :converged?])))))
    (doseq [[a b] (map vector (:weights result-a) (:weights result-b))]
      (is (near? a b 1e-6)))
    (doseq [[a b] (map vector
                       (contributions-of problem-a (:weights result-a))
                       (contributions-of problem-b (:weights result-b)))]
      (is (near? a b 1e-6)))))

(deftest all-long-and-all-short-books-ignore-opposite-stored-net-test
  (let [long-encoded (constraints/encode-constraints
                      {:universe [(perp "perp:A" :long)
                                  (perp "perp:B" :long)]
                       :constraints {:gross-leverage 1.0
                                     :net-exposure {:min -1.0 :max -1.0}}})
        short-encoded (constraints/encode-constraints
                       {:universe [(perp "perp:A" :short)
                                   (perp "perp:B" :short)]
                        :constraints {:gross-leverage 1.0
                                      :net-exposure {:min 1.0 :max 1.0}}})
        long-plan (equal-risk-plan/build-plan {:instrument-ids ["perp:A" "perp:B"]
                                          :covariance diag-covariance
                                          :encoded-constraints long-encoded})
        short-plan (equal-risk-plan/build-plan {:instrument-ids ["perp:A" "perp:B"]
                                           :covariance diag-covariance
                                           :encoded-constraints short-encoded})]
    (is (= :ok (:status long-plan)))
    (is (= :ok (:status short-plan)))
    (when (and (= :ok (:status long-plan))
               (= :ok (:status short-plan)))
      (let [long-result (solve* (first (:problems long-plan)))
            short-result (solve* (first (:problems short-plan)))]
        (is (= :solved (:status long-result)))
        (is (= :solved (:status short-result)))
        (is (near? 1.0 (:gross (exposure-of (:weights long-result))) 1e-6))
        (is (near? 1.0 (:net (exposure-of (:weights long-result))) 1e-6))
        (is (near? 1.0 (:gross (exposure-of (:weights short-result))) 1e-6))
        (is (near? -1.0 (:net (exposure-of (:weights short-result))) 1e-6))))))

(deftest active-asset-cap-binds-while-books-stay-exact-test
  (let [problem (build-problem [(perp "perp:A" :long) (perp "perp:B" :long)]
                               {:gross-leverage 1.0
                                :net-exposure {:min 1.0 :max 1.0}
                                :per-asset-overrides {"perp:B" {:max-weight 0.55}}}
                               [[0.01 0.0]
                                [0.0 0.0025]])
        result (solve* problem)
        weights (:weights result)]
    (is (= :solved (:status result)))
    (is (near? 0.55 (nth weights 1) 1e-6))
    (is (near? 0.45 (nth weights 0) 1e-6))
    (let [rrc (contributions-of problem weights)]
      (is (> (js/Math.abs (- (nth rrc 0) 0.5))
             (equal-risk/exactness-tolerance 2))))))

(deftest locked-position-stays-fixed-and-book-adjusts-test
  (let [problem (build-problem [(perp "perp:A" :long)
                                (perp "perp:B" :long)
                                (perp "perp:C" :long)]
                               {:gross-leverage 1.0
                                :net-exposure {:min 1.0 :max 1.0}
                                :held-position-locks ["perp:A"]}
                               [[0.01 0.0 0.0]
                                [0.0 0.04 0.0]
                                [0.0 0.0 0.09]]
                               {"perp:A" 0.25})
        result (solve* problem)
        weights (:weights result)]
    (is (= :solved (:status result)))
    (is (near? 0.25 (nth weights 0) 1e-9))
    (is (near? 0.75 (+ (nth weights 1) (nth weights 2)) 1e-6))
    (is (> (nth weights 1) (nth weights 2)))))

(deftest degenerate-covariance-fails-explicitly-test
  (let [problem (build-problem [(perp "perp:A" :long) (perp "perp:B" :long)]
                               {:gross-leverage 1.0
                                :net-exposure {:min 1.0 :max 1.0}}
                               [[0.0 0.0]
                                [0.0 0.0]])
        result (solve* problem)]
    (is (= :infeasible (:status result)))
    (is (= :equal-risk-degenerate-variance (:reason result)))))

(deftest permutation-invariance-test
  (let [covariance [[0.04 0.01 0.0]
                    [0.01 0.09 0.02]
                    [0.0 0.02 0.02]]
        ids ["perp:A" "perp:B" "perp:C"]
        constraints* {:gross-leverage 1.0
                      :net-exposure {:min 1.0 :max 1.0}}
        base (solve* (build-problem (mapv #(perp % :long) ids)
                                    constraints* covariance))
        permuted-ids ["perp:C" "perp:A" "perp:B"]
        permutation [2 0 1]
        permuted-covariance (mapv (fn [row-idx]
                                    (mapv (fn [col-idx]
                                            (get-in covariance
                                                    [(nth permutation row-idx)
                                                     (nth permutation col-idx)]))
                                          (range 3)))
                                  (range 3))
        permuted (solve* (build-problem (mapv #(perp % :long) permuted-ids)
                                        constraints* permuted-covariance))]
    (is (= :solved (:status base) (:status permuted)))
    (doseq [[idx permuted-idx] (map-indexed vector permutation)]
      (is (near? (nth (:weights base) permuted-idx)
                 (nth (:weights permuted) idx)
                 1e-6)
          (str "weight for " (nth permuted-ids idx))))))

(deftest covariance-scale-invariance-test
  (let [universe [(perp "perp:A" :long) (perp "perp:B" :long) (perp "perp:C" :short)]
        constraints* {:gross-leverage 2.0
                      :net-exposure {:min 0.5 :max 0.5}
                      :max-asset-weight 1.5}
        covariance [[0.04 0.01 -0.01]
                    [0.01 0.09 0.02]
                    [-0.01 0.02 0.02]]
        base (solve* (build-problem universe constraints* covariance))
        scaled (solve* (build-problem universe constraints*
                                      (mapv (fn [row] (mapv #(* 7.5 %) row))
                                            covariance)))]
    (is (= :solved (:status base) (:status scaled)))
    (doseq [[a b] (map vector (:weights base) (:weights scaled))]
      (is (near? a b 1e-6)))))

(deftest global-sign-flip-symmetry-test
  (let [covariance [[0.04 0.015] [0.015 0.0225]]
        long-short (build-problem [(perp "perp:A" :long) (perp "perp:B" :short)]
                                  {:gross-leverage 2.0
                                   :net-exposure {:min 0.5 :max 0.5}
                                   :max-asset-weight 1.5}
                                  covariance)
        short-long (build-problem [(perp "perp:A" :short) (perp "perp:B" :long)]
                                  {:gross-leverage 2.0
                                   :net-exposure {:min -0.5 :max -0.5}
                                   :max-asset-weight 1.5}
                                  covariance)
        base (solve* long-short)
        flipped (solve* short-long)]
    (is (= :solved (:status base) (:status flipped)))
    (doseq [[a b] (map vector (:weights base) (:weights flipped))]
      (is (near? a (- b) 1e-6)))
    (doseq [[a b] (map vector
                       (contributions-of long-short (:weights base))
                       (contributions-of short-long (:weights flipped)))]
      (is (near? a b 1e-6)))))

(deftest determinism-repeated-runs-are-identical-test
  (let [build #(build-problem [(perp "perp:A" :long)
                               (perp "perp:B" :long)
                               (perp "perp:C" :short)]
                              {:gross-leverage 2.0
                               :net-exposure {:min 0.5 :max 0.5}
                               :max-asset-weight 1.5
                               :max-turnover 5.0}
                              [[0.04 0.01 -0.01]
                               [0.01 0.09 0.02]
                               [-0.01 0.02 0.02]])
        first-run (solve* (build))
        second-run (solve* (build))]
    (is (= (:weights first-run) (:weights second-run)))
    (is (= (:equal-risk first-run) (:equal-risk second-run)))))

(deftest final-weights-satisfy-every-hard-constraint-test
  (let [problem (build-problem [(perp "perp:A" :long)
                                (perp "perp:B" :long)
                                (perp "perp:C" :short)]
                               {:gross-leverage 2.0
                                :net-exposure {:min 0.5 :max 0.5}
                                :per-asset-overrides {"perp:B" {:max-weight 0.7}}
                                :max-asset-weight 1.5
                                :held-position-locks ["perp:A"]}
                               [[0.04 0.01 -0.01]
                                [0.01 0.09 0.02]
                                [-0.01 0.02 0.02]]
                               {"perp:A" 0.6})
        result (solve* problem)
        weights (:weights result)
        {:keys [gross]} (exposure-of weights)]
    (is (= :solved (:status result)))
    (is (near? 2.0 gross 1e-6))
    (is (near? 0.6 (nth weights 0) 1e-9))
    (is (<= (nth weights 1) (+ 0.7 1e-6)))
    (is (<= (nth weights 2) 1e-9))))

(deftest turnover-constraint-is-respected-not-silently-dropped-test
  (let [current {"perp:A" 0.9 "perp:B" 0.1}
        problem (build-problem [(perp "perp:A" :long) (perp "perp:B" :long)]
                               {:gross-leverage 1.0
                                :net-exposure {:min 1.0 :max 1.0}
                                :max-turnover 0.2}
                               [[0.01 0.0]
                                [0.0 0.0025]]
                               current)
        result (solve* problem)
        weights (:weights result)
        turnover (+ (js/Math.abs (- (nth weights 0) 0.9))
                    (js/Math.abs (- (nth weights 1) 0.1)))]
    (is (= :solved (:status result)))
    (is (<= turnover (+ 0.4 1e-6)))
    (is (near? 0.4 turnover 1e-3))
    (is (near? 1.0 (reduce + 0 weights) 1e-6))))

(deftest iteration-limit-marks-not-converged-test
  (with-redefs [equal-risk/tolerances (assoc equal-risk/tolerances
                                             :max-iterations 0)]
    (let [problem (build-problem [(perp "perp:A" :long) (perp "perp:B" :long)]
                                 {:gross-leverage 1.0
                                  :net-exposure {:min 1.0 :max 1.0}}
                                 diag-covariance)
          result (solve* problem)]
      (is (= :solved (:status result)))
      (is (false? (get-in result [:equal-risk :converged?])))
      (is (= :max-iterations (get-in result [:equal-risk :termination-reason]))))))

(deftest sync-and-async-solver-paths-agree-test
  (async done
    (let [problem (build-problem [(perp "perp:A" :long) (perp "perp:B" :long)]
                                 {:gross-leverage 1.0
                                  :net-exposure {:min 1.0 :max 1.0}}
                                 diag-covariance)
          sync-result (solve* problem)
          async-solver (fn [subproblem]
                         (js/Promise.resolve
                          (solver-adapter/solve-with-quadprog subproblem)))
          async-result (equal-risk-solve/solve problem async-solver)]
      (is (map? sync-result))
      (is (fn? (unchecked-get async-result "then")))
      (-> async-result
          (.then (fn [result]
                   (is (= (:weights sync-result) (:weights result)))
                   (is (= (:equal-risk sync-result) (:equal-risk result)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "async equal-risk solve failed: " err))
                    (done)))))))

(deftest progress-callback-reports-iterations-test
  (let [events (atom [])
        problem (build-problem [(perp "perp:A" :long) (perp "perp:B" :long)]
                               {:gross-leverage 1.0
                                :net-exposure {:min 1.0 :max 1.0}}
                               diag-covariance)
        result (equal-risk-solve/solve problem
                                       solver-adapter/solve-with-quadprog
                                       (fn [payload] (swap! events conj payload)))]
    (is (= :solved (:status result)))
    (is (seq @events))
    (is (every? #(= :solve (:step %)) @events))
    (is (some #(re-find #"iter" (str (:detail %))) @events))
    (is (every? #(<= 5 (:percent %) 95) @events))))
