(ns hyperopen.portfolio.optimizer.application.engine-solve-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.application.engine.solve :as solve]
            [hyperopen.portfolio.optimizer.application.engine.target-selection :as target-selection]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]))

(defn- near?
  [expected actual]
  (and (number? actual)
       (< (js/Math.abs (- expected actual)) 1.0e-7)))

(def ^:private unbounded-encoded
  {:status :ok
   :long-only? false
   :net-target 1
   :lower-bounds [js/Number.NEGATIVE_INFINITY js/Number.NEGATIVE_INFINITY]
   :upper-bounds [js/Number.POSITIVE_INFINITY js/Number.POSITIVE_INFINITY]
   :locked-weights []})

(defn- closed-form-opts
  [objective]
  {:objective objective
   :instrument-ids ["A" "B"]
   :expected-returns [0.1 0.2]
   :covariance [[0.04 0]
                [0 0.09]]
   :encoded-constraints unbounded-encoded})

(defn- single-qp-opts
  []
  {:objective {:kind :minimum-variance}
   :instrument-ids ["A" "B"]
   :expected-returns [0.1 0.2]
   :covariance [[0.04 0]
                [0 0.09]]
   :encoded-constraints (constraints/encode-constraints
                         {:universe [{:instrument-id "A"}
                                     {:instrument-id "B"}]
                          :constraints {:long-only? true
                                        :max-asset-weight 0.5}})})

(defn- recording-solver
  [calls]
  (fn [problem]
    (swap! calls conj problem)
    {:status :solved
     :solver :stub
     :weights [0.5 0.5]
     :iterations 1
     :elapsed-ms 1}))

(deftest solve-plan-routes-closed-form-problems-to-domain-solver-test
  (let [calls (atom [])
        plan (objectives/build-solver-plan
              (closed-form-opts {:kind :minimum-variance}))
        results (solve/solve-plan plan (recording-solver calls))
        result (first results)]
    (is (= :closed-form (:strategy plan)))
    (is (empty? @calls)
        "Closed-form problems must never reach the injected QP solver.")
    (is (= :solved (:status result)))
    (is (= :closed-form (:solver result)))
    (is (near? 0.6923076923 (get-in result [:weights 0])))
    (is (near? 0.3076923077 (get-in result [:weights 1])))
    (is (= :closed-form-portfolio (get-in result [:problem :kind])))
    (is (= :closed-form (get-in result [:diagnostics :strategy])))
    (is (false? (get-in result [:diagnostics :fallback?])))))

(deftest solve-plan-sends-quadratic-programs-to-injected-solver-test
  (let [calls (atom [])
        plan (objectives/build-solver-plan (single-qp-opts))
        results (solve/solve-plan plan (recording-solver calls))]
    (is (= :single-qp (:strategy plan)))
    (is (= 1 (count @calls)))
    (is (= :quadratic-program (:kind (first @calls))))
    (is (= :stub (:solver (first results))))
    (is (= (first @calls) (:problem (first results)))
        "Solver results keep carrying the originating problem.")))

(deftest solve-plan-async-routes-closed-form-without-injected-solver-test
  (async done
         (let [calls (atom [])
               plan (objectives/build-solver-plan
                     (closed-form-opts {:kind :max-sharpe}))]
           (-> (solve/solve-plan-async plan (recording-solver calls))
               (.then (fn [results]
                        (is (= 1 (count results)))
                        (is (empty? @calls))
                        (is (= :solved (:status (first results))))
                        (is (= :closed-form (:solver (first results))))
                        (is (near? 0.5294117647
                                   (get-in (first results) [:weights 0])))
                        (is (near? 0.4705882353
                                   (get-in (first results) [:weights 1])))
                        (is (= :closed-form-portfolio
                               (get-in (first results) [:problem :kind])))
                        (done)))))))

(deftest solve-plan-async-sends-quadratic-programs-to-injected-solver-test
  (async done
         (let [calls (atom [])
               plan (objectives/build-solver-plan (single-qp-opts))]
           (-> (solve/solve-plan-async plan (recording-solver calls))
               (.then (fn [results]
                        (is (= 1 (count @calls)))
                        (is (= :quadratic-program (:kind (first @calls))))
                        (is (= :stub (:solver (first results))))
                        (is (= (first @calls) (:problem (first results))))
                        (done)))))))

(deftest target-selection-accepts-closed-form-points-for-all-objectives-test
  (doseq [[objective expected-weights]
          [[{:kind :minimum-variance} [0.6923076923 0.3076923077]]
           [{:kind :target-return :target-return 0.16} [0.4 0.6]]
           [{:kind :max-sharpe} [0.5294117647 0.4705882353]]
           [{:kind :target-volatility :target-volatility 0.2} [0.3846153846 0.6153846154]]]]
    (let [calls (atom [])
          opts (closed-form-opts objective)
          plan (objectives/build-solver-plan opts)
          results (solve/solve-plan plan (recording-solver calls))
          selection (target-selection/target-selection {:objective objective}
                                                       plan
                                                       results
                                                       (:expected-returns opts)
                                                       (:covariance opts))]
      (is (= :closed-form (:strategy plan)) (str objective))
      (is (empty? @calls) (str objective))
      (is (= :solved (:status selection)) (str objective))
      (is (= 1 (count (:target-frontier selection))) (str objective))
      (is (= :closed-form (get-in selection [:selected :solver])) (str objective))
      (doseq [[idx expected] (map-indexed vector expected-weights)]
        (is (near? expected (get-in selection [:selected :weights idx]))
            (str objective " weight " idx))))))
